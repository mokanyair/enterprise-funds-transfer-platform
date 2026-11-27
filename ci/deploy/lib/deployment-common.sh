#!/usr/bin/env bash

log() {
    printf '[%s] %s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

sha256_file() {
    sha256sum -- "$1" | awk '{print $1}'
}

verify_checksum() {
    local file="$1"
    local expected="$2"
    local actual

    [[ -s "$file" ]] || return 1
    [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || return 1

    actual="$(sha256_file "$file")" || return 1

    [[ "$actual" == "$expected" ]]
}

health_endpoint_up() {
    local endpoint="$1"
    local response

    response="$(curl -fsS \
        --connect-timeout 2 \
        --max-time 3 \
        "$endpoint")" || return 1

    printf '%s' "$response" |
        python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin)
    sys.exit(0 if data.get("status") == "UP" else 1)
except (ValueError, AttributeError):
    sys.exit(1)
'
}

application_healthy() {
    systemctl is-active --quiet funds-transfer.service &&
    health_endpoint_up \
        http://127.0.0.1:8080/actuator/health &&
    health_endpoint_up \
        http://127.0.0.1:8080/actuator/health/readiness
}

wait_for_application() {
    local timeout_seconds="${1:-60}"
    local deadline=$((SECONDS + timeout_seconds))
    local consecutive=0

    while (( SECONDS < deadline )); do
        if application_healthy; then
            consecutive=$((consecutive + 1))

            if (( consecutive >= 2 )); then
                log "Application health and readiness verified"
                return 0
            fi
        else
            consecutive=0
        fi

        sleep 2
    done

    log "Application health validation timed out"
    return 1
}

atomic_install() {
    # Phase 1: Stage the candidate on the destination filesystem.
    local source="$1"
    local destination="$2"
    local expected_sha="$3"
    local temporary

    temporary="$(mktemp "${destination}.tmp.XXXXXXXX")" ||
        return 1

    # Phase 2: Apply required application ownership and permissions.
    if ! install -o root -g fundsapp -m 0640 \
        "$source" "$temporary"; then
        rm -f -- "$temporary"
        return 1
    fi

    # Phase 3: Preserve the existing SELinux file context.
    if command -v selinuxenabled >/dev/null &&
        selinuxenabled &&
        [[ -e "$destination" ]]; then

        if ! chcon --reference="$destination" "$temporary"; then
            rm -f -- "$temporary"
            return 1
        fi
    fi

    # Phase 4: Validate and flush the staged application binary.
    if ! verify_checksum "$temporary" "$expected_sha" ||
        ! sync -f "$temporary"; then
        rm -f -- "$temporary"
        return 1
    fi

    # Phase 5: Atomically replace the active application JAR.
    if ! mv -fT -- "$temporary" "$destination"; then
        rm -f -- "$temporary"
        return 1
    fi

    sync -f "$destination" || return 1

    # Phase 6: Verify the installed application binary.
    verify_checksum "$destination" "$expected_sha"
}

# ============================================================
# FULL-STACK FRONTEND DEPLOYMENT FUNCTIONS
#
# Phase 1: Manage NGINX maintenance mode.
# Phase 2: Activate a validated frontend release.
# Phase 3: Restore the previous frontend release.
# Phase 4: Verify frontend runtime accessibility.
#
# All mutation functions require the caller to hold
# the exclusive application deployment lock.
# ============================================================


# PHASE 1: MAINTENANCE MODE

enable_maintenance() {
    local root="$1"

    [[ -d "$root" && ! -L "$root" ]] || return 1

    touch "$root/maintenance" || return 1

    chmod 0644 "$root/maintenance" || return 1

    sync -f "$root" || return 1

    log "NGINX maintenance mode enabled"
}


disable_maintenance() {
    local root="$1"

    rm -f -- "$root/maintenance" || return 1

    sync -f "$root" || return 1

    log "NGINX maintenance mode disabled"
}


# PHASE 2: ATOMIC FRONTEND ACTIVATION

activate_frontend() {
    local root="$1"
    local release="$2"
    local resolved
    local releases
    local temporary

    [[ -d "$release" && ! -L "$release" ]] || return 1

    [[ -s "$release/index.html" ]] || return 1

    [[ -s "$release/.release-inventory.json" ]] || return 1

    resolved="$(realpath -e -- "$release")" || return 1

    releases="$(realpath -e -- "$root/releases")" || return 1

    # Prevent activation outside the managed release directory.
    [[ "$(dirname -- "$resolved")" == "$releases" ]] ||
        return 1

    # Never replace an unmanaged directory or regular file.
    if [[ -e "$root/current" || -L "$root/current" ]]; then
        [[ -L "$root/current" ]] || return 1
    fi

    # Apply the correct SELinux labels before activation.
    if command -v restorecon >/dev/null &&
        command -v selinuxenabled >/dev/null &&
        selinuxenabled; then

        restorecon -RF "$resolved" || return 1
    fi

    # Flush extracted release files before activation.
    sync -f "$resolved" || return 1

    temporary="$(mktemp -d "$root/.activation.XXXXXXXX")" ||
        return 1

    if ! ln -s -- "$resolved" "$temporary/current"; then
        rmdir "$temporary"
        return 1
    fi

    # Atomically replace the active frontend symlink.
    if ! mv -fT -- "$temporary/current" "$root/current"; then
        rm -f -- "$temporary/current"
        rmdir "$temporary"
        return 1
    fi

    rmdir "$temporary" || return 1

    sync -f "$root" || return 1

    [[ "$(realpath -e -- "$root/current")" == "$resolved" ]] ||
        return 1

    log "Frontend activated: $resolved"
}


# PHASE 3: RESTORE PREVIOUS FRONTEND

restore_frontend() {
    local root="$1"
    local previous="${2:-}"

    if [[ -n "$previous" ]]; then

        activate_frontend "$root" "$previous" ||
            return 1

        log "Previous frontend restored"

    else

        # Build 4 has no managed frontend release.
        # Remove only the managed symbolic link.

        if [[ -e "$root/current" || -L "$root/current" ]]; then

            [[ -L "$root/current" ]] || return 1

            rm -f -- "$root/current" || return 1

        fi

        sync -f "$root" || return 1

        log "Backend-only baseline restored"

    fi
}


# PHASE 4: FRONTEND RUNTIME VERIFICATION

nginx_healthy() {
    local response

    systemctl is-active --quiet nginx || return 1

    response="$(curl -fsS \
        --connect-timeout 2 \
        --max-time 5 \
        http://127.0.0.1/healthz)" || return 1

    [[ "$response" == "nginx-up" ]]
}


verify_frontend_http() {
    local root="$1"
    local expected
    local actual
    local temporary

    nginx_healthy || return 1

    [[ ! -e "$root/maintenance" ]] || return 1

    [[ -L "$root/current" ]] || return 1

    [[ -s "$root/current/index.html" ]] || return 1

    expected="$(sha256_file "$root/current/index.html")" ||
        return 1

    temporary="$(mktemp)" || return 1

    if ! curl -fsS \
        --connect-timeout 2 \
        --max-time 10 \
        http://127.0.0.1/ \
        -o "$temporary"; then

        rm -f -- "$temporary"
        return 1
    fi

    actual="$(sha256_file "$temporary")" || {
        rm -f -- "$temporary"
        return 1
    }

    rm -f -- "$temporary"

    [[ "$actual" == "$expected" ]] || return 1

    log "NGINX is serving the expected frontend index"
}


verify_backend_only_http() {
    local root="$1"
    local status

    nginx_healthy || return 1

    [[ ! -e "$root/current" && ! -L "$root/current" ]] ||
        return 1

    [[ ! -e "$root/maintenance" ]] || return 1

    status="$(curl -sS \
        --connect-timeout 2 \
        --max-time 5 \
        -o /dev/null \
        -w '%{http_code}' \
        http://127.0.0.1/)" || return 1

    [[ "$status" == "503" ]] || return 1

    log "Backend-only baseline HTTP behavior verified"
}
