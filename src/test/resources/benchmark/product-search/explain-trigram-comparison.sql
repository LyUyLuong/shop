-- V2-PS-C3 full-OR execution-plan capture.
-- Required variables: expected_rows, candidate, has_keyword, term, status,
-- surface, offset_rows, page_size. candidate: baseline|gin|gist.
-- surface: count|data_offset|data_cursor.

\set ON_ERROR_STOP on

\if :{?expected_rows}
\else
\echo 'Missing required variable: expected_rows'
\quit 1
\endif
\if :{?candidate}
\else
\echo 'Missing required variable: candidate'
\quit 1
\endif
\if :{?has_keyword}
\else
\echo 'Missing required variable: has_keyword'
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
    :expected_rows::integer IN (10000, 100000)
    AND :'candidate' IN ('baseline', 'gin', 'gist')
    AND :has_keyword::integer IN (0, 1)
    AND :'status' IN ('ACTIVE', 'INACTIVE', 'ALL')
    AND :'surface' IN ('count', 'data_offset', 'data_cursor')
    AND :offset_rows::bigint >= 0
    AND :page_size::integer BETWEEN 1 AND 1000 AS input_ok
\gset input_

\if :input_input_ok
\else
\echo 'STOP: C3 explain input validation failed.'
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
    AND (
        (:'candidate' = 'baseline'
         AND NOT EXISTS (
             SELECT 1 FROM pg_extension
             WHERE extname = 'pg_trgm'
         )
         AND NOT EXISTS (
             SELECT 1 FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename = 'products'
               AND indexname LIKE 'c3_%'
         ))
        OR
        (:'candidate' = 'gin'
         AND EXISTS (
             SELECT 1 FROM pg_extension
             WHERE extname = 'pg_trgm'
         )
         AND to_regclass(
             'public.c3_products_sku_lower_gin_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_name_lower_gin_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_sku_lower_gist_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c3_products_name_lower_gist_trgm'
         ) IS NULL)
        OR
        (:'candidate' = 'gist'
         AND EXISTS (
             SELECT 1 FROM pg_extension
             WHERE extname = 'pg_trgm'
         )
         AND to_regclass(
             'public.c3_products_sku_lower_gist_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_name_lower_gist_trgm'
         ) IS NOT NULL
         AND to_regclass(
             'public.c3_products_sku_lower_gin_trgm'
         ) IS NULL
         AND to_regclass(
             'public.c3_products_name_lower_gin_trgm'
         ) IS NULL)
    ) AS environment_ok
\gset guard_

\if :guard_environment_ok
\else
\echo 'STOP: C3 explain environment or candidate guard failed.'
\quit 1
\endif

CREATE FUNCTION pg_temp.c3_match_count(
    p_has_keyword boolean,
    p_term text,
    p_status text
)
RETURNS bigint
LANGUAGE sql
STABLE
AS $function$
    SELECT count(*)
    FROM products AS p
    WHERE (
        NOT p_has_keyword
        OR lower(p.sku) LIKE lower(
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
$function$;

CREATE FUNCTION pg_temp.c3_explain(
    p_has_keyword boolean,
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
    v_contains text := '%' || p_term || '%';
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
    IF p_status NOT IN ('ACTIVE', 'INACTIVE', 'ALL') THEN
        RAISE EXCEPTION 'Unsupported status: %', p_status;
    END IF;
    IF p_surface NOT IN (
        'count', 'data_offset', 'data_cursor'
    ) THEN
        RAISE EXCEPTION 'Unsupported surface: %', p_surface;
    END IF;
    IF p_offset_rows < 0 THEN
        RAISE EXCEPTION 'offset_rows must be non-negative';
    END IF;
    IF p_page_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'page_size must be between 1 and 1000';
    END IF;
    IF NOT p_has_keyword AND p_surface = 'data_cursor' THEN
        RAISE EXCEPTION 'blank browse cursor is not a C3 plan control';
    END IF;

    IF p_has_keyword THEN
        v_prefix := replace(
            replace(
                replace(p_term, E'\\', E'\\\\'),
                '%', E'\\%'
            ),
            '_', E'\\_'
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
                WHEN lower(p.sku) = lower(%L) THEN 0
                WHEN lower(p.name) LIKE lower(%L)
                     ESCAPE E'\\' THEN 1
                ELSE 2
            END
            $priority$,
            p_term,
            v_prefix
        );

        v_order :=
            ' ORDER BY ' || v_priority ||
            ' ASC, p.created_at DESC, p.id DESC ';
    ELSE
        v_order := ' ORDER BY p.created_at DESC, p.id DESC ';
    END IF;

    IF p_status <> 'ALL' THEN
        v_where := v_where || format(
            ' AND p.status = %L ',
            p_status
        );
    END IF;

    IF p_surface = 'count' THEN
        v_query :=
            'SELECT count(p.id) FROM products AS p ' ||
            v_where;
    ELSE
        IF p_surface = 'data_cursor' AND p_offset_rows > 0 THEN
            v_anchor_query :=
                'SELECT ' || v_priority ||
                ' AS match_priority, p.created_at, p.id ' ||
                'FROM products AS p ' || v_where || v_order ||
                format(
                    ' OFFSET %s ROWS FETCH FIRST 1 ROW ONLY',
                    p_offset_rows - 1
                );

            EXECUTE v_anchor_query
            INTO STRICT
                v_anchor_priority,
                v_anchor_created_at,
                v_anchor_id;

            v_where := v_where || format(
                $continuation$
                AND (
                    %1$s > %2$s
                    OR (
                        %1$s = %2$s
                        AND (
                            p.created_at < %3$L::timestamptz
                            OR (
                                p.created_at = %3$L::timestamptz
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

        v_query :=
            'SELECT p.id, p.created_at, p.description, ' ||
            'p.image_key, p.image_url, p.name, p.price, ' ||
            'p.sku, p.status, p.stock_quantity, ' ||
            'p.updated_at, p.version ' ||
            'FROM products AS p ' || v_where || v_order;

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
        'EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, ' ||
        'FORMAT JSON) ' || v_query;

    EXECUTE v_explain INTO v_plan;
    RETURN v_plan;
END
$function$;

SELECT json_build_object(
    'candidate', :'candidate',
    'membershipRows', pg_temp.c3_match_count(
        :has_keyword::integer = 1,
        :'term', :'status'
    ),
    'plan', pg_temp.c3_explain(
        :has_keyword::integer = 1,
        :'term',
        :'status',
        :'surface',
        :offset_rows::bigint,
        :page_size::integer
    )
)::text;
