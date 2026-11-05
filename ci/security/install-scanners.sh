#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIR="${WORKSPACE:-$PWD}/.ci-tools"
BIN_DIR="$TOOLS_DIR/bin"
VENV_DIR="$TOOLS_DIR/venv"

SEMGREP_VERSION="1.183.0"
CHECKOV_VERSION="3.4.3"
GITLEAKS_VERSION="8.24.3"

mkdir -p "$BIN_DIR"
export PATH="$BIN_DIR:$PATH"

echo "=== Validate Python 3.11 ==="

if ! command -v python3.11 >/dev/null 2>&1; then
    echo "Python 3.11 missing. Installing..."
    sudo dnf install -y python3.11
fi

python3.11 --version

echo "=== Install Semgrep and Checkov ==="

python3.11 -m venv "$VENV_DIR"

"$VENV_DIR/bin/python" -m pip install \
    "semgrep==$SEMGREP_VERSION" \
    "checkov==$CHECKOV_VERSION"

echo "=== Install Gitleaks ==="

ARCHIVE="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${ARCHIVE}"

curl -fL --retry 3 "$URL" -o "$TOOLS_DIR/$ARCHIVE"

tar -xzf "$TOOLS_DIR/$ARCHIVE" \
    -C "$BIN_DIR" gitleaks

chmod +x "$BIN_DIR/gitleaks"

echo "=== Installed Versions ==="

"$VENV_DIR/bin/semgrep" --version
"$VENV_DIR/bin/checkov" --version
"$BIN_DIR/gitleaks" version

echo "SECURITY TOOL INSTALLATION COMPLETE"
