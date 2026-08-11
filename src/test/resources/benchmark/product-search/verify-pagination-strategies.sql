-- V2-PS-B5 semantic verification for candidate keyset pagination.
-- Required psql variables:
-- expected_rows, page_size, browse_deep_offset, common_deep_offset.
--
-- Both dataset tiers prove that a keyset page after the selected anchor is
-- identical to the corresponding offset page. The 10k smoke tier additionally
-- traverses every qualifying row page by page and uses primary keys to reject
-- duplicates while comparing the final visited count with the qualifying set.

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

CREATE TEMP TABLE b5_pagination_context (
    expected_rows integer NOT NULL,
    page_size integer NOT NULL,
    browse_deep_offset bigint NOT NULL,
    common_deep_offset bigint NOT NULL
);

INSERT INTO b5_pagination_context (
    expected_rows,
    page_size,
    browse_deep_offset,
    common_deep_offset
)
VALUES (
    :expected_rows,
    :page_size,
    :browse_deep_offset,
    :common_deep_offset
);

CREATE TEMP TABLE b5_verification_results (
    check_name text PRIMARY KEY,
    expected_value bigint,
    actual_value bigint,
    status text NOT NULL
);

CREATE TEMP TABLE b5_browse_seen (
    id uuid PRIMARY KEY
);

CREATE TEMP TABLE b5_common_seen (
    id uuid PRIMARY KEY
);

DO $verification$
DECLARE
    v_expected_rows integer;
    v_page_size integer;
    v_browse_deep_offset bigint;
    v_common_deep_offset bigint;

    v_anchor_priority integer;
    v_anchor_created_at timestamptz;
    v_anchor_id uuid;
    v_offset_ids uuid[];
    v_keyset_ids uuid[];

    v_page_count integer;
    v_expected_count bigint;
    v_seen_count bigint;
    v_row record;
