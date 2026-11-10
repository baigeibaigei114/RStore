#!/bin/bash
set -euo pipefail

release=${1:?release tag is required}
expected_sha=${2:?archive SHA256 is required}
case "$release" in ''|*[!a-zA-Z0-9._-]*) echo 'Invalid release tag' >&2; exit 1;; esac
case "$expected_sha" in *[!a-fA-F0-9]*|'') echo 'Invalid SHA256' >&2; exit 1;; esac
test ${#expected_sha} -eq 64

base=/opt/ndvi-demo
test -f "$base/.codex-deployment"
if test -f "$base/features.env"; then
    echo 'Feature deployment detected; use /opt/ndvi-demo/demo-compose.sh for maintenance or upgrades.' >&2
    exit 1
fi
archive="$base/backend-$release.tar.gz"
release_root="$base/releases/$release"
test ! -e "$release_root"
test ! -L "$base/releases"
python3 - "$archive" "$expected_sha" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1])
with path.open('rb') as stream:
    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
if actual.lower() != sys.argv[2].lower():
    raise SystemExit('Release archive checksum mismatch')
print('RELEASE_CHECKSUM_VERIFIED')
PY

mkdir -p "$release_root" "$base/shared-raster" "$base/logs"
tar --no-same-owner --no-same-permissions -xzf "$archive" -C "$release_root"
find "$release_root" -type d -exec chmod 755 {} +
find "$release_root" -type f -exec chmod 644 {} +
chmod 755 "$release_root/postgres-init/90-demo-user.sh"
chmod 755 "$base/shared-raster"

python3 - "$base" <<'PY'
import json, os, pathlib, secrets, sys
base = pathlib.Path(sys.argv[1])
env_path = base / 'secrets.env'
names = ('POSTGRES_PASSWORD', 'APP_DB_PASSWORD', 'RABBITMQ_PASSWORD', 'MINIO_PASSWORD',
         'GEOSERVER_PASSWORD', 'JWT_SECRET', 'WORKER_TOKEN', 'DEMO_PASSWORD')
if env_path.exists():
    values = dict(line.split('=', 1) for line in env_path.read_text().splitlines()
                  if line and not line.startswith('#'))
    if not all(values.get(name) for name in names):
        raise SystemExit('Existing deployment secrets are incomplete; do not replace them automatically')
else:
    values = {name: secrets.token_urlsafe(36) for name in names}
    with os.fdopen(os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        stream.write(''.join(f'{name}={values[name]}\n' for name in names))
os.chmod(env_path, 0o600)
access_path = base / 'demo-access.json'
if not access_path.exists():
    with os.fdopen(os.open(access_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        json.dump({'url': 'https://demo.example.com', 'username': 'demo',
                   'password': values['DEMO_PASSWORD']}, stream)
print('DEPLOYMENT_SECRETS_READY_VALUES_NOT_PRINTED')
PY

compose() {
    sudo -n env RELEASE_TAG="$release" docker compose --env-file "$base/secrets.env" \
        -f "$release_root/compose.yml" "$@"
}

compose config --quiet
compose pull --ignore-buildable > "$base/logs/$release-pull.log" 2>&1
compose build backend > "$base/logs/$release-backend-build.log" 2>&1
compose build worker > "$base/logs/$release-worker-build.log" 2>&1
compose up -d --no-build --wait --wait-timeout 300 > "$base/logs/$release-start.log" 2>&1
python3 - "$base" "$release_root" <<'PY'
import os, pathlib, sys
base, release_root = map(pathlib.Path, sys.argv[1:])
current = base / 'current'
if current.exists() or current.is_symlink():
    if not current.is_symlink() or current.resolve().parent != (base / 'releases').resolve():
        raise SystemExit('Unexpected current release path; refusing to replace it')
temporary = base / ('current.next-' + str(os.getpid()))
temporary.symlink_to(release_root)
os.replace(temporary, current)
PY
compose ps
sudo -n docker stats --no-stream --format '{{.Name}} | {{.MemUsage}}'
printf 'PRIVATE_BACKEND_BOOTSTRAP_COMPLETE\n'
