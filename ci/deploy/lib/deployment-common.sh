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
