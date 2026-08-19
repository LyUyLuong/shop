DO $ps_d_n2_index_guard$
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION 'Invalid PS-D N2 index environment';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'products'
          AND column_name = 'name_search_vector_v1'
          AND is_generated = 'ALWAYS'
    ) THEN
        RAISE EXCEPTION
            'N2 generated column is missing';
    END IF;

    IF to_regclass(
        'public.idx_products_name_fts_n2_v1'
    ) IS NOT NULL THEN
        RAISE EXCEPTION 'N2 index already exists';
    END IF;

    IF to_regclass(
        'public.idx_products_name_fts_n1_v1'
    ) IS NOT NULL THEN
        RAISE EXCEPTION 'N2 must not coexist with N1';
    END IF;
END
$ps_d_n2_index_guard$;

CREATE INDEX CONCURRENTLY idx_products_name_fts_n2_v1
    ON public.products
    USING gin (name_search_vector_v1);

ANALYZE public.products;