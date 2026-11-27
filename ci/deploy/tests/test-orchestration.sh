#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Complete offline deployment orchestration regression tests.
#
# Phase 1: Create isolated application and artifact directories.
# Phase 2: Mock AWS, systemd and HTTP health endpoints.
# Phase 3: Prepare old and candidate application releases.
# Phase 4: Execute successful deployment.
# Phase 5: Execute failed deployment and automatic rollback.
# Phase 6: Simulate critical rollback failure.
# Phase 7: Recover an interrupted deployment.
# ============================================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SANDBOX="$(mktemp -d)"
trap 'rm -rf -- "$SANDBOX"' EXIT

export MOCK_ROOT="$SANDBOX"

export MOCK_APP="$SANDBOX/application"
export MOCK_FRONTEND="$SANDBOX/frontend"
export MOCK_S3="$SANDBOX/s3"
export MOCK_MODE=success

STATE_DIR="$MOCK_APP/deployment-state"

mkdir -p "$SANDBOX/bin" "$SANDBOX/scripts"
mkdir -p "$MOCK_APP/releases" "$MOCK_S3" "$MOCK_FRONTEND/releases"

cp -r "$ROOT/lib" "$SANDBOX/scripts/"
cp "$ROOT/remote-install.sh" "$SANDBOX/scripts/"

export PATH="$SANDBOX/bin:$PATH"

# Phase 1: Adapt only the isolated test copy.
# Production paths and privilege checks remain unchanged
# in the actual repository deployment script.

python3 - "$SANDBOX/scripts" <<'PY'
import sys
from pathlib import Path

directory = Path(sys.argv[1])

path = directory / "remote-install.sh"
text = path.read_text()

replacements = [
    ("FRONTEND_ROOT=/var/www/novabank", 'FRONTEND_ROOT="${MOCK_FRONTEND:?}"'),
    ("APP_DIR=/opt/funds-transfer",
     'APP_DIR="${MOCK_APP:?Test directory required}"'),
    ('[[ "$EUID" -eq 0 ]] || {',
     '[[ -n "${MOCK_APP:-}" ]] || {'),
    ("wait_for_application 60",
     "wait_for_application 4"),
    ("wait_for_application 15",
     "wait_for_application 4"),
    ("local deadline=$((SECONDS + 30))",
     "local deadline=$((SECONDS + 3))"),
]

for old, new in replacements:
    if old not in text:
        raise SystemExit(f"Missing expected test adapter: {old}")
    text = text.replace(old, new)


# Inject process termination only into the temporary installer.
# SIGKILL bypasses EXIT and signal traps.

boundaries = [
    (
        "ARMED=1",
        "BEFORE_JOURNAL"
    ),
    (
        '--active-frontend "$ACTIVE_FRONTEND"\n\nenable_maintenance',
        "AFTER_JOURNAL"
    ),
    (
        '    "$CANDIDATE_SHA"\n\nrestart_application',
        "AFTER_INSTALL"
    ),
    (
        'restart_application\n\n# Phase 6:',
        "AFTER_RESTART"
    ),
]

for original, boundary in boundaries:
    if text.count(original) != 1:
        raise SystemExit(
            f"Unexpected interruption boundary: {boundary}"
        )

    hook = (
        '\nif [[ "${MOCK_INTERRUPT:-}" == "'
        + boundary
        + '" ]]; then\n'
        + '    kill -KILL "$$"\n'
        + 'fi\n'
    )

    if boundary == "AFTER_INSTALL":
        updated = original.replace(
            "\n\nrestart_application",
            hook + "\nrestart_application"
        )
    elif boundary == "AFTER_JOURNAL":
        updated = original.replace("\n\nenable_maintenance", hook + "\nenable_maintenance")
    elif boundary == "AFTER_RESTART":
        updated = original.replace(
            "\n\n# Phase 6:",
            hook + "\n# Phase 6:"
        )
    else:
        updated = original + hook

    text = text.replace(original, updated)

path.write_text(text)

path = directory / "lib/deployment-common.sh"
text = path.read_text()

if "sleep 2" not in text:
    raise SystemExit("Expected polling interval not found")

text = text.replace("sleep 2", "sleep 0.1")

path.write_text(text)
PY

# Phase 2: Mock AWS S3 downloads.
# Every requested artifact is copied from local test storage.

cat > "$SANDBOX/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

[[ "$1" == s3 && "$2" == cp ]] || exit 90

