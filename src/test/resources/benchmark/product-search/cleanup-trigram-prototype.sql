-- V2-PS-C2 dependency-sensitive cleanup.
-- Required psql variable: expected_rows.
-- All known C2 index names are explicit; CASCADE is intentionally forbidden.

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif

SELECT :expected_rows::integer = 10000 AS input_ok
\gset input_

\if :input_input_ok
\else
\echo 'STOP: C2 cleanup requires expected_rows=10000.'
\quit 1
\endif

SELECT
    current_database() = 'shop_search_benchmark'
    AND current_user = 'shop_benchmark'
    AND current_schema() = 'public'
    AND to_regclass('public.products') IS NOT NULL
    AND (
        SELECT coalesce(max(version::integer), 0)
        FROM flyway_schema_history
        WHERE success
    ) = 12 AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C2 cleanup environment guard failed.'
\quit 1
\endif

DROP INDEX IF EXISTS c2_products_sku_lower_gin_trgm;
DROP INDEX IF EXISTS c2_products_name_lower_gin_trgm;
DROP INDEX IF EXISTS c2_products_sku_lower_gist_trgm;
DROP INDEX IF EXISTS c2_products_name_lower_gist_trgm;

DROP EXTENSION IF EXISTS pg_trgm;

SELECT
    NOT EXISTS (
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
    ]::text[]
    AND NOT EXISTS (
        SELECT 1
        FROM pg_proc AS routine
        JOIN pg_namespace AS namespace
          ON namespace.oid = routine.pronamespace
        WHERE namespace.nspname = 'public'
          AND routine.proname LIKE 'c2_%'
    ) AS cleanup_ok
\gset cleanup_

\if :cleanup_cleanup_ok
\else
\echo 'STOP: C2 cleanup left an extension, index, or public helper routine.'
\quit 1
\endif

SELECT concat_ws(
    '|',
    'cleanup_result=success',
    'pg_trgm_installed=0',
    'candidate_indexes=0',
    'public_c2_routines=0',
    'baseline_product_indexes=5'
);
