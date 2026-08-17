\set ON_ERROR_STOP 1

BEGIN;

DO $ps_d_role_guard$
DECLARE
    latest_version text;
BEGIN
    IF current_database() <> 'shop_fts_benchmark' THEN
        RAISE EXCEPTION
            'PS-D role setup requires shop_fts_benchmark';
    END IF;

    IF current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D role setup requires shop_fts_migration';
    END IF;

    IF to_regclass('public.products') IS NULL THEN
        RAISE EXCEPTION 'products table is missing';
    END IF;

    SELECT version
    INTO latest_version
    FROM public.flyway_schema_history
    WHERE success
    ORDER BY installed_rank DESC
    LIMIT 1;

    IF latest_version IS DISTINCT FROM '12' THEN
        RAISE EXCEPTION
            'Expected production Flyway V12, found %',
            latest_version;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.flyway_schema_history
        WHERE NOT success
    ) THEN
        RAISE EXCEPTION
            'Flyway history contains an unsuccessful migration';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'shop_fts_runtime'
    ) THEN
        RAISE EXCEPTION
            'shop_fts_runtime already exists; possible lab residue';
    END IF;

    CREATE ROLE shop_fts_runtime
        NOLOGIN
        NOINHERIT
        NOSUPERUSER
        NOCREATEDB
        NOCREATEROLE
        NOREPLICATION
        NOBYPASSRLS;
END
$ps_d_role_guard$;

REVOKE CREATE
    ON DATABASE shop_fts_benchmark
    FROM shop_fts_runtime;

REVOKE CREATE
    ON SCHEMA public
    FROM shop_fts_runtime;

GRANT USAGE
    ON SCHEMA public
    TO shop_fts_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE public.products
    TO shop_fts_runtime;

GRANT shop_fts_runtime
    TO shop_fts_migration;

DO $ps_d_role_verify$
BEGIN
    IF has_database_privilege(
        'shop_fts_runtime',
        current_database(),
        'CREATE'
    ) THEN
        RAISE EXCEPTION
            'Runtime role must not have database CREATE';
    END IF;

    IF has_schema_privilege(
        'shop_fts_runtime',
        'public',
        'CREATE'
    ) THEN
        RAISE EXCEPTION
            'Runtime role must not have schema CREATE';
    END IF;

    IF NOT has_schema_privilege(
        'shop_fts_runtime',
        'public',
        'USAGE'
    ) THEN
        RAISE EXCEPTION
            'Runtime role requires public schema USAGE';
    END IF;

    IF NOT (
        has_table_privilege(
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
            'Runtime role is missing bounded products DML';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members membership
        JOIN pg_catalog.pg_roles granted_role
          ON granted_role.oid = membership.roleid
        JOIN pg_catalog.pg_roles member_role
          ON member_role.oid = membership.member
        WHERE granted_role.rolname = 'shop_fts_runtime'
          AND member_role.rolname = 'shop_fts_migration'
          AND NOT membership.admin_option
    ) THEN
        RAISE EXCEPTION
            'Migration principal cannot SET ROLE to runtime';
    END IF;
END
$ps_d_role_verify$;

COMMIT;