-- 为已有演示 RGB 影像补充波段语义，便于前端展示和任务按钮拦截。
-- 这些影像只有 Red/Green/Blue 三个可见光波段，不包含 NIR，因此不支持 NDVI/NDWI。
UPDATE rs_image
SET metadata_json = COALESCE(metadata_json, '{}'::jsonb)
    || jsonb_build_object(
        'bandMapping', jsonb_build_object('red', 1, 'green', 2, 'blue', 3),
        'bandMappingSource', 'SYSTEM_DEMO_RGB',
        'bandMappingConfidence', 'HIGH',
        'supportedTaskTypes', '[]'::jsonb
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE deleted_at IS NULL
  AND band_count = 3
  AND (
      image_name ILIKE '%RGB Demo%'
      OR image_name ILIKE '%RGB 样本%'
      OR object_key ILIKE '%_rgb%'
      OR object_key ILIKE '%RGB%'
  );

-- 标准 4 波段 Sentinel-2 分析样本采用固定顺序：B04/B03/B02/B08 = Red/Green/Blue/NIR。
UPDATE rs_image
SET metadata_json = COALESCE(metadata_json, '{}'::jsonb)
    || jsonb_build_object(
        'bandMapping', jsonb_build_object('red', 1, 'green', 2, 'blue', 3, 'nir', 4),
        'bandMappingSource', 'SYSTEM_STAC_SENTINEL2',
        'bandMappingConfidence', 'HIGH',
        'supportedTaskTypes', jsonb_build_array('NDVI', 'NDWI')
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE deleted_at IS NULL
  AND band_count = 4
  AND (
      image_name ILIKE '%Sentinel-2 4-Band%'
      OR image_name ILIKE '%Sentinel-2 4 Band%'
      OR object_key ILIKE '%sentinel2_4band%'
      OR object_key ILIKE '%s2_4band%'
  );
