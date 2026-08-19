DO $ps_d_sku_guard$
DECLARE
    n1_exists boolean;
    n2_exists boolean;
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION 'Invalid PS-D SKU environment';
    END IF;

    n1_exists :=
        to_regclass(
            'public.idx_products_name_fts_n1_v1'
        ) IS NOT NULL;

    n2_exists :=
        to_regclass(
            'public.idx_products_name_fts_n2_v1'
        ) IS NOT NULL;

    IF n1_exists = n2_exists THEN
        RAISE EXCEPTION
            'SKU companion requires exactly one N1 or N2 index';
    END IF;

    IF to_regclass(
        'public.idx_products_sku_lower'
    ) IS NULL THEN
        RAISE EXCEPTION
            'Production SKU uniqueness index is missing';
    END IF;

    IF to_regclass(
        'public.idx_products_sku_nfc_prefix_v1'
    ) IS NOT NULL THEN
        RAISE EXCEPTION
            'SKU companion index already exists';
    END IF;
END
$ps_d_sku_guard$;

CREATE INDEX CONCURRENTLY idx_products_sku_nfc_prefix_v1
    ON public.products (
        pg_catalog.lower(normalize(sku, NFC))
        pg_catalog.text_pattern_ops
    );

ANALYZE public.products;