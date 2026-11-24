#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
version=1.7.12
archive="actionlint_${version}_linux_amd64.tar.gz"
base="https://github.com/rhysd/actionlint/releases/download/v${version}"
directory=$(mktemp -d)
trap 'rm -rf -- "$directory"' EXIT
curl -fsSL "$base/$archive" -o "$directory/$archive"
curl -fsSL "$base/actionlint_${version}_checksums.txt" -o "$directory/checksums.txt"
(cd "$directory"; grep " ${archive}$" checksums.txt | sha256sum --check --status -)
tar -xzf "$directory/$archive" -C "$directory" actionlint
"$directory/actionlint" -oneline -shellcheck= -pyflakes= .github/workflows/*.yml
echo 'GitHub Actions validation passed'
