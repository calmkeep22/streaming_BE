"""Operator-only runtime preparation. Never return passwords or signing keys."""
import ast
import base64
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import tempfile

ROOT = Path('/opt/streaming')


def ensure_tls(root, host):
    directory = root / 'shared/media-auth-tls'
    directory.mkdir(mode=0o700, exist_ok=True)
    certificate, key = directory / 'tls.crt', directory / 'tls.key'
    if not certificate.exists() or not key.exists():
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            generated = Path(temporary)
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-sha256', '-days', '365',
                            '-subj', '/CN=streaming-media-auth', '-addext', 'subjectAltName=IP:' + host,
                            '-keyout', str(generated / 'tls.key'), '-out', str(generated / 'tls.crt')],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            shutil.copyfile(generated / 'tls.key', key)
            shutil.copyfile(generated / 'tls.crt', certificate)
    key.chmod(0o600)
    certificate.chmod(0o644)
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()


def read_values(path):
    values = {}
    for line in path.read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            values[key] = ast.literal_eval(value) if value.startswith(("'", '"')) else value
    return values


def private_ip(value):
    address = ipaddress.IPv4Address(value)
    if not any(address in ipaddress.ip_network(network) for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')):
        raise ValueError('A private backend IP is required')
    return str(address)


def prepare(component, payload, root=ROOT, source=None):
    source = source or Path(__file__).resolve().parents[1]
    host = private_ip(payload['backend_private_ip'])
    env_file = root / 'shared' / (component + '.env')
    values = read_values(env_file)
    if component == 'backend':
        for name in ('publisher', 'monitor'):
            if not re.fullmatch('[a-f0-9]{64}', payload[name + '_hash']):
                raise ValueError('Invalid credential hash')
        signing_key = values.get('MEDIA_TOKEN_SECRET') or base64.b64encode(secrets.token_bytes(32)).decode()
        key = base64.b64decode(signing_key, validate=True)
        if len(key) < 32 or key == base64.b64decode(values['JWT_SECRET']):
            raise ValueError('Invalid or reused media signing key')
        additions = {'MEDIA_ENABLED': 'true', 'MEDIA_TOKEN_SECRET': signing_key, 'MEDIA_TICKET_TTL': 'PT2M',
                     'MEDIA_PUBLISHER_PASSWORD_HASH': payload['publisher_hash'],
                     'MEDIA_MONITOR_PASSWORD_HASH': payload['monitor_hash'], 'MEDIA_CALLBACK_BIND_IP': host}
        fingerprint = ensure_tls(root, host)
    elif component == 'media':
        fingerprint = payload['fingerprint']
        if not re.fullmatch('[a-f0-9]{64}', fingerprint): raise ValueError('Invalid certificate fingerprint')
        additions = {'MEDIA_AUTH_URL': 'https://' + host + ':8082/internal/media/auth', 'MEDIA_AUTH_FINGERPRINT': fingerprint}
    else:
        raise ValueError('Unknown component')
    lines = [line for line in env_file.read_text().splitlines() if line.split('=', 1)[0] not in additions]
    lines.extend(name + "='" + value + "'" for name, value in additions.items())
    backup = env_file.with_suffix('.env.before-media-auth')
    if not backup.exists():
        shutil.copyfile(env_file, backup)
        backup.chmod(0o600)
    descriptor, temporary = tempfile.mkstemp(dir=env_file.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write('\n'.join(lines) + '\n')
        os.chmod(temporary, 0o600)
        os.replace(temporary, env_file)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    stack = root / 'production' / component
    files = ['compose.yml', 'nginx.conf'] if component == 'backend' else ['compose.yml']
    for name in files:
        target = stack / name
        old = stack / (name + '.before-media-auth')
        if not old.exists(): shutil.copyfile(target, old)
        shutil.copyfile(source / component / name, target)
        target.chmod(0o644)
    return fingerprint
    # Keep the running MediaMTX YAML until deploy-media follows a successful backend deployment.


def main():
    os.umask(0o077)
    if os.geteuid() != 0 or len(sys.argv) != 2: raise ValueError('Operator access required')
    component = sys.argv[1]
    if component == 'export-hashes':
        values = read_values(ROOT / 'shared/media.env')
        print(json.dumps({name + '_hash': hashlib.sha256(values[key].encode()).hexdigest() for name, key in
                          [('publisher', 'MEDIAMTX_PUBLISH_PASSWORD'), ('monitor', 'MEDIAMTX_MONITOR_PASSWORD')]}))
        return
    if component not in ('backend', 'media'): raise ValueError('Unknown component')
    payload = json.loads(sys.stdin.buffer.read(8192))
    with (ROOT / 'secure-deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        fingerprint = prepare(component, payload)
    print(json.dumps({'component': component, 'fingerprint': fingerprint}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Media runtime setup failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
