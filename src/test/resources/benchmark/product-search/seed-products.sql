DO $seed$
DECLARE
    v_row_count INTEGER :=
        current_setting(
            'shop_benchmark.row_count'
        )::INTEGER;
    v_seed BIGINT :=
        current_setting(
            'shop_benchmark.seed'
        )::BIGINT;
    v_database TEXT := current_database();
    v_database_user TEXT := current_user;
    v_inserted BIGINT;
    v_started_at TIMESTAMPTZ;
BEGIN
    IF NOT (
        (
            v_database = 'shop_search_benchmark'
            AND v_database_user = 'shop_benchmark'
        )
        OR
        (
            v_database = 'shop_test'
            AND v_database_user = 'shop_test'
        )
    ) THEN
        RAISE EXCEPTION
            'Refusing to seed database % as user %',
            v_database,
            v_database_user;
    END IF;

    IF v_row_count NOT IN (
        1000,
        10000,
        100000,
        1000000
    ) THEN
        RAISE EXCEPTION
            'Unsupported row count: %',
            v_row_count;
    END IF;

    IF v_seed <> 20260806 THEN
        RAISE EXCEPTION
            'Unexpected dataset seed: %',
            v_seed;
    END IF;

    IF v_database = 'shop_search_benchmark'
       AND EXISTS (SELECT 1 FROM products) THEN
        RAISE EXCEPTION
            'Benchmark products table must be empty';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM products
        WHERE description =
            'V2-PS-A deterministic dataset seed '
            || v_seed::TEXT
    ) THEN
        RAISE EXCEPTION
            'Dataset seed % already exists',
            v_seed;
    END IF;

    v_started_at := clock_timestamp();

    INSERT INTO products (
        id,
        sku,
        version,
        name,
        description,
        price,
        stock_quantity,
        status,
        image_key,
        image_url,
        created_at,
        updated_at
    )
    SELECT
        md5(
            v_seed::TEXT
            || ':product:'
            || generated.n::TEXT
        )::UUID,

        CASE
            WHEN generated.n = 8 THEN
                rpad(
                    'BOUNDARY-SKU-'
                    || lpad(
                        generated.n::TEXT,
                        8,
                        '0'
                    ),
                    100,
                    'X'
                )
            ELSE
                CASE generated.n % 4
                    WHEN 0 THEN 'ALPHA'
                    WHEN 1 THEN 'BRAVO'
                    WHEN 2 THEN 'CHARLIE'
                    ELSE 'DELTA'
                END
                || '-'
                || v_seed::TEXT
                || '-'
                || lpad(
                    generated.n::TEXT,
                    8,
                    '0'
                )
                || CASE generated.n % 3
                    WHEN 0 THEN '-RED'
                    WHEN 1 THEN '-GREEN'
                    ELSE '-BLUE'
                END
        END,

        0,

        CASE
            WHEN generated.n = 9 THEN
                rpad(
                    'Common Market Boundary Product '
                    || lpad(
                        generated.n::TEXT,
                        8,
                        '0'
                    )
                    || ' ',
                    255,
                    'N'
                )
            ELSE
                CASE
                    WHEN generated.n = 7 THEN
                        'Rare Orchid'
                    WHEN generated.n % 20 IN (0, 1) THEN
                        'Medium Cedar'
                    ELSE
                        'Common Market'
                END
                || ' Product '
                || lpad(
                    generated.n::TEXT,
                    8,
                    '0'
                )
                || CASE
                    WHEN generated.n % 100 IN (2, 5) THEN
                        ' Điện Thoại'
                    WHEN generated.n % 100 IN (3, 10) THEN
                        ' 日本語'
                    WHEN generated.n % 100 IN (4, 15) THEN
                        ' MiXeD CaSe'
                    ELSE
                        ''
                END
        END,

        'V2-PS-A deterministic dataset seed '
        || v_seed::TEXT,

        round(
            (
                1000
                + (
                    (
                        generated.n::BIGINT * 137
                    ) % 5000000
                )
            )::NUMERIC / 100,
            2
        ),

        (
            (
                generated.n::BIGINT * 17
            ) % 501
        )::INTEGER,

        CASE
            WHEN generated.n % 5 = 0 THEN
                'INACTIVE'
            ELSE
                'ACTIVE'
        END,

        NULL,
        NULL,
        generated.created_at,
        generated.created_at + INTERVAL '1 hour'
    FROM (
        SELECT
            series.n,
            TIMESTAMPTZ '2026-01-01 00:00:00+00'
            + (
                (
                    (
                        (
                            series.n::BIGINT * 7919
                        ) % v_row_count
                    ) / 100
                ) * INTERVAL '1 second'
            ) AS created_at
        FROM generate_series(
            1,
            v_row_count
        ) AS series(n)
    ) AS generated;

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    IF v_inserted <> v_row_count THEN
        RAISE EXCEPTION
            'Expected % inserted rows, found %',
            v_row_count,
            v_inserted;
    END IF;

    RAISE NOTICE
        'seed_rows=%, seed=%, server_insert_elapsed_ms=%',
        v_inserted,
        v_seed,
        round(
            EXTRACT(
                EPOCH FROM (
                    clock_timestamp() - v_started_at
                )
            ) * 1000,
            3
        );
END
$seed$;