KEY="${3#s3://eftabankingnvddatavdc/}"

cp -- "$MOCK_S3/$KEY" "$4"
MOCK

# Mock systemd without contacting the real service manager.

cat > "$SANDBOX/bin/systemctl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

case "$1" in
    restart)
        printf 'restart\n' >> "$MOCK_ROOT/restarts.log"
        ;;
    is-active)
        exit 0
        ;;
    show)
        if [[ "${MOCK_SYSTEMD_QUERY_FAILURE:-false}" == true ]]; then
            exit 1
        fi

        printf '%s\n' "${MOCK_SYSTEMD_JOB:-}"
        ;;
    *)
        exit 90
        ;;
esac
MOCK

# Mock HTTP health based on the currently installed artifact.
# A bad candidate reports DOWN; the original release reports UP.

cat > "$SANDBOX/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
    *http://127.0.0.1/healthz*) printf 'nginx-up'; exit 0 ;;
    *http://127.0.0.1/\ *)
        if [[ "$*" == *'%{http_code}'* ]]; then printf 503; exit 0; fi
        [[ ! -e "$MOCK_FRONTEND/maintenance" ]] || exit 22
        cp "$MOCK_FRONTEND/current/index.html" "${@: -1}"
        exit 0 ;;
    *http://127.0.0.1/) printf 503; exit 0 ;;
esac
case "$MOCK_MODE" in
    rollback_failure)
        if [[ -s "$MOCK_ROOT/restarts.log" ]]; then
            printf '{"status":"DOWN"}'
        else
            printf '{"status":"UP"}'
        fi
        ;;
    *)
        if grep -q BAD "$MOCK_APP/app.jar"; then
            printf '{"status":"DOWN"}'
        else
            printf '{"status":"UP"}'
        fi
        ;;
esac
MOCK

# Mock ownership changes so tests can run without sudo.
# Actual ownership and SELinux behavior require host validation.

cat > "$SANDBOX/bin/install" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

args=()

while (( $# > 0 )); do
    case "$1" in
        -o|-g)
            shift 2
            ;;
        *)
            args+=("$1")
            shift
            ;;
    esac
done

exec /usr/bin/install "${args[@]}"
MOCK

cat > "$SANDBOX/bin/selinuxenabled" <<'MOCK'
#!/usr/bin/env bash
exit 1
MOCK

printf '#!/usr/bin/env bash\nexit 0\n' > "$SANDBOX/bin/nginx"
chmod 700 "$SANDBOX/bin/"*

# Phase 3: Create release artifacts with valid checksums.

OLD_COMMIT="$(printf 'a%.0s' {1..40})"
NEW_COMMIT="$(printf 'b%.0s' {1..40})"
BAD_COMMIT="$(printf 'c%.0s' {1..40})"

BASE="jenkins-artifacts/banking-app/test/releases"

OLD_PREFIX="$BASE/$OLD_COMMIT/build-4"
NEW_PREFIX="$BASE/$NEW_COMMIT/build-5"
BAD_PREFIX="$BASE/$BAD_COMMIT/build-6"

make_release() {
    local prefix="$1"
    local content="$2"
    local directory="$MOCK_S3/$prefix"

    mkdir -p "$directory"

    printf '%s\n' "$content" > "$directory/app.jar"

    (
        cd "$directory"
        sha256sum app.jar > app.jar.sha256
    )

    python3 - "$directory" "$prefix" <<'PY'
import hashlib
import json
import sys
import tarfile
import io
from pathlib import Path

directory = Path(sys.argv[1])
parts = sys.argv[2].split("/")

manifest = {
    "commit": parts[4],
    "build_number": parts[5].removeprefix("build-"),
    "sha256": hashlib.sha256(
        (directory / "app.jar").read_bytes()
    ).hexdigest(),
}

if parts[5] != "build-4":
    archive = directory / "frontend.tar.gz"
    import gzip
    with archive.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed, tarfile.open(fileobj=compressed, mode="w") as package:
        for name, data in [("index.html", parts[5].encode()), ("assets/app.js", b"console.log(1)")]:
            member = tarfile.TarInfo(name)
            member.size = len(data)
            package.addfile(member, io.BytesIO(data))
    manifest.update(schema_version=2, environment="test", artifacts={})
    for key, name in [("backend", "app.jar"), ("frontend", "frontend.tar.gz")]:
        data = (directory / name).read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        manifest["artifacts"][key] = dict(filename=name, sha256=sha, size_bytes=len(data))
        (directory / (name + ".sha256")).write_text(f"{sha}  {name}\n")

(directory / "manifest.json").write_text(
    json.dumps(manifest)
)
PY
}

