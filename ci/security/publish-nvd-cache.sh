
#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="13.0.0"
BUCKET="eftabankingnvddatavdc"
PREFIX="jenkins-cache/dependency-check/v13"
REGION="us-east-1"

ROOT="${WORKSPACE:-$PWD}"
WORK_DIR="$ROOT/.nvd-publisher"
DATA_DIR="$WORK_DIR/data"
OUTPUT_DIR="$WORK_DIR/output"
LATEST_KEY="$PREFIX/latest.json"

for cmd in mvn aws tar sha256sum python3; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "ERROR: Missing command: $cmd" >&2
        exit 1
    }
done

: "${NVD_API_KEY:?ERROR: NVD_API_KEY is required}"

mkdir -p "$WORK_DIR" "$OUTPUT_DIR"

# Only clean this job's dedicated database directory.
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"

echo "=== NVD Cache Publisher ==="
echo "Dependency-Check version: $VERSION"
echo "S3 bucket: $BUCKET"
echo "S3 prefix: $PREFIX"

restore_previous_cache() {
    local manifest="$WORK_DIR/previous-latest.json"
    local archive="$WORK_DIR/previous-cache.tar.gz"
    local key expected actual

    echo "=== Check Existing S3 Cache ==="

    if aws s3api head-object \
        --bucket "$BUCKET" \
        --key "$LATEST_KEY" \
        --region "$REGION" >/dev/null 2>&1; then
        echo "Existing release found."
    else
        # Distinguish an absent manifest from an AWS access failure.
        if aws s3api list-objects-v2 \
            --bucket "$BUCKET" \
            --prefix "$LATEST_KEY" \
            --max-keys 1 \
            --region "$REGION" \
            --query 'Contents[].Key' \
            --output text | grep -Fxq "$LATEST_KEY"; then
            echo "ERROR: Manifest exists but cannot be read." >&2
            exit 1
        fi
        echo "No existing release. Initial database build required."
        return 0
    fi

    aws s3 cp \
        "s3://$BUCKET/$LATEST_KEY" \
        "$manifest" \
        --region "$REGION" \
        --only-show-errors

    local parsed
    parsed=$(python3 - "$manifest" "$VERSION" "$PREFIX" <<'PY'
import json
import re
import sys

path, version, prefix = sys.argv[1:]

with open(path, encoding="utf-8") as file:
    manifest = json.load(file)

key = manifest.get("key", "")
digest = manifest.get("sha256", "")

pattern = (
    re.escape(prefix)
    + r"/releases/nvd-cache-"
    + re.escape(version)
    + r"-[0-9]{8}T[0-9]{6}Z\.tar\.gz"
)

if manifest.get("dependency_check_version") != version:
    raise SystemExit("ERROR: Dependency-Check version mismatch")

if not re.fullmatch(pattern, key):
    raise SystemExit("ERROR: Invalid S3 archive key")

if not re.fullmatch(r"[a-f0-9]{64}", digest):
    raise SystemExit("ERROR: Invalid SHA-256 digest")

print(key, digest)
PY
)

    read -r key expected <<< "$parsed"

    aws s3 cp \
        "s3://$BUCKET/$key" \
        "$archive" \
        --region "$REGION" \
        --only-show-errors

    actual=$(sha256sum "$archive" | awk '{print $1}')

    if [[ "$actual" != "$expected" ]]; then
        echo "ERROR: Restored cache checksum mismatch." >&2
        exit 1
    fi

    validate_archive "$archive"

    tar -xzf "$archive" \
        -C "$WORK_DIR" \
        --no-same-owner \
        --no-same-permissions

    if ! find "$DATA_DIR" -type f -name '*.mv.db' \
        -size +0c -print -quit | grep -q .; then
        echo "ERROR: Restored H2 database missing." >&2
        exit 1
    fi

    echo "PASS: Existing NVD database restored from S3."
}

validate_archive() {
    python3 - "$1" <<'PY'
import sys
import tarfile

archive = sys.argv[1]

with tarfile.open(archive, "r:gz") as tar:
    members = tar.getmembers()

    if not members:
        raise SystemExit("ERROR: Empty archive")

    for member in members:
        name = member.name
        parts = name.split("/")

        if (
            name.startswith("/")
            or ".." in parts
            or not (name == "data" or name.startswith("data/"))
            or not (member.isfile() or member.isdir())
        ):
            raise SystemExit("ERROR: Unsafe archive member: " + name)

print("PASS: Archive structure validated")
PY
}

restore_previous_cache

echo "=== Prepare Maven Project ==="

