#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# ============================================================
# Banking APP TEST deployment
#
# Phase 1: Validate deployment inputs and acquire lock.
# Phase 2: Download and validate the candidate release.
# Phase 3: Verify the current application and create backup.
# Phase 4: Persist recovery information.
# Phase 5: Atomically install and restart the candidate.
# Phase 6: Verify health and commit release state.
# Phase 7: Automatically recover from deployment failure.
# ============================================================

BUCKET="${1:?Artifact bucket required}"
PREFIX="${2:?Release prefix required}"
REGION="${3:?AWS region required}"

[[ "$BUCKET" == eftabankingnvddatavdc ]] || exit 2
[[ "$REGION" == us-east-1 ]] || exit 2

[[ "$PREFIX" =~ ^jenkins-artifacts/banking-app/test/releases/[a-f0-9]{40}/build-[0-9]+$ ]] ||
    exit 2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$SCRIPT_DIR/lib/deployment-common.sh"

APP_DIR=/opt/funds-transfer
ACTIVE_JAR="$APP_DIR/app.jar"
STATE_DIR="$APP_DIR/deployment-state"
RELEASES_DIR="$APP_DIR/releases"
BACKUP_DIR="$STATE_DIR/backups"

STATE_MANAGER="$SCRIPT_DIR/lib/release-state.py"
VALIDATOR="$SCRIPT_DIR/lib/validate-artifact.py"
EXTRACTOR="$SCRIPT_DIR/lib/extract-frontend.py"
FRONTEND_ROOT=/var/www/novabank
FRONTEND_RELEASES="$FRONTEND_ROOT/releases"
ACTIVE_FRONTEND="$FRONTEND_ROOT/current"

COMMIT="$(cut -d/ -f5 <<< "$PREFIX")"
BUILD="${PREFIX##*/}"
RELEASE_DIR="$RELEASES_DIR/$COMMIT-$BUILD"
FRONTEND_RELEASE="$FRONTEND_RELEASES/$COMMIT-$BUILD"

PENDING="$STATE_DIR/pending-deployment.json"

STAGING=""
ARMED=0

state() {
    python3 "$STATE_MANAGER" --state-dir "$STATE_DIR" "$@"
}

# Phase 1: Prevent concurrent deployment or recovery operations.
log "PHASE 1: Validate prerequisites and acquire deployment lock"

[[ "$EUID" -eq 0 ]] || {
    log "Deployment must run as root"
    exit 1
}

for command in aws python3 curl flock sha256sum timeout realpath nginx; do
    command -v "$command" >/dev/null || {
        log "Missing required command: $command"
        exit 1
    }
done

install -d -m 0700 "$STATE_DIR" "$BACKUP_DIR"
install -d -m 0750 "$RELEASES_DIR"

exec 9>"$STATE_DIR/deployment.lock"

flock -n 9 || {
    log "DEPLOYMENT_REJECTED: Another deployment is active"
    exit 1
}

wait_for_systemd_idle() {
    local deadline=$((SECONDS + 30))
    local job

    while (( SECONDS < deadline )); do
        job="$(timeout 5s systemctl show             funds-transfer.service             -p Job --value)" || return 1

        case "$job" in
            ""|0)
                log "No outstanding application systemd job"
                return 0
                ;;
        esac

        log "Waiting for outstanding systemd job: $job"
        sleep 2
    done

    log "SYSTEMD_JOB_UNRESOLVED"
    return 1
}

restart_application() {
    # Refuse competing restart operations.
    wait_for_systemd_idle || return 1

    timeout 210s systemctl restart         funds-transfer.service || return 1

    wait_for_systemd_idle || return 1
}

