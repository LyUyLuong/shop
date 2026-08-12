-- V2-PS-C1 deterministic correctness and cardinality verification.
-- Required psql variable: expected_rows.
-- Required session setting: shop_benchmark.row_count.
--
-- This script verifies the existing LIKE contract. It does not install
-- pg_trgm, create an index, or test similarity operators.

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif

SELECT :expected_rows::bigint IN (
    10000,
    100000
) AS input_ok
\gset input_

\if :input_input_ok
\else
\echo 'STOP: expected_rows must be 10000 or 100000.'
\quit 1
\endif

SELECT
    current_database() = 'shop_search_benchmark'
    AND current_user = 'shop_benchmark'
    AND (SELECT count(*) FROM products) = :expected_rows
    AND (
        SELECT count(*)
        FROM products
        WHERE description =
            'V2-PS-A deterministic dataset seed 20260806'
    ) = :expected_rows
    AND (
        SELECT coalesce(max(version::integer), 0)
        FROM flyway_schema_history
        WHERE success
    ) = 12
    AND NOT EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    )
    AND NOT EXISTS (
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
    ) AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C1 database, dataset, Flyway, extension, or index guard failed.'
\quit 1
\endif

CREATE TEMP TABLE c1_verification_results (
    category text NOT NULL,
    check_name text PRIMARY KEY,
    expected_value bigint,
    actual_value bigint,
    status text NOT NULL
);

CREATE FUNCTION pg_temp.c1_product_id(
    p_sequence integer
)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
AS $function$
    SELECT md5(
        '20260806:product:' || p_sequence::text
    )::uuid;
$function$;

CREATE FUNCTION pg_temp.c1_match_count(
    p_term text,
    p_status text
)
RETURNS bigint
LANGUAGE sql
STABLE
AS $function$
    SELECT count(*)
    FROM products AS p
    WHERE (
        lower(p.sku) LIKE lower(
            '%' || p_term || '%'
        ) ESCAPE ''
        OR lower(p.name) LIKE lower(
            '%' || p_term || '%'
        ) ESCAPE ''
    )
    AND (
        p_status = 'ALL'
        OR p.status = p_status
    );
$function$;

CREATE FUNCTION pg_temp.c1_ranked_ids(
    p_term text,
    p_status text,
    p_limit integer
)
RETURNS uuid[]
LANGUAGE sql
STABLE
AS $function$
    SELECT coalesce(
        array_agg(
            ranked.id
            ORDER BY
                ranked.match_priority ASC,
                ranked.created_at DESC,
                ranked.id DESC
        ),
        ARRAY[]::uuid[]
    )
    FROM (
        SELECT
            p.id,
            p.created_at,
            CASE
                WHEN lower(p.sku) = lower(p_term)
                    THEN 0
                WHEN lower(p.name) LIKE lower(
                    replace(
                        replace(
                            replace(
                                p_term,
                                E'\\',
                                E'\\\\'
                            ),
                            '%',
                            E'\\%'
                        ),
                        '_',
                        E'\\_'
                    ) || '%'
                ) ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END AS match_priority
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE lower(
                '%' || p_term || '%'
            ) ESCAPE ''
            OR lower(p.name) LIKE lower(
                '%' || p_term || '%'
            ) ESCAPE ''
        )
        AND (
            p_status = 'ALL'
            OR p.status = p_status
        )
        ORDER BY
            CASE
                WHEN lower(p.sku) = lower(p_term)
                    THEN 0
                WHEN lower(p.name) LIKE lower(
                    replace(
                        replace(
                            replace(
                                p_term,
                                E'\\',
                                E'\\\\'
                            ),
                            '%',
                            E'\\%'
                        ),
                        '_',
                        E'\\_'
                    ) || '%'
                ) ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END ASC,
            p.created_at DESC,
            p.id DESC
        FETCH FIRST p_limit ROWS ONLY
    ) AS ranked;
$function$;

