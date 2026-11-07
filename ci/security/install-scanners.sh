#!/usr/bin/env bash
set -euo pipefail

ROOT="${WORKSPACE:-$PWD}"
TOOLS_DIR="$ROOT/.ci-tools"
VENV_DIR="$TOOLS_DIR/venv"
BIN_DIR="$TOOLS_DIR/bin"

GITLEAKS_VERSION="8.24.3"
ARCHIVE="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
CHECKSUM_FILE="$ROOT/ci/security/checksums/gitleaks.sha256"

mkdir -p "$BIN_DIR"

echo "=== Python 3.11 ==="
if ! command -v python3.11 >/dev/null 2>&1; then
    sudo dnf install -y python3.11 python3.11-pip
fi

python3.11 -m venv "$VENV_DIR"

echo "=== Install hash-locked dependencies ==="
"$VENV_DIR/bin/python" -m pip install \
    --require-hashes \
    --no-cache-dir \
    --quiet \
    -r "$ROOT/ci/security/requirements.lock"

echo "=== Download Gitleaks ==="
curl -fsSL --retry 3 \
    "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${ARCHIVE}" \
    -o "$TOOLS_DIR/$ARCHIVE"

echo "=== Verify Gitleaks SHA-256 ==="
(
    cd "$TOOLS_DIR"
    sha256sum -c "$CHECKSUM_FILE"
)

echo "=== Extract verified Gitleaks ==="
tar -xzf "$TOOLS_DIR/$ARCHIVE" -C "$BIN_DIR" gitleaks
chmod +x "$BIN_DIR/gitleaks"

echo "=== Validate scanner versions ==="
test "$("$VENV_DIR/bin/semgrep" --version)" = "1.183.0"
test "$("$VENV_DIR/bin/checkov" --version)" = "3.4.3"
"$BIN_DIR/gitleaks" version | grep -Fq "$GITLEAKS_VERSION"

"$VENV_DIR/bin/python" -m pip check

echo "PASS: Security scanners installed and verified"
