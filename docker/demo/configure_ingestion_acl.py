"""Add demo-only SAFE identities and rules while preserving existing GeoServer workspaces and ACLs."""
import base64
import json
import os
import urllib.error
import urllib.request
from xml.etree import ElementTree as ET


def main():
    base = os.environ['GEOSERVER_URL'].rstrip('/')
    assert base == 'http://geoserver:8080/geoserver'
    admin_auth = 'Basic ' + base64.b64encode((os.environ['GEOSERVER_USERNAME'] + ':' + os.environ['GEOSERVER_PASSWORD']).encode()).decode()
    reader = os.environ['GEOSERVER_INGESTION_READ_USERNAME']
    publisher = os.environ['GEOSERVER_INGESTION_PUBLISH_USERNAME']
    assert reader == 'rs_ingest_reader' and publisher == 'rs_ingest_publisher'

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({}))

    def call(path, method='GET', body=None, auth=admin_auth, missing=False):
        headers = {'Authorization': auth, 'Content-Type': 'application/xml', 'Accept': 'application/xml'}
        request = urllib.request.Request(base + path, data=body, method=method, headers=headers)
        try:
            with opener.open(request, timeout=20) as response:
                return response.read(1024 * 1024)
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError('GeoServer HTTP ' + str(error.code)) from None

    assert ET.fromstring(call('/rest/security/acl/catalog.xml')).findtext('mode') == 'HIDE'
    assert call('/rest/workspaces/ingest_private.xml', missing=True) is None
    original_rules = ET.fromstring(call('/rest/security/acl/layers.xml'))
    original = {node.attrib.get('resource'): node.text for node in original_rules}
    assert not any(name and name.startswith('ingest_private.') for name in original)
    users = ET.fromstring(call('/rest/security/usergroup/users'))
    names = {node.attrib.get('userName') or node.findtext('userName') for node in users.iter()}
    assert reader not in names and publisher not in names
    role_names = {node.text for node in ET.fromstring(call('/rest/security/roles')).iter() if node.tag == 'role'}
    assert 'ADMIN' in role_names and 'ROLE_INGEST_PROXY' not in role_names
    call('/rest/security/roles/role/ROLE_INGEST_PROXY', 'POST', b'')
    for name, password, role in ((reader, os.environ['GEOSERVER_INGESTION_READ_PASSWORD'], 'ROLE_INGEST_PROXY'),
                                  (publisher, os.environ['GEOSERVER_INGESTION_PUBLISH_PASSWORD'], 'ADMIN')):
        assert len(password) >= 32
        user = ET.Element('user')
        ET.SubElement(user, 'userName').text = name
        ET.SubElement(user, 'password').text = password
        ET.SubElement(user, 'enabled').text = 'true'
        call('/rest/security/usergroup/users', 'POST', ET.tostring(user))
        call('/rest/security/roles/role/' + role + '/user/' + name, 'POST', b'')
    additions = ET.Element('rules')
    ET.SubElement(additions, 'rule', resource='ingest_private.*.r').text = 'ROLE_INGEST_PROXY'
    ET.SubElement(additions, 'rule', resource='ingest_private.*.w').text = 'ROLE_ADMINISTRATOR'
    call('/rest/security/acl/layers.xml', 'POST', ET.tostring(additions))
    updated = {node.attrib.get('resource'): node.text for node in ET.fromstring(call('/rest/security/acl/layers.xml'))}
    assert all(updated.get(name) == value for name, value in original.items())
    reader_auth = 'Basic ' + base64.b64encode((reader + ':' + os.environ['GEOSERVER_INGESTION_READ_PASSWORD']).encode()).decode()
    request = urllib.request.Request(base + '/rest/workspaces.json', headers={'Authorization': reader_auth})
    try:
        opener.open(request, timeout=10)
    except urllib.error.HTTPError as error:
        assert error.code in (401, 403)
    else:
        raise RuntimeError('Read identity unexpectedly has management access')
    print(json.dumps({'privateAclProvisioned': True, 'catalogMode': 'HIDE',
                      'legacyRulesPreserved': True, 'readManagementAccess': 'denied', 'credentialsPrinted': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('SAFE map provisioning failed: ' + type(error).__name__) from None
