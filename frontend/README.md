# SDV Frontend (M16A)

React + TypeScript + Vite. Implements the login shell, the honest "준비 중"
Home page, and the ADMIN-only Google Drive connection management screen
(source list, register, connect, disconnect). It does not implement document
search/chat/citations - those are later slices (see the repository root
`docs/plan/SDV_MVP_DEFERRED.md`, MVP-02).

For a repeatable local setup with **real Keycloak login and your own Google
account** (a fully isolated Compose project/ports, never the dev setup
below), see `docs/runbooks/M16A_LOCAL_TESTBED.md` and
`scripts/testbed/start-testbed.ps1` instead of the manual steps in this file.

## Stack choices (M16A)

- **`keycloak-js`** - the OIDC client maintained by the Keycloak project
  itself, used with Authorization Code + PKCE (`pkceMethod: 'S256'`) against
  the existing `sdv` realm. No client secret (public browser client).
- **`react-router-dom`** - routing for `/` (Home) and `/admin/sources`
  (Connection management).
- **`vitest` + `@testing-library/react`** - lightweight UI tests colocated
  with the components/pages they cover (`*.test.tsx`).

Tokens are kept in `keycloak-js`'s own in-memory instance only - never written
to `localStorage`/`sessionStorage`/app state.

## Local setup

1. `npm install`
2. Copy `.env.example` to `.env.local` and adjust if your ports differ. These
   are public OIDC client identifiers, not secrets.
3. `npm run dev` - starts Vite on `http://localhost:5173`. Requests to `/api`
   are proxied to `http://localhost:8080` (the backend) by `vite.config.ts` -
   the browser only ever talks to `http://localhost:5173`, so no backend CORS
   relaxation was needed.

### Exact port/origin layout

| From | To | How |
|---|---|---|
| Browser → backend API (`/api/**`) | `http://localhost:8080` | Vite dev proxy (same-origin from the browser's point of view) |
| Browser → Keycloak (`keycloak-js` calls) | `http://localhost:8180` | Direct, not proxied - Keycloak needs its own CORS (`webOrigins`) for `http://localhost:5173` |
| Keycloak login redirect | back to `http://localhost:5173/*` | `sdv-frontend` client's `redirectUris` |
| Google OAuth consent → backend callback | `http://localhost:8080/api/admin/sources/google/callback` | unchanged from M08 (`GOOGLE_REDIRECT_URI`) |
| Backend callback → frontend return route | `http://localhost:5173/admin/sources?googleConnect=success\|failed` | `GOOGLE_OAUTH_FRONTEND_RETURN_URL` (backend `.env`) |

### Required manual Keycloak setup (not applied by editing the repo file alone)

`infra/keycloak/realm-export.json` now declares a second client, `sdv-frontend`
(public, Authorization Code + PKCE, `redirectUris: ["http://localhost:5173/*"]`,
`webOrigins: ["http://localhost:5173"]`, an `sdv-backend`-audience mapper so
tokens it issues still pass the backend's existing issuer/audience/subject/role
checks unchanged). **Editing this file does not change an already-running
Keycloak container** - re-importing on every start would also reset any other
realm state, so it is not done automatically. Apply it manually once, either:

- **Keycloak Admin Console**: Realm `sdv` → Clients → Create client → Client ID
  `sdv-frontend` → Client authentication **off** (public) → Standard flow
  **on**, Direct access grants **off** → Valid redirect URIs
  `http://localhost:5173/*` → Web origins `http://localhost:5173` → Save, then
  under Advanced set "Proof Key for Code Exchange Code Challenge Method" to
  `S256`, and under Client scopes add a dedicated mapper (Audience) with
  "Included Client Audience" = `sdv-backend`.
- **or** re-import the realm from `infra/keycloak/realm-export.json` into a
  fresh/dev-only Keycloak instance where resetting other realm state is
  acceptable.

This was not applied to any live Keycloak instance by this change - real
browser login is **UNVERIFIED** until you do one of the above and confirm
`http://localhost:8180/realms/sdv` is reachable.

### Backend-side local dev values

The backend's own `.env.example` (repository root) gained two more variables
used only by this frontend integration:

```
GOOGLE_OAUTH_FRONTEND_RETURN_URL=http://localhost:5173/admin/sources
GOOGLE_OAUTH_COOKIE_SECURE=false
```

`GOOGLE_OAUTH_COOKIE_SECURE=false` is a **local-development-only** override
(the OAuth binding cookie is exchanged over plain `http://localhost`); never
set it to `false` outside loopback development.

## Scripts

- `npm run dev` - Vite dev server
- `npm run build` - `tsc -b && vite build`
- `npm run test` - `vitest run`
- `npm run lint` - `eslint .`
- `npm run preview` - preview the production build
