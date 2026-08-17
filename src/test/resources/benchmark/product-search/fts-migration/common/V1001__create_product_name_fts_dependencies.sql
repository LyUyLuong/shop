DO $ps_d_common_guard$
DECLARE
    latest_version text;
BEGIN
    IF current_database() <> 'shop_fts_benchmark' THEN
        RAISE EXCEPTION
            'PS-D migration requires shop_fts_benchmark';
    END IF;

    IF current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D migration requires shop_fts_migration';
    END IF;

    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'PS-D requires UTF8';
    END IF;

    SELECT version
    INTO latest_version
    FROM public.flyway_schema_history
    WHERE success
    ORDER BY installed_rank DESC
    LIMIT 1;

    IF latest_version IS DISTINCT FROM '12' THEN
        RAISE EXCEPTION
            'Expected Flyway V12 before lab V1001';
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
    ) THEN
        RAISE EXCEPTION
            'unaccent already exists; possible lab residue';
    END IF;

    IF to_regconfig(
        'public.shop_product_name_unaccent_v1'
    ) IS NOT NULL THEN
        RAISE EXCEPTION
            'FTS configuration already exists';
    END IF;
END
$ps_d_common_guard$;

CREATE EXTENSION unaccent
    WITH SCHEMA public;

CREATE TEXT SEARCH CONFIGURATION
    public.shop_product_name_unaccent_v1
    (COPY = pg_catalog.simple);

ALTER TEXT SEARCH CONFIGURATION
    public.shop_product_name_unaccent_v1
    ALTER MAPPING FOR word, hword, hword_part
    WITH public.unaccent, pg_catalog.simple;

GRANT EXECUTE
    ON FUNCTION public.unaccent(pg_catalog.text)
    TO shop_fts_runtime;

GRANT EXECUTE
    ON FUNCTION public.unaccent(
        pg_catalog.regdictionary,
        pg_catalog.text
    )
    TO shop_fts_runtime;