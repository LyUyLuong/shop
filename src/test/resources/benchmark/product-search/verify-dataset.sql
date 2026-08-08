DO $verify$
DECLARE
    v_row_count INTEGER :=
        current_setting(
            'shop_benchmark.row_count'
        )::INTEGER;
    v_seed BIGINT :=
        current_setting(
            'shop_benchmark.seed'
        )::BIGINT;
    v_marker TEXT :=
        'V2-PS-A deterministic dataset seed '
        || v_seed::TEXT;

    v_total BIGINT;
    v_marker_count BIGINT;
    v_active BIGINT;
    v_inactive BIGINT;
    v_distinct_sku BIGINT;
    v_rare BIGINT;
    v_medium BIGINT;
    v_common BIGINT;
    v_vietnamese BIGINT;
    v_japanese BIGINT;
    v_mixed_case BIGINT;
    v_boundary_sku BIGINT;
    v_boundary_name BIGINT;
    v_invalid_rows BIGINT;
    v_timestamp_groups BIGINT;
    v_min_timestamp_group BIGINT;
    v_max_timestamp_group BIGINT;
BEGIN
    IF current_database() <> 'shop_search_benchmark'
       OR current_user <> 'shop_benchmark' THEN
        RAISE EXCEPTION
            'Refusing to verify database % as user %',
            current_database(),
            current_user;
    END IF;

    SELECT
        count(*),
        count(*) FILTER (
            WHERE status = 'ACTIVE'
        ),
        count(*) FILTER (
            WHERE status = 'INACTIVE'
        )
    INTO
        v_total,
        v_active,
        v_inactive
    FROM products;

    SELECT count(*)
    INTO v_marker_count
    FROM products
    WHERE description = v_marker;

    SELECT count(DISTINCT lower(sku))
    INTO v_distinct_sku
    FROM products;

    SELECT
        count(*) FILTER (
            WHERE lower(name) LIKE '%rare orchid%'
        ),
        count(*) FILTER (
            WHERE lower(name) LIKE '%medium cedar%'
        ),
        count(*) FILTER (
            WHERE lower(name) LIKE '%common market%'
        ),
        count(*) FILTER (
            WHERE name LIKE '%Điện Thoại%'
        ),
        count(*) FILTER (
            WHERE name LIKE '%日本語%'
        ),
        count(*) FILTER (
            WHERE name LIKE '%MiXeD CaSe%'
        )
    INTO
        v_rare,
        v_medium,
        v_common,
        v_vietnamese,
        v_japanese,
        v_mixed_case
    FROM products;

    SELECT
        count(*) FILTER (
            WHERE char_length(sku) = 100
        ),
        count(*) FILTER (
            WHERE char_length(name) = 255
        )
    INTO
        v_boundary_sku,
        v_boundary_name
    FROM products;

    SELECT count(*)
    INTO v_invalid_rows
    FROM products
    WHERE price < 0
       OR stock_quantity < 0
       OR version <> 0;

    SELECT
        count(*),
        min(timestamp_group_size),
        max(timestamp_group_size)
    INTO
        v_timestamp_groups,
        v_min_timestamp_group,
        v_max_timestamp_group
    FROM (
        SELECT
            created_at,
            count(*) AS timestamp_group_size
        FROM products
        GROUP BY created_at
    ) AS timestamp_groups;

    IF v_total <> v_row_count THEN
        RAISE EXCEPTION
            'Expected % rows, found %',
            v_row_count,
            v_total;
    END IF;

    IF v_marker_count <> v_row_count THEN
        RAISE EXCEPTION
            'Dataset marker count is %, expected %',
            v_marker_count,
            v_row_count;
    END IF;

    IF v_active <> v_row_count * 4 / 5
       OR v_inactive <> v_row_count / 5 THEN
        RAISE EXCEPTION
            'Status distribution is active=%, inactive=%',
            v_active,
            v_inactive;
    END IF;

    IF v_distinct_sku <> v_row_count THEN
        RAISE EXCEPTION
            'Expected % distinct normalized SKUs, found %',
            v_row_count,
            v_distinct_sku;
    END IF;

    IF v_rare <> 1
       OR v_medium <> v_row_count / 10
       OR v_common <> (
           v_row_count
           - v_row_count / 10
           - 1
       ) THEN
        RAISE EXCEPTION
            'Token distribution is rare=%, medium=%, common=%',
            v_rare,
            v_medium,
            v_common;
    END IF;

    IF v_vietnamese <> v_row_count / 50
       OR v_japanese <> v_row_count / 50
       OR v_mixed_case <> v_row_count / 50 THEN
        RAISE EXCEPTION
            'Text distribution is Vietnamese=%, Japanese=%, mixed=%',
            v_vietnamese,
            v_japanese,
            v_mixed_case;
    END IF;

    IF v_boundary_sku <> 1
       OR v_boundary_name <> 1 THEN
        RAISE EXCEPTION
            'Boundary rows are sku=%, name=%',
            v_boundary_sku,
            v_boundary_name;
    END IF;

    IF v_invalid_rows <> 0 THEN
        RAISE EXCEPTION
            'Found % invalid numeric/version rows',
            v_invalid_rows;
    END IF;

    IF v_timestamp_groups <> v_row_count / 100
       OR v_min_timestamp_group <> 100
       OR v_max_timestamp_group <> 100 THEN
        RAISE EXCEPTION
            'Timestamp groups are count=%, min=%, max=%',
            v_timestamp_groups,
            v_min_timestamp_group,
            v_max_timestamp_group;
    END IF;

    RAISE NOTICE
        'dataset_verified rows=%, seed=%',
        v_row_count,
        v_seed;
