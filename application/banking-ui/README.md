# banking-ui

React + TypeScript + Vite frontend for `funds-transfer-service`, built from
`../frontendimplementation` (the NovaBank design & implementation guide). Covers the guide's
Release 1 (MVP) screens — dashboard, accounts, account detail, transfer (form → review →
idempotent submit → status), transaction history/detail, profile and sign-out — wired to the
real backend contract. Login/MFA is delegated entirely to Keycloak and is out of scope for this UI.

## Quick start (mock data, no backend needed)

```bash
npm install
npm run dev
```

Open http://localhost:5173. Copy `.env.example` to `.env.local` first; it sets
`VITE_AUTH_MODE=dev` and `VITE_USE_MOCKS=true`. Mocks are opt-in — a build without
`VITE_USE_MOCKS=true` never includes them. With both set, the login screen offers two demo customers, and
[MSW](https://mswjs.io) intercepts every API call in the browser with realistic fake data
(`src/mocks/`) — full CRUD including idempotency replay, simulated risk review for
transfers over $10,000, and async status progression. Nothing here talks to a real network.

## Running against the real backend

Dev environment identity provider:

| Setting | Value |
|---|---|
| Keycloak server | `http://10.30.11.104:8180` (private VPC address) |
| Realm | `funds-transfer` |
| Issuer | `http://10.30.11.104:8180/realms/funds-transfer` |

1. **Backend** — start `funds-transfer-service` with
   `FUNDS_JWT_ISSUER_URI=http://10.30.11.104:8180/realms/funds-transfer` and
   `FUNDS_CORS_ALLOWED_ORIGINS=http://localhost:5173` (or your deployed origin). Every
   Keycloak user who signs in needs a row in `APP_USERS` whose `IDP_SUBJECT` is their token
   `sub`, or the API answers 401.
2. **Keycloak client** — in realm `funds-transfer`, a client `banking-web`: public (client
   authentication off), Standard flow on, Direct access grants off, PKCE method S256, valid
   redirect URI `http://localhost:5173/*`, web origin `http://localhost:5173`.
3. **Stable issuer** — the token's `iss` must equal `FUNDS_JWT_ISSUER_URI` exactly. If you
   reach Keycloak through a tunnel/port-forward (e.g. as `localhost:8180`), set Keycloak's
   `KC_HOSTNAME=http://10.30.11.104:8180` so it still issues that address.
4. **`.env.local`**:
   ```
   VITE_API_URL=http://localhost:8080/api/v1
   VITE_AUTH_MODE=keycloak
   VITE_USE_MOCKS=false
   VITE_KEYCLOAK_URL=http://10.30.11.104:8180
   VITE_KEYCLOAK_REALM=funds-transfer
   VITE_KEYCLOAK_CLIENT_ID=banking-web
   ```
   `VITE_KEYCLOAK_URL` is the server root; keycloak-js adds `/realms/<realm>/…` itself.
5. `npm run dev`, then sign in through Keycloak.

### Real end-to-end check

`scripts/e2e-smoke.sh` runs one real transfer through Keycloak → API → Oracle and checks
issuer match, 401 without a token, CORS preflight, idempotent replay (same key → same
transfer, `Idempotent-Replayed: true`), a COMPLETED status, and that the source was debited
exactly once. Run it from inside the VPC (e.g. on the app EC2 via SSM):

```bash
API_URL=http://localhost:8080/api/v1 SRC_ACCOUNT=ACC1001 DST_ACCOUNT=ACC1002 \
KC_CLIENT_ID=<test-client> KC_USERNAME=<test-user> KC_PASSWORD=... ./scripts/e2e-smoke.sh
```

Password grant needs a separate test-only client with Direct access grants on; alternatively
pass `TOKEN=<access token>` instead of the `KC_*` variables.

## Auth, idempotency and account-state behaviour

- **Token refresh** — every request first asks Keycloak for a token valid ≥30 s
  (`updateToken`, one shared in-flight refresh). A 401 triggers one forced refresh and a
  replay of the same request (same `Idempotency-Key`, same `X-Correlation-Id`); if the
  session can't be renewed the user is sent to sign in once.
- **Ambiguous transfer failures** — the key and exact body are saved to `sessionStorage`
  *before* the POST. On timeout/network error/5xx the UI keeps that key, blocks a fresh submit
  and offers "Retry same transfer". After a reload or sign-in redirect the attempt is resumed
  with the identical key and body, so the backend replays rather than re-executes.
  `IDEMPOTENCY_IN_PROGRESS` is retried automatically honouring `Retry-After`;
  `IDEMPOTENCY_KEY_EXPIRED` follows `Location` to the original transfer. A saved attempt is
  scoped to the signed-in user and cleared on sign-out.
- **Frozen/closed accounts** — never offered as a transfer source, `?from=` pointing at one
  is ignored, and their detail page shows why instead of a transfer button.
- **Cancel** — requires an explicit confirmation showing amount and destination; on success
  balances and history are refetched; on `TRANSFER_NOT_CANCELLABLE` the real status is reloaded.

## What the backend actually exposes

The design guide describes a broader API surface than what's implemented. The real contract
(`../funds-transfer-service/src/main/resources/openapi/`) is narrower:

- `GET /accounts` — the caller's accounts (added alongside this frontend; see
  `AccountsController`/`AccountService` in the backend).