cat > "$WORK_DIR/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.banking.security</groupId>
    <artifactId>nvd-cache-publisher</artifactId>
    <version>1.0.0</version>
</project>
POM

# Preserve the existing publisher JVM configuration.
export MAVEN_OPTS="-Xms512m -Xmx2560m -XX:+ExitOnOutOfMemoryError"

echo "=== Update NVD Database ==="

mvn -B -ntp \
    -f "$WORK_DIR/pom.xml" \
    "org.owasp:dependency-check-maven:${VERSION}:update-only" \
    -DdataDirectory="$DATA_DIR" \
    -DnvdApiKeyEnvironmentVariable=NVD_API_KEY \
    -DnvdApiResultsPerPage=1000 \
    -DfailOnError=true

echo "=== Validate H2 Database ==="

if ! find "$DATA_DIR" -type f -name '*.mv.db' \
    -size +0c -print -quit | grep -q .; then
    echo "ERROR: NVD H2 database not generated." >&2
    exit 1
fi

du -sh "$DATA_DIR"

echo "=== Offline Database Smoke Test ==="

cat > "$WORK_DIR/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.banking.security</groupId>
    <artifactId>nvd-cache-smoke</artifactId>
    <version>1.0.0</version>
    <dependencies>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
            <version>3.12.0</version>
        </dependency>
    </dependencies>
</project>
POM

mvn -B -ntp \
    -f "$WORK_DIR/pom.xml" \
    "org.owasp:dependency-check-maven:${VERSION}:check" \
    -DdataDirectory="$DATA_DIR" \
    -DautoUpdate=false \
    -DfailBuildOnCVSS=11 \
    -DfailOnError=true \
    -DossIndexAnalyzerEnabled=false \
    -DassemblyAnalyzerEnabled=false \
    -Dformat=JSON

test -s "$WORK_DIR/target/dependency-check-report.json" || {
    echo "ERROR: Offline smoke scan did not produce a report." >&2
    exit 1
}

echo "PASS: Offline Dependency-Check scan."

echo "=== Package Database ==="

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE_NAME="nvd-cache-${VERSION}-${STAMP}.tar.gz"
ARCHIVE="$OUTPUT_DIR/$ARCHIVE_NAME"
RELEASE_KEY="$PREFIX/releases/$ARCHIVE_NAME"

tar -C "$WORK_DIR" -czf "$ARCHIVE" data

validate_archive "$ARCHIVE"

SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"

echo "=== Upload Immutable Release ==="

# Do not overwrite a release with the same timestamp.
if aws s3api head-object \
    --bucket "$BUCKET" \
    --key "$RELEASE_KEY" \
    --region "$REGION" >/dev/null 2>&1; then
    echo "ERROR: Release key already exists." >&2
    exit 1
fi

aws s3 cp \
    "$ARCHIVE" \
    "s3://$BUCKET/$RELEASE_KEY" \
    --region "$REGION" \
    --only-show-errors

echo "=== Verify S3 Readback ==="

READBACK="$OUTPUT_DIR/readback-$ARCHIVE_NAME"

aws s3 cp \
    "s3://$BUCKET/$RELEASE_KEY" \
    "$READBACK" \
    --region "$REGION" \
    --only-show-errors

READBACK_SHA="$(sha256sum "$READBACK" | awk '{print $1}')"

if [[ "$READBACK_SHA" != "$SHA256" ]]; then
    echo "ERROR: Published archive checksum mismatch." >&2
    exit 1
fi

validate_archive "$READBACK"

echo "=== Create Release Manifest ==="

MANIFEST="$OUTPUT_DIR/latest.json"

python3 - "$MANIFEST" "$RELEASE_KEY" "$SHA256" "$VERSION" <<'PY'
import datetime
import json
import sys

path, key, digest, version = sys.argv[1:]

manifest = {
    "schema_version": 1,
    "dependency_check_version": version,
    "key": key,
    "sha256": digest,
    "published_at": datetime.datetime.now(
        datetime.timezone.utc
    ).isoformat(),
}

with open(path, "w", encoding="utf-8") as file:
    json.dump(manifest, file, indent=2)
PY

# Publish the manifest LAST.
# A failed earlier step leaves the previous release active.
aws s3 cp \
    "$MANIFEST" \
    "s3://$BUCKET/$LATEST_KEY" \
    --region "$REGION" \
    --only-show-errors

echo "=== PUBLICATION SUCCESSFUL ==="
echo "Release: s3://$BUCKET/$RELEASE_KEY"
echo "Manifest: s3://$BUCKET/$LATEST_KEY"