BEGIN
    SELECT
        expected_rows,
        page_size,
        browse_deep_offset,
        common_deep_offset
    INTO
        v_expected_rows,
        v_page_size,
        v_browse_deep_offset,
        v_common_deep_offset
    FROM b5_pagination_context;

    IF v_page_size <> 100 THEN
        RAISE EXCEPTION
            'Expected page size 100, found %',
            v_page_size;
    END IF;

    SELECT p.created_at, p.id
    INTO STRICT v_anchor_created_at, v_anchor_id
    FROM products AS p
    WHERE p.status = 'ACTIVE'
    ORDER BY p.created_at DESC, p.id DESC
    OFFSET (v_browse_deep_offset - 1)
    FETCH FIRST 1 ROW ONLY;

    SELECT array_agg(
        page.id
        ORDER BY page.created_at DESC, page.id DESC
    )
    INTO v_offset_ids
    FROM (
        SELECT p.id, p.created_at
        FROM products AS p
        WHERE p.status = 'ACTIVE'
        ORDER BY p.created_at DESC, p.id DESC
        OFFSET v_browse_deep_offset
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    SELECT array_agg(
        page.id
        ORDER BY page.created_at DESC, page.id DESC
    )
    INTO v_keyset_ids
    FROM (
        SELECT p.id, p.created_at
        FROM products AS p
        WHERE p.status = 'ACTIVE'
          AND (
              p.created_at < v_anchor_created_at
              OR (
                  p.created_at = v_anchor_created_at
                  AND p.id < v_anchor_id
              )
          )
        ORDER BY p.created_at DESC, p.id DESC
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    IF coalesce(cardinality(v_offset_ids), 0) <> v_page_size
       OR v_offset_ids IS DISTINCT FROM v_keyset_ids THEN
        RAISE EXCEPTION
            'Browse deep-page parity failed at offset %',
            v_browse_deep_offset;
    END IF;

    INSERT INTO b5_verification_results
    VALUES (
        'browse_deep_page_parity',
        v_page_size,
        cardinality(v_keyset_ids),
        'PASS'
    );

    SELECT
        ranked.match_priority,
        ranked.created_at,
        ranked.id
    INTO STRICT
        v_anchor_priority,
        v_anchor_created_at,
        v_anchor_id
    FROM (
        SELECT
            p.id,
            p.created_at,
            CASE
                WHEN lower(p.sku) = 'common market' THEN 0
                WHEN lower(p.name) LIKE 'common market%'
                     ESCAPE E'\\' THEN 1
                ELSE 2
            END AS match_priority
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE '%common market%' ESCAPE ''
            OR lower(p.name) LIKE '%common market%' ESCAPE ''
        )
        AND p.status = 'ACTIVE'
    ) AS ranked
    ORDER BY
        ranked.match_priority ASC,
        ranked.created_at DESC,
        ranked.id DESC
    OFFSET (v_common_deep_offset - 1)
    FETCH FIRST 1 ROW ONLY;

    SELECT array_agg(
        page.id
        ORDER BY
            page.match_priority ASC,
            page.created_at DESC,
            page.id DESC
    )
    INTO v_offset_ids
    FROM (
        SELECT
            p.id,
            p.created_at,
            CASE
                WHEN lower(p.sku) = 'common market' THEN 0
                WHEN lower(p.name) LIKE 'common market%'
                     ESCAPE E'\\' THEN 1
                ELSE 2
            END AS match_priority
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE '%common market%' ESCAPE ''
            OR lower(p.name) LIKE '%common market%' ESCAPE ''
        )
        AND p.status = 'ACTIVE'
        ORDER BY
            CASE
                WHEN lower(p.sku) = 'common market' THEN 0
                WHEN lower(p.name) LIKE 'common market%'
                     ESCAPE E'\\' THEN 1
                ELSE 2
            END ASC,
            p.created_at DESC,
            p.id DESC
        OFFSET v_common_deep_offset
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    SELECT array_agg(
        page.id
        ORDER BY
            page.match_priority ASC,
            page.created_at DESC,
            page.id DESC
    )
    INTO v_keyset_ids
    FROM (
        SELECT
            ranked.id,
            ranked.created_at,
            ranked.match_priority
        FROM (
            SELECT
                p.id,
                p.created_at,
                CASE
                    WHEN lower(p.sku) = 'common market' THEN 0
                    WHEN lower(p.name) LIKE 'common market%'
                         ESCAPE E'\\' THEN 1
                    ELSE 2
                END AS match_priority
            FROM products AS p
            WHERE (
                lower(p.sku) LIKE '%common market%' ESCAPE ''
                OR lower(p.name) LIKE '%common market%' ESCAPE ''
            )
            AND p.status = 'ACTIVE'
        ) AS ranked
        WHERE ranked.match_priority > v_anchor_priority
           OR (
               ranked.match_priority = v_anchor_priority
               AND (
                   ranked.created_at < v_anchor_created_at
                   OR (
                       ranked.created_at = v_anchor_created_at
                       AND ranked.id < v_anchor_id
                   )
               )
           )
        ORDER BY
            ranked.match_priority ASC,
            ranked.created_at DESC,
            ranked.id DESC
        FETCH FIRST v_page_size ROWS ONLY
    ) AS page;

    IF coalesce(cardinality(v_offset_ids), 0) <> v_page_size
       OR v_offset_ids IS DISTINCT FROM v_keyset_ids THEN
        RAISE EXCEPTION
            'Common-keyword deep-page parity failed at offset %',
            v_common_deep_offset;
    END IF;

    INSERT INTO b5_verification_results
    VALUES (
        'common_deep_page_parity',
        v_page_size,
        cardinality(v_keyset_ids),
        'PASS'
    );

    IF v_expected_rows = 10000 THEN
        v_anchor_created_at := NULL;
        v_anchor_id := NULL;

        LOOP
            v_page_count := 0;

            FOR v_row IN
                SELECT p.id, p.created_at
                FROM products AS p
                WHERE p.status = 'ACTIVE'
                  AND (
                      v_anchor_id IS NULL
                      OR p.created_at < v_anchor_created_at
                      OR (
                          p.created_at = v_anchor_created_at
                          AND p.id < v_anchor_id
                      )
                  )
                ORDER BY p.created_at DESC, p.id DESC
                FETCH FIRST v_page_size ROWS ONLY
            LOOP
                INSERT INTO b5_browse_seen (id)
                VALUES (v_row.id);

                v_anchor_created_at := v_row.created_at;
                v_anchor_id := v_row.id;
                v_page_count := v_page_count + 1;
            END LOOP;

            EXIT WHEN v_page_count = 0;
        END LOOP;

        SELECT count(*)
        INTO v_expected_count
        FROM products
        WHERE status = 'ACTIVE';

        SELECT count(*)
        INTO v_seen_count
        FROM b5_browse_seen;

        IF v_seen_count <> v_expected_count THEN
            RAISE EXCEPTION
                'Browse traversal expected % rows, visited %',
                v_expected_count,
                v_seen_count;
        END IF;

        INSERT INTO b5_verification_results
        VALUES (
            'browse_full_keyset_traversal_10k',
            v_expected_count,
            v_seen_count,
            'PASS'
        );

        v_anchor_priority := NULL;
        v_anchor_created_at := NULL;
        v_anchor_id := NULL;

        LOOP
            v_page_count := 0;

            FOR v_row IN
                SELECT
                    ranked.id,
                    ranked.created_at,
                    ranked.match_priority
                FROM (
                    SELECT
                        p.id,
                        p.created_at,
                        CASE
                            WHEN lower(p.sku) = 'common market' THEN 0
                            WHEN lower(p.name) LIKE 'common market%'
                                 ESCAPE E'\\' THEN 1
                            ELSE 2
                        END AS match_priority
                    FROM products AS p
                    WHERE (
                        lower(p.sku) LIKE '%common market%' ESCAPE ''
                        OR lower(p.name) LIKE '%common market%' ESCAPE ''
                    )
                    AND p.status = 'ACTIVE'
                ) AS ranked
                WHERE v_anchor_priority IS NULL
                   OR ranked.match_priority > v_anchor_priority
                   OR (
                       ranked.match_priority = v_anchor_priority
                       AND (
                           ranked.created_at < v_anchor_created_at
                           OR (
                               ranked.created_at = v_anchor_created_at
                               AND ranked.id < v_anchor_id
                           )
                       )
                   )
                ORDER BY
                    ranked.match_priority ASC,
                    ranked.created_at DESC,
                    ranked.id DESC
                FETCH FIRST v_page_size ROWS ONLY
            LOOP
                INSERT INTO b5_common_seen (id)
                VALUES (v_row.id);

                v_anchor_priority := v_row.match_priority;
                v_anchor_created_at := v_row.created_at;
                v_anchor_id := v_row.id;
                v_page_count := v_page_count + 1;
            END LOOP;

            EXIT WHEN v_page_count = 0;
        END LOOP;

        SELECT count(*)
        INTO v_expected_count
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE '%common market%' ESCAPE ''
            OR lower(p.name) LIKE '%common market%' ESCAPE ''
        )
        AND p.status = 'ACTIVE';

        SELECT count(*)
        INTO v_seen_count
        FROM b5_common_seen;

        IF v_seen_count <> v_expected_count THEN
            RAISE EXCEPTION
                'Common traversal expected % rows, visited %',
                v_expected_count,
                v_seen_count;
        END IF;

        INSERT INTO b5_verification_results
        VALUES (
            'common_full_keyset_traversal_10k',
            v_expected_count,
            v_seen_count,
            'PASS'
        );
    ELSE
        INSERT INTO b5_verification_results
        VALUES
            (
                'browse_full_keyset_traversal_10k',
                NULL,
                NULL,
                'SKIPPED_AT_100K_BY_DESIGN'
            ),
            (
                'common_full_keyset_traversal_10k',
                NULL,
                NULL,
                'SKIPPED_AT_100K_BY_DESIGN'
            );
    END IF;
END
$verification$;

SELECT
    check_name,
    expected_value,
    actual_value,
    status
FROM b5_verification_results
ORDER BY check_name;

SELECT 'verification_result=success';
