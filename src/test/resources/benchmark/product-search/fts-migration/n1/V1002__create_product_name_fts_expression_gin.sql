DO $ps_d_n1_guard$
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION 'Invalid PS-D N1 environment';
    END IF;

    IF to_regconfig(
        'public.shop_product_name_unaccent_v1'
    ) IS NULL THEN
        RAISE EXCEPTION 'FTS configuration is missing';
    END IF;

    IF to_regclass(
        'public.idx_products_name_fts_n1_v1'
    ) IS NOT NULL THEN
        RAISE EXCEPTION 'N1 index already exists';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND column_name = 'name_search_vector_v1'
    ) THEN
        RAISE EXCEPTION
            'N1 must not coexist with the N2 generated column';
    END IF;
END
$ps_d_n1_guard$;

CREATE INDEX CONCURRENTLY idx_products_name_fts_n1_v1
    ON public.products
    USING gin (
        pg_catalog.to_tsvector(
            'public.shop_product_name_unaccent_v1'
                ::pg_catalog.regconfig,
            normalize(name, NFC)
        )
    );

ANALYZE public.products;