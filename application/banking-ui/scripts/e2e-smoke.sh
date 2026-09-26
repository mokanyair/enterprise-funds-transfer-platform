#!/usr/bin/env bash
# Real end-to-end check: Keycloak -> funds-transfer-service -> Oracle, one transfer.
# Run from a host inside the VPC (e.g. the app EC2 via SSM), with the service running.
#
# Required env:
#   API_URL      e.g. http://localhost:8080/api/v1
#   SRC_ACCOUNT  ACTIVE account owned by the test user (e.g. ACC1001)
#   DST_ACCOUNT  ACTIVE destination account
# Token (one of):
#   TOKEN                         an access token you already have, or
#   KC_CLIENT_ID + KC_USERNAME + KC_PASSWORD
#                                 password grant; needs a *test-only* client with
#                                 "Direct access grants" on. Never enable that on banking-web.
# Optional env:
#   KC_ISSUER    default http://10.30.11.104:8180/realms/funds-transfer
#   UI_ORIGIN    default http://localhost:5173 (checked against the CORS allow-list)
#   AMOUNT       default 1.00
set -euo pipefail

KC_ISSUER="${KC_ISSUER:-http://10.30.11.104:8180/realms/funds-transfer}"
UI_ORIGIN="${UI_ORIGIN:-http://localhost:5173}"
AMOUNT="${AMOUNT:-1.00}"
: "${API_URL:?set API_URL}" "${SRC_ACCOUNT:?set SRC_ACCOUNT}" "${DST_ACCOUNT:?set DST_ACCOUNT}"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

echo "1. Keycloak discovery"
issuer=$(curl --connect-timeout 5 --max-time 30 -fsS "$KC_ISSUER/.well-known/openid-configuration" | json 'd["issuer"]') || fail "discovery unreachable at $KC_ISSUER"
[[ "$issuer" == "$KC_ISSUER" ]] && pass "issuer = $issuer" || fail "issuer '$issuer' != '$KC_ISSUER' (tokens will be rejected by the backend)"

echo "2. Access token"
if [[ -z "${TOKEN:-}" ]]; then
  : "${KC_CLIENT_ID:?set TOKEN or KC_CLIENT_ID/KC_USERNAME/KC_PASSWORD}" "${KC_USERNAME:?}" "${KC_PASSWORD:?}"
  TOKEN=$(curl --connect-timeout 5 --max-time 30 -fsS "$KC_ISSUER/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode "client_id=$KC_CLIENT_ID" \
    --data-urlencode "username=$KC_USERNAME" --data-urlencode "password=$KC_PASSWORD" | json 'd["access_token"]') \
    || fail "token request failed"
fi
token_iss=$(python3 -c "import base64,json,sys; p=sys.argv[1].split('.')[1]; print(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))['iss'])" "$TOKEN")
[[ "$token_iss" == "$KC_ISSUER" ]] && pass "token iss matches" || fail "token iss '$token_iss' != '$KC_ISSUER'"
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "3. Backend authentication"
code=$(curl --connect-timeout 5 --max-time 30 -s -o /dev/null -w '%{http_code}' "$API_URL/accounts")
[[ "$code" == 401 ]] && pass "no token -> 401" || fail "no token -> $code (expected 401)"
accounts=$(curl --connect-timeout 5 --max-time 30 -fsS "${AUTH[@]}" "$API_URL/accounts") || fail "GET /accounts with token failed (is the Keycloak sub mapped in APP_USERS.IDP_SUBJECT?)"
pass "GET /accounts -> $(echo "$accounts" | json 'len(d["accounts"])') account(s)"

echo "4. CORS preflight from $UI_ORIGIN"
allow=$(curl --connect-timeout 5 --max-time 30 -s -o /dev/null -D - -X OPTIONS "$API_URL/transfers" -H "Origin: $UI_ORIGIN" \
  -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: authorization,content-type,idempotency-key" \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')
[[ "$allow" == "$UI_ORIGIN" ]] && pass "origin allowed" || fail "origin not allowed (set FUNDS_CORS_ALLOWED_ORIGINS=$UI_ORIGIN)"

balance() { curl --connect-timeout 5 --max-time 30 -fsS "${AUTH[@]}" "$API_URL/accounts/$1/balance" | json 'd["availableBalance"]'; }
before=$(balance "$SRC_ACCOUNT")

echo "5. Transfer $AMOUNT $SRC_ACCOUNT -> $DST_ACCOUNT"
KEY=$(python3 -c 'import uuid; print(uuid.uuid4())')
BODY=$(printf '{"sourceAccountId":"%s","destinationAccountId":"%s","amount":"%s","currency":"USD","reference":"e2e-smoke"}' "$SRC_ACCOUNT" "$DST_ACCOUNT" "$AMOUNT")
post() { curl --connect-timeout 5 --max-time 30 -sS -D "$1" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" "${AUTH[@]}" -d "$BODY" "$API_URL/transfers"; }
first=$(post /tmp/e2e-h1); id=$(echo "$first" | json 'd["transferId"]') || fail "create failed: $first"
pass "created $id (status $(echo "$first" | json 'd["status"]'))"

echo "6. Idempotent replay (same key, same body)"
second=$(post /tmp/e2e-h2)
[[ "$(echo "$second" | json 'd["transferId"]')" == "$id" ]] && pass "same transferId returned" || fail "replay returned a different transfer"
grep -qi '^idempotent-replayed: *true' /tmp/e2e-h2 && pass "Idempotent-Replayed: true" || fail "replay header missing"

echo "7. Wait for a terminal status"
for _ in $(seq 1 30); do
  status=$(curl --connect-timeout 5 --max-time 30 -fsS "${AUTH[@]}" "$API_URL/transfers/$id" | json 'd["status"]')
  case "$status" in COMPLETED|REJECTED|FAILED|CANCELLED) break ;; esac
  sleep 2
done
[[ "$status" == COMPLETED ]] && pass "status COMPLETED" || fail "final status $status"

after=$(balance "$SRC_ACCOUNT")
echo "8. Source available balance: $before -> $after (expected -$AMOUNT)"
python3 -c "from decimal import Decimal as D; import sys; sys.exit(0 if D('$before')-D('$after')==D('$AMOUNT') else 1)" \
  && pass "debited exactly once" || fail "balance moved by $(python3 -c "from decimal import Decimal as D; print(D('$before')-D('$after'))")"

echo
echo "E2E smoke passed. Transfer $id — check TRANSFERS, LEDGER_ENTRIES and OUTBOX_EVENT rows for it in Oracle."
