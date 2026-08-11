-- V2-PS-B-A: isolated exact-SKU and name-prefix plan experiment.
-- Required psql variable: expected_rows (10000 or 100000).

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif

SELECT
    current_database() = 'shop_search_benchmark'
    AND current_user = 'shop_benchmark'
    AND (SELECT count(*) FROM products) = :expected_rows
    AND (
        SELECT count(*)
        FROM products
        WHERE status = 'ACTIVE'
    ) = :expected_rows * 4 / 5
    AND (
        SELECT count(*)
        FROM products
        WHERE status = 'INACTIVE'
    ) = :expected_rows / 5
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
    AND (
        SELECT string_agg(indexname, ',' ORDER BY indexname)
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ) =
        'idx_products_created_at,'
        'idx_products_name_lower,'
        'idx_products_sku_lower,'
        'idx_products_status,'
        'products_pkey'
    AND to_regclass(
        'public.idx_products_name_lower_pattern_candidate'
    ) IS NULL
    AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: database, dataset, migration, extension, or index guard failed.'
\quit 1
\endif

SELECT
    count(*) FILTER (
        WHERE lower(sku) = lower(
              'delta-20260806-00000007-green'
          )
    ) AS exact_sku,

    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND lower(name) LIKE 'rare orchid%'
    ) AS rare_prefix,

    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND lower(name) LIKE 'medium cedar%'
    ) AS medium_prefix,

    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND lower(name) LIKE 'common market%'
    ) AS common_prefix
FROM products
\gset matches_

SELECT
    :matches_exact_sku::bigint = 1
    AND :matches_rare_prefix::bigint = 1
    AND :matches_medium_prefix::bigint =
        :expected_rows / 20
    AND :matches_common_prefix::bigint =
        (:expected_rows * 3 / 4) - 1
    AS cardinality_ok
\gset guard_

\if :guard_cardinality_ok
\else
\echo 'STOP: candidate-query cardinality is incorrect.'
\quit 1
\endif

\echo 'Rows=' :expected_rows
\echo 'Exact SKU matches=' :matches_exact_sku
\echo 'Rare prefix matches=' :matches_rare_prefix
\echo 'Medium prefix matches=' :matches_medium_prefix
\echo 'Common prefix matches=' :matches_common_prefix

BEGIN;

CREATE FUNCTION pg_temp.explain_name_prefix_case(
    p_term text,
    p_count boolean
)
RETURNS SETOF text
LANGUAGE plpgsql
AS $function$
DECLARE
    v_select text;
    v_suffix text;
    v_sql text;
BEGIN
    IF p_count THEN
        v_select := 'SELECT count(p.id)';
        v_suffix := '';
    ELSE
        v_select := 'SELECT p.*';
        v_suffix :=
            ' ORDER BY p.created_at DESC'
            ' OFFSET 0 ROWS'
            ' FETCH FIRST 100 ROWS ONLY';
    END IF;

    v_sql :=
        'EXPLAIN ('
        'ANALYZE, '
        'BUFFERS, '
        'VERBOSE, '
        'SETTINGS, '
        'SUMMARY'
        ') '
        || v_select
        || ' FROM products p'
        || ' WHERE p.status = ''ACTIVE'''
        || format(
            ' AND lower(p.name) LIKE lower(%L) || ''%%''',
            p_term
        )
        || v_suffix;

    RETURN QUERY EXECUTE v_sql;
END
$function$;

\echo '=== CONTROL: exact SKU data ==='
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    SUMMARY
)
SELECT p.*
FROM products p
WHERE lower(p.sku) = lower(
    'delta-20260806-00000007-green'
);

\echo '=== BEFORE: rare prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'rare orchid',
    false
);

\echo '=== BEFORE: rare prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'rare orchid',
    true
);

\echo '=== BEFORE: medium prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'medium cedar',
    false
);

\echo '=== BEFORE: medium prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'medium cedar',
    true
);

\echo '=== BEFORE: common prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'common market',
    false
);

\echo '=== BEFORE: common prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'common market',
    true
);

SELECT
    pg_size_pretty(
        pg_indexes_size('products')
    ) AS indexes_size_before;

CREATE INDEX idx_products_name_lower_pattern_candidate
    ON products (
        lower(name) text_pattern_ops
    );

SELECT
    pg_size_pretty(
        pg_relation_size(
            'idx_products_name_lower_pattern_candidate'
        )
    ) AS candidate_index_size,

    pg_size_pretty(
        pg_indexes_size('products')
    ) AS indexes_size_after;

\echo '=== AFTER: rare prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'rare orchid',
    false
);

\echo '=== AFTER: rare prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'rare orchid',
    true
);

\echo '=== AFTER: medium prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'medium cedar',
    false
);

\echo '=== AFTER: medium prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'medium cedar',
    true
);

\echo '=== AFTER: common prefix data ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'common market',
    false
);

\echo '=== AFTER: common prefix count ==='
SELECT *
FROM pg_temp.explain_name_prefix_case(
    'common market',
    true
);

ROLLBACK;

SELECT
    to_regclass(
        'public.idx_products_name_lower_pattern_candidate'
    ) IS NULL AS candidate_removed
\gset cleanup_

\if :cleanup_candidate_removed
\echo 'PASS: candidate index was rolled back; schema restored.'
\else
\echo 'STOP: candidate index still exists.'
\quit 1
\endif
