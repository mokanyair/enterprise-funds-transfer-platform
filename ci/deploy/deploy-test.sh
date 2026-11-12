#!/usr/bin/env bash
set -euo pipefail
: "${AWS_REGION:=us-east-1}"
: "${ARTIFACT_BUCKET:=eftabankingnvddatavdc}"
: "${TEST_INSTANCE_ID:=i-0ded4009e54c9c1cd}"
PREFIX="${1:?Usage: deploy-test.sh release-prefix}"
[[ "$PREFIX" =~ ^jenkins-artifacts/banking-app/test/releases/[a-f0-9]{40}/build-[0-9]+$ ]] || exit 2
# SSM sends only a short, base64-encoded script; never embed passwords or tokens.
SCRIPT_B64="$(base64 -w0 ci/deploy/remote-install.sh)"
export SCRIPT_B64 PREFIX
python3 - <<'PY'
import json,os
b=os.environ['SCRIPT_B64']; p=os.environ['PREFIX']
commands=["set -euo pipefail", "base64 -d > /tmp/banking-test-remote-install.sh <<'SCRIPT64'",b,"SCRIPT64",
          "chmod 700 /tmp/banking-test-remote-install.sh",
          f"bash /tmp/banking-test-remote-install.sh eftabankingnvddatavdc {p} us-east-1"]
with open('.ci-release/ssm-parameters.json','w') as f: json.dump({'commands':commands},f)
PY
COMMAND_ID="$(aws ssm send-command --region "$AWS_REGION" --instance-ids "$TEST_INSTANCE_ID" \
  --document-name AWS-RunShellScript --comment 'Banking TEST release deployment' \
  --parameters file://.ci-release/ssm-parameters.json \
  --timeout-seconds 120 --query 'Command.CommandId' --output text)"
echo "SSM command: $COMMAND_ID"
STATUS=Pending
for i in $(seq 1 80); do
  STATUS="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$TEST_INSTANCE_ID" --query Status --output text 2>/dev/null || true)"
  case "$STATUS" in
    Success) break ;;
    Failed|Cancelled|TimedOut|Cancelling) break ;;
  esac
  sleep 5
done
aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$TEST_INSTANCE_ID" \
  --query '{Status:Status,Details:StatusDetails,Stdout:StandardOutputContent,Stderr:StandardErrorContent}' --output json
[[ "$STATUS" == Success ]]
