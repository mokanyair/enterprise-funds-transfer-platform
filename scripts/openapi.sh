#!/usr/bin/env bash
# Lint and bundle the modular OpenAPI specs.
#   scripts/openapi.sh lint     lint the root spec and every domain spec
#   scripts/openapi.sh bundle   write one flattened spec to target/openapi/
#   scripts/openapi.sh          lint, then bundle
# The domain files under openapi/ are the source of truth; the bundle is generated
# output (under target/, git-ignored) for tools that want a single file.
set -euo pipefail

REDOCLY="@redocly/cli@2"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC_DIR="$ROOT/application/funds-transfer-service/src/main/resources/openapi"
OUT_DIR="$ROOT/application/funds-transfer-service/target/openapi"
SPECS=(
  common/common-v1.yaml
  identity/identity-v1.yaml
  transfers/transfers-v1.yaml
  accounts/accounts-v1.yaml
  funds-transfer-v1.yaml
)

lint() {
  cd "$SPEC_DIR"
  local failed=0
  for f in "${SPECS[@]}"; do
    echo "== lint $f"
    npx --yes "$REDOCLY" lint "$f" || failed=1
  done
  return $failed
}

bundle() {
  cd "$SPEC_DIR"
  mkdir -p "$OUT_DIR"
  npx --yes "$REDOCLY" bundle funds-transfer-v1.yaml -o "$OUT_DIR/funds-transfer-v1.bundled.yaml"
}

case "${1:-all}" in
  lint)   lint ;;
  bundle) bundle ;;
  all)    lint && bundle ;;
  *)      echo "usage: $0 [lint|bundle]" >&2; exit 2 ;;
esac
