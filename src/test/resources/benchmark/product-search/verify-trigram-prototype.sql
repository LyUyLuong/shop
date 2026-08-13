-- V2-PS-C2 correctness, schema, and LAB_ONLY similarity verification.
-- Required psql variables: expected_rows, candidate.
-- Required candidate state: exactly one complete GIN or GiST pair is active.

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif

\if :{?candidate}
\else
\echo 'Missing required variable: candidate'
\quit 1
\endif

SELECT
    :expected_rows::integer = 10000
    AND :'candidate' IN ('gin', 'gist') AS input_ok
\gset input_

\if :input_input_ok
\else
\echo 'STOP: C2 verification requires expected_rows=10000 and candidate=gin|gist.'
\quit 1
\endif

SELECT set_config(
    'shop_benchmark.row_count',
    :'expected_rows',
    false
)
\gset setting_

SELECT set_config(
    'shop_benchmark.candidate',
    :'candidate',
    false
)
\gset setting_

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
        SELECT count(*)
        FROM products
        WHERE image_key LIKE 'benchmark/c1/%'
    ) = (:expected_rows::bigint * 11 / 20) + 9
    AND (
        SELECT coalesce(max(version::integer), 0)
        FROM flyway_schema_history
        WHERE success
    ) = 12
    AND (
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ) = 1
    AND (
        SELECT count(*)
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
          AND (
              indexdef ILIKE '%gin_trgm_ops%'
              OR indexdef ILIKE '%gist_trgm_ops%'
              OR indexname LIKE 'c2_%'
          )
    ) = 2
    AND (
        (:'candidate' = 'gin'
         AND to_regclass(
             'public.c2_products_sku_lower_gin_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c2_products_name_lower_gin_trgm'
         ) IS NOT NULL
         AND pg_get_indexdef(to_regclass(
             'public.c2_products_sku_lower_gin_trgm'
         )) ILIKE '%USING gin%lower%sku%gin_trgm_ops%'
         AND pg_get_indexdef(to_regclass(
             'public.c2_products_name_lower_gin_trgm'
         )) ILIKE '%USING gin%lower%name%gin_trgm_ops%'
         AND to_regclass(
             'public.c2_products_sku_lower_gist_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c2_products_name_lower_gist_trgm'
         ) IS NULL)
        OR
        (:'candidate' = 'gist'
         AND to_regclass(
             'public.c2_products_sku_lower_gist_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c2_products_name_lower_gist_trgm'
         ) IS NOT NULL
         AND pg_get_indexdef(to_regclass(
             'public.c2_products_sku_lower_gist_trgm'
         )) ILIKE '%USING gist%lower%sku%gist_trgm_ops%'
         AND pg_get_indexdef(to_regclass(
             'public.c2_products_name_lower_gist_trgm'
         )) ILIKE '%USING gist%lower%name%gist_trgm_ops%'
         AND to_regclass(
             'public.c2_products_sku_lower_gin_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c2_products_name_lower_gin_trgm'
         ) IS NULL)
    ) AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C2 verification environment or candidate guard failed.'
\quit 1
\endif

CREATE TEMP TABLE c2_verification_results (
    category text NOT NULL,
    check_name text PRIMARY KEY,
    expected_value bigint,
    actual_value bigint,
    status text NOT NULL
);

