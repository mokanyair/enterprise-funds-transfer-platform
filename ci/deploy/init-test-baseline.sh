#!/usr/bin/env bash
set -euo pipefail
umask 077

# ============================================================
# Initialize persistent TEST release tracking.
#
# Phase 1: Validate the known Build 4 release.
# Phase 2: Acquire the shared deployment lock.
# Phase 3: Verify active application health and checksum.
# Phase 4: Initialize persistent release metadata.
# Phase 5: Display the registered baseline.
#
# This script does not replace the JAR or restart the service.
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$SCRIPT_DIR/lib/deployment-common.sh"

APP_DIR=/opt/funds-transfer
STATE_DIR="$APP_DIR/deployment-state"

COMMIT=6d13eef77a7cdb1a3e0586a9e3dcbc17619b0cec

EXPECTED_SHA=b99ff71989e684e1b6317b218420c8c9d8bfffbe5ebd581c6c6882943d23b1ed

PREFIX="jenkins-artifacts/banking-app/test/releases/$COMMIT/build-4"

RELEASE_DIR="$APP_DIR/releases/$COMMIT-build-4"

[[ "$EUID" -eq 0 ]] || {
    log "Root privileges required"
    exit 1
}

# Phase 1: Validate the original release artifacts.

log "PHASE 1: Validate Build 4 release"

ACTUAL_SHA="$(python3 \
    "$SCRIPT_DIR/lib/validate-artifact.py" \
    "$RELEASE_DIR" "$PREFIX")"

[[ "$ACTUAL_SHA" == "$EXPECTED_SHA" ]]

# Phase 2: Prevent concurrent initialization or deployment.

log "PHASE 2: Acquire deployment lock"

install -d -m 0700 "$STATE_DIR" "$STATE_DIR/backups"

exec 9>"$STATE_DIR/deployment.lock"

flock -n 9 || {
    log "Another deployment operation is active"
    exit 1
}

[[ ! -e "$STATE_DIR/pending-deployment.json" ]]

[[ ! -e "$STATE_DIR/state.json" ]] || {
    log "Release state already exists; refusing overwrite"
    exit 1
}

# Phase 3: Verify the running application.

log "PHASE 3: Verify active application"

verify_checksum "$APP_DIR/app.jar" "$EXPECTED_SHA"

wait_for_application 15

# Phase 4: Initialize the verified runtime baseline.

log "PHASE 4: Initialize release state"

python3 "$SCRIPT_DIR/lib/release-state.py" \
    --state-dir "$STATE_DIR" \
    init \
    --manifest "$RELEASE_DIR/manifest.json" \
    --jar "$RELEASE_DIR/app.jar" \
    --active-jar "$APP_DIR/app.jar"

# Phase 5: Display registered release information.

log "PHASE 5: Display initialized baseline"

python3 "$SCRIPT_DIR/lib/release-state.py" \
    --state-dir "$STATE_DIR" show

log "BASELINE_REGISTRATION_SUCCESS"
