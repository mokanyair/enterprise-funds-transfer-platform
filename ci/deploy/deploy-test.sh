#!/usr/bin/env bash
set -euo pipefail
umask 077

# ============================================================
# Phase 1: Validate deployment target and release identity.
# Phase 2: Package remote installer and required libraries.
# Phase 3: Generate SSM command parameters.
# Phase 4: Execute deployment on TEST.
# Phase 5: Poll and report the final deployment result.
# ============================================================

: "${AWS_REGION:=us-east-1}"
: "${ARTIFACT_BUCKET:=eftabankingnvddatavdc}"
: "${TEST_INSTANCE_ID:=i-0ded4009e54c9c1cd}"

PREFIX="${1:?Usage: deploy-test.sh release-prefix}"

[[ "$AWS_REGION" == us-east-1 ]] || exit 2
[[ "$ARTIFACT_BUCKET" == eftabankingnvddatavdc ]] || exit 2
[[ "$TEST_INSTANCE_ID" == i-0ded4009e54c9c1cd ]] || exit 2

[[ "$PREFIX" =~ ^jenkins-artifacts/banking-app/test/releases/[a-f0-9]{40}/build-[0-9]+$ ]] ||
    exit 2

mkdir -p .ci-release

# Phase 2: Package only the files needed by the remote installer.
tar -czf .ci-release/deployment-bundle.tar.gz \
    -C ci/deploy \
    remote-install.sh \
    lib/deployment-common.sh \
    lib/validate-artifact.py \
    lib/release-state.py

BUNDLE_B64="$(base64 -w0 .ci-release/deployment-bundle.tar.gz)"

BUNDLE_SHA="$(sha256sum     .ci-release/deployment-bundle.tar.gz | awk '{print $1}')"

export BUNDLE_SHA
export BUNDLE_B64 PREFIX AWS_REGION ARTIFACT_BUCKET

# Phase 3: Use a unique temporary directory on the TEST server.
python3 - <<'PY'
import json
import os
import shlex

bundle = os.environ["BUNDLE_B64"]

arguments = " ".join(shlex.quote(value) for value in [
    os.environ["ARTIFACT_BUCKET"],
    os.environ["PREFIX"],
    os.environ["AWS_REGION"],
])

commands = [
    "set -eu",
    'WORK_DIR="$(mktemp -d /tmp/banking-deploy.XXXXXXXX)"',
    """trap 'rm -rf -- "$WORK_DIR"' EXIT""",
    'base64 -d > "$WORK_DIR/bundle.tar.gz" <<\'BUNDLE64\'',
    bundle,
    "BUNDLE64",
    'cd "$WORK_DIR"',
    'printf "%s  bundle.tar.gz\\n" ' +
        shlex.quote(os.environ["BUNDLE_SHA"]) +
        ' | sha256sum -c -',
    'echo "DEPLOYMENT_BUNDLE_INTEGRITY_VERIFIED"',
    'tar -xzf "$WORK_DIR/bundle.tar.gz" -C "$WORK_DIR"',
    'chmod 700 "$WORK_DIR/remote-install.sh"',
    f'bash "$WORK_DIR/remote-install.sh" {arguments}',
]

parameters = {
    "commands": commands,
    "executionTimeout": ["900"],
}

with open(".ci-release/ssm-parameters.json", "w") as stream:
    json.dump(parameters, stream)
PY

# Phase 4: Submit the deployment using the existing SSM document.
COMMAND_ID="$(aws ssm send-command \
    --region "$AWS_REGION" \
    --instance-ids "$TEST_INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --comment 'Banking TEST resilient release deployment' \
    --parameters file://.ci-release/ssm-parameters.json \
    --timeout-seconds 60 \
    --query 'Command.CommandId' \
    --output text)"

echo "SSM command: $COMMAND_ID"

# Phase 5: Poll for a terminal status within Jenkins stage limits.
STATUS=Pending
DEADLINE=$((SECONDS + 1020))

while (( SECONDS < DEADLINE )); do
    STATUS="$(aws ssm get-command-invocation \
        --region "$AWS_REGION" \
        --command-id "$COMMAND_ID" \
        --instance-id "$TEST_INSTANCE_ID" \
        --cli-connect-timeout 3 \
        --cli-read-timeout 5 \
        --query Status \
        --output text 2>/dev/null || true)"

    case "$STATUS" in
        Success|Failed|Cancelled|TimedOut)
            break
            ;;
    esac

    sleep 5
done

aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" \
    --instance-id "$TEST_INSTANCE_ID" \
    --cli-connect-timeout 3 \
    --cli-read-timeout 5 \
    --query '{Status:Status,Details:StatusDetails,ResponseCode:ResponseCode,Stdout:StandardOutputContent,Stderr:StandardErrorContent}' \
    --output json

if [[ "$STATUS" != Success ]]; then
    echo "TEST deployment not successful: ${STATUS:-Unknown}" >&2
    echo "Inspect SSM command $COMMAND_ID before retrying" >&2
    exit 1
fi

echo "TEST_DEPLOYMENT_SUCCESS"
