#!/bin/bash
set -euo pipefail
base=/opt/ndvi-demo
test -f "$base/.codex-deployment"
release=$(python3 -c 'import json; print(json.load(open("/opt/ndvi-demo/current/release.json"))["releaseTag"])')
case "$release" in ''|*[!a-zA-Z0-9._-]*) echo 'Invalid release tag' >&2; exit 1;; esac
exec sudo -n env RELEASE_TAG="$release" docker compose \
  --env-file "$base/secrets.env" --env-file "$base/features.env" \
  -f "$base/current/compose.yml" \
  -f "$base/features-20261109-ai-safe1/compose.features.yml" "$@"
