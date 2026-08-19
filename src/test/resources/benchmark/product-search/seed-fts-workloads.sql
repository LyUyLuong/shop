\set ON_ERROR_STOP 1

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

DO $ps_d_seed$
DECLARE
    v_rows integer :=
        current_setting('shop_benchmark.row_count')::integer;
    v_seed bigint :=
        current_setting('shop_benchmark.seed')::bigint;
    v_base_marker text :=
        'V2-PS-A deterministic dataset seed ' || v_seed::text;
    v_overlay_marker text :=
        'V2-PS-D-D07-OVERLAY-V1';
    v_latest_version text;
    v_updated integer;
    v_total bigint;
    v_active bigint;
    v_inactive bigint;
    v_overlay_count bigint;
    v_collision_groups bigint;
BEGIN
    IF current_database() <> 'shop_fts_benchmark'
       OR current_user <> 'shop_fts_migration' THEN
        RAISE EXCEPTION
            'PS-D seed requires shop_fts_benchmark/shop_fts_migration';
    END IF;

    IF v_seed <> 20260806 THEN
        RAISE EXCEPTION 'Unexpected seed: %', v_seed;
    END IF;

    IF v_rows NOT IN (1000, 10000, 100000) THEN
        RAISE EXCEPTION 'Unsupported row count: %', v_rows;
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
       ) THEN
        RAISE EXCEPTION
            'Expected successful production Flyway history through V12';
    END IF;

    SELECT
        count(*),
        count(*) FILTER (WHERE status = 'ACTIVE'),
        count(*) FILTER (WHERE status = 'INACTIVE')
    INTO v_total, v_active, v_inactive
    FROM public.products;

    IF v_total <> v_rows
       OR v_active <> v_rows * 4 / 5
       OR v_inactive <> v_rows / 5 THEN
        RAISE EXCEPTION
            'Unexpected base cardinality/status: total=%, active=%, inactive=%',
            v_total,
            v_active,
            v_inactive;
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
        WHERE description = v_base_marker
    ) <> v_rows THEN
        RAISE EXCEPTION 'Canonical PS-A dataset marker is incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.products
        WHERE image_key = v_overlay_marker
    ) THEN
        RAISE EXCEPTION
            'PS-D overlay already exists; reset the dataset first';
    END IF;

    UPDATE public.products AS product
    SET
        sku = fixture.sku,
        name = fixture.name,
        image_key = v_overlay_marker
    FROM (
        VALUES
            (101, 'PSD-EXACT-00101', 'Utility Device'),
            (102, 'PSD-PREFIX-ALPHA-00102', 'Utility Device'),
            (103, 'PSD-PREFIX-ALPHA-00103', 'Utility Device'),
            (104, 'PSD-SUFFIX-00104', 'Utility Device'),
            (105, 'PSD-INFIX-ABC-00105', 'Utility Device'),
            (106, 'PSD-DESC-00106', 'Utility Device'),
            (107, 'PSD-NAME-00107', 'Precision Camera'),
            (108, 'PSD-NAME-00108', 'Precision Camera Deluxe'),
            (109, 'PSD-NAME-00109', 'Deluxe Precision Camera'),
            (110, 'PSD-VIS-00110', 'Visibility Token'),
            (111, 'PSD-VIS-00111', 'Visibility Token'),
            (112, 'DUAL-SEARCH-00112', 'Dual Search Device'),
            (113, 'CONFLICT-ALPHA-00113', 'Utility Device'),
            (114, 'PSD-CONFLICT-00114', 'Conflict'),
            (115, 'PSD-OVERLAP-00115', 'Overlap Token'),
            (116, 'PSD-RANK-00116', 'Aurora Nebula Headphones'),
            (
                117,
                'PSD-RANK-00117',
                'Aurora Studio Edition Nebula Headphones'
            ),
            (118, 'PSD-RANK-00118', 'Aurora Headphones'),
            (119, 'PSD-RANK-00119', 'Nebula Headphones'),
            (120, 'PSD-RANK-00120', 'Aurora Nebula Headphones'),
            (121, 'PSD-VI-00121', 'Điện Thoại Cao Cấp'),
            (122, 'PSD-VI-00122', 'Dien Thoai Pho Thong'),
            (123, 'PSD-D-00123', 'Đồng Hồ Điện Tử'),
            (124, 'PSD-D-00124', 'Dong Ho Dien Tu'),
            (
                125,
                'PSD-NFD-00125',
                U&'Ca\0300 Phe\0302 Ma\0301y'
            ),
            (126, 'PSD-NFC-00126', 'Cà Phê Máy'),
            (
                127,
                'PSD-JA-00127',
                U&'\65E5\672C\8A9E \30AD\30FC\30DC\30FC\30C9'
            ),
            (128, 'PSD-PUNCT-00128', 'USB-C Adapter Pro'),
            (129, 'PSD-OR-00129', 'Red OR Blue Speaker'),
            (130, 'PSD-MINUS-00130', 'Minus Safe Device'),
            (131, 'PSD-CASE-00131', 'MiXeD SeArCh CaSe'),
            (132, 'PSD-QUOTE-00132', 'Quoted Red Blue Device'),
            (133, 'PSD-ADJ-00133', 'Alpha Beta Gamma'),
            (134, 'PSD-GAP-00134', 'Alpha Wide Gap Beta'),
            (135, 'PSD-EMPTY-00135', '!!!'),
            (136, 'PSD-SPECIAL-00136', 'Percent_Underscore Device'),
            (137, 'CAFÉ-SKU-COLLISION', 'Canonical SKU NFC'),
            (
                138,
                U&'CAFE\0301-SKU-COLLISION',
                'Canonical SKU NFD'
            ),
            (139, 'PSD-COLLISION-00139', 'Collision Ma'),
            (140, 'PSD-COLLISION-00140', 'Collision Má')
    ) AS fixture(row_number, sku, name)
    WHERE product.id = md5(
        v_seed::text
        || ':product:'
        || fixture.row_number::text
    )::uuid;

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated <> 40 THEN
        RAISE EXCEPTION
            'Expected 40 overlay rows, updated %',
            v_updated;
    END IF;

    SELECT count(*)
    INTO v_overlay_count
    FROM public.products
    WHERE image_key = v_overlay_marker;

    IF v_overlay_count <> 40 THEN
        RAISE EXCEPTION
            'Expected 40 overlay markers, found %',
            v_overlay_count;
    END IF;

    SELECT count(*)
    INTO v_collision_groups
    FROM (
        SELECT lower(normalize(sku, NFC))
        FROM public.products
        GROUP BY lower(normalize(sku, NFC))
        HAVING count(*) > 1
    ) AS collisions;

    IF v_collision_groups <> 1 THEN
        RAISE EXCEPTION
            'Expected one NFC-normalized SKU collision group, found %',
            v_collision_groups;
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
    ) <> v_rows THEN
        RAISE EXCEPTION 'Overlay changed total cardinality';
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
        WHERE description = v_base_marker
    ) <> v_rows THEN
        RAISE EXCEPTION 'Overlay changed the canonical description marker';
    END IF;

    IF (
        SELECT count(*)
        FROM public.products
        WHERE price < 0
           OR stock_quantity < 0
           OR version <> 0
    ) <> 0 THEN
        RAISE EXCEPTION 'Overlay changed protected numeric/version values';
    END IF;

    RAISE NOTICE
        'ps_d_overlay_seeded rows=%, fixtures=40, collisions=%',
        v_rows,
        v_collision_groups;
END
$ps_d_seed$;

ANALYZE public.products;

COMMIT;

SELECT
    current_setting('shop_benchmark.row_count')::integer AS rows,
    current_setting('shop_benchmark.seed')::bigint AS seed,
    count(*) FILTER (
        WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) AS overlay_rows,
    count(*) FILTER (
        WHERE status = 'ACTIVE'
          AND image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) AS active_overlay_rows,
    count(*) FILTER (
        WHERE status = 'INACTIVE'
          AND image_key = 'V2-PS-D-D07-OVERLAY-V1'
    ) AS inactive_overlay_rows
FROM public.products;