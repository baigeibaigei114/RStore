"""Verify real SAFE and AI-created NDVI against independent NumPy calculations and HTTPS files."""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

from seed_demo_assets import BASE, JOURNAL, DemoApi, save


def expected(source, geometry, destination):
    subprocess.run(['sudo', '-n', 'docker', 'cp', str(source), 'rs-demo-backend-1:' + destination], check=True, stdout=subprocess.DEVNULL)
    code = '''
import json,sys,numpy as np,rasterio
from rasterio.features import geometry_mask
from rasterio.warp import transform_geom
with rasterio.open(sys.argv[1]) as ds:
 geometry=transform_geom('EPSG:4326',ds.crs,json.loads(sys.argv[2]))
 region=geometry_mask([geometry],out_shape=ds.shape,transform=ds.transform,invert=True,all_touched=False)
 red=ds.read(3,masked=True).astype('float64').filled(np.nan)
 nir=ds.read(4,masked=True).astype('float64').filled(np.nan)
 valid=region&np.isfinite(red)&np.isfinite(nir)&(np.abs(nir+red)>1e-6)
 values=((nir[valid]-red[valid])/(nir[valid]+red[valid])).astype('float32')
 print(json.dumps({'totalPixelCount':int(region.sum()),'validPixelCount':int(valid.sum()),'mean':float(values.mean(dtype='float64')),'min':float(values.min()),'max':float(values.max())}))
'''
    return json.loads(subprocess.check_output(['sudo', '-n', 'docker', 'exec', 'rs-demo-backend-1', 'python', '-c', code,
                                              destination, json.dumps(geometry)], text=True))


def download(api, signed, destination):
    parsed = urllib.parse.urlsplit(signed)
    assert parsed.scheme == 'https' and parsed.hostname == 'rs-api.example.com'
    assert parsed.path.startswith(('/remote-sensing-images/', '/rs-demo-ingestion/'))
    request = urllib.request.Request(signed, headers={'Origin': 'https://demo.example.com'})
    with api.opener.open(request, timeout=120) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    assert len(data) <= 16 * 1024 * 1024 and data[:4] in (b'II*\0', b'MM\0*')
    destination.write_bytes(data)
    destination.chmod(0o600)
    return hashlib.sha256(data).hexdigest(), len(data), parsed


def main():
    api = DemoApi('https://rs-api.example.com/api')
    journal = json.loads(JOURNAL.read_text())
    regions = {str(row['id']): row for row in api.call('/monitoring-regions')}
    job = next(row for row in api.call('/ingestion/jobs')['items'] if row['productId'] == journal['safeProductId'])
    assert job['status'] == 'SUCCEEDED' and job['overallStatus'] == 'COMPLETE'
    assert job['publicationStatus'] == 'AVAILABLE'
    image = api.call('/images/' + str(job['imageId']))
    admission = json.loads(image['metadataJson'])['admission']
    assert image['visibility'] == 'PRIVATE' and admission['status'] == 'PASSED'
    safe_input = BASE / 'verified-safe-input.tif'
    digest, size, parsed = download(api, api.call('/images/' + str(image['id']) + '/download-url')['url'], safe_input)
    assert digest == admission['manifest']['sha256']
    unsigned = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, '', ''))
    try:
        api.opener.open(unsigned, timeout=20)
    except urllib.error.HTTPError as error:
        assert error.code == 403
    else:
        raise AssertionError('Private SAFE asset allowed an unsigned object download')
    journal.update(safeJobId=job['jobId'], safeImageId=image['id'], safePublicationId=job['publicationId'])
    if not journal.get('safeNdviTaskId'):
        submitted = api.call('/tasks', 'POST', {'imageId': image['id'], 'monitoringRegionId': journal['safeRegionId'],
                            'taskType': 'NDVI', 'clientRequestId': 'demo-safe-20261109-ndvi-v1', 'params': {'redBand': 3, 'nirBand': 4}})
        journal['safeNdviTaskId'] = submitted['taskId']
    save(journal)
    ai = json.loads((BASE / 'ai-feature-verification.json').read_text())
    view = api.call('/agent/executions/' + ai['executionId'])
    assert view.get('task') is not None
    cases = [('safe', journal['safeNdviTaskId'], journal['safeRegionId'], safe_input),
             ('ai-public', view['task']['taskId'], journal['publicSamples']['dongting-autumn']['regionId'],
              BASE / 'ai-safe-prepared/public-samples/dongting-autumn.tif')]
    results = []
    for label, task_id, region_id, source in cases:
        for _ in range(120):
            task = api.call('/tasks/' + str(task_id))
            if task['status'] in ('SUCCESS', 'FAILED', 'CANCELED'):
                break
            time.sleep(1)
        assert task['status'] == 'SUCCESS', label + ' NDVI failed'
        independent = expected(source, json.loads(regions[str(region_id)]['geometryJson']), '/tmp/verify-feature-' + label + '.tif')
        result = api.call('/tasks/' + str(task_id) + '/result')
        metadata = json.loads(result['resultMetadata'])
        statistics = metadata['statistics']
        assert metadata['scope'] == 'monitoring_region' and metadata['schemaVersion'] == 2
        for key in ('totalPixelCount', 'validPixelCount'):
            assert statistics[key] == independent[key], (label, key)
        for key in ('mean', 'min', 'max'):
            assert math.isclose(statistics[key], independent[key], rel_tol=0, abs_tol=1e-6), (label, key)
        checksum, output_size, _ = download(api, api.call('/tasks/' + str(task_id) + '/result/download-url')['url'], BASE / ('verified-' + label + '-ndvi.tif'))
        if result.get('checksum'):
            assert checksum == result['checksum'].lower()
        results.append({'case': label, 'taskId': task_id, 'statistics': statistics, 'independentExpected': independent,
                        'signedResultBytes': output_size, 'signedResultSha256': checksum})
        print('INDEPENDENT_NDVI_AND_HTTPS_DOWNLOAD_VERIFIED ' + label + ' taskId=' + str(task_id), flush=True)
    report = {'safeJobId': job['jobId'], 'safeImageId': image['id'], 'publicationStatus': 'AVAILABLE',
              'safeAssetVisibility': image['visibility'], 'safeInputSha256': digest, 'safeInputBytes': size,
              'unsignedObjectAccess': 'denied', 'regionPixelCount': job['regionPixelCount'],
              'regionValidReflectanceCount': job['regionValidReflectanceCount'], 'ndvi': results}
    path = BASE / 'feature-results-verification.json'
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    path.chmod(0o600)
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    try:
        main()
    except urllib.error.URLError as error:
        raise SystemExit('Feature file verification network failure: ' + type(error.reason).__name__) from None
