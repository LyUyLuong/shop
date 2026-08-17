-- Render __PS_D_NAME_DOCUMENT__ before pgbench execution.
-- Runner connects as shop_fts_migration with:
-- PGOPTIONS="-c role=shop_fts_runtime"
--
-- Required pgbench variables:
-- is_browse, is_count, is_cursor, is_write,
-- is_insert, is_name_update, is_sku_update, is_stock_update,
-- is_status_update, is_image_update, is_optimistic_conflict,
-- keyword, visibility, minimum_price, maximum_price,
-- has_minimum_price, has_maximum_price, offset_rows, page_size,
-- anchor_tier, anchor_surface, anchor_score,
-- anchor_epoch_micros, anchor_id,
-- target_id, write_id, write_sku.

\if :is_write
BEGIN;

    \if :is_insert
INSERT INTO public.products (
    id, sku, version, name, description, price,
    stock_quantity, status, image_key, image_url,
    created_at, updated_at
)
VALUES (
    CAST(:'write_id' AS uuid),
    CAST(:'write_sku' AS varchar),
    0,
    'PS-D write-probe product',
    'PS-D isolated write probe',
    100.00,
    10,
    'ACTIVE',
    NULL,
    NULL,
    clock_timestamp(),
    clock_timestamp()
);
    \elif :is_name_update
UPDATE public.products
SET
    name = name || ' PS-D',
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid);
    \elif :is_sku_update
UPDATE public.products
SET
    sku = sku || '-W',
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid);
    \elif :is_stock_update
UPDATE public.products
SET
    stock_quantity = stock_quantity + 1,
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid);
    \elif :is_status_update
UPDATE public.products
SET
    status = CASE status
        WHEN 'ACTIVE' THEN 'INACTIVE'
        ELSE 'ACTIVE'
    END,
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid);
    \elif :is_image_update
UPDATE public.products
SET
    image_url = 'https://example.invalid/ps-d-write-probe',
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid);
    \elif :is_optimistic_conflict
UPDATE public.products
SET
    stock_quantity = stock_quantity + 1,
    updated_at = clock_timestamp(),
    version = version + 1
WHERE id = CAST(:'target_id' AS uuid)
  AND version = -1;
    \else
SELECT 1 / 0;
    \endif

ROLLBACK;

\elif :is_browse

    \if :is_count
SELECT count(product.id)
FROM public.products AS product
WHERE CASE CAST(:'visibility' AS text)
    WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_ALL' THEN TRUE
    WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
    ELSE FALSE
END
AND (
    NOT CAST(:has_minimum_price AS boolean)
    OR product.price >= CAST(:minimum_price AS numeric)
)
AND (
    NOT CAST(:has_maximum_price AS boolean)
    OR product.price <= CAST(:maximum_price AS numeric)
);
    \elif :is_cursor
SELECT
    product.id,
    product.sku,
    product.name,
    product.description,
    product.price,
    product.stock_quantity,
    product.status,
    product.image_key,
    product.image_url,
    product.created_at,
    product.updated_at,
    product.version
FROM public.products AS product
WHERE CASE CAST(:'visibility' AS text)
    WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_ALL' THEN TRUE
    WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
    ELSE FALSE
END
AND (
    NOT CAST(:has_minimum_price AS boolean)
    OR product.price >= CAST(:minimum_price AS numeric)
)
AND (
    NOT CAST(:has_maximum_price AS boolean)
    OR product.price <= CAST(:maximum_price AS numeric)
)
AND (
    product.created_at < (
        TIMESTAMPTZ 'epoch'
        + CAST(:anchor_epoch_micros AS double precision)
          * INTERVAL '1 microsecond'
    )
    OR (
        product.created_at = (
            TIMESTAMPTZ 'epoch'
            + CAST(:anchor_epoch_micros AS double precision)
              * INTERVAL '1 microsecond'
        )
        AND product.id < CAST(:'anchor_id' AS uuid)
    )
)
ORDER BY product.created_at DESC, product.id DESC
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \else
SELECT
    product.id,
    product.sku,
    product.name,
    product.description,
    product.price,
    product.stock_quantity,
    product.status,
    product.image_key,
    product.image_url,
    product.created_at,
    product.updated_at,
    product.version
