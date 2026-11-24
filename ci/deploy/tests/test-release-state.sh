#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT

STATE_DIR="$TEST_DIR/state"

MANAGER="$ROOT/lib/release-state.py"

OLD_COMMIT="$(printf 'a%.0s' {1..40})"
NEW_COMMIT="$(printf 'b%.0s' {1..40})"

state() {
    python3 "$MANAGER" --state-dir "$STATE_DIR" "$@"
}

# Create simulated releases and their corresponding manifests.
make_release() {
    local directory="$1"
    local commit="$2"
    local build="$3"

    mkdir -p "$directory"

    printf 'Application release %s\n' "$build" \
        > "$directory/app.jar"

    python3 - "$directory" "$commit" "$build" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

directory, commit, build = sys.argv[1:]
directory = Path(directory)

sha = hashlib.sha256(
    (directory / "app.jar").read_bytes()
).hexdigest()

(directory / "manifest.json").write_text(json.dumps({
    "commit": commit,
    "build_number": build,
    "sha256": sha
}))
PY
}

check_state() {
    python3 - "$STATE_DIR/state.json" "$1" "$2" <<'PY'
import json
import sys

state = json.load(open(sys.argv[1]))
field, expected = sys.argv[2:]

if state[field]["build_number"] != expected:
    sys.exit("Unexpected release state")
PY
}

make_release "$TEST_DIR/old" "$OLD_COMMIT" 4
make_release "$TEST_DIR/new" "$NEW_COMMIT" 5

ACTIVE="$TEST_DIR/app.jar"
BACKUP="$TEST_DIR/backup.jar"

cp "$TEST_DIR/old/app.jar" "$ACTIVE"
cp "$ACTIVE" "$BACKUP"

echo "TEST 1: Initialize verified release baseline"

state init \
    --manifest "$TEST_DIR/old/manifest.json" \
    --jar "$TEST_DIR/old/app.jar" \
    --active-jar "$ACTIVE"

check_state current 4

echo "PASS"

echo "TEST 2: Reject duplicate initialization"

if state init \
    --manifest "$TEST_DIR/old/manifest.json" \
    --jar "$TEST_DIR/old/app.jar" \
    --active-jar "$ACTIVE" >/dev/null 2>&1; then
    exit 1
fi

echo "PASS"

echo "TEST 3: Persist pending deployment and backup identity"

state begin \
    --manifest "$TEST_DIR/new/manifest.json" \
    --jar "$TEST_DIR/new/app.jar" \
    --backup "$BACKUP"

test -s "$STATE_DIR/pending-deployment.json"

check_state current 4

echo "PASS"

echo "TEST 4: Reject overlapping pending deployment"

if state begin \
    --manifest "$TEST_DIR/new/manifest.json" \
    --jar "$TEST_DIR/new/app.jar" \
    --backup "$BACKUP" >/dev/null 2>&1; then
    exit 1
fi

echo "PASS"

echo "TEST 5: Reject success with incorrect active binary"

if state success --active-jar "$ACTIVE" \
    >/dev/null 2>&1; then
    exit 1
fi

echo "PASS"

echo "TEST 6: Record successful candidate deployment"

cp "$TEST_DIR/new/app.jar" "$ACTIVE"

state success --active-jar "$ACTIVE"

check_state current 5
check_state previous 4

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo "TEST 7: Reject promotion of incorrect release identity"

if state promote \
    --active-jar "$ACTIVE" \
    --commit "$OLD_COMMIT" \
    --build 4 \
    --evidence "simulated test" >/dev/null 2>&1; then
    exit 1
fi

echo "PASS"

echo "TEST 8: Record simulated functional validation"

state promote \
    --active-jar "$ACTIVE" \
    --commit "$NEW_COMMIT" \
    --build 5 \
    --evidence "OFFLINE_SIMULATION_ONLY"

check_state last_known_good 5

echo "PASS"

echo "TEST 9: Preserve original state during rollback"

cp "$ACTIVE" "$BACKUP"

state begin \
    --manifest "$TEST_DIR/old/manifest.json" \
    --jar "$TEST_DIR/old/app.jar" \
    --backup "$BACKUP"

cp "$TEST_DIR/old/app.jar" "$ACTIVE"

# Rollback must reject a binary that has not been restored.
if state rollback --active-jar "$ACTIVE" \
    >/dev/null 2>&1; then
    exit 1
fi

cp "$BACKUP" "$ACTIVE"

state rollback --active-jar "$ACTIVE"

check_state current 5
check_state last_known_good 5

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo "TEST 10: Verify persistent deployment history"

for event in BASELINE_INITIALIZED DEPLOYMENT_STARTED \
    DEPLOYMENT_SUCCESS ROLLBACK_SUCCESS LAST_KNOWN_GOOD_PROMOTED
do
    grep -q "$event" "$STATE_DIR/deployment-history.jsonl"
done

echo "PASS"


echo
echo "TEST 11: Recover interrupted metadata commit"

# Phase 1: Preserve the original release metadata.

cp "$STATE_DIR/state.json" "$TEST_DIR/original-state.json"

cp "$ACTIVE" "$BACKUP"

state begin \
    --manifest "$TEST_DIR/old/manifest.json" \
    --jar "$TEST_DIR/old/app.jar" \
    --backup "$BACKUP"

cp "$TEST_DIR/old/app.jar" "$ACTIVE"

# Phase 2: Simulate interruption after the candidate state
# has been persisted but before journal removal.

python3 - "$STATE_DIR" <<'PYSTATE'
import json
import sys
from pathlib import Path

directory = Path(sys.argv[1])

pending = json.loads(
    (directory / "pending-deployment.json").read_text()
)

state = pending["original_state"]

candidate = pending["candidate"]
candidate["status"] = "runtime_verified"

state["previous"] = pending["previous"]
state["current"] = candidate

(directory / "state.json").write_text(
    json.dumps(state, indent=2) + "\n"
)
PYSTATE

check_state current 4

test -s "$STATE_DIR/pending-deployment.json"

# Phase 3: Restore the previous application and original state.

cp "$BACKUP" "$ACTIVE"

state rollback --active-jar "$ACTIVE"

check_state current 5
check_state last_known_good 5

cmp "$STATE_DIR/state.json" "$TEST_DIR/original-state.json"

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo "ALL RELEASE STATE TESTS PASSED"
