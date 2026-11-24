#!/usr/bin/env bash
set -euo pipefail

# Run as opc. Re-running this script does not reset application data or secrets.
if ! command -v docker >/dev/null 2>&1; then
  sudo dnf install -y dnf-plugins-core
  sudo dnf config-manager --add-repo https://download.docker.com/linux/oracle/docker-ce.repo
  sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
sudo systemctl enable --now docker
sudo usermod -aG docker "$(id -un)"
sudo install -d -m 0750 -o "$(id -un)" -g "$(id -gn)" /opt/streaming
install -d -m 0700 /opt/streaming/shared
install -d -m 0750 /opt/streaming/releases
sudo docker version --format '{{.Server.Version}}'
sudo docker compose version
