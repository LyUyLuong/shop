-- V2-PS-B3 pgbench workload for the production-ranked search query.
-- Required variables: is_count, is_insert, is_update, keyword_pattern,
-- exact_keyword, name_prefix_pattern, status, page_size.
-- Prepared mode requires unquoted :variable references; cast explicitly
-- before arithmetic whenever PostgreSQL cannot infer the parameter type.
-- Also avoid literal text shaped like :name because pgbench substitutes it.

\if :is_insert
    \set random_value random(1, 2147483647)
BEGIN;
INSERT INTO products (
    id, sku, version, name, description, price, stock_quantity,
    status, image_key, image_url, created_at, updated_at
)
VALUES (
    md5(
        'psb3-insert:' || CAST(:client_id AS text) || ':' ||
        CAST(:random_value AS text) || ':' ||
        CAST(clock_timestamp() AS text)
    )::uuid,
    'PSB3-INSERT-' || CAST(:client_id AS text) || '-' ||
        CAST(:random_value AS text),
    0,
    'PSB3 Insert Product ' || CAST(:random_value AS text),
    'V2-PS-B3 write-cost probe',
    100000.00,
    10,
    'ACTIVE',
    NULL,
    NULL,
    clock_timestamp(),
    clock_timestamp()
);
ROLLBACK;
\elif :is_update
BEGIN;
UPDATE products
SET name = name || ' PSB3',
    version = version + 1,
    updated_at = clock_timestamp()
WHERE id = md5(
    '20260806' || chr(58) || 'product' || chr(58) ||
    CAST((CAST(:client_id AS integer) * 5) + 1 AS text)
)::uuid;
ROLLBACK;
\elif :is_count
SELECT count(p.id)
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(:keyword_pattern) ESCAPE ''
    OR lower(p.name) LIKE lower(:keyword_pattern) ESCAPE ''
)
AND p.status = :status;
\else
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(:keyword_pattern) ESCAPE ''
    OR lower(p.name) LIKE lower(:keyword_pattern) ESCAPE ''
)
AND p.status = :status
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(:exact_keyword) THEN 0
        WHEN lower(p.name) LIKE lower(:name_prefix_pattern)
             ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC
OFFSET 0 ROWS
FETCH FIRST :page_size ROWS ONLY;
\endif
