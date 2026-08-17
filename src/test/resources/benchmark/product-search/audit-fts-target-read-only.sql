\set ON_ERROR_STOP 1

BEGIN TRANSACTION READ ONLY;

DO $ps_d_target_audit_guard$
BEGIN
    IF current_setting('transaction_read_only') <> 'on' THEN
        RAISE EXCEPTION
            'PS-D target audit must run in a read-only transaction';
    END IF;

    IF to_regclass('public.products') IS NULL
       OR to_regclass(
           'public.flyway_schema_history'
       ) IS NULL THEN
        RAISE EXCEPTION
            'Required production schema objects are missing';
    END IF;
END
$ps_d_target_audit_guard$;

SELECT
    current_database() AS database_name,
    current_user AS database_user,
    current_setting('server_version') AS server_version,
    current_setting('server_version_num') AS server_version_num,
    current_setting('server_encoding') AS server_encoding,
    database_catalog.datcollate AS lc_collate,
    database_catalog.datctype AS lc_ctype,
    current_setting('transaction_read_only') AS transaction_read_only
FROM pg_catalog.pg_database AS database_catalog
WHERE database_catalog.datname = current_database();

SELECT
    extension.name,
    extension.default_version,
    extension.installed_version,
    extension.comment
FROM pg_catalog.pg_available_extensions AS extension
WHERE extension.name = 'unaccent';

SELECT
    EXISTS (
        SELECT 1
        FROM pg_catalog.pg_extension
        WHERE extname = 'unaccent'
    ) AS unaccent_installed,
    to_regconfig(
        'public.shop_product_name_unaccent_v1'
    ) IS NOT NULL AS candidate_configuration_installed;

SELECT
    has_database_privilege(
        current_user,
        current_database(),
        'CREATE'
    ) AS runtime_has_database_create,
    has_schema_privilege(
        current_user,
        'public',
        'USAGE'
    ) AS runtime_has_public_usage,
    has_schema_privilege(
        current_user,
        'public',
        'CREATE'
    ) AS runtime_has_public_create,
    has_table_privilege(
        current_user,
        'public.products',
        'SELECT'
    ) AS runtime_can_read_products,
    has_table_privilege(
        current_user,
        'public.products',
        'INSERT'
    ) AS runtime_can_insert_products,
    has_table_privilege(
        current_user,
        'public.products',
        'UPDATE'
    ) AS runtime_can_update_products,
    has_table_privilege(
        current_user,
        'public.products',
        'DELETE'
    ) AS runtime_can_delete_products;

SELECT
    installed_rank,
    version,
    description,
    success
FROM public.flyway_schema_history
ORDER BY installed_rank;

SELECT
    max(installed_rank) AS latest_installed_rank,
    max(version::integer) AS latest_numeric_version,
    bool_and(success) AS all_successful,
    count(*) FILTER (
        WHERE version::integer > 12
    ) AS post_v12_migrations
FROM public.flyway_schema_history;

SELECT
    count(*) FILTER (
        WHERE index_catalog.indexname IN (
            'idx_products_name_fts_n1_v1',
            'idx_products_name_fts_n2_v1',
            'idx_products_sku_nfc_prefix_v1'
        )
    ) AS candidate_index_count,
    count(*) FILTER (
        WHERE index_catalog.indexname LIKE '%_ccnew%'
           OR index_catalog.indexname LIKE '%_ccold%'
    ) AS concurrent_build_residue_count
FROM pg_catalog.pg_indexes AS index_catalog
WHERE index_catalog.schemaname = 'public'
  AND index_catalog.tablename = 'products';

SELECT
    count(*) AS candidate_generated_column_count
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'products'
  AND column_name = 'name_search_vector_v1';

SELECT
    count(*) AS product_rows,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
    ) AS active_rows,
    count(*) FILTER (
        WHERE status = 'INACTIVE'
    ) AS inactive_rows
FROM public.products;

SELECT count(*) AS normalized_sku_collision_groups
FROM (
    SELECT pg_catalog.lower(normalize(sku, NFC))
    FROM public.products
    GROUP BY pg_catalog.lower(normalize(sku, NFC))
    HAVING count(*) > 1
) AS collision_groups;

SELECT
    pg_relation_size(
        'public.products'::pg_catalog.regclass
    ) AS products_table_bytes,
    pg_indexes_size(
        'public.products'::pg_catalog.regclass
    ) AS products_indexes_bytes,
    pg_total_relation_size(
        'public.products'::pg_catalog.regclass
    ) AS products_total_bytes;

SELECT
    index_catalog.indexname,
    pg_relation_size(
        (
            quote_ident(index_catalog.schemaname)
            || '.'
            || quote_ident(index_catalog.indexname)
        )::pg_catalog.regclass
    ) AS index_bytes
FROM pg_catalog.pg_indexes AS index_catalog
WHERE index_catalog.schemaname = 'public'
  AND index_catalog.tablename = 'products'
ORDER BY index_catalog.indexname;

SELECT
    procedure.proname,
    procedure.provolatile,
    pg_catalog.pg_get_function_identity_arguments(
        procedure.oid
    ) AS identity_arguments
FROM pg_catalog.pg_proc AS procedure
JOIN pg_catalog.pg_namespace AS namespace
  ON namespace.oid = procedure.pronamespace
WHERE namespace.nspname = 'pg_catalog'
  AND procedure.proname IN (
      'normalize',
      'to_tsvector',
      'plainto_tsquery',
      'ts_rank_cd'
  )
ORDER BY procedure.proname, identity_arguments;

SELECT
    count(*) FILTER (
        WHERE NOT index_catalog.indisvalid
    ) AS invalid_product_indexes,
    count(*) FILTER (
        WHERE NOT index_catalog.indisready
    ) AS unready_product_indexes
FROM pg_catalog.pg_index AS index_catalog
JOIN pg_catalog.pg_class AS table_catalog
  ON table_catalog.oid = index_catalog.indrelid
JOIN pg_catalog.pg_namespace AS namespace
  ON namespace.oid = table_catalog.relnamespace
WHERE namespace.nspname = 'public'
  AND table_catalog.relname = 'products';

COMMIT;