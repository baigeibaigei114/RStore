"""Install an explicitly approved tunnel token from stdin without logging its value."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--account-id', required=True)
    parser.add_argument('--tunnel-id', required=True)
    args = parser.parse_args()
    base = Path('/opt/ndvi-demo')
    if base.is_symlink() or not (base / '.codex-deployment').is_file():
        raise SystemExit('Unexpected deployment directory')
    token = sys.stdin.buffer.read(8193).strip()
    if len(token) > 8192 or not re.fullmatch(rb'[A-Za-z0-9+/_=-]+', token):
        raise SystemExit('Invalid tunnel token encoding')
    try:
        payload = json.loads(base64.b64decode(token, validate=True))
    except (ValueError, UnicodeError):
        raise SystemExit('Invalid tunnel token payload') from None
    if payload.get('a') != args.account_id or payload.get('t') != args.tunnel_id:
        raise SystemExit('Tunnel token identity mismatch')
    path = base / 'tunnel-token'
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
    with os.fdopen(descriptor, 'wb') as stream:
        os.fchown(stream.fileno(), 65532, 65532)
        stream.write(token)
    print('TUNNEL_TOKEN_INSTALLED_VALUES_NOT_PRINTED')


if __name__ == '__main__':
    main()
