DO $c1_overlay$
DECLARE
    v_row_count INTEGER :=
        current_setting('shop_benchmark.row_count')::INTEGER;
    v_seed BIGINT :=
        current_setting('shop_benchmark.seed')::BIGINT;
    v_database TEXT := current_database();
    v_database_user TEXT := current_user;
    v_description_marker TEXT :=
        'V2-PS-A deterministic dataset seed ' || v_seed::TEXT;
    v_affected BIGINT;
    v_expected_overlay_rows BIGINT;
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
            'Refusing to apply C1 overlay to database % as user %',
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

    IF (
        SELECT count(*)
        FROM products
    ) <> v_row_count THEN
        RAISE EXCEPTION
            'Expected % base products before C1 overlay',
            v_row_count;
    END IF;

    IF (
        SELECT count(*)
        FROM products
        WHERE description = v_description_marker
    ) <> v_row_count THEN
        RAISE EXCEPTION
            'Base dataset marker does not cover every product';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM products
        WHERE image_key LIKE 'benchmark/c1/%'
    ) THEN
        RAISE EXCEPTION
            'C1 overlay has already been applied';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ) THEN
        RAISE EXCEPTION
            'C1 baseline must not have pg_trgm installed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
          AND (
              indexdef ILIKE '%gin_trgm_ops%'
              OR indexdef ILIKE '%gist_trgm_ops%'
              OR indexname LIKE 'c1_%'
              OR indexname LIKE 'c2_%'
          )
    ) THEN
        RAISE EXCEPTION
            'C1 baseline must not contain a trigram candidate index';
    END IF;

    UPDATE products AS product
    SET
        name = product.name || ' C1-MEDIUM-INFIX',
        image_key = 'benchmark/c1/bulk-medium'
    FROM generate_series(1, v_row_count) AS generated(n)
    WHERE product.id = md5(
        v_seed::TEXT
        || ':product:'
        || generated.n::TEXT
    )::UUID
      AND generated.n % 20 IN (1, 2)
      AND generated.n NOT BETWEEN 101 AND 124
      AND generated.n NOT IN (8, 9);

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    IF v_affected <> (v_row_count / 10) - 4 THEN
        RAISE EXCEPTION
            'Expected % medium rows, updated %',
            (v_row_count / 10) - 4,
            v_affected;
    END IF;

    UPDATE products AS product
    SET
        name = product.name || ' C1-COMMON-INFIX',
        image_key = CASE
            WHEN product.image_key = 'benchmark/c1/bulk-medium'
                THEN 'benchmark/c1/bulk-medium-common'
            ELSE 'benchmark/c1/bulk-common'
        END
    FROM generate_series(1, v_row_count) AS generated(n)
    WHERE product.id = md5(
        v_seed::TEXT
        || ':product:'
        || generated.n::TEXT
    )::UUID
      AND generated.n % 2 = 0
      AND generated.n NOT BETWEEN 101 AND 124
      AND generated.n NOT IN (8, 9);

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    IF v_affected <> (v_row_count / 2) - 13 THEN
        RAISE EXCEPTION
            'Expected % common rows, updated %',
            (v_row_count / 2) - 13,
            v_affected;
    END IF;

    UPDATE products AS product
    SET
        sku = fixture.sku,
        name = fixture.name,
        image_key = 'benchmark/c1/' || fixture.marker,
        image_url = NULL
    FROM (
        VALUES
            (
                101,
                'C1-RANK-TERM',
                'C1 Control Exact',
                'exact-sku'
            ),
            (
                102,
                'C1-RANK-TERM-PREFIX-SKU',
                'C1 Control Sku Prefix',
                'sku-prefix'
            ),
            (
                103,
                'LEFT-SKU-MIDDLE-C1-RANK-TERM-RIGHT',
                'C1 Control Sku Infix',
                'sku-infix'
            ),
            (
                104,
                'C1-NAME-PREFIX-00104',
                'C1-RANK-TERM Name Prefix',
                'name-prefix'
            ),
            (
                105,
                'C1-INACTIVE-EXACT',
                'C1 Inactive Control',
                'inactive-exact'
            ),
            (
                106,
                'C1-NAME-INFIX-00106',
                'Left NAME-MIDDLE C1-RANK-TERM Right',
                'name-infix'
            ),
            (
                107,
                'C1-SHORT-00107',
                'C1 Short Q',
                'short-q'
            ),
            (
                108,
                'C1-SHORT-00108',
                'C1 Short QZ',
                'short-qz'
            ),
            (
                109,
                'C1-SHORT-00109',
                'C1 Short QZX',
                'short-qzx'
            ),
            (
                110,
                'C1-CONTROL-00110',
                'C1 Reserved Control 110',
                'reserved-110'
            ),
            (
                111,
                'C1-MIXED-00111',
                'C1 MiXeD TeRm Product',
                'mixed-case'
            ),
            (
                112,
                'C1-ACCENTED-00112',
                U&'Thi\1EBFt b\1ECB \0111i\1EC7n C1',
                'accented'
            ),
            (
                113,
                'C1-UNACCENTED-00113',
                'Thiet bi dien C1',
                'unaccented'
            ),
            (
                114,
                'C1-UNICODE-00114',
                U&'C1 \691C\7D22\57FA\6E96 \30AD\30FC\30DC\30FC\30C9',
                'unicode'
            ),
            (
                115,
                'C1-CONTROL-00115',
                'C1 Reserved Control 115',
                'reserved-115'
            ),
            (
                116,
                'C1-PERCENT-00116',
                '%C1 Literal Percent',
                'literal-percent'
            ),
            (
                117,
                'C1-UNDERSCORE-00117',
                '_C1 Literal Underscore',
                'literal-underscore'
            ),
            (
                118,
                'C1-BACKSLASH-00118',
                U&'\005CC1 Backslash Product',
                'backslash'
            ),
            (
                119,
                'C1-ADMIN-PAIR-ACTIVE',
                'C1-ADMIN-PAIR Active Product',
                'admin-active'
            ),
            (
                120,
                'C1-ADMIN-PAIR-INACTIVE',
                'C1-ADMIN-PAIR Inactive Product',
                'admin-inactive'
            ),
            (
                121,
                'C1-HEADSET-00121',
                'C1 Wireless Headset',
                'similarity-headset'
            ),
            (
                122,
                'C1-KEYBOARD-00122',
                'C1 Mechanical Keyboard',
                'similarity-keyboard'
            ),
            (
                123,
                'LEFT-C1-DUAL-INFIX-RIGHT',
                'C1 Dual Sku Product',
                'dual-sku'
            ),
            (
                124,
                'C1-DUAL-NAME-00124',
                'Left C1-DUAL-INFIX Right',
                'dual-name'
            )
    ) AS fixture(n, sku, name, marker)
    WHERE product.id = md5(
        v_seed::TEXT
        || ':product:'
        || fixture.n::TEXT
    )::UUID;

    GET DIAGNOSTICS v_affected = ROW_COUNT;

    IF v_affected <> 24 THEN
        RAISE EXCEPTION
            'Expected 24 reserved fixture updates, updated %',
            v_affected;
    END IF;

    v_expected_overlay_rows :=
        (v_row_count::BIGINT * 11 / 20) + 9;

    IF (
        SELECT count(*)
        FROM products
        WHERE image_key LIKE 'benchmark/c1/%'
    ) <> v_expected_overlay_rows THEN
        RAISE EXCEPTION
            'Expected % C1 overlay rows',
            v_expected_overlay_rows;
    END IF;

    IF (
        SELECT count(DISTINCT lower(sku))
        FROM products
    ) <> v_row_count THEN
        RAISE EXCEPTION
            'C1 overlay introduced a duplicate SKU';
    END IF;

    IF (
        SELECT count(*)
        FROM products
        WHERE status = 'ACTIVE'
    ) <> (v_row_count * 4 / 5) THEN
        RAISE EXCEPTION
            'C1 overlay changed the ACTIVE distribution';
    END IF;

    IF (
        SELECT count(*)
        FROM products
        WHERE status = 'INACTIVE'
    ) <> (v_row_count / 5) THEN
        RAISE EXCEPTION
            'C1 overlay changed the INACTIVE distribution';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM products
        WHERE char_length(sku) > 100
           OR char_length(name) > 255
           OR char_length(image_key) > 500
    ) THEN
        RAISE EXCEPTION
            'C1 overlay exceeded a products column length';
    END IF;

    RAISE NOTICE
        'c1_overlay_rows=%, rows=%, seed=%',
        v_expected_overlay_rows,
        v_row_count,
        v_seed;
END
$c1_overlay$;