# Phase 7: Restore a verified backup and confirm recovery.
rollback_application() {
    local backup expected previous_frontend

    log "ROLLBACK: Reading persistent recovery journal"
    enable_maintenance "$FRONTEND_ROOT" || return 1
    previous_frontend="$(python3 -c '
import json,sys,runpy
previous = json.load(open(sys.argv[1]))["original_state"]["current"]
metadata = previous.get("frontend")
runpy.run_path(sys.argv[2])["verify_frontend"](metadata)
print((metadata or {}).get("directory", ""))
' "$PENDING" "$STATE_MANAGER")" || return 1

    backup="$(python3 -c '
import json,sys
print(json.load(open(sys.argv[1]))["previous"]["artifact"])
' "$PENDING")" || return 1

    expected="$(python3 -c '
import json,sys
print(json.load(open(sys.argv[1]))["previous"]["sha256"])
' "$PENDING")" || return 1

    [[ "$backup" == "$BACKUP_DIR/"* ]] || return 1

    verify_checksum "$backup" "$expected" || return 1

    wait_for_systemd_idle || return 1

    atomic_install "$backup" "$ACTIVE_JAR" "$expected" ||
        return 1

    restart_application || return 1

    wait_for_application 60 || return 1

    verify_checksum "$ACTIVE_JAR" "$expected" || return 1

    restore_frontend "$FRONTEND_ROOT" "$previous_frontend" || return 1
    disable_maintenance "$FRONTEND_ROOT" || return 1
    if [[ -n "$previous_frontend" ]]; then
        verify_frontend_http "$FRONTEND_ROOT" || return 1
    else
        verify_backend_only_http "$FRONTEND_ROOT" || return 1
    fi
    state rollback --active-jar "$ACTIVE_JAR" \
        --active-frontend "$ACTIVE_FRONTEND" || return 1

    log "ROLLBACK_SUCCESS: Previous application restored"
}

# EXIT handles ordinary command failures and trapped signals.
# A persistent journal handles interruptions that cannot be trapped.
on_exit() {
    local rc=$?

    trap - EXIT ERR
    trap '' TERM INT HUP

    if (( rc != 0 && ARMED == 1 )); then
        if [[ -f "$PENDING" ]]; then
            log "DEPLOYMENT_FAILED: Attempting rollback"

            if rollback_application; then
                log "Previous release recovered; deployment remains FAILED"
            else
                enable_maintenance "$FRONTEND_ROOT" || log "CRITICAL: Maintenance activation failed"
                state failure --reason "Automatic rollback failed" ||
                    log "Unable to write failure history"

                log "CRITICAL: ROLLBACK_FAILED; recovery journal preserved"
                rc=70
            fi
        else
            if [[ -n "${CURRENT_SHA:-}" ]] &&
                verify_checksum "$ACTIVE_JAR" "$CURRENT_SHA"; then
                log "Deployment stopped; original JAR remains intact"
            else
                log "CRITICAL: Deployment failed without recovery journal"
                rc=70
            fi
        fi
    fi

    if [[ -n "$STAGING" && -d "$STAGING" ]]; then
        rm -rf -- "$STAGING" || true
    fi

    exit "$rc"
}

trap on_exit EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
trap 'exit 129' HUP

# Recover an interrupted deployment before accepting a new one.
# Deliberately fail this invocation after recovery so Jenkins
# cannot report the new candidate as successfully deployed.
if [[ -f "$PENDING" ]]; then
    log "Interrupted deployment detected"

    if rollback_application; then
        log "Interrupted deployment recovered; retry with a new build"
        exit 75
    else
        enable_maintenance "$FRONTEND_ROOT" || log "CRITICAL: Maintenance activation failed"
        state failure --reason "Interrupted deployment recovery failed" ||
            true
        log "CRITICAL: Manual recovery required"
        exit 70
    fi
fi

# Phase 2: Stage downloads without overwriting existing releases.
[[ -d "$FRONTEND_RELEASES" && ! -L "$FRONTEND_RELEASES" ]] || exit 1
nginx_healthy || exit 1
[[ ! -e "$FRONTEND_ROOT/maintenance" ]] || {
    log "Unexpected maintenance marker; manual inspection required"
    exit 1
}
log "PHASE 2: Download and validate candidate artifact"

STAGING="$(mktemp -d "$RELEASES_DIR/.incoming.XXXXXXXX")"

for name in manifest.json app.jar app.jar.sha256 frontend.tar.gz frontend.tar.gz.sha256; do
    AWS_MAX_ATTEMPTS=2 timeout 25s aws s3 cp \
        "s3://$BUCKET/$PREFIX/$name" \
        "$STAGING/$name" \
        --region "$REGION" \
        --only-show-errors
done

CANDIDATE_SHA="$(python3 "$VALIDATOR" "$STAGING" "$PREFIX" --require-fullstack)"

