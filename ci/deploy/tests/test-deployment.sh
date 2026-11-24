#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT/lib/deployment-common.sh"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT

# Mock systemd and HTTP responses.
SERVICE_STATUS=active
HEALTH_STATUS=UP
READINESS_STATUS=UP
HTTP_FAILURE=false

systemctl() {
    [[ "$SERVICE_STATUS" == active ]]
}

curl() {
    if [[ "$HTTP_FAILURE" == true ]]; then
        return 7
    fi

    if [[ "${*: -1}" == */readiness ]]; then
        printf '{"status":"%s"}' "$READINESS_STATUS"
    else
        printf '{"status":"%s"}' "$HEALTH_STATUS"
    fi
}

expect_failure() {
    if "$@"; then
        echo "FAIL: Unexpected success: $*" >&2
        exit 1
    fi
}

echo "TEST 1: Accept active and healthy application"

application_healthy

echo "PASS"

echo "TEST 2: Reject inactive systemd service"

SERVICE_STATUS=inactive

expect_failure application_healthy

SERVICE_STATUS=active

echo "PASS"

echo "TEST 3: Reject HTTP connection failure"

HTTP_FAILURE=true

expect_failure application_healthy

HTTP_FAILURE=false

echo "PASS"

echo "TEST 4: Reject HTTP 200 with DOWN health status"

HEALTH_STATUS=DOWN

expect_failure application_healthy

HEALTH_STATUS=UP

echo "PASS"

echo "TEST 5: Reject application not ready"

READINESS_STATUS=OUT_OF_SERVICE

expect_failure application_healthy

READINESS_STATUS=UP

echo "PASS"

echo "TEST 6: Require consecutive successful health checks"

wait_for_application 10

echo "PASS"

echo "TEST 7: Detect persistent readiness failure"

READINESS_STATUS=DOWN

expect_failure wait_for_application 3

READINESS_STATUS=UP

echo "PASS"

echo "TEST 8: Reject concurrent deployment lock"

LOCK="$TEST_DIR/deployment.lock"

exec 9>"$LOCK"
flock -n 9

if (
    exec 9>&-
    flock -n "$LOCK" true
); then
    echo "FAIL: Concurrent lock acquired"
    exit 1
fi

flock -u 9
exec 9>&-

echo "PASS"

echo "TEST 9: Verify lock is available after release"

flock -n "$LOCK" true

echo "PASS"

echo "ALL DEPLOYMENT COMPONENT TESTS PASSED"