make_release "$OLD_PREFIX" "GOOD RELEASE 4"
make_release "$NEW_PREFIX" "GOOD RELEASE 5"
make_release "$BAD_PREFIX" "BAD RELEASE 6"

OLD_DIR="$MOCK_APP/releases/$OLD_COMMIT-build-4"

cp -r "$MOCK_S3/$OLD_PREFIX" "$OLD_DIR"
cp "$OLD_DIR/app.jar" "$MOCK_APP/app.jar"

state() {
    python3 "$SANDBOX/scripts/lib/release-state.py" \
        --state-dir "$STATE_DIR" "$@"
}

deploy() {
    bash "$SANDBOX/scripts/remote-install.sh" \
        eftabankingnvddatavdc "$1" us-east-1
}

check_current() {
    python3 - "$STATE_DIR/state.json" "$1" <<'PY'
import json
import sys

state = json.load(open(sys.argv[1]))

if state["current"]["build_number"] != sys.argv[2]:
    sys.exit("Unexpected current release")
PY
}

expect_exit() {
    local expected="$1"
    shift
    local rc=0

    "$@" || rc=$?

    if [[ "$rc" != "$expected" ]]; then
        echo "FAIL: Expected exit $expected, received $rc"
        exit 1
    fi
}

state init \
    --manifest "$OLD_DIR/manifest.json" \
    --jar "$OLD_DIR/app.jar" \
    --active-jar "$MOCK_APP/app.jar"

# Phase 4: Successful deployment.
# Verify binary, state transition and journal cleanup.

echo
echo "TEST 1: Complete successful deployment"

deploy "$NEW_PREFIX"

check_current 5
[[ "$(readlink "$MOCK_FRONTEND/current")" == "$MOCK_FRONTEND/releases/$NEW_COMMIT-build-5" ]]

grep -q "GOOD RELEASE 5" "$MOCK_APP/app.jar"

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

# Phase 5: Candidate passes checksum validation but fails health.
# The previous application must be restored automatically.

echo
echo "TEST 2: Unhealthy candidate triggers automatic rollback"

expect_exit 1 deploy "$BAD_PREFIX"

check_current 5

grep -q "GOOD RELEASE 5" "$MOCK_APP/app.jar"

test ! -e "$STATE_DIR/pending-deployment.json"

grep -q ROLLBACK_SUCCESS \
    "$STATE_DIR/deployment-history.jsonl"

echo "PASS"

# Reject corrupted S3 artifacts before changing the application.

echo
echo "TEST 3: Corrupted artifact rejected before installation"

make_release "$BAD_PREFIX" "BAD RELEASE 6"

printf 'CORRUPTION\n' >> "$MOCK_S3/$BAD_PREFIX/app.jar"

expect_exit 1 deploy "$BAD_PREFIX"

check_current 5

grep -q "GOOD RELEASE 5" "$MOCK_APP/app.jar"

echo "PASS"

make_release "$BAD_PREFIX" "BAD RELEASE 6"

# Phase 6: Simulate a candidate failure followed by a rollback
# health failure. The recovery journal must remain available.

echo
echo "TEST 4: Critical rollback failure preserves recovery journal"

: > "$MOCK_ROOT/restarts.log"

export MOCK_MODE=rollback_failure

expect_exit 70 deploy "$BAD_PREFIX"

test -s "$STATE_DIR/pending-deployment.json"

grep -q DEPLOYMENT_FAILURE \
    "$STATE_DIR/deployment-history.jsonl"

echo "PASS"

# Phase 7: Restore healthy conditions and invoke deployment again.
# Recovery must complete without deploying the new candidate.

echo
echo "TEST 5: Recover interrupted deployment"

export MOCK_MODE=success

expect_exit 75 deploy "$NEW_PREFIX"

check_current 5

grep -q "GOOD RELEASE 5" "$MOCK_APP/app.jar"

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo
echo "TEST 6: Deployment succeeds after recovery"

deploy "$NEW_PREFIX"

check_current 5

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo

# Phase 8: Verify systemd restart protection.
# A pending systemd job must prevent binary replacement
# and competing restart operations.

echo
echo "TEST 7: Outstanding systemd job blocks deployment"

cp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

: > "$MOCK_ROOT/restarts.log"

