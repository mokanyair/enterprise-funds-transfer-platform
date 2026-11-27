#!/usr/bin/env bash
set -Eeuo pipefail

# ============================================================
# Publish immutable full-stack TEST application releases.
#
# Phase 1: Validate release identity and build outputs.
# Phase 2: Package backend and frontend artifacts.
# Phase 3: Generate SHA256 checksums.
# Phase 4: Generate shared release manifest.
# Phase 5: Publish immutable artifacts to S3.
# Phase 6: Publish manifest as release completion marker.
# ============================================================

: "${AWS_REGION:=us-east-1}"
: "${ARTIFACT_BUCKET:=eftabankingnvddatavdc}"
: "${GIT_COMMIT:?GIT_COMMIT required}"
: "${BUILD_NUMBER:?BUILD_NUMBER required}"

JAR="application/funds-transfer-service/target/funds-transfer-service-1.0.2-SNAPSHOT.jar"
FRONTEND="application/banking-ui/dist"
RELEASE_DIR=".ci-release"

echo "PHASE 1: Validate release inputs"

[[ "$GIT_COMMIT" =~ ^[a-f0-9]{40}$ ]] || {
    echo "ERROR: Invalid Git commit SHA" >&2
    exit 1
}

[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || {
    echo "ERROR: Invalid build number" >&2
    exit 1
}

[[ "$(git rev-parse HEAD)" == "$GIT_COMMIT" ]] || {
    echo "ERROR: Release commit differs from checkout" >&2
    exit 1
}

[[ -s "$JAR" ]] || {
    echo "ERROR: Backend JAR missing" >&2
    exit 1
}

[[ -s "$FRONTEND/index.html" && -d "$FRONTEND/assets" ]] || {
    echo "ERROR: Frontend build missing" >&2
    exit 1
}

PREFIX="jenkins-artifacts/banking-app/test/releases/${GIT_COMMIT}/build-${BUILD_NUMBER}"

mkdir -p "$RELEASE_DIR"

# Remove stale completion marker from any previous execution.
rm -f -- "$RELEASE_DIR/release-prefix.txt"

echo "PHASE 2: Package application artifacts"

cp "$JAR" "$RELEASE_DIR/app.jar"

# Reject unexpected filesystem objects before packaging.
UNSAFE="$(find "$FRONTEND" ! -type f ! -type d -print -quit)"

[[ -z "$UNSAFE" ]] || {
    echo "ERROR: Unsupported frontend filesystem object" >&2
    exit 1
}

# Exclude the development mock service worker and source maps.
tar \
    --exclude='./mockServiceWorker.js' \
    --exclude='*.map' \
    -czf "$RELEASE_DIR/frontend.tar.gz" \
    -C "$FRONTEND" .

echo "PHASE 3: Generate artifact checksums"

(
    cd "$RELEASE_DIR"

    sha256sum app.jar > app.jar.sha256

    sha256sum frontend.tar.gz > frontend.tar.gz.sha256

    sha256sum -c app.jar.sha256

    sha256sum -c frontend.tar.gz.sha256
)

echo "PHASE 4: Generate release manifest"

python3 - "$GIT_COMMIT" "$BUILD_NUMBER" <<'PY'
import datetime
import hashlib
import json
import sys
from pathlib import Path

release = Path(".ci-release")

def sha256(path):
    digest = hashlib.sha256()

    with path.open("rb") as stream:
        for chunk in iter(
            lambda: stream.read(1024 * 1024), b""
        ):
            digest.update(chunk)

    return digest.hexdigest()

backend = release / "app.jar"
frontend = release / "frontend.tar.gz"

backend_sha = sha256(backend)
frontend_sha = sha256(frontend)

manifest = {
    "schema_version": 2,
    "commit": sys.argv[1],
    "build_number": sys.argv[2],
    "environment": "test",
    "sha256": backend_sha,
    "published_utc": datetime.datetime.now(
        datetime.timezone.utc
    ).isoformat(),
    "artifacts": {
        "backend": {
            "filename": backend.name,
            "sha256": backend_sha,
            "size_bytes": backend.stat().st_size
        },
        "frontend": {
            "filename": frontend.name,
            "sha256": frontend_sha,
            "size_bytes": frontend.stat().st_size
        }
    }
}

(release / "manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n"
)
PY

echo "PHASE 5: Publish immutable artifacts"

for name in \
    app.jar \
    app.jar.sha256 \
    frontend.tar.gz \
    frontend.tar.gz.sha256
do
    aws s3api put-object \
        --region "$AWS_REGION" \
        --bucket "$ARTIFACT_BUCKET" \
        --key "$PREFIX/$name" \
        --body "$RELEASE_DIR/$name" \
        --if-none-match '*' \
        --server-side-encryption AES256 \
        >/dev/null

    echo "PUBLISHED: $name"
done

echo "PHASE 6: Publish release manifest"

# The manifest is published last.
# A failed publication must use a new Jenkins build number.

aws s3api put-object \
    --region "$AWS_REGION" \
    --bucket "$ARTIFACT_BUCKET" \
    --key "$PREFIX/manifest.json" \
    --body "$RELEASE_DIR/manifest.json" \
    --if-none-match '*' \
    --server-side-encryption AES256 \
    >/dev/null

printf '%s\n' "$PREFIX" > "$RELEASE_DIR/release-prefix.txt"

echo "FULL_STACK_ARTIFACT_PUBLISH_SUCCESS"

echo "Published s3://$ARTIFACT_BUCKET/$PREFIX"
