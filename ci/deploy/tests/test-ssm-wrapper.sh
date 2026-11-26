#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# SSM deployment wrapper regression tests.
#
# Phase 1: Create an isolated deployment environment.
# Phase 2: Mock AWS SSM submission and polling.
# Phase 3: Validate terminal deployment statuses.
# Phase 4: Validate remote bundle corruption rejection.
# ============================================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT

mkdir -p "$TEST_DIR/bin" "$TEST_DIR/ci"

cp -r "$ROOT" "$TEST_DIR/ci/deploy"

cd "$TEST_DIR"

export MOCK_SSM_ROOT="$TEST_DIR"
export MOCK_SSM_STATUS=Success

# Phase 1: Mock AWS commands.
# No real AWS API calls are permitted.

cat > "$TEST_DIR/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

case "$2" in
    send-command)
        cp .ci-release/ssm-parameters.json \
            "$MOCK_SSM_ROOT/captured-parameters.json"

        echo "simulated-command-id"
        ;;

    get-command-invocation)
        if [[ "$*" == *"--query Status"* ]]; then
            echo "$MOCK_SSM_STATUS"
        else
            printf '{"Status":"%s"}\n' "$MOCK_SSM_STATUS"
        fi
        ;;

    *)
        echo "Unexpected AWS operation: $*" >&2
        exit 90
        ;;
esac
MOCK

chmod 700 "$TEST_DIR/bin/aws"

export PATH="$TEST_DIR/bin:$PATH"

PREFIX="jenkins-artifacts/banking-app/test/releases/6d13eef77a7cdb1a3e0586a9e3dcbc17619b0cec/build-4"

# Phase 2: Verify successful deployment handling.

echo "TEST 1: Successful SSM deployment"

export MOCK_SSM_STATUS=Success

bash ci/deploy/deploy-test.sh "$PREFIX" \
    > "$TEST_DIR/result.log" 2>&1

grep -q TEST_DEPLOYMENT_SUCCESS "$TEST_DIR/result.log"

echo "PASS"

# Phase 3: Verify terminal failure statuses.
# None of these statuses may be reported as success.

TEST_NUMBER=2

for status in Failed Cancelled TimedOut
do
    echo "TEST $TEST_NUMBER: Reject SSM status $status"

    export MOCK_SSM_STATUS="$status"

    if bash ci/deploy/deploy-test.sh "$PREFIX" \
        > "$TEST_DIR/result.log" 2>&1
    then
        echo "FAIL: $status incorrectly accepted"
        exit 1
    fi

    if grep -q TEST_DEPLOYMENT_SUCCESS "$TEST_DIR/result.log"
    then
        echo "FAIL: Incorrect success message"
        exit 1
    fi

    echo "PASS"

    TEST_NUMBER=$((TEST_NUMBER + 1))
done

# Phase 4: Corrupt the generated remote bundle.
# Execute only the command sequence through checksum validation.
# The real installer is never executed.

echo "TEST 5: Remote bundle checksum rejection"

python3 - <<'PY'
import json
import base64
from pathlib import Path

parameters = json.loads(
    Path("captured-parameters.json").read_text()
)

commands = parameters["commands"]

bundle = Path(
    ".ci-release/deployment-bundle.tar.gz"
).read_bytes()

encoded = base64.b64encode(bundle).decode()

corrupted = base64.b64encode(
    bundle + b"SIMULATED_CORRUPTION"
).decode()

assert commands.count(encoded) == 1

commands[commands.index(encoded)] = corrupted

checksum_index = next(
    i for i, command in enumerate(commands)
    if "sha256sum -c -" in command
)

extract_index = next(
    i for i, command in enumerate(commands)
    if "tar -xzf" in command
)

assert checksum_index < extract_index

safe_commands = commands[:checksum_index + 1]

safe_commands.append(
    'echo "UNEXPECTED_CHECKSUM_SUCCESS"'
)

Path("corrupted-remote.sh").write_text(
    "\n".join(safe_commands) + "\n"
)
PY

if bash corrupted-remote.sh \
    > "$TEST_DIR/corruption.log" 2>&1
then
    echo "FAIL: Corrupted remote bundle accepted"
    exit 1
fi

grep -q FAILED "$TEST_DIR/corruption.log"

if grep -q UNEXPECTED_CHECKSUM_SUCCESS \
    "$TEST_DIR/corruption.log"
then
    echo "FAIL: Execution continued after checksum failure"
    exit 1
fi

echo "PASS"

echo "ALL SSM WRAPPER REGRESSION TESTS PASSED"
