# Phase 5b Local Auth and BFF Development

This stack adds the local services needed by the Phase 5b Auth.js/BFF work without wiring application code in this slice.

## Services

- `redis`: Redis 7 Alpine, enabled by default for BFF session storage.
- `authentik-postgres`: Authentik's local PostgreSQL database, enabled by the `auth` profile.
- `authentik-server`: local Authentik UI and OIDC issuer, enabled by the `auth` profile.
- `authentik-worker`: Authentik background worker, enabled by the `auth` profile.

## Run the Stack

Start the existing CloudStack/MySQL/web stack plus Redis:

```bash
docker compose up --build
```

Start the local Authentik services as well:

```bash
docker compose --profile auth up --build
```

URLs:

- CloudStack legacy UI: `http://localhost:8080/client`
- Phase 5 web UI: `http://localhost:3000`
- Authentik local dev UI: `http://authentik.localhost:9000`
- Redis from the host: `redis://localhost:6379`
- Redis from compose services: `redis://redis:6379`

## Web Environment

For host-based Next.js development, copy `web/.env.example` to `web/.env.local` and fill the Phase 5b values when the Auth.js/BFF code lands:

```dotenv
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=replace-me-with-output-of-openssl-rand-hex-32
REDIS_URL=redis://localhost:6379
AUTHENTIK_ISSUER=http://localhost:9000/application/o/cloudstack/
AUTHENTIK_CLIENT_ID=cloudstack-bff
AUTHENTIK_CLIENT_SECRET=replace-me-with-authentik-provider-secret
CS_URL=http://localhost:8080
CS_SERVICE_APIKEY=replace-me-with-local-service-account-api-key
CS_SERVICE_SECRETKEY=replace-me-with-local-service-account-secret-key
BFF_SESSION_TTL_SECONDS=28800
CS_SESSION_REFRESH_MARGIN_SECONDS=120
```

Inside `docker compose`, the `web` service already receives container-network defaults:

- `REDIS_URL=redis://redis:6379`
- `CS_URL=http://cloudstack:8080`
- `AUTHENTIK_ISSUER=http://authentik.localhost:9000/application/o/cloudstack/`

The compose Authentik issuer uses `authentik.localhost` so browser redirects resolve to the host port while the `web` container resolves the same name to the Authentik service through a compose network alias. If you run Next.js directly on the host, `http://localhost:9000/application/o/cloudstack/` is also fine as long as the Authentik provider/application is created with the same issuer host.

## Manual Authentik Setup

Compose starts Authentik, but the local OIDC application/provider still needs to be created in the Authentik UI.

1. Open `http://authentik.localhost:9000`.
2. Complete Authentik's first-run admin setup if the local volume is empty.
3. Create an OAuth2/OpenID provider:
   - Name: `CloudStack BFF`
   - Client ID: `cloudstack-bff`
   - Client secret: copy into `AUTHENTIK_CLIENT_SECRET`
   - Redirect URI: `http://localhost:3000/api/auth/callback/authentik`
   - Signing key: Authentik's default generated signing key is fine for local dev.
4. Create an application:
   - Name: `CloudStack`
   - Slug: `cloudstack`
   - Provider: `CloudStack BFF`
5. Confirm the issuer URL is `http://authentik.localhost:9000/application/o/cloudstack/` for compose-based web development, or `http://localhost:9000/application/o/cloudstack/` for host-based Next.js development.

Service-account API credentials for `CS_SERVICE_APIKEY` and `CS_SERVICE_SECRETKEY` remain a CloudStack-side manual step until the Java/API slice lands.

## Local Secrets

The compose file includes insecure local defaults so `docker compose config` and first boot are easy. Do not reuse these defaults outside local development:

- `NEXTAUTH_SECRET`
- `AUTHENTIK_SECRET_KEY`
- `AUTHENTIK_POSTGRES_PASSWORD`
- `AUTHENTIK_CLIENT_SECRET`
- CloudStack service API keys
