\set ON_ERROR_STOP 1

CREATE TEMP TABLE ps_d_build_metrics (
    phase text PRIMARY KEY,
    elapsed_ms numeric NOT NULL
) ON COMMIT PRESERVE ROWS;

DO $ps_d_create$
DECLARE
    v_state text :=
        current_setting('shop_ps_d.candidate_state');
    v_rows integer :=
        current_setting('shop_benchmark.row_count')::integer;
    v_seed bigint :=
        current_setting('shop_benchmark.seed')::bigint;
    v_started_at timestamptz;
    v_name_index_expected boolean;
    v_sku_index_expected boolean;
    v_generated_expected boolean;
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D candidate creation requires the isolated lab';
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
        RAISE EXCEPTION 'Verified PS-D dataset overlay is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'shop_fts_runtime'
    ) THEN
        RAISE EXCEPTION 'shop_fts_runtime is missing';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    )
       OR EXISTS (
           SELECT 1
           FROM pg_catalog.pg_ts_config
           WHERE cfgname = 'shop_product_name_unaccent_v1'
             AND cfgnamespace = 'public'::regnamespace
       )
       OR to_regclass(
           'public.idx_products_name_fts_n1_v1'
       ) IS NOT NULL
       OR to_regclass(
           'public.idx_products_name_fts_n2_v1'
       ) IS NOT NULL
       OR to_regclass(
           'public.idx_products_sku_nfc_prefix_v1'
       ) IS NOT NULL
       OR EXISTS (
           SELECT 1
           FROM information_schema.columns
           WHERE table_schema = 'public'
             AND table_name = 'products'
             AND column_name = 'name_search_vector_v1'
       ) THEN
        RAISE EXCEPTION
            'Candidate residue exists before state %',
            v_state;
    END IF;

    v_started_at := clock_timestamp();

    EXECUTE 'CREATE EXTENSION unaccent WITH SCHEMA public';

    EXECUTE $ddl$
        CREATE TEXT SEARCH CONFIGURATION
            public.shop_product_name_unaccent_v1
            (COPY = pg_catalog.simple)
    $ddl$;

    EXECUTE $ddl$
        ALTER TEXT SEARCH CONFIGURATION
            public.shop_product_name_unaccent_v1
        ALTER MAPPING FOR word, hword, hword_part
        WITH public.unaccent, pg_catalog.simple
    $ddl$;

    EXECUTE $ddl$
        GRANT EXECUTE
        ON FUNCTION public.unaccent(pg_catalog.text)
        TO shop_fts_runtime
    $ddl$;

    EXECUTE $ddl$
        GRANT EXECUTE
        ON FUNCTION public.unaccent(
            pg_catalog.regdictionary,
            pg_catalog.text
        )
        TO shop_fts_runtime
    $ddl$;

    INSERT INTO ps_d_build_metrics(phase, elapsed_ms)
    VALUES (
        'dependencies',
        round(
            extract(
                epoch FROM clock_timestamp() - v_started_at
            ) * 1000,
            3
        )
    );

    IF v_state IN ('N1', 'N1+S') THEN
        v_started_at := clock_timestamp();

        EXECUTE $ddl$
            CREATE INDEX idx_products_name_fts_n1_v1
            ON public.products
            USING gin (
                pg_catalog.to_tsvector(
                    'public.shop_product_name_unaccent_v1'
                        ::pg_catalog.regconfig,
                    normalize(name, NFC)
                )
            )
        $ddl$;

        INSERT INTO ps_d_build_metrics(phase, elapsed_ms)
        VALUES (
            'name_candidate',
            round(
                extract(
                    epoch FROM clock_timestamp() - v_started_at
                ) * 1000,
                3
            )
        );
    ELSIF v_state IN ('N2', 'N2+S') THEN
        v_started_at := clock_timestamp();

        EXECUTE $ddl$
            ALTER TABLE public.products
            ADD COLUMN name_search_vector_v1 tsvector
            GENERATED ALWAYS AS (
                pg_catalog.to_tsvector(
                    'public.shop_product_name_unaccent_v1'
                        ::pg_catalog.regconfig,
                    normalize(name, NFC)
                )
            ) STORED
        $ddl$;

        EXECUTE $ddl$
            CREATE INDEX idx_products_name_fts_n2_v1
            ON public.products
            USING gin (name_search_vector_v1)
        $ddl$;

        INSERT INTO ps_d_build_metrics(phase, elapsed_ms)
        VALUES (
            'name_candidate',
            round(
                extract(
                    epoch FROM clock_timestamp() - v_started_at
                ) * 1000,
                3
            )
        );
    END IF;

    IF v_state IN ('N0+S', 'N1+S', 'N2+S') THEN
        v_started_at := clock_timestamp();

        EXECUTE $ddl$
            CREATE INDEX idx_products_sku_nfc_prefix_v1
            ON public.products (
                pg_catalog.lower(normalize(sku, NFC))
                pg_catalog.text_pattern_ops
            )
        $ddl$;

        INSERT INTO ps_d_build_metrics(phase, elapsed_ms)
        VALUES (
            'sku_companion',
            round(
                extract(
                    epoch FROM clock_timestamp() - v_started_at
                ) * 1000,
                3
            )
        );
    END IF;

    EXECUTE 'ANALYZE public.products';

    v_name_index_expected :=
        v_state IN ('N1', 'N1+S', 'N2', 'N2+S');
    v_generated_expected :=
        v_state IN ('N2', 'N2+S');
    v_sku_index_expected :=
        v_state IN ('N0+S', 'N1+S', 'N2+S');

    IF (
        (
            to_regclass('public.idx_products_name_fts_n1_v1')
                IS NOT NULL
        )::integer
        +
        (
            to_regclass('public.idx_products_name_fts_n2_v1')
                IS NOT NULL
        )::integer
    ) <> (
        CASE WHEN v_name_index_expected THEN 1 ELSE 0 END
    ) THEN
        RAISE EXCEPTION
            'Unexpected name-index inventory for %',
            v_state;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND column_name = 'name_search_vector_v1'
          AND is_generated = 'ALWAYS'
    ) IS DISTINCT FROM v_generated_expected THEN
        RAISE EXCEPTION
            'Unexpected generated-column state for %',
            v_state;
    END IF;

    IF (
        to_regclass(
            'public.idx_products_sku_nfc_prefix_v1'
        ) IS NOT NULL
    ) IS DISTINCT FROM v_sku_index_expected THEN
        RAISE EXCEPTION
            'Unexpected SKU-companion state for %',
            v_state;
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
        RAISE EXCEPTION
            'Candidate state contains an invalid index';
    END IF;

    RAISE NOTICE
        'ps_d_candidate_created state=%, rows=%',
        v_state,
        v_rows;
END
$ps_d_create$;

SELECT phase, elapsed_ms
FROM ps_d_build_metrics
ORDER BY phase;

SELECT
    current_setting('shop_ps_d.candidate_state') AS candidate_state,
    pg_size_pretty(
        pg_relation_size('public.products'::regclass)
    ) AS table_size,
    pg_size_pretty(
        pg_indexes_size('public.products'::regclass)
    ) AS all_indexes_size,
    pg_size_pretty(
        pg_total_relation_size('public.products'::regclass)
    ) AS total_relation_size,
    pg_relation_size(
        to_regclass('public.idx_products_name_fts_n1_v1')
    ) AS n1_index_bytes,
    pg_relation_size(
        to_regclass('public.idx_products_name_fts_n2_v1')
    ) AS n2_index_bytes,
    pg_relation_size(
        to_regclass('public.idx_products_sku_nfc_prefix_v1')
    ) AS sku_companion_bytes;