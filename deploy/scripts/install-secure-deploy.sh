#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run with sudo' >&2; exit 2; }
component=${1:?backend or media}
key_file=${2:?CI public key file}
case "$component" in backend|media) ;; *) exit 2;; esac
public_key=$(tr -d '\r\n' < "$key_file")
[[ "$public_key" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+\ [A-Za-z0-9._-]+$ ]] || exit 2
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
stack="/opt/streaming/production/$component"
install -d -m 0750 -o root -g root /opt/streaming/production "$stack"
install -m 0644 -o root -g root "$source_root/deploy/$component/compose.yml" "$stack/compose.yml"
if [[ "$component" == backend ]]; then
  for file in nginx.conf redis-start.sh sentinel-start.sh; do
    install -m 0644 -o root -g root "$source_root/deploy/backend/$file" "$stack/$file"
  done
else
  # Do not replace an existing running media configuration during key rotation.
  if [[ ! -f "$stack/mediamtx.yml" ]]; then
    install -m 0644 -o root -g root "$source_root/deploy/media/mediamtx.yml" "$stack/mediamtx.yml"
  fi
fi
install -m 0755 -o root -g root "$source_root/deploy/scripts/secure-deploy.py" /usr/local/sbin/streaming-deploy
if ! id streaming-deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash streaming-deploy
fi
# No docker/wheel groups, shell session, SFTP, or port forwarding for the CI key.
usermod -G '' streaming-deploy
chown root:streaming-deploy /home/streaming-deploy
chmod 0750 /home/streaming-deploy
install -d -m 0750 -o root -g streaming-deploy /home/streaming-deploy/.ssh
printf 'restrict,command="sudo -n /usr/local/sbin/streaming-deploy %s" %s\n' "$component" "$public_key" > /home/streaming-deploy/.ssh/authorized_keys
chown root:streaming-deploy /home/streaming-deploy/.ssh/authorized_keys
chmod 0640 /home/streaming-deploy/.ssh/authorized_keys
temporary=$(mktemp)
trap 'rm -f -- "$temporary"' EXIT
printf 'streaming-deploy ALL=(root) NOPASSWD: /usr/local/sbin/streaming-deploy %s\n' "$component" > "$temporary"
visudo -cf "$temporary"
install -m 0440 -o root -g root "$temporary" /etc/sudoers.d/streaming-deploy
restorecon -RF /home/streaming-deploy/.ssh /usr/local/sbin/streaming-deploy /etc/sudoers.d/streaming-deploy
echo "Restricted CI entry point ready: $component"
