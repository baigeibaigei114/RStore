"""Create server-only feature credentials; the existing provider key arrives on stdin."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import quote

BASE = Path('/opt/ndvi-demo')
REVISION = '7999e1d3359715c523056ef9478215996d62a620'


def write_private(path, text):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        stream.write(text)


def env(values):
    return ''.join(f'{key}={value}\n' for key, value in values.items())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True, choices=('deepseek-v4-flash', 'deepseek-flash'))
    args = parser.parse_args()
    assert (BASE / '.codex-deployment').is_file() and not BASE.is_symlink()
    paths = [BASE / name for name in ('feature-admin.json', 'features.env', 'agent-runtime.env', 'agent-index.env')]
    if any(path.exists() for path in paths):
        raise SystemExit('Feature configuration already exists; inspect it instead of replacing credentials')
    key = sys.stdin.read(8193).strip()
    if not re.fullmatch(r'sk-[A-Za-z0-9_-]{16,8000}', key):
        raise SystemExit('Provider key missing or malformed')
    admin = {name: secrets.token_urlsafe(36) for name in ('readerPassword', 'indexerPassword', 'internalToken', 'mapReadPassword', 'mapPublishPassword')}
    feature = {'AGENT_INTERNAL_TOKEN': admin['internalToken'], 'DEEPSEEK_API_KEY': key, 'DEEPSEEK_MODEL': args.model,
               'GEOSERVER_INGESTION_READ_USERNAME': 'rs_ingest_reader', 'GEOSERVER_INGESTION_READ_PASSWORD': admin['mapReadPassword'],
               'GEOSERVER_INGESTION_PUBLISH_USERNAME': 'rs_ingest_publisher', 'GEOSERVER_INGESTION_PUBLISH_PASSWORD': admin['mapPublishPassword'],
               'INGESTION_PUBLICATION_ENABLED': 'false', 'INGESTION_MAP_BOUNDARY_VERIFIED': 'false'}
    model_path = '/models/' + REVISION
    runtime = {'AGENT_ENABLED': 'true', 'AGENT_INTERNAL_TOKEN': admin['internalToken'],
               'AGENT_DATABASE_URL': f"postgresql://rs_agent_reader:{quote(admin['readerPassword'], safe='')}@postgres:5432/rs_demo",
               'AGENT_MODEL_PATH': model_path, 'DEEPSEEK_API_KEY': key,
               'DEEPSEEK_MODEL': args.model, 'DEEPSEEK_BASE_URL': 'https://api.deepseek.com/v1'}
    index = {'AGENT_INDEX_DATABASE_URL': f"postgresql://rs_agent_indexer:{quote(admin['indexerPassword'], safe='')}@postgres:5432/rs_demo",
             'AGENT_MODEL_PATH': model_path, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
    write_private(paths[0], json.dumps(admin))
    write_private(paths[1], env(feature))
    write_private(paths[2], env(runtime))
    write_private(paths[3], env(index))
    print('FEATURE_SERVER_CONFIGURATION_READY_VALUES_NOT_PRINTED')


if __name__ == '__main__':
    main()
