"""Explicit one-time provisioning on a verified NEW isolated GeoServer, never at service startup.

Uses only standard library. Secrets come from the calling process environment; nothing logs bodies.
Existing rules/users are not overwritten. Existing installations require a separate reviewed migration.
"""
import argparse, base64, json, os, re, urllib.request, urllib.error
from xml.etree import ElementTree as ET

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--new-isolated-instance',action='store_true');args=parser.parse_args()
    if not args.new_isolated_instance: raise RuntimeError('New isolated instance assertion required')
    base=os.environ['GEOSERVER_URL'].rstrip('/')
    publisher=os.environ['GEOSERVER_INGESTION_PUBLISH_USERNAME'];password=os.environ['GEOSERVER_INGESTION_PUBLISH_PASSWORD']
    reader=os.environ['GEOSERVER_INGESTION_READ_USERNAME'];read_password=os.environ['GEOSERVER_INGESTION_READ_PASSWORD']
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',reader) or reader==publisher or len(read_password)<16: raise RuntimeError('Invalid isolated credentials')
    auth='Basic '+base64.b64encode((publisher+':'+password).encode()).decode()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): return None
    opener=urllib.request.build_opener(NoRedirect)
    def call(path,method='GET',body=None,type='application/xml',missing=False):
        req=urllib.request.Request(base+path,data=body,method=method,headers={'Authorization':auth,'Content-Type':type,'Accept':type})
        try:
            with opener.open(req,timeout=30) as r:return r.read(1024*1024)
        except urllib.error.HTTPError as error:
            if missing and error.code==404:return None
            raise RuntimeError('GeoServer provisioning refused with HTTP '+str(error.code)) from None
    if call('/rest/workspaces/ingest_private.json',type='application/json',missing=True) is not None: raise RuntimeError('Existing private workspace; do not provision')
    rules=ET.fromstring(call('/rest/security/acl/layers.xml'))
    if any(n.attrib.get('resource','').startswith('ingest_private.') for n in rules): raise RuntimeError('Existing private ACL; do not overwrite')
    if ET.fromstring(call('/rest/security/acl/catalog.xml')).findtext('mode')!='HIDE': raise RuntimeError('Existing non-HIDE catalog requires reviewed migration')
    users=ET.fromstring(call('/rest/security/usergroup/users'))
    if any(n.attrib.get('userName')==reader or n.findtext('userName')==reader for n in users.iter()): raise RuntimeError('Reader already exists; do not overwrite')
    roles=ET.fromstring(call('/rest/security/roles'))
    if any(n.text=='ROLE_INGEST_PROXY' for n in roles.iter()): raise RuntimeError('Role already exists; do not overwrite')
    call('/rest/security/roles/role/ROLE_INGEST_PROXY','POST',b'')
    # XML serializer escapes the password; the request and response bodies are never printed.
    user=ET.Element('user');ET.SubElement(user,'userName').text=reader;ET.SubElement(user,'password').text=read_password;ET.SubElement(user,'enabled').text='true'
    call('/rest/security/usergroup/users','POST',ET.tostring(user))
    call('/rest/security/roles/role/ROLE_INGEST_PROXY/user/'+reader,'POST',b'')
    additions=ET.Element('rules')
    ET.SubElement(additions,'rule',resource='ingest_private.*.r').text='ROLE_INGEST_PROXY'
    ET.SubElement(additions,'rule',resource='ingest_private.*.w').text='ROLE_ADMINISTRATOR'
    call('/rest/security/acl/layers.xml','POST',ET.tostring(additions))
    print(json.dumps({'privateAclProvisioned':True,'catalogMode':'HIDE','credentialsPrinted':False}))

if __name__=='__main__':
    try:main()
    except Exception as error:
        # urllib failures can include connection details; report only the category.
        print(json.dumps({'provisioned':False,'errorType':type(error).__name__}));raise SystemExit(1)
