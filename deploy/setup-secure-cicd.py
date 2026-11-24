"""Operator-only setup. Keep administrator SSH keys out of GitHub Actions."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repository', default='calmkeep22/streaming_BE')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
download = Path.home() / 'Downloads'
import tempfile

hosts = [
    ('backend', '129.225.185.153', download / 'ssh-key-2026-11-24 (1).key', 'streaming-oci-new-known-hosts'),
    ('media', '138.2.62.8', download / 'ssh-key-2026-11-24 (5).key', 'streaming-oci-media-known-hosts'),
]

for component, host, key, known_name in hosts:
    known = Path(tempfile.gettempdir()) / known_name
    options = ['-i', str(key), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
               '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
               '-o', 'UserKnownHostsFile=' + str(known)]
    remote = 'opc@' + host
    stage = '/opt/streaming/secure-ci-setup'
    subprocess.run(['ssh', *options, remote, 'mkdir -p ' + stage], check=True)
    subprocess.run(['scp', *options, '-r', str(repo / 'deploy'), remote + ':' + stage + '/'], check=True)
    script = '''set -euo pipefail
umask 077
key_dir=$(mktemp -d)
trap 'rm -f -- "$key_dir/key" "$key_dir/key.pub" "$key_dir/known_hosts" "$key_dir/check.out" "$key_dir/check.err"; rmdir -- "$key_dir"' EXIT
ssh-keygen -q -t ed25519 -N '' -C streaming-cicd-COMPONENT -f "$key_dir/key"
sudo -n bash STAGE/deploy/scripts/install-secure-deploy.sh COMPONENT "$key_dir/key.pub" >/dev/null
awk '{ print "127.0.0.1 " $1 " " $2 }' /etc/ssh/ssh_host_ed25519_key.pub > "$key_dir/known_hosts"
set +e
printf '{}' | ssh -T -i "$key_dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$key_dir/known_hosts" streaming-deploy@127.0.0.1 'echo UNRESTRICTED_SHELL' > "$key_dir/check.out" 2> "$key_dir/check.err"
result=$?
set -e
if [ "$result" -ne 1 ] || ! grep -q 'Deployment rejected or failed' "$key_dir/check.err" || grep -q UNRESTRICTED_SHELL "$key_dir/check.out"; then
  echo 'Restricted login verification failed' >&2
  cat "$key_dir/check.err" >&2
  exit 1
fi
if sudo -u streaming-deploy sudo -n /bin/true >/dev/null 2>&1; then
  echo 'Unexpected unrestricted sudo permission' >&2
  exit 1
fi
if id -nG streaming-deploy | grep -Eq '(^| )(docker|wheel)( |$)'; then
  echo 'Unexpected privileged group membership' >&2
  exit 1
fi
cat "$key_dir/key"
'''.replace('COMPONENT', component).replace('STAGE', stage)
    result = subprocess.run(['ssh', *options, remote, "tr -d '\\r' | bash -s"], input=script,
                            text=True, capture_output=True, check=False)
    if result.returncode:
        # Setup scripts never emit private-key contents on stderr.
        raise SystemExit(component + ' setup failed: ' + result.stderr)
    private_key = result.stdout.strip()
    if not private_key.startswith('-----BEGIN OPENSSH PRIVATE KEY-----') or not private_key.endswith('-----END OPENSSH PRIVATE KEY-----'):
        raise SystemExit(component + ': invalid key response; value was not displayed')
    values = {'SSH_KEY': private_key, 'HOST': host, 'KNOWN_HOSTS': known.read_text()}
    for suffix, value in values.items():
        subprocess.run(['gh', 'secret', 'set', component.upper() + '_' + suffix,
                        '--repo', args.repository], input=value, text=True, check=True)
    values.clear()
    private_key = None
    result = None
    print(component + ': restricted login verified; GitHub key replaced', flush=True)
