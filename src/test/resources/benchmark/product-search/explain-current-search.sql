-- V2-PS-A3 current Product Search execution-plan template.
-- Required psql variables:
-- is_count, has_keyword, has_status, keyword_pattern,
-- status, offset_rows, page_size.

\set ON_ERROR_STOP on

\if :is_count
\if :has_keyword
\if :has_status
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT count(p.id)
FROM products AS p
WHERE 1 = 1
  AND (
      lower(p.sku) LIKE :'keyword_pattern' ESCAPE ''
      OR lower(p.name) LIKE :'keyword_pattern' ESCAPE ''
  )
  AND p.status = :'status';
\else
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT count(p.id)
FROM products AS p
WHERE 1 = 1
  AND (
      lower(p.sku) LIKE :'keyword_pattern' ESCAPE ''
      OR lower(p.name) LIKE :'keyword_pattern' ESCAPE ''
  );
\endif
\else
\if :has_status
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT count(p.id)
FROM products AS p
WHERE 1 = 1
  AND p.status = :'status';
\else
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT count(p.id)
FROM products AS p
WHERE 1 = 1;
\endif
\endif
\else
\if :has_keyword
\if :has_status
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT
    p.id,
    p.created_at,
    p.description,
    p.image_key,
    p.image_url,
    p.name,
    p.price,
    p.sku,
    p.status,
    p.stock_quantity,
    p.updated_at,
    p.version
FROM products AS p
WHERE 1 = 1
  AND (
      lower(p.sku) LIKE :'keyword_pattern' ESCAPE ''
      OR lower(p.name) LIKE :'keyword_pattern' ESCAPE ''
  )
  AND p.status = :'status'
ORDER BY p.created_at DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
\else
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT
    p.id,
    p.created_at,
    p.description,
    p.image_key,
    p.image_url,
    p.name,
    p.price,
    p.sku,
    p.status,
    p.stock_quantity,
    p.updated_at,
    p.version
FROM products AS p
WHERE 1 = 1
  AND (
      lower(p.sku) LIKE :'keyword_pattern' ESCAPE ''
      OR lower(p.name) LIKE :'keyword_pattern' ESCAPE ''
  )
ORDER BY p.created_at DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
\endif
\else
\if :has_status
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT
    p.id,
    p.created_at,
    p.description,
    p.image_key,
    p.image_url,
    p.name,
    p.price,
    p.sku,
    p.status,
    p.stock_quantity,
    p.updated_at,
    p.version
FROM products AS p
WHERE 1 = 1
  AND p.status = :'status'
ORDER BY p.created_at DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
\else
EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    FORMAT JSON
)
SELECT
    p.id,
    p.created_at,
    p.description,
    p.image_key,
    p.image_url,
    p.name,
    p.price,
    p.sku,
    p.status,
    p.stock_quantity,
    p.updated_at,
    p.version
FROM products AS p
WHERE 1 = 1
ORDER BY p.created_at DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
\endif
\endif
\endif