- `GET /accounts/{accountId}/balance`, `GET /accounts/{accountId}/transfers` — account-scoped
  reads, paginated, server-side filtered.
- `POST /transfers` (`Idempotency-Key` header), `GET /transfers/{id}`,
  `POST /transfers/{id}/cancel`.

There is no beneficiaries, payments, cards, or statements endpoint, so those modules from the
guide are intentionally not built — the guide itself says not to expose dead menu items for
services that don't exist yet. There's also no cross-account "all transactions" feed; the
Transactions screen picks an account first, then shows that account's filtered history.

## Scripts

| Command | Purpose |
|---|---|
| `npm run dev` | Dev server with HMR |
| `npm run build` | Type-check + production build |
| `npm run typecheck` | Type-check only |
| `npm test` | Vitest + Testing Library suite (MSW-backed, no network) |
| `npm run preview` | Serve the production build locally |
| `npm run verify` | Typecheck → test → build (CI gate; run after `npm ci`) |
| `scripts/e2e-smoke.sh` | Real Keycloak + API + Oracle transfer check (run inside the VPC) |

## Structure

```
src/
├── api/         typed client (types mirror the backend DTOs exactly), axios instance, errors
├── auth/        AuthContext + Dev/Keycloak providers + ProtectedRoute
├── components/  design-system primitives (Button classes live in styles/global.css), AppShell
├── features/    one folder per screen area (dashboard, accounts, transfers, transactions, login)
├── hooks/       TanStack Query hooks per resource
├── lib/         env config + display formatting (account masking, currency)
├── mocks/       MSW handlers + in-memory fake data (dev/test only, excluded unless VITE_USE_MOCKS=true)
├── test/        Vitest setup, MSW node server, render helper
└── styles/      design tokens (tokens.css) + global stylesheet
```

## Design system

Tokens in `src/styles/tokens.css` implement the guide's Part I §3 palette/typography exactly
(background `#FAFAFA`, accent `#FD2F02`, etc.), with a responsive shell (persistent sidebar
≥1024px, bottom nav below) per §6.

## Release 1 status

All MVP screens from guide §44 are built except Login/MFA, which is intentionally left to
Keycloak. Two mappings to note:

- **Transaction detail** — the backend's only transaction type is a transfer, so
  `/transfers/:transferId` serves as the transaction detail screen (amount, status, parties,
  confirmation number, submitted and finalised times).
- **Profile** — there is no profile endpoint; `/profile` shows the identity token's name and
  links to the Keycloak account console for password/MFA management.

Transaction filtering is status-only because that's the only filter
`GET /accounts/{id}/transfers` accepts.

## Deferred to later phases

No Playwright E2E suite, accessibility audit, CI/CD pipeline, security headers (CSP/HSTS),
or deployment yet (guide phases 11–13). The Keycloak path is unverified against a real IdP
(see above). `npm audit` reports a moderate react-router advisory (open redirect via
backslash paths; SSR hydration) fixed only in v7 — not exploitable here (no SSR, computed
navigation targets are `encodeURIComponent`-encoded); revisit with a v7 upgrade. The Vitest suite covers the transfer flow (validation, review, idempotency key
reuse on retry, duplicate-submit guard, business rejections), masking, and loading/error states.
