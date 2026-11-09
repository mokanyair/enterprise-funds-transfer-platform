#!/usr/bin/env bash
set -euo pipefail

ROOT="${WORKSPACE:-$PWD}"
BIN_DIR="$ROOT/.ci-tools/bin"
VERSION="2.3.8"

mkdir -p "$BIN_DIR"

python3 - "$VERSION" "$BIN_DIR" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.request

version, destination = sys.argv[1:]
api = f"https://api.github.com/repos/google/osv-scanner/releases/tags/v{version}"

def request(url):
    return urllib.request.Request(
        url, headers={"User-Agent": "banking-app-ci-sca"}
    )

with urllib.request.urlopen(request(api), timeout=30) as response:
    release = json.load(response)

assets = [
    a for a in release["assets"]
    if re.search(r"linux_amd64$", a["name"])
    and a["name"].startswith("osv-scanner_")
]

if len(assets) != 1:
    raise SystemExit("Expected exactly one Linux AMD64 OSV binary")

asset = assets[0]
digest = asset.get("digest", "")

if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
    raise SystemExit("Release asset has no valid SHA-256 digest")

with urllib.request.urlopen(
    request(asset["browser_download_url"]), timeout=180
) as response:
    binary = response.read()

actual = hashlib.sha256(binary).hexdigest()
if actual != digest.split(":", 1)[1]:
    raise SystemExit("OSV binary checksum verification failed")

path = pathlib.Path(destination) / "osv-scanner"
path.write_bytes(binary)
path.chmod(0o755)

print(f"Installed verified OSV-Scanner v{version}")
PY

"$BIN_DIR/osv-scanner" --version
