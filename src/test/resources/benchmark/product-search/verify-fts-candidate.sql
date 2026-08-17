\set ON_ERROR_STOP 1

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

DO $ps_d_verify_environment$
DECLARE
    v_state text :=
        current_setting('shop_ps_d.candidate_state');
    v_rows integer :=
        current_setting('shop_benchmark.row_count')::integer;
    v_seed bigint :=
        current_setting('shop_benchmark.seed')::bigint;
    v_expected_n1 boolean;
    v_expected_n2 boolean;
    v_expected_s boolean;
    v_generated_mismatches bigint;
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D verification requires the isolated lab';
    END IF;

    IF v_state NOT IN (
        'N0',
        'N0+S',
        'N1',
        'N1+S',
        'N2',
        'N2+S'
    ) THEN
        RAISE EXCEPTION 'Unsupported candidate state: %', v_state;
    END IF;

    IF v_seed <> 20260806
       OR v_rows NOT IN (1000, 10000, 100000) THEN
        RAISE EXCEPTION
            'Unexpected dataset identity: rows=%, seed=%',
            v_rows,
            v_seed;
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
    ) <> v_rows
       OR (
           SELECT count(*)
           FROM public.products
           WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1'
       ) <> 40 THEN
        RAISE EXCEPTION 'PS-D fixture overlay is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    )
       OR NOT EXISTS (
           SELECT 1
           FROM pg_catalog.pg_ts_config
           WHERE cfgname = 'shop_product_name_unaccent_v1'
             AND cfgnamespace = 'public'::regnamespace
       ) THEN
        RAISE EXCEPTION
            'FTS extension or configuration is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'shop_fts_runtime'
    ) THEN
        RAISE EXCEPTION 'Runtime role is missing';
    END IF;

    IF has_database_privilege(
        'shop_fts_runtime',
        current_database(),
        'CREATE'
    )
       OR has_schema_privilege(
           'shop_fts_runtime',
           'public',
           'CREATE'
       ) THEN
        RAISE EXCEPTION
            'Runtime role has forbidden DDL privileges';
    END IF;

    IF NOT (
        has_schema_privilege(
            'shop_fts_runtime',
            'public',
            'USAGE'
        )
        AND has_table_privilege(
            'shop_fts_runtime',
            'public.products',
            'SELECT'
        )
        AND has_table_privilege(
            'shop_fts_runtime',
            'public.products',
            'INSERT'
        )
        AND has_table_privilege(
            'shop_fts_runtime',
            'public.products',
            'UPDATE'
        )
        AND has_table_privilege(
            'shop_fts_runtime',
            'public.products',
            'DELETE'
        )
    ) THEN
        RAISE EXCEPTION
            'Runtime role is missing bounded products privileges';
    END IF;

    v_expected_n1 := v_state IN ('N1', 'N1+S');
    v_expected_n2 := v_state IN ('N2', 'N2+S');
    v_expected_s := v_state IN ('N0+S', 'N1+S', 'N2+S');

    IF (
        to_regclass(
            'public.idx_products_name_fts_n1_v1'
        ) IS NOT NULL
    ) IS DISTINCT FROM v_expected_n1 THEN
        RAISE EXCEPTION 'Unexpected N1 object state';
    END IF;

    IF (
        to_regclass(
            'public.idx_products_name_fts_n2_v1'
        ) IS NOT NULL
    ) IS DISTINCT FROM v_expected_n2 THEN
        RAISE EXCEPTION 'Unexpected N2 index state';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND column_name = 'name_search_vector_v1'
          AND is_generated = 'ALWAYS'
    ) IS DISTINCT FROM v_expected_n2 THEN
        RAISE EXCEPTION 'Unexpected N2 generated-column state';
    END IF;

    IF (
        to_regclass(
            'public.idx_products_sku_nfc_prefix_v1'
        ) IS NOT NULL
    ) IS DISTINCT FROM v_expected_s THEN
        RAISE EXCEPTION 'Unexpected SKU-companion state';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_index AS index_catalog
        JOIN pg_catalog.pg_class AS index_class
          ON index_class.oid = index_catalog.indexrelid
        WHERE index_class.relname IN (
            'idx_products_name_fts_n1_v1',
            'idx_products_name_fts_n2_v1',
            'idx_products_sku_nfc_prefix_v1'
        )
          AND (
              NOT index_catalog.indisvalid
              OR NOT index_catalog.indisready
          )
    ) THEN
        RAISE EXCEPTION 'Candidate has an invalid index';
    END IF;

    IF v_expected_n2 THEN
        EXECUTE $query$
            SELECT count(*)
            FROM public.products
            WHERE name_search_vector_v1
                IS DISTINCT FROM
                pg_catalog.to_tsvector(
                    'public.shop_product_name_unaccent_v1'
                        ::pg_catalog.regconfig,
                    normalize(name, NFC)
                )
        $query$
        INTO v_generated_mismatches;

        IF v_generated_mismatches <> 0 THEN
            RAISE EXCEPTION
                'Generated-vector mismatches: %',
                v_generated_mismatches;
        END IF;
    END IF;

    IF pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize('Điện Thoại', NFC)
    ) IS DISTINCT FROM pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize('Dien Thoai', NFC)
    ) THEN
        RAISE EXCEPTION
            'Vietnamese accent folding is asymmetric';
    END IF;

    IF pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize('Đồng Hồ', NFC)
    ) IS DISTINCT FROM pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize('Dong Ho', NFC)
    ) THEN
        RAISE EXCEPTION
            'Vietnamese D/Đ folding is asymmetric';
    END IF;

    IF pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize(U&'Ca\0300 Phe\0302 Ma\0301y', NFC)
    ) IS DISTINCT FROM pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        normalize('Cà Phê Máy', NFC)
    ) THEN
        RAISE EXCEPTION
            'NFC/NFD document normalization is inconsistent';
    END IF;

    IF pg_catalog.to_tsvector(
        'public.shop_product_name_unaccent_v1'
            ::pg_catalog.regconfig,
        U&'\65E5\672C\8A9E'
    ) = ''::tsvector THEN
        RAISE EXCEPTION
            'Non-Latin control unexpectedly produced an empty vector';
    END IF;
