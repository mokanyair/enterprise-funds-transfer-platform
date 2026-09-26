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

1. Start `funds-transfer-service` (needs Oracle + a real OIDC issuer — see its own README).
   It has no CORS configuration by default; set `FUNDS_CORS_ALLOWED_ORIGINS=http://localhost:5173`
   (or your deployed origin) in its environment, otherwise the browser will block every request.
2. Set up a Keycloak realm/client for the SPA (public client, Authorization Code + PKCE, no
   client secret) and fill in `VITE_KEYCLOAK_*` in `.env.local`.
3. Set:
   ```
   VITE_API_URL=http://localhost:8080/api/v1
   VITE_AUTH_MODE=keycloak
   VITE_USE_MOCKS=false
   ```
4. `npm run dev`.

The Keycloak integration (`src/auth/KeycloakAuthProvider.tsx`) has not been exercised end to
end — there is no Keycloak instance in the environment this was built in. Verify the redirect
URI, web origins, and realm/client settings against a real IdP before relying on it.

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
(see above). The Vitest suite covers the transfer flow (validation, review, idempotency key
reuse on retry, duplicate-submit guard, business rejections), masking, and loading/error states.