CREATE FUNCTION pg_temp.c1_expected_ids(
    p_row_count integer,
    p_sequences integer[],
    p_priorities integer[]
)
RETURNS uuid[]
LANGUAGE sql
IMMUTABLE
AS $function$
    SELECT array_agg(
        fixture.id
        ORDER BY
            fixture.priority ASC,
            fixture.timestamp_group DESC,
            fixture.id DESC
    )
    FROM (
        SELECT
            sequence_value AS sequence_number,
            p_priorities[sequence_order] AS priority,
            (
                (
                    sequence_value::bigint * 7919
                ) % p_row_count
            ) / 100 AS timestamp_group,
            pg_temp.c1_product_id(
                sequence_value
            ) AS id
        FROM unnest(p_sequences)
            WITH ORDINALITY
            AS sequence_list(
                sequence_value,
                sequence_order
            )
    ) AS fixture;
$function$;

CREATE FUNCTION pg_temp.c1_record(
    p_category text,
    p_check_name text,
    p_expected bigint,
    p_actual bigint
)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    INSERT INTO c1_verification_results (
        category,
        check_name,
        expected_value,
        actual_value,
        status
    )
    VALUES (
        p_category,
        p_check_name,
        p_expected,
        p_actual,
        CASE
            WHEN p_actual = p_expected THEN 'PASS'
            ELSE 'FAIL'
        END
    );

    IF p_actual <> p_expected THEN
        RAISE EXCEPTION
            'C1 check % expected %, found %',
            p_check_name,
            p_expected,
            p_actual;
    END IF;
END
$function$;

DO $verification$
DECLARE
    v_rows integer :=
        current_setting('shop_benchmark.row_count')::integer;

    v_expected_overlay bigint :=
        (v_rows::bigint * 11 / 20) + 9;

    v_medium bigint :=
        (v_rows::bigint / 10) - 4;

    v_common_all bigint :=
        (v_rows::bigint / 2) - 13;

    v_common_active bigint :=
        (v_rows::bigint * 2 / 5) - 11;

    v_common_inactive bigint :=
        (v_rows::bigint / 10) - 2;

    v_actual_ids uuid[];
    v_expected_ids uuid[];

    v_anchor_priority integer;
    v_anchor_created_at timestamptz;
    v_anchor_id uuid;

    v_offset_ids uuid[];
    v_cursor_ids uuid[];

    v_deep_offset bigint :=
        v_rows::bigint / 4;

    v_page_size integer := 100;
