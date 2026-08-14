-- V2-PS-C3 prepared pgbench workload for the unchanged production query.
-- Required variables: is_count, is_cursor, has_status, keyword_pattern,
-- exact_keyword, name_prefix_pattern, status, offset_rows, page_size,
-- anchor_priority, anchor_epoch_micros, anchor_id.

\if :is_count
    \if :has_status
SELECT count(p.id)
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
)
AND p.status = CAST(:status AS varchar);
    \else
SELECT count(p.id)
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
);
    \endif
\elif :is_cursor
    \if :has_status
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
)
AND p.status = CAST(:status AS varchar)
AND (
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END > CAST(:anchor_priority AS integer)
    OR (
        CASE
            WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
                THEN 0
            WHEN lower(p.name) LIKE lower(
                CAST(:name_prefix_pattern AS text)
            ) ESCAPE E'\\' THEN 1
            ELSE 2
        END = CAST(:anchor_priority AS integer)
        AND (
            p.created_at < (
                TIMESTAMPTZ 'epoch'
                + CAST(:anchor_epoch_micros AS double precision)
                  * INTERVAL '1 microsecond'
            )
            OR (
                p.created_at = (
                    TIMESTAMPTZ 'epoch'
                    + CAST(:anchor_epoch_micros AS double precision)
                      * INTERVAL '1 microsecond'
                )
                AND p.id < CAST(:anchor_id AS uuid)
            )
        )
    )
)
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \else
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
)
AND (
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END > CAST(:anchor_priority AS integer)
    OR (
        CASE
            WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
                THEN 0
            WHEN lower(p.name) LIKE lower(
                CAST(:name_prefix_pattern AS text)
            ) ESCAPE E'\\' THEN 1
            ELSE 2
        END = CAST(:anchor_priority AS integer)
        AND (
            p.created_at < (
                TIMESTAMPTZ 'epoch'
                + CAST(:anchor_epoch_micros AS double precision)
                  * INTERVAL '1 microsecond'
            )
            OR (
                p.created_at = (
                    TIMESTAMPTZ 'epoch'
                    + CAST(:anchor_epoch_micros AS double precision)
                      * INTERVAL '1 microsecond'
                )
                AND p.id < CAST(:anchor_id AS uuid)
            )
        )
    )
)
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \endif
\else
    \if :has_status
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
)
AND p.status = CAST(:status AS varchar)
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
OFFSET CAST(:offset_rows AS bigint) ROWS
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \else
SELECT
    p.id, p.created_at, p.description, p.image_key, p.image_url,
    p.name, p.price, p.sku, p.status, p.stock_quantity,
    p.updated_at, p.version
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
    OR lower(p.name) LIKE lower(CAST(:keyword_pattern AS text)) ESCAPE ''
)
ORDER BY
    CASE
        WHEN lower(p.sku) = lower(CAST(:exact_keyword AS text))
            THEN 0
        WHEN lower(p.name) LIKE lower(
            CAST(:name_prefix_pattern AS text)
        ) ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
OFFSET CAST(:offset_rows AS bigint) ROWS
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \endif
\endif
