#!/usr/bin/env bash
set -euo pipefail
BUCKET="$1"; PREFIX="$2"; REGION="$3"
[[ "$BUCKET" == eftabankingnvddatavdc ]] || exit 2
[[ "$PREFIX" =~ ^jenkins-artifacts/banking-app/test/releases/[a-f0-9]{40}/build-[0-9]+$ ]] || exit 2
[[ "$REGION" == us-east-1 ]] || exit 2
APP_DIR=/opt/funds-transfer
RELEASE_DIR="$APP_DIR/releases/$(echo "$PREFIX" | cut -d/ -f5)-${PREFIX##*/}"
mkdir -p "$RELEASE_DIR"
chmod 750 "$RELEASE_DIR"
for name in app.jar app.jar.sha256 manifest.json; do
  aws s3 cp "s3://$BUCKET/$PREFIX/$name" "$RELEASE_DIR/$name" --region "$REGION" --only-show-errors
done
(cd "$RELEASE_DIR" && sha256sum -c app.jar.sha256)
python3 - "$RELEASE_DIR/manifest.json" "$PREFIX" "$RELEASE_DIR/app.jar" <<'PY'
import json,sys,hashlib,re
m=json.load(open(sys.argv[1])); p=sys.argv[2]
assert m['commit']==p.split('/')[4] and str(m['build_number'])==p.split('/')[5].removeprefix('build-')
assert re.fullmatch(r'[a-f0-9]{64}',m['sha256'])
with open(sys.argv[3],'rb') as f:
    hasher = hashlib.sha256()
    for chunk in iter(lambda: f.read(1024 * 1024), b''):
        hasher.update(chunk)
    assert hasher.hexdigest() == m['sha256']
PY
[[ -s "$APP_DIR/app.jar" ]] || { echo 'Existing app.jar missing; refusing first-time install'; exit 1; }
BACKUP="$APP_DIR/app.jar.pre-deploy.$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$APP_DIR/app.jar" "$BACKUP"
chmod 640 "$BACKUP"
TMP="$APP_DIR/app.jar.deploy.$$"
cp "$RELEASE_DIR/app.jar" "$TMP"
chown root:fundsapp "$TMP"
chmod 640 "$TMP"
mv -f "$TMP" "$APP_DIR/app.jar"
rollback() {
  echo 'Deployment failed; restoring previous JAR'
  cp -p "$BACKUP" "$APP_DIR/app.jar"
  systemctl restart funds-transfer.service || true
}
trap rollback ERR
systemctl restart funds-transfer.service
ok=0
for i in $(seq 1 30); do
  if systemctl is-active --quiet funds-transfer.service && \
     curl -fsS --connect-timeout 2 --max-time 4 http://127.0.0.1:8080/actuator/health >/dev/null; then
    ok=1; break
  fi
  sleep 3
done
[[ "$ok" == 1 ]] || { echo 'Application health failed' >&2; false; }
trap - ERR
printf 'DEPLOY_SUCCESS prefix=%s backup=%s\n' "$PREFIX" "$BACKUP"