if [[ -d "$RELEASE_DIR" ]]; then
    EXISTING_SHA="$(python3 "$VALIDATOR" "$RELEASE_DIR" "$PREFIX" --require-fullstack)"

    cmp -s "$STAGING/manifest.json" "$RELEASE_DIR/manifest.json" || exit 1
    [[ "$EXISTING_SHA" == "$CANDIDATE_SHA" ]] || {
        log "Existing release differs from downloaded release"
        exit 1
    }

    rm -rf -- "$STAGING"
    STAGING=""
else
    mv -T -- "$STAGING" "$RELEASE_DIR"
    STAGING=""
fi

log "Candidate artifact validated: $CANDIDATE_SHA"
if [[ -e "$FRONTEND_RELEASE" || -L "$FRONTEND_RELEASE" ]]; then
    [[ -d "$FRONTEND_RELEASE" && ! -L "$FRONTEND_RELEASE" ]] || exit 1
    STAGING="$(mktemp -d "$FRONTEND_RELEASES/.verify.XXXXXXXX")"
    python3 "$EXTRACTOR" --archive "$RELEASE_DIR/frontend.tar.gz" \
        --destination "$STAGING/frontend"
    diff -r --no-dereference "$STAGING/frontend" "$FRONTEND_RELEASE"
    rm -rf -- "$STAGING"
    STAGING=""
else
    python3 "$EXTRACTOR" --archive "$RELEASE_DIR/frontend.tar.gz" \
        --destination "$FRONTEND_RELEASE"
fi
sync -f "$FRONTEND_RELEASE"

# Phase 3: Refuse installation without a healthy rollback baseline.
log "PHASE 3: Validate existing application and backup"

[[ -s "$ACTIVE_JAR" ]] || {
    log "Existing application JAR missing"
    exit 1
}

[[ -f "$STATE_DIR/state.json" ]] || {
    log "Release state not initialized; refusing deployment"
    exit 1
}

wait_for_application 15 || {
    log "Existing application is unhealthy; deployment refused"
    exit 1
}

CURRENT_SHA="$(python3 -c '
import json,sys
print(json.load(open(sys.argv[1]))["current"]["sha256"])
' "$STATE_DIR/state.json")"

verify_checksum "$ACTIVE_JAR" "$CURRENT_SHA" || {
    log "Active application differs from registered release"
    exit 1
}

BACKUP="$(mktemp "$BACKUP_DIR/app.jar.XXXXXXXX")"

install -o root -g fundsapp -m 0640 "$ACTIVE_JAR" "$BACKUP"

verify_checksum "$BACKUP" "$CURRENT_SHA"

# Flush backup data before recording the recovery journal.
sync -f "$BACKUP"

log "Verified backup: $BACKUP"

# Phase 4: Persist recovery journal before modifying active JAR.
log "PHASE 4: Register pending deployment"

# Arm recovery before writing the persistent deployment journal.
ARMED=1

state begin \
    --manifest "$RELEASE_DIR/manifest.json" \
    --jar "$RELEASE_DIR/app.jar" \
    --backup "$BACKUP" \
    --frontend-dir "$FRONTEND_RELEASE" \
    --active-frontend "$ACTIVE_FRONTEND"

enable_maintenance "$FRONTEND_ROOT"

# Phase 5: Atomically replace the JAR and restart the service.
log "PHASE 5: Install candidate application"

wait_for_systemd_idle

atomic_install \
    "$RELEASE_DIR/app.jar" \
    "$ACTIVE_JAR" \
    "$CANDIDATE_SHA"

restart_application

# Phase 6: Validate runtime and persist successful release.
log "PHASE 6: Verify application health and readiness"

wait_for_application 60

verify_checksum "$ACTIVE_JAR" "$CANDIDATE_SHA"

activate_frontend "$FRONTEND_ROOT" "$FRONTEND_RELEASE"
disable_maintenance "$FRONTEND_ROOT"
verify_frontend_http "$FRONTEND_ROOT"
state success --active-jar "$ACTIVE_JAR" \
    --active-frontend "$ACTIVE_FRONTEND"

ARMED=0

log "FULL_STACK_DEPLOYMENT_SUCCESS"
log "DEPLOY_SUCCESS prefix=$PREFIX backup=$BACKUP"
