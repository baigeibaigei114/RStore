"""Exercise the demo through business APIs; credentials and signed URLs stay in memory."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


BASE = Path('/opt/ndvi-demo')
ORIGIN = 'https://demo.example.com'
API_HOST = 'rs-api.example.com'
IMAGE_NAME = '示例影像 · 鄱阳湖 2024-11-29'
REGION_NAME = '示例区域 · 250 × 250 像素'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', required=True)
    parser.add_argument('--api-base', default='http://127.0.0.1:18080/api')
    parser.add_argument('--external-files', action='store_true')
    args = parser.parse_args()
    root = BASE / 'releases' / args.release
    if root.resolve().parent != (BASE / 'releases').resolve():
        raise RuntimeError('Unexpected release directory')
    access = json.loads((BASE / 'demo-access.json').read_text())
    report_path = BASE / ('public-verification.json' if args.external_files else 'private-verification.json')
    if args.external_files and args.api_base != 'https://' + API_HOST + '/api':
        raise RuntimeError('Public verification requires the actual public API endpoint')
    journal_path = BASE / (('public-' if args.external_files else 'private-') + args.release + '-journal.json')
    journal = json.loads(journal_path.read_text()) if journal_path.exists() else {'release': args.release}
    if journal['release'] != args.release:
        raise RuntimeError('A verification journal already belongs to another release')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    opener.addheaders = [('User-Agent', 'rs-demo-deployment-verifier/1.0 (+https://demo.example.com)')]
    token = None

    def save_journal():
        journal_path.write_text(json.dumps(journal, ensure_ascii=False, indent=2))
        os.chmod(journal_path, 0o600)

    def raw(path, method='GET', data=None, content_type=None, authenticated=True):
        headers = {'Origin': ORIGIN, 'Host': API_HOST}
        if method == 'OPTIONS':
            headers['Access-Control-Request-Method'] = 'POST'
            headers['Access-Control-Request-Headers'] = 'authorization,content-type'
        if content_type:
            headers['Content-Type'] = content_type
        if token and authenticated:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.api_base.rstrip('/') + path, data=data, method=method, headers=headers)
        try:
            with opener.open(request, timeout=120) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def call(path, method='GET', body=None, data=None, content_type=None):
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            content_type = 'application/json'
        status, headers, payload = raw(path, method, data, content_type)
        if status != 200:
            raise RuntimeError(f'Business request failed: HTTP {status}, path={path.split("?")[0]}')
        value = json.loads(payload)
        if value.get('code') != 200:
            raise RuntimeError(f'Business request rejected: code={value.get("code")}, path={path.split("?")[0]}')
        return value['data']

    status, headers, _ = raw('/auth/login', 'OPTIONS', authenticated=False)
    assert status == 204 and headers.get('Access-Control-Allow-Origin') == ORIGIN
    token = call('/auth/login', 'POST', {'username': access['username'], 'password': access['password']})['accessToken']
    user = call('/auth/me')
    print('LOGIN_VERIFIED', flush=True)

    compose_prefix = ['sudo', '-n', 'env', 'RELEASE_TAG=' + args.release, 'docker', 'compose',
                      '--env-file', str(BASE / 'secrets.env'), '-f', str(root / 'compose.yml')]
    container = subprocess.check_output(compose_prefix + ['ps', '-q', 'backend'], text=True).strip()

    def container_probe(code, local_file=None, remote_file=None):
        if local_file:
            subprocess.run(['sudo', '-n', 'docker', 'cp', str(local_file), container + ':' + remote_file], check=True, stdout=subprocess.DEVNULL)
        result = subprocess.check_output(['sudo', '-n', 'docker', 'exec', '-i', container, 'python', '-c', code], text=True)
        return json.loads(result)

    expected = container_probe('''
import json,numpy as np,rasterio
from rasterio.windows import Window
from rasterio.warp import transform_geom
with rasterio.open('/tmp/demo-public.tif') as ds:
    window=Window(125,125,250,250)
    west,south,east,north=ds.window_bounds(window)
    geom=transform_geom(ds.crs,'EPSG:4326',{'type':'Polygon','coordinates':[[[west,south],[east,south],[east,north],[west,north],[west,south]]]})
    red=ds.read(3,window=window,masked=True).astype('float64').filled(np.nan)
    nir=ds.read(4,window=window,masked=True).astype('float64').filled(np.nan)
    valid=np.isfinite(red)&np.isfinite(nir)&(np.abs(nir+red)>1e-6)
    values=((nir[valid]-red[valid])/(nir[valid]+red[valid])).astype('float32')
    points=geom['coordinates'][0]
    print(json.dumps({'geometry':geom,'bbox':[min(p[0] for p in points),min(p[1] for p in points),max(p[0] for p in points),max(p[1] for p in points)],'totalPixelCount':62500,'validPixelCount':int(values.size),'mean':float(values.mean(dtype='float64'))}))
''', root / 'sample/public.tif', '/tmp/demo-public.tif')

    if not journal.get('imageId'):
        existing = call('/images?pageNum=1&pageSize=100')['records']
        existing = [image for image in existing if image['imageName'] == IMAGE_NAME]
        if existing:
            image = call('/images/' + str(existing[0]['id']))
        else:
            boundary = 'rs-demo-' + uuid.uuid4().hex
            parts = []
            for name, filename, mime in [('file', 'public.tif', 'image/tiff'), ('manifest', 'manifest.json', 'application/json')]:
                parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode() + (root / 'sample' / filename).read_bytes() + b'\r\n')
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="name"\r\n\r\n{IMAGE_NAME}\r\n--{boundary}--\r\n').encode())
            image = call('/images/upload-standardized', 'POST', data=b''.join(parts), content_type='multipart/form-data; boundary=' + boundary)
        metadata = json.loads(image['metadataJson'])
        assert metadata['admission']['status'] == 'PASSED'
        journal['imageId'] = image['id']
        save_journal()
    print('PUBLIC_SAMPLE_READY imageId=' + str(journal['imageId']), flush=True)

    if not journal.get('regionId'):
        regions = call('/monitoring-regions')
        region = next((r for r in regions if r['name'] == REGION_NAME), None)
        if region is None:
            region = call('/monitoring-regions', 'POST', {'name': REGION_NAME, 'geometry': expected['geometry']})
        else:
            existing_ring = json.loads(region['geometryJson'])['coordinates'][0]
            expected_ring = expected['geometry']['coordinates'][0]
            assert len(existing_ring) == len(expected_ring)
            assert all(abs(a-b) < 1e-7 for left,right in zip(existing_ring,expected_ring) for a,b in zip(left,right))
        journal['regionId'] = region['id']
        save_journal()
    if not journal.get('taskId'):
        result = call('/tasks', 'POST', {'imageId': journal['imageId'], 'monitoringRegionId': journal['regionId'],
                      'taskType': 'NDVI', 'clientRequestId': 'deploy-' + args.release + ('-public-v1' if args.external_files else '-ndvi-v1'),
                      'params': {'redBand': 3, 'nirBand': 4}})
        journal['taskId'] = result['taskId']
        save_journal()
    task_path = '/tasks/' + str(journal['taskId'])
    previous_status = None
    for _ in range(120):
        task = call(task_path)
        if task['status'] != previous_status:
            print('TASK_STATUS taskId=' + str(journal['taskId']) + ' status=' + task['status'], flush=True)
            previous_status = task['status']
        if task['status'] in ('SUCCESS', 'FAILED', 'CANCELED'):
            break
        time.sleep(1)
    assert task['status'] == 'SUCCESS', 'Demo NDVI did not succeed'
    result = call(task_path + '/result')
    metadata = json.loads(result['resultMetadata'])
    statistics = metadata['statistics']
    assert metadata['scope'] == 'monitoring_region' and metadata['schemaVersion'] == 2
    assert statistics['totalPixelCount'] == expected['totalPixelCount']
    assert statistics['validPixelCount'] == expected['validPixelCount']
    assert math.isclose(statistics['mean'], expected['mean'], rel_tol=0, abs_tol=1e-6)

    signed = urllib.parse.urlsplit(call(task_path + '/result/download-url')['url'])
    assert signed.scheme == 'https' and signed.hostname == API_HOST
    assert signed.path.startswith('/remote-sensing-images/')
    if args.external_files:
        download_url = urllib.parse.urlunsplit(signed)
    else:
        download_url = 'http://127.0.0.1:18080' + signed.path + '?' + signed.query
    with opener.open(urllib.request.Request(download_url, headers={'Host': API_HOST, 'Origin': ORIGIN}), timeout=120) as response:
        output = response.read()
    digest = hashlib.sha256(output).hexdigest()
    if result.get('checksum') and len(result['checksum']) == 64:
        assert digest == result['checksum'].lower()
    output_path = BASE / 'verified-result.tif'
    output_path.write_bytes(output)

    map_verified = False
    for _ in range(60):
        result = call(task_path + '/result')
        if result.get('status') == 'PUBLISHED':
            break
        time.sleep(2)
    if result.get('status') != 'PUBLISHED':
        call('/geoserver/publish/' + str(journal['taskId']), 'POST')
        result = call(task_path + '/result')
    if result.get('status') == 'PUBLISHED':
        query = urllib.parse.urlencode({'request': 'GetMap', 'version': '1.1.1', 'srs': 'EPSG:4326',
                                      'bbox': ','.join(map(str, expected['bbox'])), 'width': 256, 'height': 256,
                                      'format': 'image/png', 'transparent': 'true'})
        status, headers, png = raw('/layers/' + str(result['id']) + '/wms?' + query)
        assert status == 200 and png.startswith(b'\x89PNG\r\n\x1a\n')
        (BASE / 'verified-map.png').write_bytes(png)
        map_verified = True
    assert map_verified, 'Demo map was not published'
    summary = {'release': args.release, 'apiBase': args.api_base, 'userId': user['userId'],
               **journal, 'statistics': statistics, 'independentExpected': {k: expected[k] for k in ('totalPixelCount','validPixelCount','mean')},
               'signedDownloadVerified': True, 'downloadSha256': digest, 'downloadBytes': len(output),
               'mapPngVerified': map_verified, 'publicationStatus': result['status'], 'paidModelCalls': 0}
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    os.chmod(report_path, 0o600)
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    try:
        main()
    except urllib.error.URLError as error:
        raise SystemExit('Network verification failed: ' + type(error.reason).__name__)
