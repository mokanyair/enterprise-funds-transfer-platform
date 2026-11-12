#!/usr/bin/env bash
set -euo pipefail
: "${AWS_REGION:=us-east-1}"
: "${ARTIFACT_BUCKET:=eftabankingnvddatavdc}"
: "${GIT_COMMIT:?GIT_COMMIT required}"
: "${BUILD_NUMBER:?BUILD_NUMBER required}"
ARTIFACT_DIR=application/funds-transfer-service/target
JAR="$ARTIFACT_DIR/funds-transfer-service-1.0.2-SNAPSHOT.jar"
[[ -s "$JAR" ]] || { echo "Missing executable JAR: $JAR" >&2; exit 1; }
[[ "$GIT_COMMIT" =~ ^[a-f0-9]{40}$ ]] || { echo 'Invalid full Git commit SHA' >&2; exit 1; }
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || { echo 'Invalid build number' >&2; exit 1; }
PREFIX="jenkins-artifacts/banking-app/test/releases/${GIT_COMMIT}/build-${BUILD_NUMBER}"
mkdir -p .ci-release
cp "$JAR" .ci-release/app.jar
(cd .ci-release && sha256sum app.jar > app.jar.sha256)
python3 - "$GIT_COMMIT" "$BUILD_NUMBER" <<'PY'
import json,sys,datetime,hashlib
with open('.ci-release/app.jar','rb') as f:
    hasher = hashlib.sha256()
    for chunk in iter(lambda: f.read(1024 * 1024), b''):
        hasher.update(chunk)
    digest = hasher.hexdigest()
with open('.ci-release/manifest.json','w') as f:
    json.dump({'commit':sys.argv[1],'build_number':sys.argv[2], 'sha256':digest,
               'published_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()},f,indent=2)
PY
# Conditional PutObject prevents overwriting existing keys, including on non-versioned buckets.
# On partial failure, retry with a new build number; never mutate an existing release.
for name in app.jar app.jar.sha256 manifest.json; do
  aws s3api put-object --region "$AWS_REGION" --bucket "$ARTIFACT_BUCKET" \
    --key "$PREFIX/$name" --body ".ci-release/$name" \
    --if-none-match '*' --server-side-encryption AES256 >/dev/null
done
printf '%s\n' "$PREFIX" > .ci-release/release-prefix.txt
printf 'Published s3://%s/%s\n' "$ARTIFACT_BUCKET" "$PREFIX"