FROM public.products AS product
WHERE CASE CAST(:'visibility' AS text)
    WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_ALL' THEN TRUE
    WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
    WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
    ELSE FALSE
END
AND (
    NOT CAST(:has_minimum_price AS boolean)
    OR product.price >= CAST(:minimum_price AS numeric)
)
AND (
    NOT CAST(:has_maximum_price AS boolean)
    OR product.price <= CAST(:maximum_price AS numeric)
)
ORDER BY product.created_at DESC, product.id DESC
OFFSET CAST(:offset_rows AS bigint)
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \endif

\else

    \if :is_count
WITH parameters AS (
    SELECT normalize(CAST(:'keyword' AS text), NFC) AS keyword_nfc
),
normalized_parameters AS (
    SELECT
        pg_catalog.lower(keyword_nfc) AS sku_keyword,
        pg_catalog.plainto_tsquery(
            'public.shop_product_name_unaccent_v1'
                ::pg_catalog.regconfig,
            keyword_nfc
        ) AS name_query,
        replace(
            replace(
                replace(
                    pg_catalog.lower(keyword_nfc),
                    E'\\',
                    E'\\\\'
                ),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%' AS escaped_sku_prefix
    FROM parameters
),
evaluated AS NOT MATERIALIZED (
    SELECT
        product.id,
        pg_catalog.lower(
            normalize(product.sku, NFC)
        ) AS normalized_sku,
        __PS_D_NAME_DOCUMENT__ AS name_document,
        parameters.*
    FROM public.products AS product
    CROSS JOIN normalized_parameters AS parameters
    WHERE CASE CAST(:'visibility' AS text)
        WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_ALL' THEN TRUE
        WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
        ELSE FALSE
    END
    AND (
        NOT CAST(:has_minimum_price AS boolean)
        OR product.price >= CAST(:minimum_price AS numeric)
    )
    AND (
        NOT CAST(:has_maximum_price AS boolean)
        OR product.price <= CAST(:maximum_price AS numeric)
    )
)
SELECT count(evaluated.id)
FROM evaluated
WHERE
    evaluated.normalized_sku LIKE
        evaluated.escaped_sku_prefix ESCAPE E'\\'
    OR evaluated.name_document @@ evaluated.name_query;

    \else
WITH parameters AS (
    SELECT normalize(CAST(:'keyword' AS text), NFC) AS keyword_nfc
),
normalized_parameters AS (
    SELECT
        keyword_nfc,
        pg_catalog.lower(keyword_nfc) AS sku_keyword,
        pg_catalog.lower(
            public.unaccent(
                'public.unaccent'::pg_catalog.regdictionary,
                keyword_nfc
            )
        ) AS folded_keyword,
        pg_catalog.plainto_tsquery(
            'public.shop_product_name_unaccent_v1'
                ::pg_catalog.regconfig,
            keyword_nfc
        ) AS name_query,
        pg_catalog.plainto_tsquery(
            'pg_catalog.simple'::pg_catalog.regconfig,
            keyword_nfc
        ) AS accent_query,
        replace(
            replace(
                replace(
                    pg_catalog.lower(keyword_nfc),
                    E'\\',
                    E'\\\\'
                ),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%' AS escaped_sku_prefix,
        replace(
            replace(
                replace(
                    pg_catalog.lower(
                        public.unaccent(
                            'public.unaccent'
                                ::pg_catalog.regdictionary,
                            keyword_nfc
                        )
                    ),
                    E'\\',
                    E'\\\\'
                ),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%' AS escaped_name_prefix
    FROM parameters
),
evaluated AS NOT MATERIALIZED (
    SELECT
        product.*,
        pg_catalog.lower(
            normalize(product.sku, NFC)
        ) AS normalized_sku,
        pg_catalog.lower(
            public.unaccent(
                'public.unaccent'::pg_catalog.regdictionary,
                normalize(product.name, NFC)
            )
        ) AS folded_name,
        __PS_D_NAME_DOCUMENT__ AS name_document,
        pg_catalog.to_tsvector(
            'pg_catalog.simple'::pg_catalog.regconfig,
            normalize(product.name, NFC)
        ) AS accent_document,
        parameters.*
    FROM public.products AS product
    CROSS JOIN normalized_parameters AS parameters
    WHERE CASE CAST(:'visibility' AS text)
        WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_ALL' THEN TRUE
        WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
        ELSE FALSE
    END
    AND (
        NOT CAST(:has_minimum_price AS boolean)
        OR product.price >= CAST(:minimum_price AS numeric)
    )
    AND (
        NOT CAST(:has_maximum_price AS boolean)
        OR product.price <= CAST(:maximum_price AS numeric)
    )
),
signals AS NOT MATERIALIZED (
    SELECT
        evaluated.*,
        normalized_sku = sku_keyword AS exact_sku,
        normalized_sku LIKE escaped_sku_prefix
            ESCAPE E'\\' AS sku_prefix,
        name_document @@ name_query AS name_match,
        folded_name = folded_keyword AS exact_name,
        folded_name LIKE escaped_name_prefix
            ESCAPE E'\\' AS name_prefix,
        accent_document @@ accent_query AS accent_match
    FROM evaluated
),
members AS NOT MATERIALIZED (
    SELECT
        signals.*,
        CASE
            WHEN exact_sku THEN 0
            WHEN exact_name THEN 1
            WHEN sku_prefix THEN 2
            WHEN name_prefix THEN 3
            ELSE 4
        END AS match_tier
    FROM signals
    WHERE sku_prefix OR name_match
),
ranked AS NOT MATERIALIZED (
    SELECT
        members.*,
        CASE
            WHEN match_tier IN (0, 2) THEN 0
            WHEN accent_match THEN 0
            ELSE 1
        END AS surface_form_priority,
        CASE
            WHEN match_tier IN (0, 2) THEN 0::bigint
            ELSE round(
                pg_catalog.ts_rank_cd(
                    name_document,
                    name_query,
                    32
                )::numeric * 1000000
            )::bigint
        END AS rank_score
    FROM members
)
SELECT
    ranked.id,
    ranked.sku,
    ranked.name,
    ranked.description,
    ranked.price,
    ranked.stock_quantity,
    ranked.status,
    ranked.image_key,
    ranked.image_url,
    ranked.created_at,
    ranked.updated_at,
    ranked.version,
    ranked.match_tier,
    ranked.surface_form_priority,
    ranked.rank_score
FROM ranked
WHERE
    NOT CAST(:is_cursor AS boolean)
    OR ranked.match_tier > CAST(:anchor_tier AS integer)
    OR (
        ranked.match_tier = CAST(:anchor_tier AS integer)
        AND ranked.surface_form_priority >
            CAST(:anchor_surface AS integer)
    )
    OR (
        ranked.match_tier = CAST(:anchor_tier AS integer)
        AND ranked.surface_form_priority =
            CAST(:anchor_surface AS integer)
        AND ranked.rank_score < CAST(:anchor_score AS bigint)
    )
    OR (
        ranked.match_tier = CAST(:anchor_tier AS integer)
        AND ranked.surface_form_priority =
            CAST(:anchor_surface AS integer)
        AND ranked.rank_score = CAST(:anchor_score AS bigint)
        AND ranked.created_at < (
            TIMESTAMPTZ 'epoch'
            + CAST(:anchor_epoch_micros AS double precision)
              * INTERVAL '1 microsecond'
        )
    )
    OR (
        ranked.match_tier = CAST(:anchor_tier AS integer)
        AND ranked.surface_form_priority =
            CAST(:anchor_surface AS integer)
        AND ranked.rank_score = CAST(:anchor_score AS bigint)
        AND ranked.created_at = (
            TIMESTAMPTZ 'epoch'
            + CAST(:anchor_epoch_micros AS double precision)
              * INTERVAL '1 microsecond'
        )
        AND ranked.id < CAST(:'anchor_id' AS uuid)
    )
ORDER BY
    ranked.match_tier ASC,
    ranked.surface_form_priority ASC,
    ranked.rank_score DESC,
    ranked.created_at DESC,
    ranked.id DESC
OFFSET CASE
    WHEN CAST(:is_cursor AS boolean) THEN 0
    ELSE CAST(:offset_rows AS bigint)
END
FETCH FIRST CAST(:page_size AS integer) ROWS ONLY;
    \endif
\endif