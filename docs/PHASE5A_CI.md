# Phase 5a CI: Two-image build (UI + API split)

This document describes the two new GitHub Actions workflows added as part
of Phase 5a ("UI split") of the CloudStack microservices roadmap, what they
produce, what remains before they are production-ready, and how to reproduce
the same build locally.

## What the workflows produce

| Workflow | File | Image | Trigger paths |
|---|---|---|---|
| `Phase 5a – UI image` | `.github/workflows/ui-image.yml` | `ghcr.io/<owner>/cloudstack-ui` | `ui/**` |
| `Phase 5a – API/management image` | `.github/workflows/api-image.yml` | `ghcr.io/<owner>/cloudstack-management` | everything except `ui/**` |

### Image anatomy

**`cloudstack-ui`** — built from `ui/Dockerfile`:
- Stage 1 (`node:20-bookworm`): `npm install && npm run build` → `ui/dist/`
- Stage 2 (`nginx:alpine`): serves the static SPA; proxies `/client/*` to the
  management server via `ui/nginx/default.conf`.

**`cloudstack-management`** — built from the root `Dockerfile`:
- Stage 1 (`eclipse-temurin:21-jdk-noble`): full Maven build via
  `mvn -B -ntp install -DskipTests -P developer,systemvm`.
- Stage 2 (`eclipse-temurin:21-jre-noble`): minimal JRE runtime, `tini` init,
  drops to `cloud` (uid 1000) user, exposes 8080 + 8443.
- **UI assets are NOT bundled** into this image — that is the whole point of
  Phase 5a.

### Tagging scheme

Both workflows use `docker/metadata-action` to produce:
- `<branch>-<sha7>` on every push/PR (e.g. `modernize-2026-abc1234`)
- `<semver>` from Git tags when present
- `latest` only on pushes to `main`

### PR vs merge behaviour

Each workflow has two distinct blocks:
1. **Pre-merge (PR)** — image is built and layers are cached in the GHA
   cache, but `push: false`. No registry credentials are needed or used.
2. **Post-merge (push)** — same build, but `push: true` and the GHCR login
   step is enabled.  Requires `GHCR_TOKEN` secret (see below).

This avoids the common failure mode of PRs from forks triggering pushes
without access to registry secrets.

## What still has to be done before production

1. **Registry credentials**
   - Create a GitHub personal access token (classic) or a fine-grained token
     with `write:packages` scope.
   - Add it as a repository secret named `GHCR_TOKEN`.
   - Replace the placeholder org in `IMAGE_NAME` env vars with your actual
     GitHub org/user name (or use `${{ github.repository }}` if your repo is
     already at the right path).

2. **`.dockerignore` for the API build context**
   - The root `Dockerfile` currently has no `.dockerignore`. Add one that
     excludes `ui/` so the multi-GB `ui/node_modules/` directory is never
     sent to the Docker daemon.
   - Recommended entries: `ui/node_modules`, `ui/dist`, `.git`, `target/`.

3. **`ui/Dockerfile` Node upgrade**
   - The committed `ui/Dockerfile` uses `node:14-bullseye` (EOL).
   - The workflow passes `--build-arg NODE_BASE=node:20-bookworm` as a
     workaround, but the Dockerfile does not currently consume that arg.
   - TODO: add `ARG NODE_BASE=node:20-bookworm` and change the `FROM` line
     to `FROM ${NODE_BASE} AS build`, then drop the build-arg from the workflow.

4. **Image vulnerability scanning**
   - Add `aquasecurity/trivy-action` (or equivalent) as a post-build step in
     both workflows.  Fail on CRITICAL CVEs.
   - Example step to append after the build-push step:
     ```yaml
     - name: Scan image for CVEs
       uses: aquasecurity/trivy-action@0.20.0
       with:
         image-ref: ${{ env.IMAGE_NAME }}:${{ steps.meta.outputs.version }}
         format: table
         exit-code: '1'
         severity: CRITICAL
     ```

5. **SBOM generation**
   - Add `anchore/sbom-action` after each push to produce a CycloneDX or
     SPDX SBOM and attach it to the GitHub release or as an image attestation.

6. **Image signing**
   - Use `sigstore/cosign-installer` + `cosign sign` to add keyless Sigstore
     signatures to every pushed image.  Requires `id-token: write` permission
     in the job.

7. **Multi-platform builds** (optional near-term)
   - The workflows currently target the runner's native architecture (`amd64`).
   - Add `platforms: linux/amd64,linux/arm64` to the build-push step and set
     up a QEMU emulation step (`docker/setup-qemu-action`) for `arm64`.

8. **Helm sub-chart**
   - Per the microservices roadmap, Phase 5a also includes a Helm sub-chart
     that pairs the two images behind a single nginx-ingress (`/` → UI,
     `/client/*` → management).  That is tracked separately and not part of
     this CI draft.

## Running the same build locally

### Prerequisites

- Docker 24+ with BuildKit enabled (`DOCKER_BUILDKIT=1` or Docker Desktop)
- For the UI: Node 20+ and npm 10+
- For the API: JDK 21 + Maven 3.9 (or just Docker — the Dockerfile handles
  the Maven build in its own stage)

### UI image

```bash
# From repo root
docker build \
  --build-arg NODE_BASE=node:20-bookworm \
  -t cloudstack-ui:local \
  ./ui

# Run locally (serves on http://localhost:8080)
# Point it at a running management server:
docker run --rm -p 8080:80 \
  -e CS_BACKEND_URL=http://localhost:8443 \
  cloudstack-ui:local
```

### API / management image

```bash
# From repo root — this will take 10-20 min on first run (full Maven build)
docker build \
  -t cloudstack-management:local \
  .

# Run (requires an external MySQL):
docker run --rm -p 8080:8080 -p 8443:8443 \
  -e DB_HOST=127.0.0.1 \
  -e DB_USER=cloud \
  -e DB_PASSWORD=cloud \
  cloudstack-management:local
```

### Speed up the API build with a local Maven cache

```bash
docker build \
  --cache-from type=local,src=/tmp/docker-cache \
  --cache-to   type=local,dest=/tmp/docker-cache,mode=max \
  -t cloudstack-management:local \
  .
```

## Open questions / TODOs for the team

- [ ] Confirm desired GHCR org namespace (fork repo vs. separate org).
- [ ] Decide whether to keep the `ui/Dockerfile` Node 14 line or upgrade it
      immediately — the workaround build-arg approach is temporary.
- [ ] Agree on the image scanning severity threshold (CRITICAL only vs. HIGH+).
- [ ] Decide signing strategy: keyless Sigstore vs. org-managed key pair.
- [ ] Helm sub-chart ownership: same PR as this CI draft, or a follow-on?
