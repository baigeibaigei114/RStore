const fs = require('fs');
const path = require('path');

const rootDir = path.resolve(__dirname, '..', '..');
const rawDir = path.join(rootDir, 'data', 'admin-region', 'raw', 'datav');
const outputDir = path.join(rootDir, 'data', 'admin-region', 'processed');
const outputFile = path.join(outputDir, 'datav_admin_region_import.sql');

const SOURCE = 'datav';
const SOURCE_VERSION = 'areas_v3';

function sqlString(value) {
  if (value === null || value === undefined) {
    return 'NULL';
  }
  return `'${String(value).replace(/'/g, "''")}'`;
}

function dollarQuote(value, tag) {
  return `$${tag}$${JSON.stringify(value)}$${tag}$`;
}

function normalizeFeature(feature) {
  const properties = feature.properties || {};
  const adcode = properties.adcode;

  if (!Number.isInteger(adcode)) {
    return null;
  }

  const parentAdcode = properties.parent && Number.isInteger(properties.parent.adcode)
    ? properties.parent.adcode
    : null;

  return {
    id: adcode,
    adcode: String(adcode),
    name: properties.name || String(adcode),
    level: properties.level || 'unknown',
    parentId: parentAdcode && parentAdcode !== 100000 ? parentAdcode : null,
    geometry: feature.geometry,
  };
}

function collectRegions() {
  const regionsByAdcode = new Map();
  const files = fs.readdirSync(rawDir)
    .filter((name) => /^\d{6}_full\.json$/.test(name))
    .sort();

  for (const file of files) {
    const filePath = path.join(rawDir, file);
    const data = JSON.parse(fs.readFileSync(filePath, 'utf8'));

    for (const feature of data.features || []) {
      const region = normalizeFeature(feature);
      if (!region) {
        continue;
      }
      regionsByAdcode.set(region.id, region);
    }
  }

  return Array.from(regionsByAdcode.values())
    .sort((left, right) => left.id - right.id);
}

function buildInsert(region, index) {
  const geometry = dollarQuote(region.geometry, `geom_${index}`);
  return `INSERT INTO rs_admin_region (id, adcode, name, level, parent_id, geom, source, source_version)
VALUES (
    ${region.id},
    ${sqlString(region.adcode)},
    ${sqlString(region.name)},
    ${sqlString(region.level)},
    ${region.parentId === null ? 'NULL' : region.parentId},
    ST_Multi(
        ST_CollectionExtract(
            ST_MakeValid(
                ST_SetSRID(ST_GeomFromGeoJSON(${geometry}), 4326)
            ),
            3
        )
    )::geometry(MultiPolygon, 4326),
    ${sqlString(SOURCE)},
    ${sqlString(SOURCE_VERSION)}
)
ON CONFLICT (id) DO UPDATE SET
    adcode = EXCLUDED.adcode,
    name = EXCLUDED.name,
    level = EXCLUDED.level,
    parent_id = EXCLUDED.parent_id,
    geom = EXCLUDED.geom,
    source = EXCLUDED.source,
    source_version = EXCLUDED.source_version,
    updated_at = CURRENT_TIMESTAMP;`;
}

function main() {
  if (!fs.existsSync(rawDir)) {
    throw new Error(`Raw DataV directory not found: ${rawDir}`);
  }
  fs.mkdirSync(outputDir, { recursive: true });

  const regions = collectRegions();
  const counts = regions.reduce((acc, region) => {
    acc[region.level] = (acc[region.level] || 0) + 1;
    return acc;
  }, {});

  const lines = [];
  lines.push('-- Generated from DataV GeoAtlas raw GeoJSON files.');
  lines.push('-- Source: https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json');
  lines.push('BEGIN;');
  lines.push('');
  lines.push('ALTER TABLE rs_admin_region');
  lines.push('    ADD COLUMN IF NOT EXISTS adcode VARCHAR(20),');
  lines.push('    ADD COLUMN IF NOT EXISTS source VARCHAR(50),');
  lines.push('    ADD COLUMN IF NOT EXISTS source_version VARCHAR(50);');
  lines.push('');
  lines.push('CREATE UNIQUE INDEX IF NOT EXISTS uk_rs_admin_region_adcode');
  lines.push('    ON rs_admin_region (adcode)');
  lines.push('    WHERE adcode IS NOT NULL;');
  lines.push('');

  regions.forEach((region, index) => {
    lines.push(buildInsert(region, index));
    lines.push('');
  });

  lines.push("SELECT setval(pg_get_serial_sequence('rs_admin_region', 'id'), GREATEST((SELECT MAX(id) FROM rs_admin_region), 1));");
  lines.push('COMMIT;');
  lines.push('');

  fs.writeFileSync(outputFile, lines.join('\n'), 'utf8');
  console.log(JSON.stringify({
    outputFile,
    total: regions.length,
    counts,
  }, null, 2));
}

main();
