#!/usr/bin/env bash
set -euo pipefail
umask 077
public_ip=${1:?Usage: initialize-media-env.sh public-ip}
[[ "$public_ip" =~ ^[0-9.]+$ ]] || exit 2
file=/opt/streaming/shared/media.env
if [[ -e "$file" ]]; then echo 'Existing media environment preserved'; exit 0; fi
temporary=$(mktemp /opt/streaming/shared/media.env.XXXXXX)
trap 'rm -f -- "$temporary"' EXIT
{
  printf 'MEDIA_PUBLIC_IP=%s\n' "$public_ip"
  printf 'MEDIAMTX_PUBLISH_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'MEDIAMTX_READ_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'MEDIAMTX_MONITOR_PASSWORD=%s\n' "$(openssl rand -hex 32)"
} > "$temporary"
mv -n -- "$temporary" "$file"
echo 'Media environment ready; secret values were not printed'
