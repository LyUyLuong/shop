-- V2-PS-C3 guarded creation of one global trigram candidate pair.
-- Required psql variables: expected_rows, candidate.
-- candidate must be gin or gist. Baseline creates no candidate.

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
    :expected_rows::integer IN (10000, 100000)
    AND :'candidate' IN ('gin', 'gist') AS input_ok
\gset input_

\if :input_input_ok
\else
\echo 'STOP: C3 creation requires rows=10000|100000 and candidate=gin|gist.'
\quit 1
\endif

SELECT
    current_database() = 'shop_search_benchmark'
    AND current_user = 'shop_benchmark'
    AND current_schema() = 'public'
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
        FROM pg_available_extensions
        WHERE name = 'pg_trgm'
    ) = 1
    AND has_database_privilege(
        current_user,
        current_database(),
        'CREATE'
    )
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
              OR indexname LIKE 'c3_%'
          )
    )
    AND (
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
    ]::text[] AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C3 creation environment guard failed.'
\quit 1
\endif

CREATE EXTENSION pg_trgm;

SELECT :'candidate' = 'gin' AS use_gin
\gset candidate_

\if :candidate_use_gin
CREATE INDEX c3_products_sku_lower_gin_trgm
    ON products USING gin (lower(sku) gin_trgm_ops);

CREATE INDEX c3_products_name_lower_gin_trgm
    ON products USING gin (lower(name) gin_trgm_ops);
\else
CREATE INDEX c3_products_sku_lower_gist_trgm
    ON products USING gist (lower(sku) gist_trgm_ops);

CREATE INDEX c3_products_name_lower_gist_trgm
    ON products USING gist (lower(name) gist_trgm_ops);
\endif

ANALYZE products;

SELECT
    (
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ) = 1
    AND (
        SELECT count(*)
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
          AND indexname LIKE 'c3_%'
    ) = 2
    AND (
        (:'candidate' = 'gin'
         AND to_regclass(
             'public.c3_products_sku_lower_gin_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_name_lower_gin_trgm'
         ) IS NOT NULL
         AND pg_get_indexdef(to_regclass(
             'public.c3_products_sku_lower_gin_trgm'
         )) ILIKE '%USING gin%lower%sku%gin_trgm_ops%'
         AND pg_get_indexdef(to_regclass(
             'public.c3_products_name_lower_gin_trgm'
         )) ILIKE '%USING gin%lower%name%gin_trgm_ops%'
         AND to_regclass(
             'public.c3_products_sku_lower_gist_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c3_products_name_lower_gist_trgm'
         ) IS NULL)
        OR
        (:'candidate' = 'gist'
         AND to_regclass(
             'public.c3_products_sku_lower_gist_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_name_lower_gist_trgm'
         ) IS NOT NULL
         AND pg_get_indexdef(to_regclass(
             'public.c3_products_sku_lower_gist_trgm'
         )) ILIKE '%USING gist%lower%sku%gist_trgm_ops%'
         AND pg_get_indexdef(to_regclass(
             'public.c3_products_name_lower_gist_trgm'
         )) ILIKE '%USING gist%lower%name%gist_trgm_ops%'
         AND to_regclass(
             'public.c3_products_sku_lower_gin_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c3_products_name_lower_gin_trgm'
         ) IS NULL)
    ) AS candidate_ok
\gset created_

\if :created_candidate_ok
\else
\echo 'STOP: C3 candidate creation verification failed.'
\quit 1
\endif

SELECT concat_ws(
    '|',
    'create_result=success',
    'candidate=' || :'candidate',
    'pg_trgm_installed=1',
    'candidate_indexes=2',
    'candidate_bytes=' || (
        SELECT sum(pg_relation_size(indexrelid))
        FROM pg_index
        WHERE indexrelid IN (
            to_regclass(
                'public.c3_products_sku_lower_' ||
                :'candidate' || '_trgm'
            ),
            to_regclass(
                'public.c3_products_name_lower_' ||
                :'candidate' || '_trgm'
            )
        )
    )
);
