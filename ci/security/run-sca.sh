
#!/usr/bin/env bash
set -euo pipefail

ROOT="${WORKSPACE:-$PWD}"
REPORTS="$ROOT/reports/sca"
BACKEND="$ROOT/application/funds-transfer-service"
FRONTEND="$ROOT/application/banking-ui"
OSV="$ROOT/.ci-tools/bin/osv-scanner"

# Keep OWASP's H2 database isolated from earlier failed runs.
OWASP_DATA="$ROOT/.ci-tools/dependency-check-data"

mkdir -p "$REPORTS" "$OWASP_DATA"

echo "=== Generate Backend CycloneDX SBOM ==="

mvn -B -ntp -f "$BACKEND/pom.xml" \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DskipAttach=true

test -s "$BACKEND/target/bom.json"
cp "$BACKEND/target/bom.json" "$REPORTS/backend-sbom.json"

run_osv() {
    local name="$1"
    shift

    local report="$REPORTS/osv-${name}.json"
    local rc=0

    "$OSV" scan "$@" \
      --format json \
      --output-file "$report" || rc=$?

    case "$rc" in
        0)
            echo "OSV $name: no findings reported"
            ;;
        1)
            echo "OSV $name: findings detected (report-only)"
            ;;
        *)
            echo "OSV $name: scanner error ($rc)" >&2
            return 1
            ;;
    esac

    test -s "$report"

    python3 - "$report" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)

if not isinstance(data.get("results"), list):
    raise SystemExit("Invalid OSV results JSON")

print("OSV result groups:", len(data["results"]))
PY
}

echo "=== OSV Backend ==="
run_osv backend -L "$BACKEND/target/bom.json"

echo "=== OSV Frontend ==="
run_osv frontend --lockfile "$FRONTEND/package-lock.json"

echo "=== OWASP Dependency-Check ==="

: "${NVD_API_KEY:?NVD_API_KEY is required}"

# Apply memory limits only to the OWASP Maven invocation.
# This is an initial setting for the existing 4 GiB EC2 agent.
OWASP_MAVEN_OPTS="-Xms512m -Xmx2048m -XX:+ExitOnOutOfMemoryError"

echo "OWASP version: 13.0.0"
echo "OWASP JVM maximum heap: 2048 MiB"
echo "NVD API results per page: 1000"

MAVEN_OPTS="$OWASP_MAVEN_OPTS" \
mvn -B -ntp -f "$BACKEND/pom.xml" \
  org.owasp:dependency-check-maven:13.0.0:check \
  -Dformat=ALL \
  -DfailBuildOnCVSS=11 \
  -DfailOnError=true \
  -DdataDirectory="$OWASP_DATA" \
  -DnvdApiKeyEnvironmentVariable=NVD_API_KEY \
  -DnvdApiResultsPerPage=1000 \
  -DossIndexAnalyzerEnabled=false \
  -DassemblyAnalyzerEnabled=false

test -s "$BACKEND/target/dependency-check-report.json"
test -s "$BACKEND/target/dependency-check-report.html"

echo "SCA COMPLETE — vulnerability enforcement disabled"
