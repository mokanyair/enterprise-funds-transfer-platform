#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT/lib/deployment-common.sh"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT

COMMIT="6d13eef77a7cdb1a3e0586a9e3dcbc17619b0cec"
PREFIX="jenkins-artifacts/banking-app/test/releases/$COMMIT/build-4"

printf 'Test application artifact\n' > "$TEST_DIR/app.jar"

SHA="$(sha256_file "$TEST_DIR/app.jar")"

printf '%s  app.jar\n' "$SHA" \
    > "$TEST_DIR/app.jar.sha256"

python3 - "$TEST_DIR" "$COMMIT" "$SHA" <<'PY'
import json
import sys
from pathlib import Path

directory, commit, sha = sys.argv[1:]

Path(directory, "manifest.json").write_text(
    json.dumps({
        "commit": commit,
        "build_number": "4",
        "sha256": sha
    })
)
PY

echo "TEST 1: Valid artifact"

python3 "$ROOT/lib/validate-artifact.py" \
    "$TEST_DIR" "$PREFIX" >/dev/null

echo "PASS"

echo "TEST 2: Invalid commit"

if python3 "$ROOT/lib/validate-artifact.py" \
    "$TEST_DIR" \
    "jenkins-artifacts/banking-app/test/releases/0000000000000000000000000000000000000000/build-4" \
    >/dev/null 2>&1; then
    echo "FAIL: Invalid commit accepted"
    exit 1
fi

echo "PASS"

echo "TEST 3: Invalid build number"

if python3 "$ROOT/lib/validate-artifact.py" \
    "$TEST_DIR" "${PREFIX%/*}/build-99" \
    >/dev/null 2>&1; then
    echo "FAIL: Invalid build accepted"
    exit 1
fi

echo "PASS"

echo "TEST 4: Checksum validation"

verify_checksum "$TEST_DIR/app.jar" "$SHA"

echo "PASS"

echo "TEST 5: Corrupted artifact"

printf 'Corruption\n' >> "$TEST_DIR/app.jar"

if python3 "$ROOT/lib/validate-artifact.py" \
    "$TEST_DIR" "$PREFIX" >/dev/null 2>&1; then
    echo "FAIL: Corrupted artifact accepted"
    exit 1
fi

echo "PASS"

echo "TEST 6: Corrupted checksum rejection"

if verify_checksum "$TEST_DIR/app.jar" "$SHA"; then
    echo "FAIL: Invalid checksum accepted"
    exit 1
fi

echo "PASS"

echo "ALL VALIDATION TESTS PASSED"
