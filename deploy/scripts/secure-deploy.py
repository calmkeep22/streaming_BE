#!/usr/bin/python3 -I
"""Root-owned entry point for the restricted CI SSH key. Never executes uploaded scripts."""
import base64
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path('/opt/streaming')


def validate(component, request):
    if component not in ('backend', 'media') or not isinstance(request, dict):
        raise ValueError('Invalid deployment request')
    allowed = {'image', 'registry_user', 'registry_token'} if component == 'backend' else {'image', 'config'}
    if set(request) != allowed or not all(isinstance(value, str) for value in request.values()):
        raise ValueError('Unexpected deployment fields')
    if component == 'backend':
        if not re.fullmatch(r'ghcr\.io/calmkeep22/streaming_be@sha256:[a-f0-9]{64}', request['image']):
            raise ValueError('Only this repository image pinned by digest is allowed')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}(?:\[bot\])?', request['registry_user']):
            raise ValueError('Invalid registry user')
        if not 1 <= len(request['registry_token']) <= 10000:
            raise ValueError('Missing registry token')
    else:
        if not re.fullmatch(r'bluenviron/mediamtx:[0-9]+\.[0-9]+\.[0-9]+', request['image']):
            raise ValueError('Only versioned official MediaMTX images are allowed')
        if not 1 <= len(request['config']) <= 32768 or '\0' in request['config']:
            raise ValueError('Invalid media configuration')


def atomic_write(path, contents):
    descriptor, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(contents)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def health(component, env_file):
    headers = {}
    url = 'http://127.0.0.1:8081/actuator/health/readiness'
    if component == 'media':
        values = dict(line.split('=', 1) for line in env_file.read_text().splitlines() if '=' in line)
        password = values['MEDIAMTX_MONITOR_PASSWORD'].strip("'\"")
        credentials = base64.b64encode(('monitor:' + password).encode()).decode()
        headers['Authorization'] = 'Basic ' + credentials
        url = 'http://127.0.0.1:9997/v3/paths/list'
    for _ in range(20):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5) as response:
                if response.status == 200:
                    return True
        except (OSError, ValueError):
            pass
        time.sleep(3)
    return False


def deploy(component, request, root=ROOT, runner=subprocess.run, check_health=health):
    validate(component, request)
    stack = root / 'production' / component
    env_file = root / 'shared' / (component + '.env')
    if not env_file.is_file():
        raise RuntimeError('Server environment must be configured by the administrator first')
    image_file = stack / 'image.env'
    config_file = stack / 'mediamtx.yml'
    good_image_file = stack / 'last-good-image.env'
    good_config_file = stack / 'last-good-mediamtx.yml'
    previous_image = good_image_file.read_text() if good_image_file.exists() else None
    previous_config = (good_config_file if good_config_file.exists() else config_file).read_text() if component == 'media' else None
    image_key = 'APP_IMAGE' if component == 'backend' else 'MEDIAMTX_IMAGE'

    with tempfile.TemporaryDirectory(prefix='streaming-registry-') as auth_dir:
        env = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
               'HOME': '/root', 'DOCKER_CONFIG': auth_dir}
        if component == 'backend':
            runner(['docker', 'login', 'ghcr.io', '-u', request['registry_user'], '--password-stdin'],
                   input=request['registry_token'], text=True, check=True, env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        def compose(*arguments):
            runner(['docker', 'compose', '--project-name', 'streaming-' + component,
                    '--env-file', str(env_file), '--env-file', str(image_file),
                    '-f', str(stack / 'compose.yml'), *arguments], check=True, env=env)

        def start():
            flags = ['--force-recreate'] if component == 'media' else []
            compose('up', '-d', *flags, '--wait', '--wait-timeout', '240')

        atomic_write(image_file, image_key + '=' + request['image'] + '\n')
        if component == 'media':
            atomic_write(config_file, request['config'])
            config_file.chmod(0o644)
        try:
            compose('config', '--quiet')
            compose('pull', '--quiet')
            start()
            if not check_health(component, env_file):
                raise RuntimeError('Application health check failed')
        except Exception:
            if previous_image is not None:
                atomic_write(image_file, previous_image)
            else:
                image_file.unlink(missing_ok=True)
            if component == 'media':
                atomic_write(config_file, previous_config)
                config_file.chmod(0o644)
            if previous_image is not None:
                try:
                    start()
                    if not check_health(component, env_file):
                        raise RuntimeError('Rollback health check failed')
                    print('Previous image/configuration restored', file=sys.stderr)
                except Exception:
                    print('ROLLBACK FAILED: administrator intervention required', file=sys.stderr)
            else:
                # The failed image is needed to parse Compose while stopping it.
                atomic_write(image_file, image_key + '=' + request['image'] + '\n')
                try:
                    compose('stop', *(['app', 'nginx'] if component == 'backend' else ['mediamtx']))
                finally:
                    image_file.unlink(missing_ok=True)
            raise
        atomic_write(good_image_file, image_file.read_text())
        if component == 'media':
            atomic_write(good_config_file, request['config'])
    print('Deployment succeeded: ' + component)


def main():
    os.umask(0o077)
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise RuntimeError('Use the installed restricted deployment entry point')
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError('Request too large')
    request = json.loads(raw)
    validate(sys.argv[1], request)
    with (ROOT / 'secure-deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        deploy(sys.argv[1], request)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not print requests, tokens, subprocess environments, or tracebacks.
        print('Deployment rejected or failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