CREATE FUNCTION pg_temp.c2_product_id(
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

CREATE FUNCTION pg_temp.c2_match_count(
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

CREATE FUNCTION pg_temp.c2_ranked_page_ids(
    p_term text,
    p_status text,
    p_offset integer,
    p_limit integer
)
RETURNS uuid[]
LANGUAGE sql
STABLE
AS $function$
    SELECT coalesce(
        array_agg(
            page.id
            ORDER BY
                page.match_priority ASC,
                page.created_at DESC,
                page.id DESC
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
        OFFSET p_offset
        FETCH FIRST p_limit ROWS ONLY
    ) AS page;
$function$;

CREATE FUNCTION pg_temp.c2_cursor_page_ids(
    p_term text,
    p_status text,
    p_anchor_priority integer,
    p_anchor_created_at timestamptz,
    p_anchor_id uuid,
    p_limit integer
)
RETURNS uuid[]
LANGUAGE sql
STABLE
AS $function$
    SELECT coalesce(
        array_agg(
            page.id
            ORDER BY
                page.match_priority ASC,
                page.created_at DESC,
                page.id DESC
        ),
        ARRAY[]::uuid[]
    )
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
        ) AS ranked
        WHERE ranked.match_priority > p_anchor_priority
           OR (
               ranked.match_priority = p_anchor_priority
               AND (
                   ranked.created_at < p_anchor_created_at
                   OR (
                       ranked.created_at = p_anchor_created_at
                       AND ranked.id < p_anchor_id
                   )
               )
           )
        ORDER BY
            ranked.match_priority ASC,
            ranked.created_at DESC,
            ranked.id DESC
        FETCH FIRST p_limit ROWS ONLY
    ) AS page;
$function$;

CREATE FUNCTION pg_temp.c2_expected_ids(
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
            p_priorities[sequence_order] AS priority,
            (
                (sequence_value::bigint * 7919) %
                p_row_count
            ) / 100 AS timestamp_group,
            pg_temp.c2_product_id(sequence_value) AS id
        FROM unnest(p_sequences)
            WITH ORDINALITY
            AS sequence_list(
                sequence_value,
                sequence_order
            )
    ) AS fixture;
$function$;

CREATE FUNCTION pg_temp.c2_record(
    p_category text,
    p_check_name text,
    p_expected bigint,
    p_actual bigint
)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    INSERT INTO c2_verification_results (
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
            'C2 check % expected %, found %',
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
    v_candidate text :=
        current_setting('shop_benchmark.candidate');

    v_expected_indexes text[];
    v_case record;
    v_actual_ids uuid[];
    v_expected_ids uuid[];

    v_anchor_priority integer;
    v_anchor_created_at timestamptz;
    v_anchor_id uuid;
    v_offset_ids uuid[];
    v_cursor_ids uuid[];

    v_deep_offset integer := v_rows / 4;
    v_page_size integer := 100;

    v_typo_similarity real;
    v_transposition_similarity real;
    v_unrelated_similarity real;
BEGIN
    v_expected_indexes := CASE v_candidate
        WHEN 'gin' THEN ARRAY[
            'c2_products_name_lower_gin_trgm',
            'c2_products_sku_lower_gin_trgm',
            'idx_products_created_at',
            'idx_products_name_lower',
            'idx_products_sku_lower',
            'idx_products_status',
            'products_pkey'
        ]::text[]
        WHEN 'gist' THEN ARRAY[
            'c2_products_name_lower_gist_trgm',
            'c2_products_sku_lower_gist_trgm',
            'idx_products_created_at',
            'idx_products_name_lower',
            'idx_products_sku_lower',
            'idx_products_status',
            'products_pkey'
        ]::text[]
        ELSE ARRAY[]::text[]
    END;

    PERFORM pg_temp.c2_record(
        'DATASET',
        'total_rows',
        v_rows,
        (SELECT count(*) FROM products)
    );

    PERFORM pg_temp.c2_record(
        'DATASET',
        'active_rows',
        v_rows * 4 / 5,
        (
            SELECT count(*)
            FROM products
            WHERE status = 'ACTIVE'
        )
    );

    PERFORM pg_temp.c2_record(
        'DATASET',
        'inactive_rows',
        v_rows / 5,
        (
            SELECT count(*)
            FROM products
            WHERE status = 'INACTIVE'
        )
    );

    PERFORM pg_temp.c2_record(
        'SCHEMA',
        'pg_trgm_installed',
        1,
        (
            SELECT count(*)
            FROM pg_extension
            WHERE extname = 'pg_trgm'
        )
    );

    PERFORM pg_temp.c2_record(
        'SCHEMA',
        'selected_candidate_pair',
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
            ) = v_expected_indexes
                THEN 1
            ELSE 0
        END
    );

    FOR v_case IN
        SELECT *
        FROM (
            VALUES
                ('LIKE', 'rank_term_membership',
                 'C1-RANK-TERM', 'ACTIVE', 5::bigint),
                ('LIKE', 'sku_prefix_membership',
                 'C1-RANK-TERM-PREFIX-SKU', 'ACTIVE', 1::bigint),
                ('LIKE', 'sku_middle_infix_membership',
                 'SKU-MIDDLE', 'ACTIVE', 1::bigint),
                ('LIKE', 'name_prefix_membership',
                 'C1-RANK-TERM Name', 'ACTIVE', 1::bigint),
                ('LIKE', 'name_middle_infix_membership',
                 'NAME-MIDDLE', 'ACTIVE', 1::bigint),
                ('LIKE', 'medium_infix_active',
                 'C1-MEDIUM-INFIX', 'ACTIVE',
                 (v_rows::bigint / 10) - 4),
                ('LIKE', 'common_infix_all_statuses',
                 'C1-COMMON-INFIX', 'ALL',
                 (v_rows::bigint / 2) - 13),
                ('LIKE', 'common_infix_active',
                 'C1-COMMON-INFIX', 'ACTIVE',
                 (v_rows::bigint * 2 / 5) - 11),
                ('LIKE', 'common_infix_inactive',
                 'C1-COMMON-INFIX', 'INACTIVE',
                 (v_rows::bigint / 10) - 2),
                ('LIKE', 'no_result_term',
                 'C1-NO-RESULT-NEEDLE', 'ACTIVE', 0::bigint),
                ('LIKE', 'one_character_term',
                 'q', 'ACTIVE', 3::bigint),
                ('LIKE', 'two_character_term',
                 'qz', 'ACTIVE', 2::bigint),
                ('LIKE', 'three_character_term',
                 'qzx', 'ACTIVE', 1::bigint),
                ('LIKE', 'mixed_case_term',
                 'c1 mixed term', 'ACTIVE', 1::bigint),
                ('LIKE', 'accented_vietnamese_term',
                 U&'Thi\1EBFt b\1ECB \0111i\1EC7n',
                 'ACTIVE', 1::bigint),
                ('LIKE', 'unaccented_vietnamese_term',
                 'Thiet bi dien', 'ACTIVE', 1::bigint),
                ('LIKE', 'unicode_infix_term',
                 U&'\691C\7D22\57FA\6E96', 'ACTIVE', 1::bigint),
                ('LIKE', 'percent_wildcard_active',
                 '%', 'ACTIVE', (v_rows::bigint * 4 / 5)),
                ('LIKE', 'underscore_wildcard_active',
                 '_', 'ACTIVE', (v_rows::bigint * 4 / 5)),
                ('LIKE', 'backslash_character',
                 E'\\', 'ACTIVE', 1::bigint),
                ('LIKE', 'five_thousand_character_term',
                 repeat('x', 5000), 'ACTIVE', 0::bigint),
                ('VISIBILITY', 'inactive_exact_public',
                 'C1-INACTIVE-EXACT', 'ACTIVE', 0::bigint),
                ('VISIBILITY', 'inactive_exact_admin_all',
                 'C1-INACTIVE-EXACT', 'ALL', 1::bigint),
                ('VISIBILITY', 'inactive_exact_admin_inactive',
                 'C1-INACTIVE-EXACT', 'INACTIVE', 1::bigint),
                ('VISIBILITY', 'admin_pair_public',
                 'C1-ADMIN-PAIR', 'ACTIVE', 1::bigint),
                ('VISIBILITY', 'admin_pair_all',
                 'C1-ADMIN-PAIR', 'ALL', 2::bigint),
                ('VISIBILITY', 'admin_pair_inactive',
                 'C1-ADMIN-PAIR', 'INACTIVE', 1::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'correct_headset_term',
                 'wireless headset', 'ACTIVE', 1::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'headset_single_edit_not_like_match',
                 'wireles headset', 'ACTIVE', 0::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'headset_transposition_not_like_match',
                 'wireless haedset', 'ACTIVE', 0::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'correct_keyboard_term',
                 'mechanical keyboard', 'ACTIVE', 1::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'keyboard_single_edit_not_like_match',
                 'mechanicl keyboard', 'ACTIVE', 0::bigint),
                ('SIMILARITY_FIXTURE_CURRENT_LIKE',
                 'keyboard_transposition_not_like_match',
                 'mechanical keybaord', 'ACTIVE', 0::bigint)
        ) AS cases(
            category,
            check_name,
            term,
            status,
            expected_value
        )
    LOOP
        PERFORM pg_temp.c2_record(
            v_case.category,
            v_case.check_name,
            v_case.expected_value,
            pg_temp.c2_match_count(
                v_case.term,
                v_case.status
            )
        );
    END LOOP;

    v_actual_ids := pg_temp.c2_ranked_page_ids(
        'C1-RANK-TERM',
        'ACTIVE',
        0,
        v_rows
    );
    v_expected_ids := pg_temp.c2_expected_ids(
        v_rows,
        ARRAY[101, 102, 103, 104, 106],
        ARRAY[0, 2, 2, 1, 2]
    );
    PERFORM pg_temp.c2_record(
        'ORDER',
        'rank_term_exact_prefix_infix_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c2_record(
        'ORDER',
        'percent_literal_prefix_priority',
        1,
        CASE
            WHEN pg_temp.c2_ranked_page_ids(
                '%', 'ACTIVE', 0, 1
            ) = ARRAY[
                pg_temp.c2_product_id(116)
            ]::uuid[] THEN 1
            ELSE 0
        END
    );

    PERFORM pg_temp.c2_record(
        'ORDER',
        'underscore_literal_prefix_priority',
        1,
        CASE
            WHEN pg_temp.c2_ranked_page_ids(
                '_', 'ACTIVE', 0, 1
            ) = ARRAY[
                pg_temp.c2_product_id(117)
            ]::uuid[] THEN 1
            ELSE 0
        END
    );

    v_actual_ids := pg_temp.c2_ranked_page_ids(
        'C1-ADMIN-PAIR',
        'ALL',
        0,
        v_rows
    );
    v_expected_ids := pg_temp.c2_expected_ids(
        v_rows,
        ARRAY[119, 120],
        ARRAY[1, 1]
    );
    PERFORM pg_temp.c2_record(
        'ORDER',
        'admin_pair_total_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
    );

    v_actual_ids := pg_temp.c2_ranked_page_ids(
        'C1-DUAL-INFIX',
        'ACTIVE',
        0,
        v_rows
    );
    v_expected_ids := pg_temp.c2_expected_ids(
        v_rows,
        ARRAY[123, 124],
        ARRAY[2, 2]
    );
    PERFORM pg_temp.c2_record(
        'ORDER',
        'dual_field_total_order',
        1,
        CASE
            WHEN v_actual_ids = v_expected_ids THEN 1
            ELSE 0
        END
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
                WHEN lower(p.sku) = lower('C1-COMMON-INFIX')
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

    v_offset_ids := pg_temp.c2_ranked_page_ids(
        'C1-COMMON-INFIX',
        'ACTIVE',
        v_deep_offset,
        v_page_size
    );

    v_cursor_ids := pg_temp.c2_cursor_page_ids(
        'C1-COMMON-INFIX',
        'ACTIVE',
        v_anchor_priority,
        v_anchor_created_at,
        v_anchor_id,
        v_page_size
    );

    PERFORM pg_temp.c2_record(
        'PAGINATION',
        'common_deep_offset_page_size',
        v_page_size,
        cardinality(v_offset_ids)
    );
    PERFORM pg_temp.c2_record(
        'PAGINATION',
        'common_deep_cursor_page_size',
        v_page_size,
        cardinality(v_cursor_ids)
    );
    PERFORM pg_temp.c2_record(
        'PAGINATION',
        'common_deep_offset_cursor_parity',
        1,
        CASE
            WHEN v_offset_ids IS NOT DISTINCT FROM v_cursor_ids
                THEN 1
            ELSE 0
        END
    );

    v_typo_similarity := similarity(
        'wireless headset',
        'wireles headset'
    );
    v_transposition_similarity := similarity(
        'wireless headset',
        'wireless haedset'
    );
    v_unrelated_similarity := similarity(
        'wireless headset',
        'unrelated toaster'
    );

    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'default_threshold_is_bounded',
        1,
        CASE
            WHEN current_setting(
                'pg_trgm.similarity_threshold'
            )::real BETWEEN 0.0 AND 1.0 THEN 1
            ELSE 0
        END
    );
    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'single_edit_scores_above_unrelated',
        1,
        CASE
            WHEN v_typo_similarity > v_unrelated_similarity THEN 1
            ELSE 0
        END
    );
    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'transposition_scores_above_unrelated',
        1,
        CASE
            WHEN v_transposition_similarity >
                 v_unrelated_similarity THEN 1
            ELSE 0
        END
    );
    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'single_edit_matches_default_operator',
        1,
        CASE
            WHEN 'wireless headset' % 'wireles headset'
                THEN 1
            ELSE 0
        END
    );
    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'transposition_matches_default_operator',
        1,
        CASE
            WHEN 'wireless headset' % 'wireless haedset'
                THEN 1
            ELSE 0
        END
    );
    PERFORM pg_temp.c2_record(
        'LAB_ONLY_SIMILARITY',
        'unrelated_rejected_by_default_operator',
        1,
        CASE
            WHEN NOT (
                'wireless headset' % 'unrelated toaster'
            ) THEN 1
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
FROM c2_verification_results
ORDER BY
    category,
    check_name;

SELECT concat_ws(
    '|',
    'LAB_ONLY',
    'candidate=' || :'candidate',
    'threshold=' || current_setting(
        'pg_trgm.similarity_threshold'
    ),
    'single_edit=' || similarity(
        'wireless headset',
        'wireles headset'
    ),
    'transposition=' || similarity(
        'wireless headset',
        'wireless haedset'
    ),
    'unrelated=' || similarity(
        'wireless headset',
        'unrelated toaster'
    )
);

SELECT concat_ws(
    '|',
    'verification_result=success',
    'candidate=' || :'candidate',
    'checks=' || (
        SELECT count(*)
        FROM c2_verification_results
    ),
    'failed=0'
);
