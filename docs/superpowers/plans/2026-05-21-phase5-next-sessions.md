# Phase 5 Next Sessions Plan

## Current Position

Phase 5 has crossed from scaffold into a real CloudStack operator surface. The
Next.js app now has authenticated BFF plumbing, real dashboard and inventory
pages, major detail routes, a guarded Deploy Wizard, and mutation controls for
instances, volumes, SSH keys, templates, security groups, and network public IPs.

The next sessions should turn that breadth into a shippable foundation: browser
coverage for the new mutation surfaces, real console access for instance detail,
and Phase 5e polish for accessibility, internationalisation, and loading states.

## Strategic Goal

Make the Phase 5 UI safe to extend at speed.

That means each new CloudStack surface should have:

- Unit coverage for CloudStack response normalisation and BFF behavior.
- Browser smoke coverage against mocked BFF responses.
- Keyboard and screen-reader sane interactions for dialogs, tabs, menus, and
  destructive actions.
- A clear i18n path that avoids scattering new hard-coded operator copy through
  the app.
- Clean session handoff state: merged branches, pushed `modernize-2026`, updated
  handover, and no abandoned worktrees.

## Session 1: Browser Smoke Harness

### Why First

The repo has strong Node test coverage for the BFF and CloudStack data helpers,
but the new action surfaces are mostly unprotected at the browser level. Before
adding more UI, add a mocked-BFF e2e harness so future slices can prove that the
operator workflows still open, validate, submit, and render status correctly.

### Primary Deliverables

- Add Playwright test infrastructure under `web/`.
- Add a local mocked BFF fixture layer for `/api/cs/*`.
- Add smoke tests for:
  - Deploy Wizard catalog load, advanced option entry, guarded launch submit, and
    async job polling display.
  - Instance list action buttons and instance detail tabs.
  - Volume detach/delete flows.
  - SSH key create/register/delete flows.
  - Template delete/copy/featured flows.
  - Security group create/delete/rule flows.
  - Network detail public IP acquire/release/static NAT flows.
- Add `npm run test:e2e` and focused documentation for running the suite.

### Suggested Branches

- `phase5e-smoke-harness`
- `phase5e-deploy-wizard-smoke`
- `phase5e-action-pages-smoke`
- `phase5e-network-security-smoke`

### Suggested Agent Split

- Worker A owns Playwright config, scripts, fixture helpers, and README updates.
- Worker B owns Deploy Wizard specs and launch/job-polling mocks.
- Worker C owns instance, volume, SSH key, and template specs.
- Worker D owns network and security group specs.
- Coordinator owns integration, naming consistency, and final full verification.

### Acceptance Criteria

- `npm run test:e2e` runs locally from `web/`.
- Tests do not require a live CloudStack server.
- Mock fixtures are obvious enough that later slices can add commands quickly.
- Existing checks still pass:
  - `node --test --experimental-strip-types app/api/cs/route-core.test.ts lib/bff/*.test.ts lib/cloudstack/*.test.ts`
  - `npm run typecheck`
  - `npm run lint`
  - `npm run build`

## Session 2: Instance Console Access

### Why Next

Instance detail is now tabbed, which gives a natural home for console access.
This is one of the highest-value operator actions still missing, but it needs a
careful CloudStack API scout first because console URLs and session material
should stay short-lived and server-mediated.

### Primary Deliverables

- Scout the current CloudStack API command and legacy UI implementation for
  console access.
- Add a typed CloudStack helper for console access response normalisation.
- Add route-core or BFF tests for the command shape.
- Add an Instance Detail Console tab or action panel.
- Keep console launch user-initiated and avoid persisting console URLs in client
  state longer than needed.
- Render clear unavailable/error states when the instance is stopped, destroyed,
  or the API refuses console access.

### Suggested Branches

- `phase5d-console-access-scout`
- `phase5d-console-access-bff`
- `phase5d-console-access-ui`

### Suggested Agent Split

- Worker A scouts API command names, parameters, response shape, and legacy UI
  behavior.
- Worker B implements helper and unit tests.
- Worker C implements UI wiring in instance detail.
- Worker D does a focused security review of URL handling and client exposure.

### Acceptance Criteria

- Console access command is verified from repo source or CloudStack API docs in
  the checked-out tree before implementation.
- No raw CloudStack secret, API key, session key, or durable console URL is
  exposed unnecessarily.
- Instance detail still builds and existing instance-detail tests pass.
- Browser smoke coverage includes the console tab/button with mocked responses.

## Session 3: Accessibility And Keyboard Hardening

### Why Here

The new UI uses Radix primitives and has a strong base, but the action-heavy
surfaces need explicit keyboard and screen-reader verification before they grow
further.

### Primary Deliverables

- Add `@axe-core/playwright` once Playwright is available.
- Add smoke-level axe checks for core pages:
  - Dashboard
  - Instances
  - Instance Detail
  - Deploy Wizard
  - Networks
  - Security Groups
  - Settings
- Harden Deploy Wizard keyboard behavior:
  - Predictable focus after opening and closing.
  - Keyboard path through required fields.
  - Clear disabled and busy states.
  - `aria-live` status for launch and job polling.
