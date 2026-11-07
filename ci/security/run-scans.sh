#!/usr/bin/env bash
set -euo pipefail

ROOT="${WORKSPACE:-$PWD}"
TOOLS_DIR="$ROOT/.ci-tools"
REPORTS="$ROOT/security-reports"

mkdir -p "$REPORTS"

export PATH="$TOOLS_DIR/bin:$TOOLS_DIR/venv/bin:$PATH"

cd "$ROOT"

echo "=== Gitleaks: Git Repository ==="

gitleaks git . \
    --config ci/security/gitleaks.toml \
    --report-format json \
    --report-path "$REPORTS/gitleaks.json" \
    --exit-code 1

echo "=== Semgrep: Java and TypeScript ==="

semgrep scan \
    --config p/java \
    --config p/typescript \
    --config p/security-audit \
    --json \
    --output "$REPORTS/semgrep.json" \
    application/funds-transfer-service/src \
    application/banking-ui/src

echo "=== Checkov: Terraform ==="

checkov \
    --directory infrastructure/environments/dev \
    --framework terraform \
    --output json \
    --output-file-path "$REPORTS/checkov.json" \
    --soft-fail

echo "SECURITY SCANNING COMPLETE"
