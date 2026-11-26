#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Banking APP consolidated deployment regression runner.
#
# Phase 1: Validate Bash syntax.
# Phase 2: Validate Python syntax.
# Phase 3: Execute all five regression suites.
# Phase 4: Report consolidated validation results.
# ============================================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "PHASE 1: Validate Bash syntax"

find "$ROOT" -type f -name '*.sh' -print0 |
    xargs -0 -n 1 bash -n

echo "PASS"

echo
echo "PHASE 2: Validate Python syntax"

python3 - "$ROOT/lib" <<'PY'
import sys
from pathlib import Path

for path in sorted(Path(sys.argv[1]).glob("*.py")):
    compile(path.read_text(), str(path), "exec")
    print(f"PASS: {path.name}")
PY

echo
echo "PHASE 3: Execute deployment regression suites"

for script in \
    test-validation.sh \
    test-release-state.sh \
    test-deployment.sh \
    test-orchestration.sh \
    test-ssm-wrapper.sh
do
    echo
    echo "========================================"
    echo "Running: $script"
    echo "========================================"

    bash "$ROOT/tests/$script"
done

echo
echo "PHASE 4: Consolidated validation result"
echo "ALL DEPLOYMENT REGRESSION SUITES PASSED"
