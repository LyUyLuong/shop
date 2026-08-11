-- V2-PS-B3 plans for the real B2 ranked search shape.
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
    ) AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: database, dataset, migration, or extension guard failed.'
\quit 1
\endif

SELECT
    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND (
              lower(sku) LIKE '%delta-20260806-00000007-green%'
              OR lower(name) LIKE '%delta-20260806-00000007-green%'
          )
    ) AS exact_matches,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND (
              lower(sku) LIKE '%rare orchid%'
              OR lower(name) LIKE '%rare orchid%'
          )
    ) AS rare_matches,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND (
              lower(sku) LIKE '%medium cedar%'
              OR lower(name) LIKE '%medium cedar%'
          )
    ) AS medium_matches,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND (
              lower(sku) LIKE '%common market%'
              OR lower(name) LIKE '%common market%'
          )
    ) AS common_matches
FROM products
\gset matches_

SELECT
    :matches_exact_matches::bigint = 1
    AND :matches_rare_matches::bigint = 1
    AND :matches_medium_matches::bigint = :expected_rows / 20
    AND :matches_common_matches::bigint =
        (:expected_rows * 3 / 4) - 1 AS cardinality_ok
\gset guard_

\if :guard_cardinality_ok
\else
\echo 'STOP: ranked-query cardinality guard failed.'
\quit 1
\endif

\echo 'Rows=' :expected_rows
\echo 'Exact matches=' :matches_exact_matches
\echo 'Rare matches=' :matches_rare_matches
\echo 'Medium matches=' :matches_medium_matches
\echo 'Common matches=' :matches_common_matches

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'products'
ORDER BY indexname;

SELECT
    pg_size_pretty(pg_table_size('products')) AS table_size,
    pg_size_pretty(pg_indexes_size('products')) AS indexes_size;

CREATE FUNCTION pg_temp.explain_ranked_search(
    p_term text,
    p_count boolean
)
RETURNS SETOF text
LANGUAGE plpgsql
AS $function$
DECLARE
    v_contains text := '%' || p_term || '%';
    v_prefix text := p_term || '%';
    v_sql text;
BEGIN
    IF p_count THEN
        v_sql := format(
            $query$
            EXPLAIN (
                ANALYZE, BUFFERS, VERBOSE, SETTINGS, SUMMARY
            )
            SELECT count(p.id)
            FROM products AS p
            WHERE (
                lower(p.sku) LIKE lower(%L) ESCAPE ''
                OR lower(p.name) LIKE lower(%L) ESCAPE ''
            )
            AND p.status = 'ACTIVE'
            $query$,
            v_contains,
            v_contains
        );
    ELSE
        v_sql := format(
            $query$
            EXPLAIN (
                ANALYZE, BUFFERS, VERBOSE, SETTINGS, SUMMARY
            )
            SELECT
                p.id, p.created_at, p.description, p.image_key,
                p.image_url, p.name, p.price, p.sku, p.status,
                p.stock_quantity, p.updated_at, p.version
            FROM products AS p
            WHERE (
                lower(p.sku) LIKE lower(%L) ESCAPE ''
                OR lower(p.name) LIKE lower(%L) ESCAPE ''
            )
            AND p.status = 'ACTIVE'
            ORDER BY
                CASE
                    WHEN lower(p.sku) = lower(%L) THEN 0
                    WHEN lower(p.name) LIKE lower(%L)
                         ESCAPE E'\\' THEN 1
                    ELSE 2
                END ASC,
                p.created_at DESC
            OFFSET 0 ROWS
            FETCH FIRST 100 ROWS ONLY
            $query$,
            v_contains,
            v_contains,
            p_term,
            v_prefix
        );
    END IF;

    RETURN QUERY EXECUTE v_sql;
END
$function$;

\echo '=== EXACT SKU: data ==='
SELECT * FROM pg_temp.explain_ranked_search(
    'delta-20260806-00000007-green', false
);
\echo '=== EXACT SKU: count ==='
SELECT * FROM pg_temp.explain_ranked_search(
    'delta-20260806-00000007-green', true
);

\echo '=== RARE NAME: data ==='
SELECT * FROM pg_temp.explain_ranked_search('rare orchid', false);
\echo '=== RARE NAME: count ==='
SELECT * FROM pg_temp.explain_ranked_search('rare orchid', true);

\echo '=== MEDIUM NAME: data ==='
SELECT * FROM pg_temp.explain_ranked_search('medium cedar', false);
\echo '=== MEDIUM NAME: count ==='
SELECT * FROM pg_temp.explain_ranked_search('medium cedar', true);

\echo '=== COMMON NAME: data ==='
SELECT * FROM pg_temp.explain_ranked_search('common market', false);
\echo '=== COMMON NAME: count ==='
SELECT * FROM pg_temp.explain_ranked_search('common market', true);
