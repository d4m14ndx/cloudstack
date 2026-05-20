<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# CloudStack Web — Phase 5 UI

Next-generation Apache CloudStack web console. Built on Next.js 14 (App Router), TypeScript, Tailwind 4, and a modern infrastructure-console aesthetic (Linear / Vercel / Tailscale influence).

**Status:** Phase 5b auth foundation — design system + shell + 17 route stubs, Auth.js v5 provider wiring, Redis-backed session adapter foundation, and mock-friendly current-user compatibility. `/api/cs/*` BFF proxy wiring lands in the companion Phase 5b slices. See `/Users/damian/Claude/PHASE5-PLAN.md` for the roadmap.

This lives alongside the legacy Vue UI at `../ui/`. Both can ship simultaneously. The legacy UI is abandoned-in-place and will be deprecated once feature parity is reached.

## Getting started

```bash
cp .env.example .env.local        # auth variables optional for local mock mode
npm install
npm run dev
# open http://localhost:3000
```

For Phase 5b local Auth.js/BFF dependencies, use the root compose stack:

```bash
docker compose up --build
docker compose --profile auth up --build   # also starts local Authentik
```

See `../docs/phase5b-local-dev.md` for Redis, Authentik, and CloudStack service-account environment setup.

Local development still builds without Authentik or Redis secrets. When no identity provider is configured, the shell keeps using the Phase 5a mock user bridge so UI work can continue. Production runtime fails fast if required Auth.js, Authentik, or Redis variables are missing.

## Auth configuration

Auth.js v5 is configured in `auth.ts` with Authentik as the primary OIDC provider and Microsoft Entra ID as an optional secondary provider. The Auth.js route is mounted at `/api/auth/[...nextauth]`.

Required production variables:

| Variable | Purpose |
|----------|---------|
| `NEXTAUTH_URL` | Public web origin, for example `https://cloud.example.com` |
| `NEXTAUTH_SECRET` | Auth.js secret; generate with `openssl rand -hex 32` |
| `AUTHENTIK_ISSUER` | Authentik OIDC issuer, for example `https://auth.example.com/application/o/cloudstack` |
| `AUTHENTIK_CLIENT_ID` | Authentik OAuth client ID |
| `AUTHENTIK_CLIENT_SECRET` | Authentik OAuth client secret |
| `REDIS_URL` | Redis URL for database sessions, for example `redis://redis:6379` |

Optional Entra ID variables:

| Variable | Purpose |
|----------|---------|
| `AUTH_MICROSOFT_ENTRA_ID_ID` | Entra application client ID |
| `AUTH_MICROSOFT_ENTRA_ID_SECRET` | Entra client secret |
| `AUTH_MICROSOFT_ENTRA_ID_ISSUER` | Tenant issuer; defaults to common when omitted |

Existing client shell components intentionally import `@/lib/auth/mock` for Phase 5a compatibility. Server code that needs the real Auth.js session should import `getCurrentUser()` or `hasRole()` from `@/lib/auth/server`.

## Scripts

| Script             | Purpose                                              |
|--------------------|------------------------------------------------------|
| `npm run dev`      | Dev server on port 3000                              |
| `npm run build`    | Production build (standalone output for Docker)      |
| `npm run start`    | Run the production build                             |
| `npm run lint`     | ESLint (next/core-web-vitals)                        |
| `npm run typecheck`| `tsc --noEmit` — strict mode, no unchecked indexes   |

Note: `experimental.typedRoutes` is intentionally disabled for Phase 5a.

## BFF API proxy

Phase 5b adds the server-side `/api/cs/[command]` proxy foundation. Browser code calls same-origin URLs such as `/api/cs/listVirtualMachines?listall=true`; the route strips any client-supplied `sessionkey`, forces `response=json`, attaches the server-side CloudStack session key from the BFF session, and forwards to `${CS_URL}/client/api`.

Required runtime variables for real CloudStack use:

```bash
CS_URL=http://cloudstack-mgmt:8080
CS_SERVICE_APIKEY=<service-account-api-key>
CS_SERVICE_SECRETKEY=<service-account-secret-key>
BFF_SESSION_TTL_SECONDS=28800
CS_SESSION_REFRESH_MARGIN_SECONDS=120
```

