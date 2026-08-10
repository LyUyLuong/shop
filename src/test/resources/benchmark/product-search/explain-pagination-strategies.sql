-- V2-PS-B5 execution plans for the final B4 ordering and candidate keyset
-- continuation predicates.
-- Required psql variables:
-- expected_rows, page_size, browse_deep_offset, common_deep_offset,
-- browse_anchor_epoch, browse_anchor_id, common_anchor_priority,
-- common_anchor_epoch, common_anchor_id.

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

\echo '=== BROWSE FIRST PAGE: SHARED QUERY ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = 'ACTIVE'
ORDER BY p.created_at DESC, p.id DESC
OFFSET 0 ROWS
FETCH FIRST :page_size ROWS ONLY;

\echo '=== BROWSE DEEP PAGE: OFFSET ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = 'ACTIVE'
ORDER BY p.created_at DESC, p.id DESC
OFFSET :browse_deep_offset ROWS
FETCH FIRST :page_size ROWS ONLY;

\echo '=== BROWSE DEEP PAGE: KEYSET ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = 'ACTIVE'
  AND (
      p.created_at < to_timestamp(
          :browse_anchor_epoch::double precision
      )
      OR (
          p.created_at = to_timestamp(
              :browse_anchor_epoch::double precision
          )
          AND p.id < :'browse_anchor_id'::uuid
      )
  )
ORDER BY p.created_at DESC, p.id DESC
FETCH FIRST :page_size ROWS ONLY;

\echo '=== BROWSE COUNT ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT count(p.id)
FROM products AS p
WHERE p.status = 'ACTIVE';

\echo '=== COMMON KEYWORD FIRST PAGE: SHARED QUERY ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE '%common market%' ESCAPE ''
    OR lower(p.name) LIKE '%common market%' ESCAPE ''
)
AND p.status = 'ACTIVE'
ORDER BY
    CASE
        WHEN lower(p.sku) = 'common market' THEN 0
        WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
OFFSET 0 ROWS
FETCH FIRST :page_size ROWS ONLY;

\echo '=== COMMON KEYWORD DEEP PAGE: OFFSET ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE '%common market%' ESCAPE ''
    OR lower(p.name) LIKE '%common market%' ESCAPE ''
)
AND p.status = 'ACTIVE'
ORDER BY
    CASE
        WHEN lower(p.sku) = 'common market' THEN 0
        WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
OFFSET :common_deep_offset ROWS
FETCH FIRST :page_size ROWS ONLY;

\echo '=== COMMON KEYWORD DEEP PAGE: KEYSET ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE '%common market%' ESCAPE ''
    OR lower(p.name) LIKE '%common market%' ESCAPE ''
)
AND p.status = 'ACTIVE'
AND (
    CASE
        WHEN lower(p.sku) = 'common market' THEN 0
        WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
        ELSE 2
    END > :common_anchor_priority::integer
    OR (
        CASE
            WHEN lower(p.sku) = 'common market' THEN 0
            WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
            ELSE 2
        END = :common_anchor_priority::integer
        AND (
            p.created_at < to_timestamp(
                :common_anchor_epoch::double precision
            )
            OR (
                p.created_at = to_timestamp(
                    :common_anchor_epoch::double precision
                )
                AND p.id < :'common_anchor_id'::uuid
            )
        )
    )
)
ORDER BY
    CASE
        WHEN lower(p.sku) = 'common market' THEN 0
        WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
FETCH FIRST :page_size ROWS ONLY;

\echo '=== COMMON KEYWORD COUNT ==='
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT count(p.id)
FROM products AS p
WHERE (
    lower(p.sku) LIKE '%common market%' ESCAPE ''
    OR lower(p.name) LIKE '%common market%' ESCAPE ''
)
AND p.status = 'ACTIVE';
