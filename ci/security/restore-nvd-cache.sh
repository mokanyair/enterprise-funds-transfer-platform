
#!/usr/bin/env bash
set -Eeuo pipefail

BUCKET="eftabankingnvddatavdc"
PREFIX="jenkins-cache/dependency-check/v13"
REGION="us-east-1"
VERSION="13.0.0"

ROOT="${WORKSPACE:-$PWD}"
DATA_DIR="$ROOT/.ci-tools/dependency-check-data"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for cmd in aws tar sha256sum python3; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "ERROR: Missing command: $cmd" >&2
        exit 1
    }
done

echo "=== Restore NVD Database from S3 ==="

aws s3 cp \
    "s3://$BUCKET/$PREFIX/latest.json" \
    "$TMP/latest.json" \
    --region "$REGION" \
    --only-show-errors

PARSED=$(python3 - "$TMP/latest.json" "$VERSION" "$PREFIX" <<'PY'
import json
import re
import sys

path, version, prefix = sys.argv[1:]

with open(path, encoding="utf-8") as file:
    manifest = json.load(file)

key = manifest.get("key", "")
digest = manifest.get("sha256", "")

pattern = (
    re.escape(prefix)
    + r"/releases/nvd-cache-"
    + re.escape(version)
    + r"-[0-9]{8}T[0-9]{6}Z\.tar\.gz"
)

if manifest.get("dependency_check_version") != version:
    raise SystemExit("ERROR: Incompatible database version")

if not re.fullmatch(pattern, key):
    raise SystemExit("ERROR: Invalid archive key")

if not re.fullmatch(r"[a-f0-9]{64}", digest):
    raise SystemExit("ERROR: Invalid checksum")

print(key, digest)
PY
)

read -r KEY EXPECTED_SHA <<< "$PARSED"

echo "Downloading verified NVD release."

aws s3 cp \
    "s3://$BUCKET/$KEY" \
    "$TMP/archive.tar.gz" \
    --region "$REGION" \
    --only-show-errors

ACTUAL_SHA="$(sha256sum "$TMP/archive.tar.gz" | awk '{print $1}')"

if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
    echo "ERROR: NVD archive checksum mismatch." >&2
    exit 1
fi

echo "=== Validate Archive Structure ==="

python3 - "$TMP/archive.tar.gz" <<'PY'
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as tar:
    members = tar.getmembers()

    if not members:
        raise SystemExit("ERROR: Empty archive")

    for member in members:
        name = member.name
        if (
            name.startswith("/")
            or ".." in name.split("/")
            or not (name == "data" or name.startswith("data/"))
            or not (member.isfile() or member.isdir())
        ):
            raise SystemExit("ERROR: Unsafe archive member: " + name)

print("PASS: Archive structure validated")
PY

echo "=== Extract NVD Database ==="

tar -xzf "$TMP/archive.tar.gz" \
    -C "$TMP" \
    --no-same-owner \
    --no-same-permissions

if ! find "$TMP/data" -type f -name '*.mv.db' \
    -size +0c -print -quit | grep -q .; then
    echo "ERROR: H2 database missing from archive." >&2
    exit 1
fi

mkdir -p "$(dirname "$DATA_DIR")"

# Replace only the Dependency-Check cache directory.
rm -rf "$DATA_DIR"
mv "$TMP/data" "$DATA_DIR"

echo "=== NVD Cache Restored Successfully ==="
echo "Database directory: $DATA_DIR"
du -sh "$DATA_DIR"