The Auth.js foundation includes provider wiring and a Redis-backed Auth.js adapter. The CloudStack BFF proxy still keeps its own session lookup boundary for the server-side CloudStack session key; production requests without a `cloudstack.session` BFF cookie return `401`; local development can opt into a mock BFF session with `BFF_DEV_SESSION=true` or `NEXT_PUBLIC_APP_ENV=dev`.

## Stack

| Layer        | Choice                              |
|--------------|-------------------------------------|
| Framework    | Next.js 14 App Router               |
| Language     | TypeScript (strict)                 |
| Styling      | Tailwind CSS 4 + CSS custom props   |
| State (client)| Zustand                            |
| State (server)| TanStack Query *(Phase 5c)*        |
| Auth         | Auth.js v5 / NextAuth               |
| Session store| Redis via a local Auth.js adapter foundation |
| Icons        | Lucide                              |
| Components   | Radix primitives + hand-rolled UI   |
| Command palette | cmdk                             |
| Fonts        | Geist Sans + Geist Mono             |

## Directory layout

```
app/
  layout.tsx               root layout (theme provider + Geist font)
  globals.css              design tokens + base styles
  login/                   login page (no shell, Auth.js provider links)
  (app)/                   authenticated shell wrapper
    layout.tsx             sidebar + topbar + ⌘K + tweaks panel
    page.tsx               Overview / dashboard
    instances/             list + detail
    networks/              list + detail
    volumes, templates, kubernetes, events, accounts,
    ssh-keys, security, infrastructure, domains,
    billing, settings/     remaining 11 routes (stubs in 5a)
components/
  ui/                      design primitives (Button, Card, Badge, ...)
  shell/                   sidebar, topbar, scope switcher, ⌘K palette
  icons.tsx                Lucide re-exports aliased to design names
  page-header.tsx          page-title + stub helpers
  theme-provider.tsx       data-theme / data-density / --accent binding
  tweaks-panel.tsx         floating dev-tool for theme / accent / density
lib/
  utils.ts                 cn(), formatBytes, relativeTime
  nav.ts                   sidebar nav config
  store/tweaks.ts          Zustand store (persisted)
  auth/mock.ts             client-safe mock current user bridge
  auth/server.ts           server-side Auth.js current user helpers
```

## Design tokens

Ported verbatim from `../new-ui/design_handoff_cloudstack_ui/styles.css`. Defined in `app/globals.css` as CSS custom properties under `:root` / `[data-theme="dark"]` blocks. Tweakable at runtime (theme, accent color, density, sidebar style) via the floating tweaks panel.

| Token         | Light  | Dark    |
|---------------|--------|---------|
| `--bg`        | `#fafaf9` | `#0b0c0f` |
| `--surface`   | `#ffffff` | `#131418` |
| `--fg`        | `#14151a` | `#f5f5f4` |
| `--border`    | `#e5e5e2` | `#25272d` |
| `--accent`    | `#5b5bf5` (electric indigo, tweakable) |

Full palette + sizing + radius + typography tokens in `app/globals.css`.

## Phase 5a acceptance criteria

- [x] `npm install && npm run dev` starts on `http://localhost:3000`
- [x] Visiting `/` renders the Overview route stub inside the shell
- [x] Sidebar navigation routes between all 17 screens
- [x] Theme toggle in topbar swaps light ↔ dark instantly (no flash)
- [x] ⌘K / Ctrl+K opens command palette with mock results
- [x] Tweaks panel changes accent color + density across all screens
- [x] `npm run build` succeeds with standalone output
- [x] `docker build -t cloudstack-web ./web` succeeds
- [x] Zero TypeScript errors with strict + `noUncheckedIndexedAccess`
- [x] ESLint clean

## What's next

- **Phase 5b — BFF:** wire `/api/cs/*` proxy and CloudStack sessionkey exchange against the Java companion API
- **Phase 5b-java — CloudStack `createUserSessionToken` API:** the Java-side companion that enables sessionkey exchange (planned in `../PHASE5B-JAVA-API.md`)
- **Phase 5c-d:** real screens (Dashboard, Instances, Deploy Wizard, etc.)
- **Phase 5e:** i18n, a11y, polish
- **Phase 5f:** production cutover from legacy UI

See `/Users/damian/Claude/PHASE5A-SCAFFOLD.md` and `/Users/damian/Claude/PHASE5-BFF-ARCHITECTURE.md` for full specs.
