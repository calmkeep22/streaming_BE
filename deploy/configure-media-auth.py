"""Prepare reviewed server runtime files using operator SSH keys, without changing CI keys."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true', required=True)
    parser.add_argument('--backend-private-ip', default='10.0.0.217')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    hosts = [('media', '138.2.62.8', '(5)', 'streaming-oci-media-known-hosts'),
             ('backend', '129.225.185.153', '(1)', 'streaming-oci-new-known-hosts')]
    connections = {}
    stage = '/opt/streaming/secure-ci-setup/deploy'
    for component, host, suffix, known in hosts:
        key = Path.home() / 'Downloads' / ('ssh-key-2026-11-24 ' + suffix + '.key')
        options = ['-i', str(key), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
                   '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
                   '-o', 'UserKnownHostsFile=' + str(Path(tempfile.gettempdir()) / known)]
        remote = 'opc@' + host
        connections[component] = (options, remote)
        files = ['scripts/configure-media-runtime.py', component + '/compose.yml']
        if component == 'backend': files.append('backend/nginx.conf')
        for file in files:
            subprocess.run(['scp', *options, str(repo / 'deploy' / file), remote + ':' + stage + '/' + file],
                           check=True, capture_output=True)
    options, remote = connections['media']
    hashes = subprocess.run(['ssh', *options, remote, 'sudo -n python3 -I ' + stage
                             + '/scripts/configure-media-runtime.py export-hashes'],
                            check=True, capture_output=True).stdout
    payload = json.loads(hashes)
    payload['backend_private_ip'] = args.backend_private_ip
    for component in ('backend', 'media'):
        options, remote = connections[component]
        result = subprocess.run(['ssh', *options, remote, 'sudo -n python3 -I ' + stage
                                 + '/scripts/configure-media-runtime.py ' + component],
                                input=json.dumps(payload).encode(), check=True, capture_output=True)
        if component == 'backend': payload['fingerprint'] = json.loads(result.stdout)['fingerprint']
        print(component + ': reviewed runtime prepared', flush=True)
    print('Next: allow TCP 8082 from media private IP 10.0.0.150/32 to backend; deploy backend before media')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not dump captured output or input containing credentials/hashes.
        raise SystemExit('Media authentication preparation failed: ' + type(error).__name__)