- Harden destructive action dialogs and dropdown actions:
  - Focus returns to the invoking control.
  - Destructive buttons have explicit accessible names.
  - Async success and failure states are announced.

### Suggested Branches

- `phase5e-axe-smoke`
- `phase5e-deploy-keyboard`
- `phase5e-action-a11y`

### Suggested Agent Split

- Worker A owns axe setup and baseline specs.
- Worker B owns Deploy Wizard keyboard/focus fixes.
- Worker C owns action components accessibility fixes.
- Coordinator owns review to avoid duplicating utility patterns.

### Acceptance Criteria

- Axe smoke tests pass for selected pages with mocked BFF data.
- Keyboard-only flow can open Deploy Wizard, complete the required launch path,
  and close or submit without pointer input.
- Async action results are announced without visual-only feedback.
- No broad styling churn outside the touched components.

## Session 4: i18n Foundation

### Why After Smoke Coverage

`next-intl` is part of the Phase 5 plan, but i18n can sprawl quickly. Once
browser smoke tests are in place, the safest first slice is to wire the provider
and migrate stable shell/navigation copy before moving page-level operator text.

### Primary Deliverables

- Add `next-intl` app wiring if not already present.
- Add `web/messages/en.json`.
- Add a small translation helper pattern for server and client components.
- Migrate shell/navigation/page-header copy first:
  - Sidebar labels
  - Topbar scope/user labels where static
  - Settings section labels
  - Shared action status labels where low risk
- Document the migration pattern so future slices do not invent competing
  message shapes.

### Suggested Branches

- `phase5e-i18n-foundation`
- `phase5e-shell-messages`
- `phase5e-action-messages`

### Suggested Agent Split

- Worker A owns provider/config/messages.
- Worker B owns shell and navigation message extraction.
- Worker C owns shared action component messages.
- Coordinator keeps the message namespace coherent.

### Acceptance Criteria

- The app still renders with the default English locale.
- No route behavior changes.
- Typecheck and build pass.
- The message file structure is documented and easy for later agents to follow.

## Session 5: Loading And Empty-State Polish

### Why Last In This Block

The real data surfaces are now broad enough that perceived reliability matters.
This slice improves operator confidence without changing CloudStack behavior.

### Primary Deliverables

- Add route-level or component-level loading states for slow pages.
- Standardise empty/error states for major inventory pages.
- Reuse existing `Skeleton`, `Badge`, `Card`, and table primitives.
- Avoid decorative redesign; keep it operational and dense.

### Suggested Branches

- `phase5e-loading-routes`
- `phase5e-empty-states`

### Acceptance Criteria

- Loading states are stable and do not cause layout jumps.
- Empty states are useful but not marketing-like.
- Build, lint, and relevant browser smoke tests pass.

## Coordination Rules

- Use isolated worktrees for every implementation branch.
- Keep worker ownership disjoint where possible.
- Merge only after targeted verification in the worker branch.
- Run full web verification from `modernize-2026` after each merge batch.
- Push `modernize-2026` after a green batch.
- Update `/Users/damian/Claude/HANDOVER.md` before any usage-window wrap-up.
- Stop launching new workers once usage drops under roughly 20%; finish, merge,
  verify, push, and hand over.

## Recommended First Batch

Start with four workers plus coordinator integration:

1. `phase5e-smoke-harness`: Playwright config, scripts, base fixture helper.
2. `phase5e-deploy-wizard-smoke`: Deploy Wizard smoke specs against mocked BFF.
3. `phase5e-action-pages-smoke`: Instances, Volumes, SSH Keys, Templates specs.
4. `phase5d-console-access-scout`: console API command and security scout only.

When Worker 1 lands the harness, rebase the smoke-spec branches onto it or merge
the harness first, then adapt the specs. If the console scout returns quickly and
the command shape is clear, dispatch BFF and UI workers for console access while
the smoke tests continue.

## Verification Gate For Each Big Batch

Run from `web/`:

```bash
node --test --experimental-strip-types app/api/cs/route-core.test.ts lib/bff/*.test.ts lib/cloudstack/*.test.ts
npm run typecheck
npm run lint
npm run build
npm run test:e2e
```

Run from repo root:

```bash
git status -sb
git diff --check
git log --oneline -12
```

## Risks To Manage

- Playwright dependency install or browser install may need network access and
  enough time in the usage window.
- Mocked BFF tests can become false confidence if they drift from route-core
  behavior; keep helper fixtures close to actual response shapes.
- Console access can leak sensitive or long-lived URLs if implemented casually;
  scout first and review URL handling before merge.
- i18n can become a wide churn branch; keep the first slice to foundation and
  shared shell text.
- Accessibility fixes should reuse existing primitives and avoid redesigning
  page layouts as part of the same branch.

## Done For This Phase Block

This block is done when:

- Mutation surfaces have browser smoke coverage.
- Instance console access is implemented or explicitly deferred with scout notes.
- Core pages have baseline axe coverage.
- The i18n foundation is present and documented.
- Loading and empty states are consistent across the main inventory pages.
- `modernize-2026` is green, pushed, and reflected in `HANDOVER.md`.
