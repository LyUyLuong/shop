\set ON_ERROR_STOP 1

DO $ps_d_cleanup_objects$
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D cleanup requires the isolated lab';
    END IF;

    EXECUTE
        'DROP INDEX IF EXISTS '
        || 'public.idx_products_sku_nfc_prefix_v1';

    EXECUTE
        'DROP INDEX IF EXISTS '
        || 'public.idx_products_name_fts_n1_v1';

    EXECUTE
        'DROP INDEX IF EXISTS '
        || 'public.idx_products_name_fts_n2_v1';

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND column_name = 'name_search_vector_v1'
    ) THEN
        EXECUTE $ddl$
            ALTER TABLE public.products
            DROP COLUMN name_search_vector_v1
        $ddl$;
    END IF;

    IF to_regconfig(
        'public.shop_product_name_unaccent_v1'
    ) IS NOT NULL THEN
        EXECUTE $ddl$
            DROP TEXT SEARCH CONFIGURATION
                public.shop_product_name_unaccent_v1
        $ddl$;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    ) THEN
        EXECUTE 'DROP EXTENSION unaccent';
    END IF;
END
$ps_d_cleanup_objects$;

DO $ps_d_cleanup_role$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'shop_fts_runtime'
    ) THEN
        EXECUTE $ddl$
            REVOKE ALL PRIVILEGES
            ON TABLE public.products
            FROM shop_fts_runtime
        $ddl$;

        EXECUTE $ddl$
            REVOKE ALL PRIVILEGES
            ON SCHEMA public
            FROM shop_fts_runtime
        $ddl$;

        EXECUTE $ddl$
            REVOKE ALL PRIVILEGES
            ON DATABASE shop_fts_benchmark
            FROM shop_fts_runtime
        $ddl$;

        IF EXISTS (
            SELECT 1
            FROM pg_catalog.pg_auth_members AS membership
            JOIN pg_catalog.pg_roles AS granted_role
              ON granted_role.oid = membership.roleid
            JOIN pg_catalog.pg_roles AS member_role
              ON member_role.oid = membership.member
            WHERE granted_role.rolname = 'shop_fts_runtime'
              AND member_role.rolname = 'shop_fts_migration'
        ) THEN
            EXECUTE $ddl$
                REVOKE shop_fts_runtime
                FROM shop_fts_migration
            $ddl$;
        END IF;

        EXECUTE 'DROP ROLE shop_fts_runtime';
    END IF;
END
$ps_d_cleanup_role$;

DO $ps_d_cleanup_verify$
DECLARE
    v_rows integer :=
        current_setting('shop_benchmark.row_count')::integer;
    v_seed bigint :=
        current_setting('shop_benchmark.seed')::bigint;
    v_latest_version text;
    v_indexes text[];
    v_expected_indexes constant text[] := ARRAY[
        'idx_products_created_at',
        'idx_products_name_lower',
        'idx_products_sku_lower',
        'idx_products_status',
        'products_pkey'
    ]::text[];
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    )
       OR to_regconfig(
           'public.shop_product_name_unaccent_v1'
       ) IS NOT NULL
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
       )
       OR EXISTS (
           SELECT 1
           FROM pg_catalog.pg_roles
           WHERE rolname = 'shop_fts_runtime'
       ) THEN
        RAISE EXCEPTION 'PS-D candidate residue remains';
    END IF;

    SELECT array_agg(indexname::text ORDER BY indexname)
    INTO v_indexes
    FROM pg_catalog.pg_indexes
    WHERE schemaname = 'public'
      AND tablename = 'products';

    IF v_indexes IS DISTINCT FROM v_expected_indexes THEN
        RAISE EXCEPTION
            'Production index inventory changed: %',
            v_indexes;
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
    ) <> v_rows THEN
        RAISE EXCEPTION 'Cleanup changed product cardinality';
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
        WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) <> 40 THEN
        RAISE EXCEPTION 'Cleanup changed the PS-D overlay';
    END IF;

    IF v_seed <> 20260806 THEN
        RAISE EXCEPTION 'Cleanup received an unexpected seed';
    END IF;

    SELECT version
    INTO v_latest_version
    FROM public.flyway_schema_history
    WHERE success
    ORDER BY installed_rank DESC
    LIMIT 1;

    IF v_latest_version IS DISTINCT FROM '12'
       OR EXISTS (
           SELECT 1
           FROM public.flyway_schema_history
           WHERE NOT success
       )
       OR EXISTS (
           SELECT 1
           FROM public.flyway_schema_history
           WHERE version::integer > 12
       ) THEN
        RAISE EXCEPTION
            'Cleanup changed production Flyway history';
    END IF;

    RAISE NOTICE
        'ps_d_cleanup_verified rows=%, seed=%',
        v_rows,
        v_seed;
END
$ps_d_cleanup_verify$;

SELECT
    count(*) AS product_rows,
    count(*) FILTER (
        WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) AS overlay_rows,
    (
        SELECT count(*)
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    ) AS unaccent_residue,
    (
        SELECT count(*)
        FROM pg_catalog.pg_roles
        WHERE rolname = 'shop_fts_runtime'
    ) AS runtime_role_residue
FROM public.products;