END
$ps_d_verify_environment$;

CREATE FUNCTION pg_temp.ps_d_fixture_id(
    fixture_number integer
)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
AS $function$
    SELECT md5(
        '20260806:product:' || fixture_number::text
    )::uuid
$function$;

CREATE FUNCTION pg_temp.ps_d_search(
    search_keyword text,
    visibility text DEFAULT 'PUBLIC',
    minimum_price numeric DEFAULT NULL,
    maximum_price numeric DEFAULT NULL
)
RETURNS TABLE (
    id uuid,
    match_tier integer,
    surface_form_priority integer,
    raw_rank double precision,
    rank_score bigint,
    created_at timestamptz
)
LANGUAGE sql
STABLE
AS $function$
WITH parameters AS (
    SELECT
        normalize(
            NULLIF(btrim(search_keyword), ''),
            NFC
        ) AS keyword_nfc
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
evaluated AS (
    SELECT
        product.id,
        product.created_at,
        pg_catalog.lower(
            normalize(product.sku, NFC)
        ) AS normalized_sku,
        pg_catalog.lower(
            public.unaccent(
                'public.unaccent'::pg_catalog.regdictionary,
                normalize(product.name, NFC)
            )
        ) AS folded_name,
        pg_catalog.to_tsvector(
            'public.shop_product_name_unaccent_v1'
                ::pg_catalog.regconfig,
            normalize(product.name, NFC)
        ) AS name_document,
        pg_catalog.to_tsvector(
            'pg_catalog.simple'::pg_catalog.regconfig,
            normalize(product.name, NFC)
        ) AS accent_document,
        parameters.sku_keyword,
        parameters.folded_keyword,
        parameters.name_query,
        parameters.accent_query,
        parameters.escaped_sku_prefix,
        parameters.escaped_name_prefix
    FROM public.products AS product
    CROSS JOIN normalized_parameters AS parameters
    WHERE CASE visibility
        WHEN 'PUBLIC' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_ALL' THEN TRUE
        WHEN 'ADMIN_ACTIVE' THEN product.status = 'ACTIVE'
        WHEN 'ADMIN_INACTIVE' THEN product.status = 'INACTIVE'
        ELSE FALSE
    END
      AND (
          minimum_price IS NULL
          OR product.price >= minimum_price
      )
      AND (
          maximum_price IS NULL
          OR product.price <= maximum_price
      )
),
signals AS (
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
members AS (
    SELECT
        signals.*,
        CASE
            WHEN exact_sku THEN 0
            WHEN exact_name THEN 1
            WHEN sku_prefix THEN 2
            WHEN name_prefix THEN 3
            ELSE 4
        END AS calculated_tier
    FROM signals
    WHERE sku_prefix OR name_match
),
ranked AS (
    SELECT
        members.*,
        CASE
            WHEN calculated_tier IN (0, 2) THEN 0
            WHEN accent_match THEN 0
            ELSE 1
        END AS calculated_surface,
        CASE
            WHEN calculated_tier IN (0, 2) THEN 0::double precision
            ELSE pg_catalog.ts_rank_cd(
                name_document,
                name_query,
                32
            )::double precision
        END AS calculated_raw_rank
    FROM members
)
SELECT
    ranked.id,
    calculated_tier,
    calculated_surface,
    calculated_raw_rank,
    round(
        calculated_raw_rank::numeric * 1000000
    )::bigint,
    ranked.created_at
FROM ranked
ORDER BY
    calculated_tier ASC,
    calculated_surface ASC,
    round(
        calculated_raw_rank::numeric * 1000000
    )::bigint DESC,
    ranked.created_at DESC,
    ranked.id DESC
$function$;

CREATE FUNCTION pg_temp.ps_d_assert_set(
    assertion_name text,
    search_keyword text,
    visibility text,
    expected_fixture_numbers integer[],
    minimum_price numeric DEFAULT NULL,
    maximum_price numeric DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_actual uuid[];
    v_expected uuid[];
BEGIN
    SELECT array_agg(result.id ORDER BY result.id)
    INTO v_actual
    FROM pg_temp.ps_d_search(
        search_keyword,
        visibility,
        minimum_price,
        maximum_price
    ) AS result;

    SELECT array_agg(
        pg_temp.ps_d_fixture_id(fixture_number)
        ORDER BY pg_temp.ps_d_fixture_id(fixture_number)
    )
    INTO v_expected
    FROM unnest(expected_fixture_numbers) AS fixture(fixture_number);

    IF v_actual IS DISTINCT FROM v_expected THEN
        RAISE EXCEPTION
            'Assertion % failed: actual=%, expected=%',
            assertion_name,
            v_actual,
            v_expected;
    END IF;
END
$function$;

CREATE FUNCTION pg_temp.ps_d_assert_order(
    assertion_name text,
    search_keyword text,
    visibility text,
    expected_fixture_numbers integer[]
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_actual uuid[];
    v_expected uuid[];
BEGIN
    SELECT array_agg(
        result.id
        ORDER BY
            result.match_tier ASC,
            result.surface_form_priority ASC,
            result.rank_score DESC,
            result.created_at DESC,
            result.id DESC
    )
    INTO v_actual
    FROM pg_temp.ps_d_search(
        search_keyword,
        visibility
    ) AS result;

    SELECT array_agg(
        pg_temp.ps_d_fixture_id(fixture_number)
        ORDER BY expected_position
    )
    INTO v_expected
    FROM unnest(expected_fixture_numbers)
        WITH ORDINALITY AS fixture(
            fixture_number,
            expected_position
        );

    IF v_actual IS DISTINCT FROM v_expected THEN
        RAISE EXCEPTION
            'Order assertion % failed: actual=%, expected=%',
            assertion_name,
            v_actual,
            v_expected;
    END IF;
END
$function$;

CREATE FUNCTION pg_temp.ps_d_assert_empty(
    assertion_name text,
    search_keyword text,
    visibility text
)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_temp.ps_d_search(
            search_keyword,
            visibility
        )
    ) THEN
        RAISE EXCEPTION
            'Expected no result for %',
            assertion_name;
    END IF;
END
$function$;

CREATE FUNCTION pg_temp.ps_d_assert_signal(
    assertion_name text,
    search_keyword text,
    visibility text,
    fixture_number integer,
    expected_tier integer,
    expected_surface integer
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_tier integer;
    v_surface integer;
BEGIN
    SELECT
        result.match_tier,
        result.surface_form_priority
    INTO v_tier, v_surface
    FROM pg_temp.ps_d_search(
        search_keyword,
        visibility
    ) AS result
    WHERE result.id =
        pg_temp.ps_d_fixture_id(fixture_number);

    IF NOT FOUND
       OR v_tier <> expected_tier
       OR v_surface <> expected_surface THEN
        RAISE EXCEPTION
            'Signal assertion % failed: tier=%, surface=%',
            assertion_name,
            v_tier,
            v_surface;
    END IF;
END
$function$;

DO $ps_d_literal_oracle$
DECLARE
    v_first_rank double precision;
    v_second_rank double precision;
    v_offset_id uuid;
    v_cursor_id uuid;
    v_anchor record;
    v_first_order uuid[];
    v_second_order uuid[];
BEGIN
    PERFORM pg_temp.ps_d_assert_set(
        'exact SKU',
        'psd-exact-00101',
        'PUBLIC',
        ARRAY[101]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'selective SKU prefix',
        'psd-prefix-alpha',
        'PUBLIC',
        ARRAY[102, 103]
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'rejected SKU suffix',
        '00104',
        'PUBLIC'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'rejected SKU infix',
        'infix-abc',
        'ADMIN_ALL'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'description exclusion',
        'deterministic dataset',
        'PUBLIC'
    );

    PERFORM pg_temp.ps_d_assert_order(
        'exact name, name prefix, remaining FTS',
        'precision camera',
        'PUBLIC',
        ARRAY[107, 108, 109]
    );

    PERFORM pg_temp.ps_d_assert_order(
        'exact name before SKU prefix',
        'conflict',
        'PUBLIC',
        ARRAY[114, 113]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'deduplicate SKU and name membership',
        'dual',
        'PUBLIC',
        ARRAY[112]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'public visibility',
        'visibility token',
        'PUBLIC',
        ARRAY[111]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'ADMIN visibility',
        'visibility token',
        'ADMIN_ALL',
        ARRAY[110, 111]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'ADMIN inactive visibility',
        'visibility token',
        'ADMIN_INACTIVE',
        ARRAY[110]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'price filter',
        'visibility token',
        'ADMIN_ALL',
        ARRAY[111],
        161.00,
        163.00
    );

    SELECT result.raw_rank
    INTO v_first_rank
    FROM pg_temp.ps_d_search(
        'aurora headphones',
        'PUBLIC'
    ) AS result
    WHERE result.id = pg_temp.ps_d_fixture_id(116);

    SELECT result.raw_rank
    INTO v_second_rank
    FROM pg_temp.ps_d_search(
        'aurora headphones',
        'PUBLIC'
    ) AS result
    WHERE result.id = pg_temp.ps_d_fixture_id(117);

    IF v_first_rank IS NULL
       OR v_second_rank IS NULL
       OR v_first_rank <= v_second_rank THEN
        RAISE EXCEPTION
            'Adjacent-term cover density did not outrank separated terms';
    END IF;

    PERFORM pg_temp.ps_d_assert_signal(
        'accent-preserving Vietnamese form',
        'Điện Thoại',
        'PUBLIC',
        121,
        3,
        0
    );

    PERFORM pg_temp.ps_d_assert_signal(
        'folded-only Vietnamese form',
        'Điện Thoại',
        'PUBLIC',
        122,
        3,
        1
    );

    PERFORM pg_temp.ps_d_assert_signal(
        'plain Vietnamese surface',
        'Dien Thoai',
        'PUBLIC',
        122,
        3,
        0
    );

    PERFORM pg_temp.ps_d_assert_signal(
        'accented row under plain query',
        'Dien Thoai',
        'PUBLIC',
        121,
        3,
        1
    );

    PERFORM pg_temp.ps_d_assert_signal(
        'Đ-preserving form',
        'Đồng Hồ Điện Tử',
        'PUBLIC',
        123,
        1,
        0
    );

    PERFORM pg_temp.ps_d_assert_signal(
        'D folded-only form',
        'Đồng Hồ Điện Tử',
        'PUBLIC',
        124,
        1,
        1
    );

    PERFORM pg_temp.ps_d_assert_set(
        'NFC and NFD names',
        'Cà Phê Máy',
        'ADMIN_ALL',
        ARRAY[125, 126]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'Japanese non-Latin search',
        U&'\65E5\672C\8A9E \30AD\30FC\30DC\30FC\30C9',
        'PUBLIC',
        ARRAY[127]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'hyphenated punctuation',
        'USB-C Adapter Pro',
        'PUBLIC',
        ARRAY[128]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'OR remains plain text',
        'red OR blue',
        'PUBLIC',
        ARRAY[129]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'quotes do not create phrase syntax',
        '"red blue"',
        'PUBLIC',
        ARRAY[129, 132]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'leading minus remains plain text',
        '-safe',
        'ADMIN_ALL',
        ARRAY[130]
    );

    PERFORM pg_temp.ps_d_assert_set(
        'mixed case',
        'mixed search case',
        'PUBLIC',
        ARRAY[131]
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'partial final token',
        'precis',
        'PUBLIC'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'empty generated tsquery',
        '!!!',
        'ADMIN_ALL'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'literal percent',
        '%',
        'ADMIN_ALL'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'literal underscore',
        '_',
        'ADMIN_ALL'
    );

    PERFORM pg_temp.ps_d_assert_empty(
        'SQL-looking input remains data',
        '%'' OR 1=1 --',
        'ADMIN_ALL'
    );

    PERFORM pg_temp.ps_d_assert_set(
        'NFC-normalized SKU ambiguity',
        'café-sku-collision',
        'PUBLIC',
        ARRAY[137, 138]
    );

    SELECT array_agg(
        result.id
        ORDER BY
            result.match_tier,
            result.surface_form_priority,
            result.rank_score DESC,
            result.created_at DESC,
            result.id DESC
    )
    INTO v_first_order
    FROM pg_temp.ps_d_search(
        'precision camera',
        'PUBLIC'
    ) AS result;

    SELECT array_agg(
        result.id
        ORDER BY
            result.match_tier,
            result.surface_form_priority,
            result.rank_score DESC,
            result.created_at DESC,
            result.id DESC
    )
    INTO v_second_order
    FROM pg_temp.ps_d_search(
        'precision camera',
        'PUBLIC'
    ) AS result;

    IF v_first_order IS DISTINCT FROM v_second_order THEN
        RAISE EXCEPTION 'Repeated ordering is not deterministic';
    END IF;

    SELECT *
    INTO v_anchor
    FROM pg_temp.ps_d_search(
        'precision camera',
        'PUBLIC'
    )
    ORDER BY
        match_tier,
        surface_form_priority,
        rank_score DESC,
        created_at DESC,
        id DESC
    LIMIT 1;

    SELECT result.id
    INTO v_offset_id
    FROM pg_temp.ps_d_search(
        'precision camera',
        'PUBLIC'
    ) AS result
    ORDER BY
        result.match_tier,
        result.surface_form_priority,
        result.rank_score DESC,
        result.created_at DESC,
        result.id DESC
    OFFSET 1
    LIMIT 1;

    SELECT result.id
    INTO v_cursor_id
    FROM pg_temp.ps_d_search(
        'precision camera',
        'PUBLIC'
    ) AS result
    WHERE
        result.match_tier > v_anchor.match_tier
        OR (
            result.match_tier = v_anchor.match_tier
            AND result.surface_form_priority >
                v_anchor.surface_form_priority
        )
        OR (
            result.match_tier = v_anchor.match_tier
            AND result.surface_form_priority =
                v_anchor.surface_form_priority
            AND result.rank_score < v_anchor.rank_score
        )
        OR (
            result.match_tier = v_anchor.match_tier
            AND result.surface_form_priority =
                v_anchor.surface_form_priority
            AND result.rank_score = v_anchor.rank_score
            AND result.created_at < v_anchor.created_at
        )
        OR (
            result.match_tier = v_anchor.match_tier
            AND result.surface_form_priority =
                v_anchor.surface_form_priority
            AND result.rank_score = v_anchor.rank_score
            AND result.created_at = v_anchor.created_at
            AND result.id < v_anchor.id
        )
    ORDER BY
        result.match_tier,
        result.surface_form_priority,
        result.rank_score DESC,
        result.created_at DESC,
        result.id DESC
    LIMIT 1;

    IF v_cursor_id IS DISTINCT FROM v_offset_id THEN
        RAISE EXCEPTION
            'Offset/cursor continuation mismatch: offset=%, cursor=%',
            v_offset_id,
            v_cursor_id;
    END IF;
END
$ps_d_literal_oracle$;

SAVEPOINT ps_d_live_data;

DELETE FROM public.products
WHERE id = pg_temp.ps_d_fixture_id(107);

SELECT pg_temp.ps_d_assert_order(
    'delete-between-pages behavior',
    'precision camera',
    'PUBLIC',
    ARRAY[108, 109]
);

UPDATE public.products
SET name = 'Utility Device'
WHERE id = pg_temp.ps_d_fixture_id(108);

SELECT pg_temp.ps_d_assert_set(
    'update-between-pages behavior',
    'precision camera',
    'PUBLIC',
    ARRAY[109]
);

ROLLBACK TO SAVEPOINT ps_d_live_data;

DO $ps_d_post_live_verify$
BEGIN
    IF (
        SELECT count(*)
        FROM public.products
    ) <> current_setting(
        'shop_benchmark.row_count'
    )::integer THEN
        RAISE EXCEPTION
            'Live-data verification changed cardinality';
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
        WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) <> 40 THEN
        RAISE EXCEPTION
            'Live-data verification changed fixture overlay';
    END IF;
END
$ps_d_post_live_verify$;

SELECT
    current_setting('shop_ps_d.candidate_state')
        AS candidate_state,
    current_setting('shop_benchmark.row_count')::integer
        AS product_rows,
    40 AS literal_fixture_rows,
    'PASS' AS lifecycle_result,
    'PASS' AS literal_oracle_result,
    'PASS' AS ordering_result,
    'PASS' AS cursor_arithmetic_result,
    'PASS' AS live_data_result;

ROLLBACK;