END
$verify$;

SELECT
    current_database() AS database_name,
    current_user AS database_user,
    current_setting(
        'shop_benchmark.row_count'
    )::INTEGER AS expected_rows,
    current_setting(
        'shop_benchmark.seed'
    )::BIGINT AS dataset_seed,
    current_setting(
        'server_version'
    ) AS postgres_version,
    current_setting(
        'server_encoding'
    ) AS server_encoding,
    database_catalog.datcollate AS lc_collate,
    database_catalog.datctype AS lc_ctype
FROM pg_database AS database_catalog
WHERE database_catalog.datname = current_database();

SELECT
    count(*) AS total_rows,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
    ) AS active_rows,
    count(*) FILTER (
        WHERE status = 'INACTIVE'
    ) AS inactive_rows,
    count(DISTINCT lower(sku)) AS distinct_normalized_skus
FROM products;

SELECT
    count(*) FILTER (
        WHERE lower(name) LIKE '%rare orchid%'
    ) AS rare_rows,
    count(*) FILTER (
        WHERE lower(name) LIKE '%medium cedar%'
    ) AS medium_rows,
    count(*) FILTER (
        WHERE lower(name) LIKE '%common market%'
    ) AS common_rows,
    count(*) FILTER (
        WHERE name LIKE '%Điện Thoại%'
    ) AS vietnamese_rows,
    count(*) FILTER (
        WHERE name LIKE '%日本語%'
    ) AS japanese_rows,
    count(*) FILTER (
        WHERE name LIKE '%MiXeD CaSe%'
    ) AS mixed_case_rows
FROM products;

SELECT
    max(char_length(sku)) AS max_sku_length,
    max(char_length(name)) AS max_name_length,
    min(price) AS min_price,
    max(price) AS max_price,
    min(stock_quantity) AS min_stock,
    max(stock_quantity) AS max_stock
FROM products;

SELECT
    count(*) AS timestamp_group_count,
    min(timestamp_group_size) AS min_group_size,
    max(timestamp_group_size) AS max_group_size
FROM (
    SELECT
        created_at,
        count(*) AS timestamp_group_size
    FROM products
    GROUP BY created_at
) AS timestamp_groups;

SELECT
    pg_size_pretty(
        pg_relation_size(
            'public.products'::REGCLASS
        )
    ) AS table_size,
    pg_size_pretty(
        pg_indexes_size(
            'public.products'::REGCLASS
        )
    ) AS all_product_indexes_size,
    pg_size_pretty(
        pg_total_relation_size(
            'public.products'::REGCLASS
        )
    ) AS total_product_storage_size;

SELECT
    indexrelname AS index_name,
    pg_size_pretty(
        pg_relation_size(indexrelid)
    ) AS index_size
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND relname = 'products'
ORDER BY indexrelname;

SELECT
    n_live_tup,
    n_dead_tup,
    last_analyze
FROM pg_stat_user_tables
WHERE schemaname = 'public'
  AND relname = 'products';

SELECT
    installed_rank,
    version,
    description,
    success
FROM flyway_schema_history
ORDER BY installed_rank;