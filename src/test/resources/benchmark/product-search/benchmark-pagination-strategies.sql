-- V2-PS-B5 pgbench workload for offset-versus-keyset comparison.
-- Required variables:
-- is_count, has_keyword, is_keyset, is_deep, keyword_pattern,
-- exact_keyword, name_prefix_pattern, status, offset_rows, page_size,
-- anchor_priority, anchor_epoch, anchor_id.
--
-- Prepared mode requires unquoted :variable references. Every value is
-- supplied by run-pagination-strategy-comparison.ps1.

\if :is_count
    \if :has_keyword
SELECT count(p.id)
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(:keyword_pattern) ESCAPE ''
    OR lower(p.name) LIKE lower(:keyword_pattern) ESCAPE ''
)
AND p.status = :status;
    \else
SELECT count(p.id)
FROM products AS p
WHERE p.status = :status;
    \endif
\else
    \if :has_keyword
        \if :is_deep
            \if :is_keyset
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
AND (
    CASE
        WHEN lower(p.sku) = lower(:exact_keyword) THEN 0
        WHEN lower(p.name) LIKE lower(:name_prefix_pattern)
             ESCAPE E'\\' THEN 1
        ELSE 2
    END > CAST(:anchor_priority AS integer)
    OR (
        CASE
            WHEN lower(p.sku) = lower(:exact_keyword) THEN 0
            WHEN lower(p.name) LIKE lower(:name_prefix_pattern)
                 ESCAPE E'\\' THEN 1
            ELSE 2
        END = CAST(:anchor_priority AS integer)
        AND (
            p.created_at < to_timestamp(
                CAST(:anchor_epoch AS double precision)
            )
            OR (
                p.created_at = to_timestamp(
                    CAST(:anchor_epoch AS double precision)
                )
                AND p.id < CAST(:anchor_id AS uuid)
            )
        )
    )
)
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(:exact_keyword) THEN 0
        WHEN lower(p.name) LIKE lower(:name_prefix_pattern)
             ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
FETCH FIRST :page_size ROWS ONLY;
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
    p.created_at DESC,
    p.id DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
            \endif
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
    p.created_at DESC,
    p.id DESC
OFFSET 0 ROWS
FETCH FIRST :page_size ROWS ONLY;
        \endif
    \else
        \if :is_deep
            \if :is_keyset
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = :status
  AND (
      p.created_at < to_timestamp(
          CAST(:anchor_epoch AS double precision)
      )
      OR (
          p.created_at = to_timestamp(
              CAST(:anchor_epoch AS double precision)
          )
          AND p.id < CAST(:anchor_id AS uuid)
      )
  )
ORDER BY p.created_at DESC, p.id DESC
FETCH FIRST :page_size ROWS ONLY;
            \else
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = :status
ORDER BY p.created_at DESC, p.id DESC
OFFSET :offset_rows ROWS
FETCH FIRST :page_size ROWS ONLY;
            \endif
        \else
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE p.status = :status
ORDER BY p.created_at DESC, p.id DESC
OFFSET 0 ROWS
FETCH FIRST :page_size ROWS ONLY;
        \endif
    \endif
\endif
