"""Seed public crops and a real official SAFE product through authenticated business APIs."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

BASE = Path('/opt/ndvi-demo')
PREPARED = BASE / 'ai-safe-prepared'
JOURNAL = BASE / 'feature-seed-journal.json'


class DemoApi:
    def __init__(self, base_url='http://127.0.0.1:18080/api'):
        self.base_url = base_url.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.opener.addheaders = [('User-Agent', 'rs-demo-feature-validation/1.0 (+https://demo.example.com)')]
        self.token = None
        access = json.loads((BASE / 'demo-access.json').read_text())
        self.token = self.call('/auth/login', 'POST', {'username': access['username'], 'password': access['password']})['accessToken']

    def raw(self, path, method='GET', data=None, content_type=None, length=None, base_url=None):
        headers = {'Host': 'rs-api.example.com', 'Origin': 'https://demo.example.com'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if content_type:
            headers['Content-Type'] = content_type
        if length is not None:
            headers['Content-Length'] = str(length)
        request = urllib.request.Request((base_url or self.base_url) + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=300) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def call(self, path, method='GET', body=None, data=None, content_type=None, length=None, base_url=None):
        if body is not None:
            data, content_type = json.dumps(body, ensure_ascii=False).encode(), 'application/json'
        status, _, payload = self.raw(path, method, data, content_type, length, base_url)
        if not 200 <= status < 300:
            raise RuntimeError(f'API HTTP {status}: {path}')
        value = json.loads(payload)
        if value.get('code') != 200:
            raise RuntimeError(f'API business code {value.get("code")}: {path}')
        return value['data']


def save(journal):
    JOURNAL.write_text(json.dumps(journal, ensure_ascii=False, indent=2))
    JOURNAL.chmod(0o600)


def ensure_region(api, name, geometry):
    existing = next((row for row in api.call('/monitoring-regions') if row['name'] == name), None)
    if existing:
        return existing
    return api.call('/monitoring-regions', 'POST', {'name': name, 'geometry': geometry})


def seed_public(api, journal):
    records = json.loads((PREPARED / 'public-samples/catalog.json').read_text())
    journal.setdefault('publicSamples', {})
    for record in records:
        slug = record['slug']
        folder = PREPARED / 'public-samples'
        manifest = json.loads((folder / (slug + '.json')).read_text())
        if slug in journal['publicSamples']:
            image = api.call('/images/' + str(journal['publicSamples'][slug]['imageId']))
        else:
            existing = api.call('/images?pageNum=1&pageSize=100')['records']
            image = next((row for row in existing if row['imageName'] == record['name']), None)
            if image is None:
                boundary = 'rs-demo-' + uuid.uuid4().hex
                parts = []
                for name, suffix, mime in [('file', '.tif', 'image/tiff'), ('manifest', '.json', 'application/json')]:
                    file = folder / (slug + suffix)
                    parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{file.name}"\r\nContent-Type: {mime}\r\n\r\n').encode() + file.read_bytes() + b'\r\n')
                parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="name"\r\n\r\n{record["name"]}\r\n--{boundary}--\r\n').encode())
                image = api.call('/images/upload-standardized', 'POST', data=b''.join(parts), content_type='multipart/form-data; boundary=' + boundary)
        metadata = json.loads(image['metadataJson'])
        assert metadata['admission']['status'] == 'PASSED'
        assert metadata['admission']['manifest']['sha256'] == manifest['sha256']
        assert metadata['admission']['manifest']['sourceId'] == record['sourceId']
        if image['visibility'] != 'PUBLIC':
            image = api.call('/images/' + str(image['id']) + '/visibility', 'PATCH', {'visibility': 'PUBLIC'})
        region = ensure_region(api, record['name'].replace('示例影像', '示例区域'), record['regionGeometry'])
        journal['publicSamples'][slug] = {**record, 'imageId': image['id'], 'regionId': region['id'],
                                         'visibility': image['visibility'], 'admission': 'PASSED', 'sha256': manifest['sha256']}
        save(journal)
        print('PUBLIC_SAMPLE_REGISTERED ' + slug + ' imageId=' + str(image['id']), flush=True)


def seed_safe(api, journal):
    source = PREPARED / 'official-pb0513.zip'
    if not journal.get('safeProductId'):
        existing = api.call('/ingestion/products')['items']
        product = next((row for row in existing if row['name'] == '官方 SAFE 示例 · Sentinel-2 L2A PB05.13'), None)
        if product is None:
            journal.setdefault('safeCreateRequestId', str(uuid.uuid4()))
            save(journal)
            product = api.call('/ingestion/products', 'POST', {'clientRequestId': journal['safeCreateRequestId'],
                               'name': '官方 SAFE 示例 · Sentinel-2 L2A PB05.13', 'fileName': source.name, 'sizeBytes': source.stat().st_size})
        journal['safeProductId'] = product['productId']
        save(journal)
    path = '/ingestion/products/' + journal['safeProductId']
    product = api.call(path)
    if product['status'] == 'AWAITING_UPLOAD':
        # Operator-only preload uses the private Docker address. The public proxy still has its upload limit.
        address = subprocess.check_output(['sudo', '-n', 'docker', 'inspect', '--format',
                   '{{ (index .NetworkSettings.Networks "rs-demo_default").IPAddress }}', 'rs-demo-backend-1'], text=True).strip()
        assert ipaddress.ip_address(address).is_private
        with source.open('rb') as stream:
            api.call(path + '/content', 'PUT', data=stream, content_type='application/zip',
                     length=source.stat().st_size, base_url='http://' + address + ':8080/api')
        print('OFFICIAL_SAFE_UPLOADED_THROUGH_BUSINESS_API', flush=True)
    previous = None
    for _ in range(150):
        product = api.call(path)
        if product['status'] != previous:
            print('SAFE_PRODUCT_STATUS=' + product['status'], flush=True)
            previous = product['status']
        if product['status'] in ('READY', 'FAILED', 'EXPIRED'):
            break
        time.sleep(2)
    if product['status'] != 'READY':
        raise RuntimeError('SAFE inspection did not succeed: ' + product['status'] + '/' + str((product.get('error') or {}).get('reason')))
    grid = product['grid']
    code = '''
import json,sys
from affine import Affine
from rasterio.warp import transform_geom
grid=json.loads(sys.argv[1]);transform=Affine(*grid['transform'])
ring=[transform*point for point in [(4000.1,4511.9),(4511.9,4511.9),(4511.9,4000.1),(4000.1,4000.1),(4000.1,4511.9)]]
print(json.dumps(transform_geom(grid['crs'],'EPSG:4326',{'type':'Polygon','coordinates':[ring]})))
'''
    geometry = json.loads(subprocess.check_output(['sudo', '-n', 'docker', 'exec', 'rs-demo-backend-1',
                          'python', '-c', code, json.dumps(grid)], text=True))
    region = ensure_region(api, 'SAFE 示例选区 · 512 × 512 像素', geometry)
    journal['safeRegionId'], journal['safeSourceId'] = region['id'], product['sourceId']
    journal['safeArchiveSha256'] = 'e6dba7035969b213551ff53f6684e7c22acf4aa355ef8908065f85a601634143'
    save(journal)
    print('OFFICIAL_SAFE_READY productId=' + journal['safeProductId'] + ' regionId=' + str(region['id']), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--public', action='store_true')
    parser.add_argument('--safe', action='store_true')
    args = parser.parse_args()
    api = DemoApi()
    journal = json.loads(JOURNAL.read_text()) if JOURNAL.exists() else {}
    if args.public:
        seed_public(api, journal)
    if args.safe:
        seed_safe(api, journal)


if __name__ == '__main__':
    main()