BEGIN
    PERFORM pg_temp.c1_record(
        'DATASET',
        'total_rows',
        v_rows,
        (SELECT count(*) FROM products)
    );

    PERFORM pg_temp.c1_record(
        'DATASET',
        'active_rows',
        v_rows * 4 / 5,
        (
            SELECT count(*)
            FROM products
            WHERE status = 'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'DATASET',
        'inactive_rows',
        v_rows / 5,
        (
            SELECT count(*)
            FROM products
            WHERE status = 'INACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'DATASET',
        'overlay_rows',
        v_expected_overlay,
        (
            SELECT count(*)
            FROM products
            WHERE image_key LIKE 'benchmark/c1/%'
        )
    );

    PERFORM pg_temp.c1_record(
        'DATASET',
        'distinct_normalized_skus',
        v_rows,
        (
            SELECT count(DISTINCT lower(sku))
            FROM products
        )
    );

    PERFORM pg_temp.c1_record(
        'SCHEMA',
        'flyway_latest_version',
        12,
        (
            SELECT coalesce(
                max(version::integer),
                0
            )
            FROM flyway_schema_history
            WHERE success
        )
    );

    PERFORM pg_temp.c1_record(
        'SCHEMA',
        'pg_trgm_installed',
        0,
        (
            SELECT count(*)
            FROM pg_extension
            WHERE extname = 'pg_trgm'
        )
    );

    PERFORM pg_temp.c1_record(
        'SCHEMA',
        'pg_trgm_available',
        1,
        (
            SELECT count(*)
            FROM pg_available_extensions
            WHERE name = 'pg_trgm'
        )
    );

    PERFORM pg_temp.c1_record(
        'SCHEMA',
        'trigram_candidate_indexes',
        0,
        (
            SELECT count(*)
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'products'
              AND (
                  indexdef ILIKE '%gin_trgm_ops%'
                  OR indexdef ILIKE '%gist_trgm_ops%'
                  OR indexname LIKE 'c1_%'
                  OR indexname LIKE 'c2_%'
              )
        )
    );

    PERFORM pg_temp.c1_record(
        'SCHEMA',
        'baseline_product_indexes',
        1,
        CASE
            WHEN (
                SELECT array_agg(
                    indexname::text
                    ORDER BY indexname::text
                )
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'products'
            ) = ARRAY[
                'idx_products_created_at',
                'idx_products_name_lower',
                'idx_products_sku_lower',
                'idx_products_status',
                'products_pkey'
            ]::text[]
                THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'rank_term_membership',
        5,
        pg_temp.c1_match_count(
            'C1-RANK-TERM',
            'ACTIVE'
        )
    );

    v_actual_ids := pg_temp.c1_ranked_ids(
        'C1-RANK-TERM',
        'ACTIVE',
        v_rows
    );

    v_expected_ids := pg_temp.c1_expected_ids(
        v_rows,
        ARRAY[101, 102, 103, 104, 106],
        ARRAY[0, 2, 2, 1, 2]
    );

    PERFORM pg_temp.c1_record(
        'ORDER',
        'rank_term_exact_prefix_infix_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'sku_prefix_membership',
        1,
        pg_temp.c1_match_count(
            'C1-RANK-TERM-PREFIX-SKU',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'sku_middle_infix_membership',
        1,
        pg_temp.c1_match_count(
            'SKU-MIDDLE',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'name_prefix_membership',
        1,
        pg_temp.c1_match_count(
            'C1-RANK-TERM Name',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'name_middle_infix_membership',
        1,
        pg_temp.c1_match_count(
            'NAME-MIDDLE',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'medium_infix_active',
        v_medium,
        pg_temp.c1_match_count(
            'C1-MEDIUM-INFIX',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'common_infix_all_statuses',
        v_common_all,
        pg_temp.c1_match_count(
            'C1-COMMON-INFIX',
            'ALL'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'common_infix_active',
        v_common_active,
        pg_temp.c1_match_count(
            'C1-COMMON-INFIX',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'common_infix_inactive',
        v_common_inactive,
        pg_temp.c1_match_count(
            'C1-COMMON-INFIX',
            'INACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'no_result_term',
        0,
        pg_temp.c1_match_count(
            'C1-NO-RESULT-NEEDLE',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'one_character_term',
        3,
        pg_temp.c1_match_count(
            'q',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'two_character_term',
        2,
        pg_temp.c1_match_count(
            'qz',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'three_character_term',
        1,
        pg_temp.c1_match_count(
            'qzx',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'mixed_case_term',
        1,
        pg_temp.c1_match_count(
            'c1 mixed term',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'accented_vietnamese_term',
        1,
        pg_temp.c1_match_count(
            U&'Thi\1EBFt b\1ECB \0111i\1EC7n',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'unaccented_vietnamese_term',
        1,
        pg_temp.c1_match_count(
            'Thiet bi dien',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'accented_term_does_not_fold',
        1,
        CASE
            WHEN pg_temp.c1_ranked_ids(
                'Thiet bi dien',
                'ACTIVE',
                v_rows
            ) = ARRAY[
                pg_temp.c1_product_id(113)
            ]::uuid[]
                THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'unicode_infix_term',
        1,
        pg_temp.c1_match_count(
            U&'\691C\7D22\57FA\6E96',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'percent_wildcard_active',
        v_rows * 4 / 5,
        pg_temp.c1_match_count(
            '%',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'ORDER',
        'percent_literal_prefix_priority',
        1,
        CASE
            WHEN pg_temp.c1_ranked_ids(
                '%',
                'ACTIVE',
                1
            ) = ARRAY[
                pg_temp.c1_product_id(116)
            ]::uuid[]
                THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'underscore_wildcard_active',
        v_rows * 4 / 5,
        pg_temp.c1_match_count(
            '_',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'ORDER',
        'underscore_literal_prefix_priority',
        1,
        CASE
            WHEN pg_temp.c1_ranked_ids(
                '_',
                'ACTIVE',
                1
            ) = ARRAY[
                pg_temp.c1_product_id(117)
            ]::uuid[]
                THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'backslash_character',
        1,
        pg_temp.c1_match_count(
            E'\\',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'LIKE',
        'five_thousand_character_term',
        0,
        pg_temp.c1_match_count(
            repeat('x', 5000),
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'inactive_exact_public',
        0,
        pg_temp.c1_match_count(
            'C1-INACTIVE-EXACT',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'inactive_exact_admin_all',
        1,
        pg_temp.c1_match_count(
            'C1-INACTIVE-EXACT',
            'ALL'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'inactive_exact_admin_inactive',
        1,
        pg_temp.c1_match_count(
            'C1-INACTIVE-EXACT',
            'INACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'admin_pair_public',
        1,
        pg_temp.c1_match_count(
            'C1-ADMIN-PAIR',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'admin_pair_all',
        2,
        pg_temp.c1_match_count(
            'C1-ADMIN-PAIR',
            'ALL'
        )
    );

    PERFORM pg_temp.c1_record(
        'VISIBILITY',
        'admin_pair_inactive',
        1,
        pg_temp.c1_match_count(
            'C1-ADMIN-PAIR',
            'INACTIVE'
        )
    );

    v_actual_ids := pg_temp.c1_ranked_ids(
        'C1-ADMIN-PAIR',
        'ALL',
        v_rows
    );

    v_expected_ids := pg_temp.c1_expected_ids(
        v_rows,
        ARRAY[119, 120],
        ARRAY[1, 1]
    );

    PERFORM pg_temp.c1_record(
        'ORDER',
        'admin_pair_total_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
    );

    v_actual_ids := pg_temp.c1_ranked_ids(
        'C1-DUAL-INFIX',
        'ACTIVE',
        v_rows
    );

    v_expected_ids := pg_temp.c1_expected_ids(
        v_rows,
        ARRAY[123, 124],
        ARRAY[2, 2]
    );

    PERFORM pg_temp.c1_record(
        'ORDER',
        'dual_field_total_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'correct_headset_term',
        1,
        pg_temp.c1_match_count(
            'wireless headset',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'headset_single_edit_not_like_match',
        0,
        pg_temp.c1_match_count(
            'wireles headset',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'headset_transposition_not_like_match',
        0,
        pg_temp.c1_match_count(
            'wireless haedset',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'correct_keyboard_term',
        1,
        pg_temp.c1_match_count(
            'mechanical keyboard',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'keyboard_single_edit_not_like_match',
        0,
        pg_temp.c1_match_count(
            'mechanicl keyboard',
            'ACTIVE'
        )
    );

    PERFORM pg_temp.c1_record(
        'SIMILARITY_FIXTURE_CURRENT_LIKE',
        'keyboard_transposition_not_like_match',
        0,
        pg_temp.c1_match_count(
            'mechanical keybaord',
            'ACTIVE'
        )
    );

    SELECT
        ranked.match_priority,
        ranked.created_at,
        ranked.id
    INTO STRICT
        v_anchor_priority,
        v_anchor_created_at,
        v_anchor_id
    FROM (
        SELECT
            p.id,
            p.created_at,
            CASE
                WHEN lower(p.sku) =
                     lower('C1-COMMON-INFIX')
                    THEN 0
                WHEN lower(p.name) LIKE lower(
                    'C1-COMMON-INFIX%'
                ) ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END AS match_priority
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE lower(
                '%C1-COMMON-INFIX%'
            ) ESCAPE ''
            OR lower(p.name) LIKE lower(
                '%C1-COMMON-INFIX%'
            ) ESCAPE ''
        )
        AND p.status = 'ACTIVE'
    ) AS ranked
    ORDER BY
        ranked.match_priority ASC,
        ranked.created_at DESC,
        ranked.id DESC
    OFFSET (v_deep_offset - 1)
    FETCH FIRST 1 ROW ONLY;

    SELECT array_agg(
        page.id
        ORDER BY
            page.match_priority ASC,
            page.created_at DESC,
            page.id DESC
    )
    INTO v_offset_ids
    FROM (
        SELECT
            p.id,
            p.created_at,
            CASE
                WHEN lower(p.sku) =
                     lower('C1-COMMON-INFIX')
                    THEN 0
                WHEN lower(p.name) LIKE lower(
                    'C1-COMMON-INFIX%'
                ) ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END AS match_priority
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE lower(
                '%C1-COMMON-INFIX%'
            ) ESCAPE ''
            OR lower(p.name) LIKE lower(
                '%C1-COMMON-INFIX%'
            ) ESCAPE ''
        )
        AND p.status = 'ACTIVE'
        ORDER BY
            CASE
                WHEN lower(p.sku) =
                     lower('C1-COMMON-INFIX')
                    THEN 0
                WHEN lower(p.name) LIKE lower(
                    'C1-COMMON-INFIX%'
                ) ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END ASC,
            p.created_at DESC,
            p.id DESC
        OFFSET v_deep_offset
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    SELECT array_agg(
        page.id
        ORDER BY
            page.match_priority ASC,
            page.created_at DESC,
            page.id DESC
    )
    INTO v_cursor_ids
    FROM (
        SELECT
            ranked.id,
            ranked.created_at,
            ranked.match_priority
        FROM (
            SELECT
                p.id,
                p.created_at,
                CASE
                    WHEN lower(p.sku) =
                         lower('C1-COMMON-INFIX')
                        THEN 0
                    WHEN lower(p.name) LIKE lower(
                        'C1-COMMON-INFIX%'
                    ) ESCAPE E'\\'
                        THEN 1
                    ELSE 2
                END AS match_priority
            FROM products AS p
            WHERE (
                lower(p.sku) LIKE lower(
                    '%C1-COMMON-INFIX%'
                ) ESCAPE ''
                OR lower(p.name) LIKE lower(
                    '%C1-COMMON-INFIX%'
                ) ESCAPE ''
            )
            AND p.status = 'ACTIVE'
        ) AS ranked
        WHERE ranked.match_priority > v_anchor_priority
           OR (
               ranked.match_priority = v_anchor_priority
               AND (
                   ranked.created_at < v_anchor_created_at
                   OR (
                       ranked.created_at = v_anchor_created_at
                       AND ranked.id < v_anchor_id
                   )
               )
           )
        ORDER BY
            ranked.match_priority ASC,
            ranked.created_at DESC,
            ranked.id DESC
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    PERFORM pg_temp.c1_record(
        'PAGINATION',
        'common_deep_offset_page_size',
        v_page_size,
        coalesce(cardinality(v_offset_ids), 0)
    );

    PERFORM pg_temp.c1_record(
        'PAGINATION',
        'common_deep_cursor_page_size',
        v_page_size,
        coalesce(cardinality(v_cursor_ids), 0)
    );

    PERFORM pg_temp.c1_record(
        'PAGINATION',
        'common_deep_offset_cursor_parity',
        1,
        CASE
            WHEN v_offset_ids IS NOT DISTINCT FROM v_cursor_ids
                THEN 1
            ELSE 0
        END
    );
END
$verification$;

SELECT
    category,
    check_name,
    expected_value,
    actual_value,
    status
FROM c1_verification_results
ORDER BY
    category,
    check_name;

SELECT 'verification_result=success';
