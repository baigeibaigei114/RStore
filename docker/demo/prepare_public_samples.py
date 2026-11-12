"""Prepare three small, distinct public Sentinel-2 observations using the existing calibration helper."""
import json
from pathlib import Path
import sys

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.vrt import WarpedVRT
from rasterio.warp import transform as transform_coordinates, transform_geom
from rasterio.windows import Window
import requests

sys.path.insert(0, '/support/shared/baseline')
from fetch_public_sample import calibration_for_cog
from scripts.prepare_ndvi_baseline import NODATA, write_stack

CASES = [
    ('dongting-autumn', '洞庭湖湿地', 112.95, 29.30, '2024-11-01T00:00:00Z/2024-11-30T23:59:59Z'),
    ('banna-winter', '西双版纳森林', 100.85, 21.90, '2025-01-01T00:00:00Z/2025-01-31T23:59:59Z'),
    ('poyang-summer', '鄱阳湖夏季', 116.025, 28.925, '2024-07-01T00:00:00Z/2024-07-31T23:59:59Z'),
]


def main():
    output = Path('/out/public-samples')
    output.mkdir(exist_ok=False)
    session = requests.Session()
    session.trust_env = False
    session.headers['User-Agent'] = 'rs-demo-public-sample-preparation/1.0'
    records = []
    for slug, label, lon, lat, interval in CASES:
        response = session.post('https://earth-search.aws.element84.com/v1/search', json={
            'collections': ['sentinel-2-l2a'], 'intersects': {'type': 'Point', 'coordinates': [lon, lat]},
            'datetime': interval, 'limit': 10, 'query': {'eo:cloud_cover': {'lt': 30}},
            'sortby': [{'field': 'properties.eo:cloud_cover', 'direction': 'asc'}]}, timeout=45)
        response.raise_for_status()
        items = response.json()['features']
        if not items:
            raise RuntimeError('No public observations for ' + slug)
        item = items[0]
        assets = item['assets']
        arrays, calibration = [], {}
        print('PREPARING ' + slug + ' sourceId=' + item['id'], flush=True)
        with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN='EMPTY_DIR', GDAL_HTTP_TIMEOUT='90',
                          GDAL_HTTP_MAX_RETRY='1', GDAL_HTTP_MULTIRANGE='SERIAL',
                          GDAL_HTTP_MERGE_CONSECUTIVE_RANGES='NO'):
            with rasterio.open(assets['red']['href']) as reference:
                xs, ys = transform_coordinates('EPSG:4326', reference.crs, [lon], [lat])
                row, col = reference.index(xs[0], ys[0])
                window = Window(col - 250, row - 250, 500, 500)
                if min(window.col_off, window.row_off) < 0 or window.col_off + 500 > reference.width or window.row_off + 500 > reference.height:
                    raise RuntimeError('Window outside source observation')
                crs, transform = reference.crs, reference.window_transform(window)
                for band_name in ('blue', 'green', 'red', 'nir'):
                    asset = assets[band_name]
                    scale, offset = calibration_for_cog(item, asset)
                    calibration[band_name] = {'scale': scale, 'offset': offset,
                                              'catalogOffset': asset['raster:bands'][0]['offset']}
                    with rasterio.open(asset['href']) as band:
                        assert band.crs == reference.crs and band.transform == reference.transform and band.shape == reference.shape
                        raw = band.read(1, window=window, masked=True).astype('float32').filled(np.nan)
                        raw[raw == 0] = np.nan
                        arrays.append(raw * scale + offset)
            with rasterio.open(assets['scl']['href']) as scl:
                with WarpedVRT(scl, crs=crs, transform=transform, width=500, height=500,
                               resampling=Resampling.nearest) as aligned:
                    quality = aligned.read(1, masked=True).filled(0)
        data = np.stack(arrays)
        valid = np.isfinite(data).all(axis=0) & np.isin(quality, [4, 5, 6, 7])
        if valid.sum() < 25000:
            raise RuntimeError('Public crop has fewer than ten percent valid pixels: ' + slug)
        data[:, ~valid] = NODATA
        path = output / (slug + '.tif')
        stac_url = f"https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/{item['id']}"
        write_stack(path, data, crs, transform, {
            'source': 'Copernicus Sentinel-2 L2A via Element 84 Earth Search',
            'sourceId': item['id'], 'stacUrl': stac_url, 'captureTime': item['properties']['datetime'],
            'sceneCloudCover': item['properties']['eo:cloud_cover'], 'calibration': calibration,
            'window': [int(value) for value in window.flatten()],
            'boaOffsetAlreadyApplied': item['properties']['earthsearch:boa_offset_applied'],
            'sclValidClasses': [4, 5, 6, 7], 'sclResampling': 'nearest',
            'validInputPixels': int(valid.sum()), 'inputUnits': 'surface_reflectance'})
        path.with_suffix('.stac.json').write_text(json.dumps(item, indent=2, allow_nan=False))
        west, south, east, north = rasterio.transform.array_bounds(500, 500, transform)
        # The reusable demo region is entirely inside the raster footprint.
        inset = 0.05
        dx, dy = (east - west) * inset, (north - south) * inset
        geometry = transform_geom(crs, 'EPSG:4326', {'type': 'Polygon', 'coordinates': [[
            [west + dx, south + dy], [east - dx, south + dy], [east - dx, north - dy],
            [west + dx, north - dy], [west + dx, south + dy]]]})
        records.append({'slug': slug, 'name': '示例影像 · ' + label + ' ' + item['properties']['datetime'][:10],
                        'sourceId': item['id'], 'stacUrl': stac_url, 'regionGeometry': geometry,
                        'validInputPixels': int(valid.sum())})
        print('PUBLIC_CROP_READY ' + slug + ' valid=' + str(valid.sum()), flush=True)
    (output / 'catalog.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))
    print('THREE_PUBLIC_SAMPLES_PREPARED', flush=True)


if __name__ == '__main__':
    main()