export MOCK_SYSTEMD_JOB=12345

expect_exit 70 deploy "$BAD_PREFIX"

cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

test ! -s "$MOCK_ROOT/restarts.log"

test -s "$STATE_DIR/pending-deployment.json"

check_current 5

echo "PASS"

# Phase 9: Verify recovery when the existing job has settled.

echo
echo "TEST 8: Recover after systemd becomes idle"

unset MOCK_SYSTEMD_JOB

expect_exit 75 deploy "$NEW_PREFIX"

check_current 5

cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

# Phase 10: A failed systemd query must not be interpreted
# as confirmation that the service is idle.

echo
echo "TEST 9: Systemd query failure blocks deployment"

: > "$MOCK_ROOT/restarts.log"

export MOCK_SYSTEMD_QUERY_FAILURE=true

expect_exit 70 deploy "$BAD_PREFIX"

cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

test ! -s "$MOCK_ROOT/restarts.log"

test -s "$STATE_DIR/pending-deployment.json"

echo "PASS"

echo
echo "TEST 10: Recover after systemd query is restored"

unset MOCK_SYSTEMD_QUERY_FAILURE

expect_exit 75 deploy "$NEW_PREFIX"

check_current 5

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

# Phase 11: Verify recovery backup integrity.
# A damaged backup must never replace the application.

echo
echo "TEST 11: Reject corrupted rollback backup"

: > "$MOCK_ROOT/restarts.log"

export MOCK_MODE=rollback_failure

expect_exit 70 deploy "$BAD_PREFIX"

PENDING="$STATE_DIR/pending-deployment.json"

test -s "$PENDING"

RECOVERY_BACKUP="$(python3 - "$PENDING" <<'PYBACKUP'
import json
import sys

with open(sys.argv[1]) as stream:
    print(json.load(stream)["previous"]["artifact"])
PYBACKUP
)"

cp "$RECOVERY_BACKUP" "$SANDBOX/valid-recovery.jar"

printf 'CORRUPTED BACKUP\n' > "$RECOVERY_BACKUP"

cp "$MOCK_APP/app.jar" "$SANDBOX/pre-recovery.jar"

export MOCK_MODE=success

: > "$MOCK_ROOT/restarts.log"

expect_exit 70 deploy "$NEW_PREFIX"

cmp "$MOCK_APP/app.jar" "$SANDBOX/pre-recovery.jar"

test ! -s "$MOCK_ROOT/restarts.log"

test -s "$PENDING"

echo "PASS"

# Phase 12: Confirm recovery after restoring the valid backup.

echo
echo "TEST 12: Recover using restored valid backup"

cp "$SANDBOX/valid-recovery.jar" "$RECOVERY_BACKUP"

expect_exit 75 deploy "$NEW_PREFIX"

check_current 5

cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

test ! -e "$PENDING"

echo "PASS"

echo

# Phase 13: Simulate abrupt deployment process termination.
# The actual deployment installer remains unchanged.

echo
echo "TEST 13: Termination before recovery journal creation"

export MOCK_INTERRUPT=BEFORE_JOURNAL

expect_exit 137 deploy "$BAD_PREFIX"

unset MOCK_INTERRUPT

check_current 5

cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

test ! -e "$STATE_DIR/pending-deployment.json"

echo "PASS"

# Phase 14: Interrupt at each critical transaction boundary.
# The next invocation must recover the previous application
# and must not report the new candidate as deployed.

TEST_NUMBER=14

for boundary in AFTER_JOURNAL AFTER_INSTALL AFTER_RESTART
do
    echo
    echo "TEST $TEST_NUMBER: Recover interruption at $boundary"

    cp "$STATE_DIR/state.json" "$SANDBOX/expected-state.json"

    export MOCK_INTERRUPT="$boundary"

    expect_exit 137 deploy "$BAD_PREFIX"

    unset MOCK_INTERRUPT

    test -s "$STATE_DIR/pending-deployment.json"

    expect_exit 75 deploy "$NEW_PREFIX"

    check_current 5

    cmp "$MOCK_APP/app.jar" "$SANDBOX/expected-active.jar"

    cmp "$STATE_DIR/state.json" "$SANDBOX/expected-state.json"

    test ! -e "$STATE_DIR/pending-deployment.json"

    echo "PASS"

    TEST_NUMBER=$((TEST_NUMBER + 1))
done

echo
echo "ALL ORCHESTRATION REGRESSION TESTS PASSED"
