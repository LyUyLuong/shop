-- V2-PS-C1 no-trigram execution-plan template.
--
-- Required psql variables:
-- expected_rows, term, status, surface, offset_rows, page_size.
--
-- term:
--   __C1_BROWSE__ means no keyword predicate.
--
-- status:
--   ACTIVE, INACTIVE, or ALL.
--
-- surface:
--   count, data_offset, or data_cursor.
--
-- For data_cursor, offset_rows=0 captures the first cursor query.
-- A positive offset derives the preceding anchor and captures the
-- equivalent keyset continuation query.

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif

\if :{?term}
\else
\echo 'Missing required variable: term'
\quit 1
\endif

\if :{?status}
\else
\echo 'Missing required variable: status'
\quit 1
\endif

\if :{?surface}
\else
\echo 'Missing required variable: surface'
\quit 1
\endif

\if :{?offset_rows}
\else
\echo 'Missing required variable: offset_rows'
\quit 1
\endif

\if :{?page_size}
\else
\echo 'Missing required variable: page_size'
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
        SELECT count(*)
        FROM products
        WHERE image_key LIKE 'benchmark/c1/%'
    ) = (:expected_rows::bigint * 11 / 20) + 9
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
    ) AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C1 baseline environment guard failed.'
\quit 1
\endif

CREATE FUNCTION pg_temp.c1_match_count(
    p_term text,
    p_status text
)
RETURNS bigint
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
    v_count bigint;
BEGIN
    IF p_term IS NULL THEN
        SELECT count(*)
        INTO v_count
        FROM products AS p
        WHERE (
            p_status = 'ALL'
            OR p.status = p_status
        );
    ELSE
        SELECT count(*)
        INTO v_count
        FROM products AS p
        WHERE (
            lower(p.sku) LIKE lower(
                '%' || p_term || '%'
            ) ESCAPE ''
            OR lower(p.name) LIKE lower(
                '%' || p_term || '%'
            ) ESCAPE ''
        )
        AND (
            p_status = 'ALL'
            OR p.status = p_status
        );
    END IF;

    RETURN v_count;
END
$function$;

CREATE FUNCTION pg_temp.c1_explain(
    p_term text,
    p_status text,
    p_surface text,
    p_offset_rows bigint,
    p_page_size integer
)
RETURNS json
LANGUAGE plpgsql
AS $function$
DECLARE
    v_contains text;
    v_prefix text;

    v_where text := ' WHERE 1 = 1 ';
    v_priority text;
    v_order text;

    v_query text;
    v_anchor_query text;
    v_explain text;

    v_anchor_priority integer;
    v_anchor_created_at timestamptz;
    v_anchor_id uuid;

    v_plan json;
BEGIN
    IF p_status NOT IN (
        'ACTIVE',
        'INACTIVE',
        'ALL'
    ) THEN
        RAISE EXCEPTION
            'Unsupported status: %',
            p_status;
    END IF;

    IF p_surface NOT IN (
        'count',
        'data_offset',
        'data_cursor'
    ) THEN
        RAISE EXCEPTION
            'Unsupported surface: %',
            p_surface;
    END IF;

    IF p_offset_rows < 0 THEN
        RAISE EXCEPTION
            'offset_rows must be non-negative';
    END IF;

    IF p_page_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION
            'page_size must be between 1 and 1000';
    END IF;

    IF p_term IS NOT NULL THEN
        v_contains := '%' || p_term || '%';

        v_prefix := replace(
            replace(
                replace(
                    p_term,
                    E'\\',
                    E'\\\\'
                ),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%';

        v_where := v_where || format(
            $fragment$
            AND (
                lower(p.sku) LIKE lower(%L) ESCAPE ''
                OR lower(p.name) LIKE lower(%L) ESCAPE ''
            )
            $fragment$,
            v_contains,
            v_contains
        );

        v_priority := format(
            $priority$
            CASE
                WHEN lower(p.sku) = lower(%L)
                    THEN 0
                WHEN lower(p.name) LIKE lower(%L)
                     ESCAPE E'\\'
                    THEN 1
                ELSE 2
            END
            $priority$,
            p_term,
            v_prefix
        );

        v_order :=
            ' ORDER BY '
            || v_priority
            || ' ASC, p.created_at DESC, p.id DESC ';
    ELSE
        v_priority := 'NULL::integer';

        v_order :=
            ' ORDER BY p.created_at DESC, p.id DESC ';
    END IF;

    IF p_status <> 'ALL' THEN
        v_where := v_where || format(
            ' AND p.status = %L ',
            p_status
        );
    END IF;

    IF p_surface = 'count' THEN
        v_query :=
            'SELECT count(p.id) '
            || 'FROM products AS p '
            || v_where;
    ELSE
        IF (
            p_surface = 'data_cursor'
            AND p_offset_rows > 0
        ) THEN
            v_anchor_query :=
                'SELECT '
                || v_priority
                || ' AS match_priority, '
                || 'p.created_at, p.id '
                || 'FROM products AS p '
                || v_where
                || v_order
                || format(
                    ' OFFSET %s ROWS '
                    || 'FETCH FIRST 1 ROW ONLY',
                    p_offset_rows - 1
                );

            EXECUTE v_anchor_query
            INTO STRICT
                v_anchor_priority,
                v_anchor_created_at,
                v_anchor_id;

            IF p_term IS NULL THEN
                v_where := v_where || format(
                    $continuation$
                    AND (
                        p.created_at < %L::timestamptz
                        OR (
                            p.created_at = %L::timestamptz
                            AND p.id < %L::uuid
                        )
                    )
                    $continuation$,
                    v_anchor_created_at,
                    v_anchor_created_at,
                    v_anchor_id
                );
            ELSE
                v_where := v_where || format(
                    $continuation$
                    AND (
                        %1$s > %2$s
                        OR (
                            %1$s = %2$s
                            AND (
                                p.created_at < %3$L::timestamptz
                                OR (
                                    p.created_at =
                                        %3$L::timestamptz
                                    AND p.id < %4$L::uuid
                                )
                            )
                        )
                    )
                    $continuation$,
                    v_priority,
                    v_anchor_priority,
                    v_anchor_created_at,
                    v_anchor_id
                );
            END IF;
        END IF;

        v_query :=
            'SELECT '
            || 'p.id, p.created_at, p.description, '
            || 'p.image_key, p.image_url, p.name, '
            || 'p.price, p.sku, p.status, '
            || 'p.stock_quantity, p.updated_at, p.version '
            || 'FROM products AS p '
            || v_where
            || v_order;

        IF p_surface = 'data_offset' THEN
            v_query := v_query || format(
                ' OFFSET %s ROWS ',
                p_offset_rows
            );
        END IF;

        v_query := v_query || format(
            ' FETCH FIRST %s ROWS ONLY ',
            p_page_size
        );
    END IF;

    v_explain :=
        'EXPLAIN ('
        || 'ANALYZE, '
        || 'BUFFERS, '
        || 'VERBOSE, '
        || 'SETTINGS, '
        || 'FORMAT JSON'
        || ') '
        || v_query;

    EXECUTE v_explain INTO v_plan;

    RETURN v_plan;
END
$function$;

SELECT json_build_object(
    'membershipRows',
    pg_temp.c1_match_count(
        nullif(
            :'term',
            '__C1_BROWSE__'
        ),
        :'status'
    ),
    'plan',
    pg_temp.c1_explain(
        nullif(
            :'term',
            '__C1_BROWSE__'
        ),
        :'status',
        :'surface',
        :offset_rows::bigint,
        :page_size::integer
    )
)::text;