# Phase 5j Settings Surfaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Phase 5 settings placeholders into practical CloudStack-backed profile, security, API-token, and settings navigation surfaces, with browser coverage and a small isolated cleanup for the Node module warning.

**Architecture:** Keep Phase 5j inside the existing Next.js/BFF pattern: server pages call typed helpers under `web/lib/cloudstack`, helpers fetch same-origin `/api/cs/*` routes and preserve mock fallback when `CS_URL` is absent, and client actions POST only safe command parameters. Split by settings page so subagents can work in isolated branches and the coordinator can resolve the shared `web/messages/en.json` merge centrally.

**Tech Stack:** Next.js 14 App Router, React 18, next-intl, Node `node:test`, Playwright, CloudStack BFF route `/api/cs/[command]`, Java 17 remains the repo verification target outside `web/`.

---

## Current Checkpoint

- Base branch: `modernize-2026`
- Base commit: `436457a38521bab74f235841da1d38e7d0e38c0f`
- `origin/modernize-2026` has already been pushed at this checkpoint.
- Phase 5i completed:
  - Remaining high-traffic page i18n.
  - Action failure e2e coverage.
  - Detail not-found e2e coverage.
  - E2E coverage README updates.
- Phase 5j was briefly started and then intentionally paused at user request.
- These empty Phase 5j worktrees currently exist at the base commit and can be reused after verifying they remain clean:
  - `.worktrees/phase5j-profile-settings`
  - `.worktrees/phase5j-api-token-settings`
  - `.worktrees/phase5j-security-settings`
  - `.worktrees/phase5j-settings-index`
  - `.worktrees/phase5j-settings-e2e`
  - `.worktrees/phase5j-package-metadata`
- No Phase 5j worker produced a commit before shutdown.

## Scope

Phase 5j is a focused settings block. It should not expand into a full account-admin console.

In scope:

- `/settings` landing page.
- `/settings/profile` read-only current-user profile surface.
- `/settings/security` read-only current-user security status, with password-change action only if it stays small.
- `/settings/api-tokens` API-key visibility and generation surface.
- Settings e2e coverage aligned with the new surfaces.
- Optional package metadata cleanup for `MODULE_TYPELESS_PACKAGE_JSON` warnings.

Out of scope:

- Admin CRUD for all users.
- Editing account/domain/project settings.
- Full notification, integration, billing, or advanced settings implementations.
- Broad design-system refactors.
- Any Veeam/KVM work.
- Any renewed god-class decomposition work.

## Coordination Rules

- Use isolated worktrees and branches for implementation.
- Keep worker ownership disjoint except for `web/messages/en.json`; the coordinator owns final message-file merge.
- Workers should run targeted verification in their branch before committing.
- Coordinator should merge one slice at a time into `modernize-2026`, resolving `web/messages/en.json` by preserving all `Settings.pages.*` subtrees.
- After the batch, run full web verification from `modernize-2026`:

```bash
cd web
npm run test:unit
npm run typecheck
npm run lint
npm run build
PLAYWRIGHT_PORT=3155 npm run test:e2e
```

- Run from repo root:

```bash
git diff --check
git status -sb
git log --oneline -12
```

- Push only after green verification:

```bash
git push origin modernize-2026
```

- Update `/Users/damian/Claude/HANDOVER.md` after merge/push.

## File Map

Create or modify these files.

- `web/lib/cloudstack/users.ts`
  - Owns current-user CloudStack `listUsers` mapping and mock fallback.
  - Exports `CloudStackUserProfile`, `getCurrentUserProfileFromBff`, `mapCloudStackUserToProfile`, and `buildListUsersUrl`.

- `web/lib/cloudstack/users.test.ts`
  - Tests current-user mapping, BFF fetch shape, missing envelope fallback, HTTP failure fallback, and `CS_URL` absent fallback.

- `web/app/(app)/settings/profile/page.tsx`
  - Replaces placeholder empty state with profile cards/rows.

- `web/lib/cloudstack/api-tokens.ts`
  - Owns `getUserKeys`, `registerUserKeys`, masking, and response mapping.
  - Exports `UserApiTokenSummary`, `GeneratedUserApiToken`, `getUserApiTokenSummaryFromBff`, `registerUserApiToken`, `maskSecret`.

- `web/lib/cloudstack/api-tokens.test.ts`
  - Tests `getUserKeys` mapping, `registerUserKeys` mapping, masking, safe POST body, and fallback.

- `web/components/settings/api-token-actions.tsx`
  - Optional client component for generating a new API key pair and rendering accessible success/error status.

- `web/app/(app)/settings/api-tokens/page.tsx`
  - Replaces placeholder empty state with token status and optional generation action.

- `web/lib/cloudstack/security-settings.ts`
  - Owns current-user security status derived from `listUsers` and mock fallback.
  - May import mapping from `users.ts` after the profile slice lands.

- `web/lib/cloudstack/security-settings.test.ts`
  - Tests status mapping and fallback.

- `web/components/settings/password-change-form.tsx`
  - Optional client component if password change is implemented.

- `web/app/(app)/settings/security/page.tsx`
  - Replaces placeholder empty state with security status cards/rows.

- `web/app/(app)/settings/page.tsx`
  - Replaces placeholder empty state with a settings section index.

- `web/tests/e2e/settings-pages.spec.ts`
  - Updates browser coverage for active and still-placeholder settings pages.

- `web/tests/e2e/README.md`
  - Optional: document mocked settings BFF envelopes if tests add new patterns.

- `web/messages/en.json`
  - Shared message file. Each worker owns only its `Settings.pages.<page>` subtree.

- `web/lib/settings-messages.test.ts`
  - Extend assertions for new settings keys.

- `web/package.json`
  - Optional: package metadata warning cleanup, only if verified safe.

## Preflight

### Task 0: Reopen Phase 5j Safely

**Files:**
- Inspect only: `.worktrees/phase5j-*`
- Inspect only: `docs/superpowers/plans/2026-05-21-phase5j-settings-surfaces.md`

- [ ] **Step 1: Confirm the main checkout is clean**

Run:

```bash
cd /Users/damian/Claude/cloudstack
git status -sb
git log --oneline -5
```

Expected:

```text
## modernize-2026
436457a385 Merge Phase 5i network page i18n
```

- [ ] **Step 2: Confirm the paused worktrees are clean**

Run:

```bash
cd /Users/damian/Claude/cloudstack
git worktree list --porcelain
for wt in .worktrees/phase5j-profile-settings .worktrees/phase5j-api-token-settings .worktrees/phase5j-security-settings .worktrees/phase5j-settings-index .worktrees/phase5j-settings-e2e .worktrees/phase5j-package-metadata; do git -C "$wt" status -sb; done
```

Expected for each Phase 5j worktree:

```text
## phase5j-...
```

- [ ] **Step 3: If any Phase 5j worktree is missing, recreate it**

Run only for missing worktrees:

```bash
git worktree add -b phase5j-profile-settings .worktrees/phase5j-profile-settings modernize-2026
git worktree add -b phase5j-api-token-settings .worktrees/phase5j-api-token-settings modernize-2026
git worktree add -b phase5j-security-settings .worktrees/phase5j-security-settings modernize-2026
git worktree add -b phase5j-settings-index .worktrees/phase5j-settings-index modernize-2026
git worktree add -b phase5j-settings-e2e .worktrees/phase5j-settings-e2e modernize-2026
git worktree add -b phase5j-package-metadata .worktrees/phase5j-package-metadata modernize-2026
```

Expected:

```text
Preparing worktree (new branch 'phase5j-...')
HEAD is now at 436457a385 Merge Phase 5i network page i18n
```

- [ ] **Step 4: Install web dependencies in worktrees as needed**

Run inside each worktree before tests if `node_modules` is absent:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-profile-settings/web
npm ci
```

Expected:

```text
added ... packages
```

Deprecation warnings are acceptable. Test failures are not.

## Agent Dispatch Queue

Start these in parallel when quota allows. If the current app still limits worker count, run five workers and keep one lane local.

| Branch | Owner | Dependency | Merge order |
| --- | --- | --- | --- |
| `phase5j-profile-settings` | Profile helper and page | none | 1 |
| `phase5j-api-token-settings` | API token helper, page, action | none | 2 |
| `phase5j-security-settings` | Security helper and page | can later reuse `users.ts`, but should be self-contained first | 3 |
| `phase5j-settings-index` | Settings landing page | none | 4 |
| `phase5j-settings-e2e` | Browser coverage | easiest after page slices, but can draft before | 5 |
| `phase5j-package-metadata` | Optional module warning cleanup | none; merge last or discard | 6 |

## Task 1: Profile Settings Surface

**Files:**
- Create: `web/lib/cloudstack/users.ts`
- Create: `web/lib/cloudstack/users.test.ts`
- Modify: `web/app/(app)/settings/profile/page.tsx`
- Modify: `web/messages/en.json` under `Settings.pages.profile`
- Modify: `web/lib/settings-messages.test.ts`

- [ ] **Step 1: Write the failing mapping test**

Add `web/lib/cloudstack/users.test.ts`:

```ts
import assert from "node:assert/strict";
import test from "node:test";

import { mockUser } from "../auth/mock.ts";
import type { CurrentUser } from "../auth/types.ts";
import {
  getCurrentUserProfileFromBff,
  mapCloudStackUserToProfile,
  usersFromListUsersResponse,
} from "./users.ts";

const currentUser: CurrentUser = {
  id: "user-uuid-1",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "domain-root",
};

test("mapCloudStackUserToProfile maps CloudStack user fields into profile rows", () => {
  const profile = mapCloudStackUserToProfile({
    id: "user-uuid-1",
    username: "alex",
    firstname: "Alex",
    lastname: "Kim",
    email: "alex@example.test",
    account: "admin",
    accounttype: 1,
    domain: "ROOT",
    domainid: "domain-root",
    timezone: "Australia/Perth",
    usersource: "native",
    state: "enabled",
    apikeyaccess: true,
    is2faenabled: true,
    is2famandated: false,
  }, currentUser);

  assert.deepEqual(profile, {
    id: "user-uuid-1",
    username: "alex",
    displayName: "Alex Kim",
    email: "alex@example.test",
    account: "admin",
    role: "ROOT",
    domain: "ROOT",
    domainId: "domain-root",
    timezone: "Australia/Perth",
    source: "native",
    state: "enabled",
    apiKeyAccess: "enabled",
    twoFactorEnabled: true,
    twoFactorMandated: false,
  });
});

test("usersFromListUsersResponse maps the listUsers envelope", () => {
  const users = usersFromListUsersResponse({
    listusersresponse: {
      count: 1,
      user: [{ id: "user-uuid-1", username: "alex", firstname: "Alex", lastname: "Kim" }],
    },
  }, currentUser);

  assert.equal(users.length, 1);
  assert.equal(users[0]?.displayName, "Alex Kim");
});

test("getCurrentUserProfileFromBff calls listUsers for the current user and forwards cookies", async () => {
  const urls: URL[] = [];
  const headers: HeadersInit[] = [];
  const profile = await getCurrentUserProfileFromBff(currentUser, {
    requestHeaders: new Headers({ host: "ui.example.test", cookie: "cloudstack.session=opaque" }),
    fetchImpl: async (input, init) => {
      urls.push(new URL(String(input)));
      headers.push(init?.headers ?? {});
      return Response.json({
        listusersresponse: {
          count: 1,
          user: [{ id: "user-uuid-1", username: "alex", firstname: "Alex", lastname: "Kim" }],
        },
      });
    },
  });

  assert.equal(urls[0]?.pathname, "/api/cs/listUsers");
  assert.equal(urls[0]?.searchParams.get("id"), "user-uuid-1");
  assert.deepEqual(headers[0], { cookie: "cloudstack.session=opaque" });
  assert.equal(profile.displayName, "Alex Kim");
});

test("getCurrentUserProfileFromBff falls back to Auth.js/mock identity when CloudStack is unavailable", async () => {
  const previous = process.env.CS_URL;
  delete process.env.CS_URL;

  try {
    const profile = await getCurrentUserProfileFromBff(mockUser, {
      fetchImpl: async () => {
        throw new Error("should not fetch without CS_URL");
      },
    });

    assert.equal(profile.displayName, mockUser.name);
    assert.equal(profile.email, mockUser.email);
  } finally {
    process.env.CS_URL = previous;
  }
});
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-profile-settings/web
node --test "lib/cloudstack/users.test.ts"
```

Expected:

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module .../lib/cloudstack/users.ts
```

- [ ] **Step 3: Implement `web/lib/cloudstack/users.ts`**

Create:

```ts
import { mockUser } from "../auth/mock.ts";
import type { CurrentUser, Role } from "../auth/types.ts";

export type CloudStackUser = {
  id?: string;
  username?: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  account?: string;
  accounttype?: number | string;
  roletype?: string;
  rolename?: string;
  domain?: string;
  domainid?: string;
  timezone?: string;
  usersource?: string;
  state?: string;
  apikeyaccess?: boolean | string;
  is2faenabled?: boolean | string;
  is2famandated?: boolean | string;
  isdefault?: boolean | string;
};

export type ListUsersResponse = {
  listusersresponse?: {
    count?: number | string;
    user?: CloudStackUser[];
  };
};

export type CloudStackUserProfile = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  account: string;
  role: Role;
  domain: string;
  domainId: string;
  timezone: string;
  source: string;
  state: string;
  apiKeyAccess: "enabled" | "disabled" | "unknown";
  twoFactorEnabled: boolean;
  twoFactorMandated: boolean;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getCurrentUserProfileFromBff(
  currentUser: CurrentUser = mockUser,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<CloudStackUserProfile> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return profileFromCurrentUser(currentUser);
  }

  try {
    const response = await fetchImpl(buildListUsersUrl(currentUser.id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return profileFromCurrentUser(currentUser);
    }

    const payload = (await response.json()) as ListUsersResponse;
    const profile = usersFromListUsersResponse(payload, currentUser)[0];
    return profile ?? profileFromCurrentUser(currentUser);
  } catch {
    return profileFromCurrentUser(currentUser);
  }
}

export function usersFromListUsersResponse(
  response: ListUsersResponse,
  currentUser: CurrentUser = mockUser,
): CloudStackUserProfile[] {
  return (response.listusersresponse?.user ?? []).map((user) => mapCloudStackUserToProfile(user, currentUser));
}

export function mapCloudStackUserToProfile(
  user: CloudStackUser,
  currentUser: CurrentUser = mockUser,
): CloudStackUserProfile {
  return {
    id: user.id ?? currentUser.id,
    username: user.username ?? currentUser.username,
    displayName: formatDisplayName(user, currentUser),
    email: user.email ?? currentUser.email,
    account: user.account ?? currentUser.username,
    role: mapRole(user, currentUser.role),
    domain: user.domain ?? currentUser.domain,
    domainId: user.domainid ?? currentUser.domainId,
    timezone: user.timezone ?? "Browser default",
    source: user.usersource ?? "unknown",
    state: user.state ?? "unknown",
    apiKeyAccess: mapApiKeyAccess(user.apikeyaccess),
    twoFactorEnabled: readBoolean(user.is2faenabled),
    twoFactorMandated: readBoolean(user.is2famandated),
  };
}

function profileFromCurrentUser(user: CurrentUser): CloudStackUserProfile {
  return {
    id: user.id,
    username: user.username,
    displayName: user.name,
    email: user.email,
    account: user.username,
    role: user.role,
    domain: user.domain,
    domainId: user.domainId,
    timezone: "Browser default",
    source: "session",
    state: "active",
    apiKeyAccess: "unknown",
    twoFactorEnabled: false,
    twoFactorMandated: false,
  };
}

function formatDisplayName(user: CloudStackUser, currentUser: CurrentUser): string {
  const name = [user.firstname, user.lastname].filter(Boolean).join(" ").trim();
  return name || currentUser.name || user.username || "Unknown user";
}

function mapRole(user: CloudStackUser, fallback: Role): Role {
  const text = `${user.roletype ?? ""} ${user.rolename ?? ""} ${user.accounttype ?? ""}`.toLowerCase();
  if (text.includes("root") || text.includes("admin") || text.includes("1")) {
    return "ROOT";
  }
  if (text.includes("resource")) {
    return "ADMIN";
  }
  if (text.includes("domain") || text.includes("2")) {
    return "DOMAIN_ADMIN";
  }
  return fallback;
}

function mapApiKeyAccess(value: CloudStackUser["apikeyaccess"]): CloudStackUserProfile["apiKeyAccess"] {
  if (value === true || String(value).toLowerCase() === "enabled" || String(value).toLowerCase() === "true") {
    return "enabled";
  }
  if (value === false || String(value).toLowerCase() === "disabled" || String(value).toLowerCase() === "false") {
    return "disabled";
  }
  return "unknown";
}

function readBoolean(value: boolean | string | undefined): boolean {
  return value === true || String(value).toLowerCase() === "true";
}

export function buildListUsersUrl(userId: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({ id: userId, showicon: "true" });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listUsers?${params.toString()}`;
}

function getRequestOrigin(requestHeaders?: Pick<Headers, "get">): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  const host = requestHeaders?.get("x-forwarded-host") ?? requestHeaders?.get("host") ?? "localhost:3000";
  const protocol = requestHeaders?.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}

function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
```

- [ ] **Step 4: Replace the profile page placeholder**

Modify `web/app/(app)/settings/profile/page.tsx`:

```tsx
import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getCurrentUser } from "@/lib/auth/server";
import { getCurrentUserProfileFromBff } from "@/lib/cloudstack/users";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.profile");
  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.profile");
  const currentUser = await getCurrentUser();
  const profile = await getCurrentUserProfileFromBff(currentUser, { requestHeaders: headers() });

  const rows = [
    [t("fields.username"), profile.username],
    [t("fields.email"), profile.email],
    [t("fields.account"), profile.account],
    [t("fields.domain"), profile.domain],
    [t("fields.timezone"), profile.timezone],
    [t("fields.source"), profile.source],
  ] as const;

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-4 lg:grid-cols-[1fr_360px]">
        <Card>
          <CardHeader>
            <CardTitle>{profile.displayName}</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-3 sm:grid-cols-2">
              {rows.map(([label, value]) => (
                <div key={label} className="rounded-md border border-[color:var(--border)] p-3">
                  <dt className="text-xs text-[color:var(--fg-dim)]">{label}</dt>
                  <dd className="mt-1 text-sm font-medium text-[color:var(--fg)]">{value}</dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("summary.title")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <SummaryRow label={t("summary.role")} value={profile.role} />
            <SummaryRow label={t("summary.state")} value={profile.state} />
            <SummaryRow label={t("summary.apiKeyAccess")} value={t(`apiKeyAccess.${profile.apiKeyAccess}`)} />
            <SummaryRow label={t("summary.twoFactor")} value={profile.twoFactorEnabled ? t("states.enabled") : t("states.disabled")} />
            <Badge variant={profile.state.toLowerCase() === "enabled" || profile.state === "active" ? "success" : "warning"}>
              {profile.state}
            </Badge>
          </CardContent>
        </Card>
      </div>
    </>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-[color:var(--fg-dim)]">{label}</span>
      <span className="font-medium text-[color:var(--fg)]">{value}</span>
    </div>
  );
}
```

If `CardTitle` is not exported by `web/components/ui/card.tsx`, use the existing local card markup pattern from other pages instead of adding a new primitive.

- [ ] **Step 5: Add profile messages**

Modify only `Settings.pages.profile` in `web/messages/en.json`:

```json
"profile": {
  "metadataTitle": "Profile settings",
  "title": "Profile",
  "description": "Current CloudStack identity, scope, and console preferences.",
  "fields": {
    "username": "Username",
    "email": "Email",
    "account": "Account",
    "domain": "Domain",
    "timezone": "Timezone",
    "source": "Source"
  },
  "summary": {
    "title": "Account summary",
    "role": "Role",
    "state": "State",
    "apiKeyAccess": "API key access",
    "twoFactor": "Two-factor authentication"
  },
  "states": {
    "enabled": "Enabled",
    "disabled": "Disabled"
  },
  "apiKeyAccess": {
    "enabled": "Enabled",
    "disabled": "Disabled",
    "unknown": "Not reported"
  }
}
```

- [ ] **Step 6: Extend settings message test**

Update `web/lib/settings-messages.test.ts` to assert the new profile keys:

```ts
const SETTINGS_MESSAGE_KEYS = [
  "pages.profile.fields.username",
  "pages.profile.fields.email",
  "pages.profile.summary.title",
  "pages.profile.apiKeyAccess.enabled",
] as const;

test("settings profile resolves operational message keys", () => {
  for (const key of SETTINGS_MESSAGE_KEYS) {
    assert.equal(typeof readSettingsMessage(key), "string", key);
  }
});
```

- [ ] **Step 7: Verify and commit**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-profile-settings/web
npm run test:unit
npm run typecheck
npm run lint
cd ..
git diff --check
git status -sb
git add web/lib/cloudstack/users.ts web/lib/cloudstack/users.test.ts 'web/app/(app)/settings/profile/page.tsx' web/messages/en.json web/lib/settings-messages.test.ts
git commit -m "Add CloudStack-backed profile settings"
```

Expected:

```text
# tests pass
[phase5j-profile-settings ...] Add CloudStack-backed profile settings
```

## Task 2: API Token Settings Surface

**Files:**
- Create: `web/lib/cloudstack/api-tokens.ts`
- Create: `web/lib/cloudstack/api-tokens.test.ts`
- Create: `web/components/settings/api-token-actions.tsx`
- Modify: `web/app/(app)/settings/api-tokens/page.tsx`
- Modify: `web/messages/en.json` under `Settings.pages.apiTokens`
- Modify: `web/lib/settings-messages.test.ts`

- [ ] **Step 1: Write API token tests**

Create `web/lib/cloudstack/api-tokens.test.ts`:

```ts
import assert from "node:assert/strict";
import test from "node:test";

import {
  getUserApiTokenSummaryFromBff,
  mapGetUserKeysResponse,
  mapRegisterUserKeysResponse,
  maskSecret,
  registerUserApiToken,
} from "./api-tokens.ts";

test("maskSecret preserves short inspection prefix and suffix", () => {
  assert.equal(maskSecret("abcdefghijklmnop"), "abcd...mnop");
  assert.equal(maskSecret("abc"), "••••");
  assert.equal(maskSecret(null), "Not generated");
});

test("mapGetUserKeysResponse maps API key access and masks sensitive values", () => {
  const summary = mapGetUserKeysResponse({
    getuserkeysresponse: {
      userkeys: {
        apikeyaccess: true,
        apikey: "api-key-123456",
        secretkey: "secret-key-abcdef",
      },
    },
  });

  assert.deepEqual(summary, {
    access: "enabled",
    apiKeyMasked: "api-...3456",
    secretKeyMasked: "secr...cdef",
    hasApiKey: true,
    hasSecretKey: true,
  });
});

test("mapRegisterUserKeysResponse maps generated key pair without dropping the one-time secret", () => {
  const generated = mapRegisterUserKeysResponse({
    registeruserkeysresponse: {
      userkeys: {
        id: "keypair-1",
        name: "automation",
        apikey: "generated-api",
        secretkey: "generated-secret",
      },
    },
  });

  assert.equal(generated.id, "keypair-1");
  assert.equal(generated.apiKey, "generated-api");
  assert.equal(generated.secretKey, "generated-secret");
});

test("getUserApiTokenSummaryFromBff calls getUserKeys for the current user", async () => {
  const urls: URL[] = [];
  const summary = await getUserApiTokenSummaryFromBff("user-1", {
    requestHeaders: new Headers({ host: "ui.example.test", cookie: "cloudstack.session=opaque" }),
    fetchImpl: async (input) => {
      urls.push(new URL(String(input)));
      return Response.json({ getuserkeysresponse: { userkeys: { apikeyaccess: false } } });
    },
  });

  assert.equal(urls[0]?.pathname, "/api/cs/getUserKeys");
  assert.equal(urls[0]?.searchParams.get("id"), "user-1");
  assert.equal(summary.access, "disabled");
});

test("registerUserApiToken posts safe JSON params to registerUserKeys", async () => {
  const calls: Array<{ url: URL; body: unknown }> = [];
  await registerUserApiToken({
    userId: "user-1",
    name: "automation",
    description: "Created from settings",
    fetchImpl: async (input, init) => {
      calls.push({ url: new URL(String(input), "http://ui.example.test"), body: JSON.parse(String(init?.body)) });
      return Response.json({
        registeruserkeysresponse: {
          userkeys: { id: "keypair-1", apikey: "generated-api", secretkey: "generated-secret" },
        },
      });
    },
  });

  assert.equal(calls[0]?.url.pathname, "/api/cs/registerUserKeys");
  assert.deepEqual(calls[0]?.body, {
    id: "user-1",
    name: "automation",
    description: "Created from settings",
  });
});
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-api-token-settings/web
node --test "lib/cloudstack/api-tokens.test.ts"
```

Expected:

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module .../lib/cloudstack/api-tokens.ts
```

- [ ] **Step 3: Implement `api-tokens.ts`**

Create a helper with these exported contracts:

```ts
export type UserApiTokenSummary = {
  access: "enabled" | "disabled" | "unknown";
  apiKeyMasked: string;
  secretKeyMasked: string;
  hasApiKey: boolean;
  hasSecretKey: boolean;
};

export type GeneratedUserApiToken = {
  id: string | null;
  name: string;
  apiKey: string;
  secretKey: string;
};

export function maskSecret(value: string | null | undefined): string {
  if (!value || value.length < 8) {
    return value ? "••••" : "Not generated";
  }
  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}
```

Implementation details:

- `getUserApiTokenSummaryFromBff(userId, options)`:
  - Return mock summary if `NEXT_PUBLIC_APP_ENV === "mock"` or `!process.env.CS_URL`.
  - Fetch `GET /api/cs/getUserKeys?id=<userId>`.
  - Forward only `cookie`.
  - Return disabled/unknown summary on non-OK, malformed envelope, or thrown fetch.

- `registerUserApiToken(input)`:
  - POST `/api/cs/registerUserKeys`.
  - Body: `{ id, name, description }`; omit blank description.
  - Throw CloudStack error text if `errorresponse.errortext` exists.
  - Throw if generated envelope lacks `apikey` or `secretkey`.

- Accept both response shapes:
  - `getuserkeysresponse.userkeys`
  - `registeruserkeysresponse.userkeys`
  - direct `registeruserkeysresponse.apikey`/`secretkey` if CloudStack returns a flatter response.

- [ ] **Step 4: Add client action component**

Create `web/components/settings/api-token-actions.tsx`:

```tsx
"use client";

import { useState, useTransition } from "react";

import { Button } from "@/components/ui/button";
import { registerUserApiToken, type GeneratedUserApiToken } from "@/lib/cloudstack/api-tokens";

type ApiTokenActionsProps = {
  userId: string;
  generateLabel: string;
  successLabel: string;
  errorLabel: string;
};

export function ApiTokenActions({ userId, generateLabel, successLabel, errorLabel }: ApiTokenActionsProps) {
  const [isPending, startTransition] = useTransition();
  const [generated, setGenerated] = useState<GeneratedUserApiToken | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="space-y-3">
      <Button
        type="button"
        variant="primary"
        disabled={isPending}
        onClick={() => {
          startTransition(async () => {
            setError(null);
            try {
              const token = await registerUserApiToken({
                userId,
                name: "CloudStack UI automation",
                description: "Generated from modern CloudStack settings",
              });
              setGenerated(token);
            } catch (err) {
              setError(err instanceof Error ? err.message : errorLabel);
            }
          });
        }}
      >
        {isPending ? generateLabel : generateLabel}
      </Button>
      {generated ? (
        <div role="status" className="rounded-md border border-[color:var(--border)] p-3 text-sm">
          <p className="font-medium">{successLabel}</p>
          <p className="mt-2 font-mono text-xs">API: {generated.apiKey}</p>
          <p className="mt-1 font-mono text-xs">Secret: {generated.secretKey}</p>
        </div>
      ) : null}
      {error ? <p role="alert" className="text-sm text-[color:var(--danger)]">{error}</p> : null}
    </div>
  );
}
```

If showing the full generated secret is considered too sensitive during review, change the component to show `maskSecret(generated.secretKey)` and add a one-time copy button only in a later dedicated slice.

- [ ] **Step 5: Replace API token page placeholder**

Modify `web/app/(app)/settings/api-tokens/page.tsx`:

```tsx
import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { ApiTokenActions } from "@/components/settings/api-token-actions";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getCurrentUser } from "@/lib/auth/server";
import { getUserApiTokenSummaryFromBff } from "@/lib/cloudstack/api-tokens";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.apiTokens");
  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.apiTokens");
  const user = await getCurrentUser();
  const summary = await getUserApiTokenSummaryFromBff(user.id, { requestHeaders: headers() });

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-4 lg:grid-cols-[1fr_380px]">
        <Card>
          <CardHeader>
            <CardTitle>{t("current.title")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <TokenRow label={t("current.access")} value={t(`access.${summary.access}`)} />
            <TokenRow label={t("current.apiKey")} value={summary.apiKeyMasked} mono />
            <TokenRow label={t("current.secretKey")} value={summary.secretKeyMasked} mono />
            <Badge variant={summary.access === "enabled" ? "success" : "warning"}>
              {t(`access.${summary.access}`)}
            </Badge>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("generate.title")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="mb-4 text-sm text-[color:var(--fg-muted)]">{t("generate.description")}</p>
            <ApiTokenActions
              userId={user.id}
              generateLabel={t("generate.action")}
              successLabel={t("generate.success")}
              errorLabel={t("generate.error")}
            />
          </CardContent>
        </Card>
      </div>
    </>
  );
}

function TokenRow({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-md border border-[color:var(--border)] p-3">
      <span className="text-sm text-[color:var(--fg-dim)]">{label}</span>
      <span className={mono ? "font-mono text-xs text-[color:var(--fg)]" : "text-sm font-medium text-[color:var(--fg)]"}>{value}</span>
    </div>
  );
}
```

- [ ] **Step 6: Add API token messages and test keys**

Add to `Settings.pages.apiTokens`:

```json
"current": {
  "title": "Current key status",
  "access": "API key access",
  "apiKey": "API key",
  "secretKey": "Secret key"
},
"access": {
  "enabled": "Enabled",
  "disabled": "Disabled",
  "unknown": "Not reported"
},
"generate": {
  "title": "Generate key pair",
  "description": "Generate a new CloudStack API key pair for automation. The secret is returned once by CloudStack.",
  "action": "Generate key pair",
  "success": "Generated key pair",
  "error": "Unable to generate API key pair"
}
```

Extend `web/lib/settings-messages.test.ts` with:

```ts
const API_TOKEN_MESSAGE_KEYS = [
  "pages.apiTokens.current.title",
  "pages.apiTokens.current.apiKey",
  "pages.apiTokens.generate.action",
  "pages.apiTokens.access.enabled",
] as const;
```

- [ ] **Step 7: Verify and commit**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-api-token-settings/web
npm run test:unit
npm run typecheck
npm run lint
cd ..
git diff --check
git add web/lib/cloudstack/api-tokens.ts web/lib/cloudstack/api-tokens.test.ts web/components/settings/api-token-actions.tsx 'web/app/(app)/settings/api-tokens/page.tsx' web/messages/en.json web/lib/settings-messages.test.ts
git commit -m "Add CloudStack API token settings"
```

## Task 3: Security Settings Surface

**Files:**
- Create: `web/lib/cloudstack/security-settings.ts`
- Create: `web/lib/cloudstack/security-settings.test.ts`
- Optional create: `web/components/settings/password-change-form.tsx`
- Modify: `web/app/(app)/settings/security/page.tsx`
- Modify: `web/messages/en.json` under `Settings.pages.security`
- Modify: `web/lib/settings-messages.test.ts`

- [ ] **Step 1: Write security mapping tests**

Create `web/lib/cloudstack/security-settings.test.ts`:

```ts
import assert from "node:assert/strict";
import test from "node:test";

import { mockUser } from "../auth/mock.ts";
import {
  getCurrentUserSecuritySettingsFromBff,
  mapCloudStackUserToSecuritySettings,
} from "./security-settings.ts";

test("mapCloudStackUserToSecuritySettings maps current user security flags", () => {
  const settings = mapCloudStackUserToSecuritySettings({
    id: "user-1",
    usersource: "native",
    state: "enabled",
    apikeyaccess: "Enabled",
    is2faenabled: true,
    is2famandated: false,
    passwordchangerequired: true,
  });

  assert.deepEqual(settings, {
    source: "native",
    state: "enabled",
    apiKeyAccess: "enabled",
    twoFactorEnabled: true,
    twoFactorMandated: false,
    passwordChangeRequired: true,
  });
});

test("getCurrentUserSecuritySettingsFromBff falls back when CS_URL is absent", async () => {
  const previous = process.env.CS_URL;
  delete process.env.CS_URL;

  try {
    const settings = await getCurrentUserSecuritySettingsFromBff(mockUser, {
      fetchImpl: async () => {
        throw new Error("should not fetch without CS_URL");
      },
    });
    assert.equal(settings.source, "session");
    assert.equal(settings.apiKeyAccess, "unknown");
  } finally {
    process.env.CS_URL = previous;
  }
});
```

- [ ] **Step 2: Implement `security-settings.ts`**

Create:

```ts
import type { CurrentUser } from "../auth/types.ts";
import { type CloudStackUser, type ListUsersResponse, buildListUsersUrl } from "./users.ts";

export type CurrentUserSecuritySettings = {
  source: string;
  state: string;
  apiKeyAccess: "enabled" | "disabled" | "unknown";
  twoFactorEnabled: boolean;
  twoFactorMandated: boolean;
  passwordChangeRequired: boolean;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getCurrentUserSecuritySettingsFromBff(
  user: CurrentUser,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<CurrentUserSecuritySettings> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return fallbackSecuritySettings();
  }

  try {
    const response = await fetchImpl(buildListUsersUrl(user.id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });
    if (!response.ok) {
      return fallbackSecuritySettings();
    }
    const payload = (await response.json()) as ListUsersResponse;
    const cloudStackUser = payload.listusersresponse?.user?.[0];
    return cloudStackUser ? mapCloudStackUserToSecuritySettings(cloudStackUser) : fallbackSecuritySettings();
  } catch {
    return fallbackSecuritySettings();
  }
}

export function mapCloudStackUserToSecuritySettings(user: CloudStackUser): CurrentUserSecuritySettings {
  return {
    source: user.usersource ?? "unknown",
    state: user.state ?? "unknown",
    apiKeyAccess: mapAccess(user.apikeyaccess),
    twoFactorEnabled: readBoolean(user.is2faenabled),
    twoFactorMandated: readBoolean(user.is2famandated),
    passwordChangeRequired: readBoolean((user as CloudStackUser & { passwordchangerequired?: boolean | string }).passwordchangerequired),
  };
}

function fallbackSecuritySettings(): CurrentUserSecuritySettings {
  return {
    source: "session",
    state: "active",
    apiKeyAccess: "unknown",
    twoFactorEnabled: false,
    twoFactorMandated: false,
    passwordChangeRequired: false,
  };
}

function mapAccess(value: CloudStackUser["apikeyaccess"]): CurrentUserSecuritySettings["apiKeyAccess"] {
  if (value === true || String(value).toLowerCase() === "enabled" || String(value).toLowerCase() === "true") return "enabled";
  if (value === false || String(value).toLowerCase() === "disabled" || String(value).toLowerCase() === "false") return "disabled";
  return "unknown";
}

function readBoolean(value: boolean | string | undefined): boolean {
  return value === true || String(value).toLowerCase() === "true";
}

function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
```

If this branch runs before Task 1 lands, copy the minimal `CloudStackUser`, `ListUsersResponse`, and `buildListUsersUrl` definitions locally. During coordinator merge, replace the duplicate with imports from `users.ts`.

- [ ] **Step 3: Replace the security page placeholder**

Modify `web/app/(app)/settings/security/page.tsx`:

```tsx
import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getCurrentUser } from "@/lib/auth/server";
import { getCurrentUserSecuritySettingsFromBff } from "@/lib/cloudstack/security-settings";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.security");
  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.security");
  const user = await getCurrentUser();
  const settings = await getCurrentUserSecuritySettingsFromBff(user, { requestHeaders: headers() });

  const rows = [
    [t("fields.source"), settings.source],
    [t("fields.state"), settings.state],
    [t("fields.apiKeyAccess"), t(`access.${settings.apiKeyAccess}`)],
    [t("fields.twoFactorEnabled"), settings.twoFactorEnabled ? t("states.enabled") : t("states.disabled")],
    [t("fields.twoFactorMandated"), settings.twoFactorMandated ? t("states.enabled") : t("states.disabled")],
    [t("fields.passwordChangeRequired"), settings.passwordChangeRequired ? t("states.required") : t("states.notRequired")],
  ] as const;

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <Card>
        <CardHeader>
          <CardTitle>{t("summary.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {rows.map(([label, value]) => (
              <div key={label} className="rounded-md border border-[color:var(--border)] p-3">
                <div className="text-xs text-[color:var(--fg-dim)]">{label}</div>
                <div className="mt-1 text-sm font-medium text-[color:var(--fg)]">{value}</div>
              </div>
            ))}
          </div>
          <div className="mt-4 flex gap-2">
            <Badge variant={settings.twoFactorEnabled ? "success" : "warning"}>{t("badges.twoFactor")}</Badge>
            <Badge variant={settings.apiKeyAccess === "enabled" ? "success" : "warning"}>{t("badges.apiKeyAccess")}</Badge>
          </div>
        </CardContent>
      </Card>
    </>
  );
}
```

- [ ] **Step 4: Add security messages and tests**

Add to `Settings.pages.security`:

```json
"summary": {
  "title": "Security status"
},
"fields": {
  "source": "Authentication source",
  "state": "User state",
  "apiKeyAccess": "API key access",
  "twoFactorEnabled": "Two-factor enabled",
  "twoFactorMandated": "Two-factor mandated",
  "passwordChangeRequired": "Password change"
},
"states": {
  "enabled": "Enabled",
  "disabled": "Disabled",
  "required": "Required",
  "notRequired": "Not required"
},
"access": {
  "enabled": "Enabled",
  "disabled": "Disabled",
  "unknown": "Not reported"
},
"badges": {
  "twoFactor": "Two-factor",
  "apiKeyAccess": "API keys"
}
```

Extend `web/lib/settings-messages.test.ts` with security keys.

- [ ] **Step 5: Verify and commit**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-security-settings/web
npm run test:unit
npm run typecheck
npm run lint
cd ..
git diff --check
git add web/lib/cloudstack/security-settings.ts web/lib/cloudstack/security-settings.test.ts 'web/app/(app)/settings/security/page.tsx' web/messages/en.json web/lib/settings-messages.test.ts
git commit -m "Add CloudStack security settings"
```

## Task 4: Settings Landing Page

**Files:**
- Modify: `web/app/(app)/settings/page.tsx`
- Modify: `web/messages/en.json` under `Settings.pages.index`
- Modify: `web/lib/settings-messages.test.ts`

- [ ] **Step 1: Replace the settings index placeholder**

Modify `web/app/(app)/settings/page.tsx`:

```tsx
import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const SECTIONS = [
  { key: "profile", href: "/settings/profile", state: "available" },
  { key: "security", href: "/settings/security", state: "available" },
  { key: "apiTokens", href: "/settings/api-tokens", state: "available" },
  { key: "integrations", href: "/settings/integrations", state: "notConfigured" },
  { key: "billing", href: "/settings/billing", state: "notConfigured" },
  { key: "notifications", href: "/settings/notifications", state: "notConfigured" },
  { key: "advanced", href: "/settings/advanced", state: "notConfigured" },
] as const;

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.index");
  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.index");

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {SECTIONS.map((section) => (
          <Link key={section.key} href={section.href} className="block rounded-[var(--radius-lg)] focus:outline-none focus:ring-2 focus:ring-[color:var(--accent)]">
            <Card className="h-full transition-colors hover:border-[color:var(--border-strong)]">
              <CardHeader>
                <div className="flex items-center justify-between gap-3">
                  <CardTitle>{t(`sections.${section.key}.title`)}</CardTitle>
                  <Badge variant={section.state === "available" ? "success" : "default"}>
                    {t(`states.${section.state}`)}
                  </Badge>
                </div>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-[color:var(--fg-muted)]">{t(`sections.${section.key}.description`)}</p>
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>
    </>
  );
}
```

- [ ] **Step 2: Add index messages**

Replace `Settings.pages.index.emptyState` with:

```json
"states": {
  "available": "Available",
  "notConfigured": "Not configured"
},
"sections": {
  "profile": {
    "title": "Profile",
    "description": "Current CloudStack user, account, domain, and console preferences."
  },
  "security": {
    "title": "Security",
    "description": "Authentication source, two-factor status, API key access, and session posture."
  },
  "apiTokens": {
    "title": "API tokens",
    "description": "CloudStack API key visibility and scoped key-pair generation."
  },
  "integrations": {
    "title": "Integrations",
    "description": "Identity, monitoring, and automation integrations for this scope."
  },
  "billing": {
    "title": "Billing",
    "description": "Invoices, usage exports, and payment configuration."
  },
  "notifications": {
    "title": "Notifications",
    "description": "Email, event, and operational alert preferences."
  },
  "advanced": {
    "title": "Advanced",
    "description": "Low-level console and experimental operator controls."
  }
}
```

- [ ] **Step 3: Verify and commit**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-settings-index/web
npm run test:unit
npm run typecheck
npm run lint
cd ..
git diff --check
git add 'web/app/(app)/settings/page.tsx' web/messages/en.json web/lib/settings-messages.test.ts
git commit -m "Add settings landing surface"
```

## Task 5: Settings Browser Coverage

**Files:**
- Modify: `web/tests/e2e/settings-pages.spec.ts`
- Optional modify: `web/tests/e2e/README.md`

- [ ] **Step 1: Update e2e test framing**

Replace the current `test.describe("settings placeholder pages", ...)` with:

```ts
import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("settings surfaces", () => {
  test("settings index links to active and not-configured sections", async ({ page }) => {
    await page.goto("/settings");

    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Profile/ })).toBeVisible();
    await expect(page.getByRole("link", { name: /Security/ })).toBeVisible();
    await expect(page.getByRole("link", { name: /API tokens/ })).toBeVisible();
    await expect(page.getByText("Available")).toHaveCount(3);
  });

  test("profile page renders current user identity from listUsers", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listUsers", {
      listusersresponse: {
        count: 1,
        user: [{
          id: "mock-uuid-alex",
          username: "alex",
          firstname: "Alex",
          lastname: "Kim",
          email: "alex@example.test",
          account: "admin",
          domain: "ROOT",
          timezone: "Australia/Perth",
          usersource: "native",
          state: "enabled",
          apikeyaccess: true,
          is2faenabled: true,
        }],
      },
    });

    await page.goto("/settings/profile");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page.getByText("Alex Kim")).toBeVisible();
    await expect(page.getByText("alex@example.test")).toBeVisible();
    expect(mockCloudStackBff.calls("listUsers").at(-1)?.params.get("id")).toBe("mock-uuid-alex");
  });

  test("security page renders current user security flags from listUsers", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listUsers", {
      listusersresponse: {
        count: 1,
        user: [{
          id: "mock-uuid-alex",
          username: "alex",
          usersource: "native",
          state: "enabled",
          apikeyaccess: true,
          is2faenabled: true,
          is2famandated: false,
        }],
      },
    });

    await page.goto("/settings/security");

    await expect(page.getByRole("heading", { name: "Security" })).toBeVisible();
    await expect(page.getByText("Authentication source")).toBeVisible();
    await expect(page.getByText("native")).toBeVisible();
  });

  test("API token page renders getUserKeys status and can request key generation", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("getUserKeys", {
      getuserkeysresponse: {
        userkeys: {
          apikeyaccess: true,
          apikey: "api-key-12345678",
          secretkey: "secret-key-abcdefgh",
        },
      },
    });
    mockCloudStackBff.use("registerUserKeys", {
      registeruserkeysresponse: {
        userkeys: {
          id: "keypair-generated",
          apikey: "generated-api-key",
          secretkey: "generated-secret-key",
        },
      },
    });

    await page.goto("/settings/api-tokens");

    await expect(page.getByRole("heading", { name: "API tokens" })).toBeVisible();
    await expect(page.getByText("api-...5678")).toBeVisible();
    await page.getByRole("button", { name: "Generate key pair" }).click();
    await expect(page.getByRole("status")).toContainText("Generated key pair");
    expect(mockCloudStackBff.calls("registerUserKeys").at(-1)?.json).toMatchObject({
      id: "mock-uuid-alex",
    });
  });
});

const placeholderPages = [
  ["/settings/integrations", "Integrations", "No integrations configured"],
  ["/settings/billing", "Billing", "No billing settings available"],
  ["/settings/notifications", "Notifications", "No notification preferences configured"],
  ["/settings/advanced", "Advanced", "No advanced controls available"],
] as const;

test.describe("settings not-configured pages", () => {
  for (const [path, heading, emptyTitle] of placeholderPages) {
    test(`${path} keeps stable not-configured state`, async ({ page }) => {
      await page.goto(path);
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
      await expect(page.getByText(emptyTitle)).toBeVisible();
    });
  }
});
```

If the e2e branch runs before page branches merge, keep assertions compatible by allowing either the new operational text or the old empty-state text, then tighten assertions after coordinator merge.

- [ ] **Step 2: Verify and commit**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-settings-e2e/web
PLAYWRIGHT_PORT=3156 npm run test:e2e -- tests/e2e/settings-pages.spec.ts
npm run typecheck
cd ..
git diff --check
git add web/tests/e2e/settings-pages.spec.ts web/tests/e2e/README.md
git commit -m "Add settings surface browser coverage"
```

If Playwright fails with `EPERM` binding `127.0.0.1`, rerun the same command with sandbox escalation.

## Task 6: Optional Package Metadata Warning Cleanup

**Files:**
- Modify only if safe: `web/package.json`

- [ ] **Step 1: Reproduce the warning**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-package-metadata/web
npm run test:unit
```

Expected current warning:

```text
MODULE_TYPELESS_PACKAGE_JSON
```

- [ ] **Step 2: Add ESM package metadata**

Modify `web/package.json`:

```json
{
  "name": "cloudstack-web",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "description": "Modern role-based Apache CloudStack UI (Phase 5 rebuild)"
}
```

Keep all existing scripts and dependencies unchanged.

- [ ] **Step 3: Run full web verification in the branch**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-package-metadata/web
npm run test:unit
npm run typecheck
npm run lint
npm run build
PLAYWRIGHT_PORT=3157 npm run test:e2e -- tests/e2e/app-shell.spec.ts
```

Expected:

```text
# unit tests pass without MODULE_TYPELESS_PACKAGE_JSON warning
# typecheck/lint/build pass
# focused e2e passes
```

- [ ] **Step 4: Abort if config churn starts**

If adding `"type": "module"` requires changing Next config, Playwright config, TS config, imports, or more than `web/package.json`, revert this branch:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-package-metadata
git diff
git restore web/package.json
git status -sb
```

Expected:

```text
## phase5j-package-metadata
```

- [ ] **Step 5: Commit only if verification is green**

Run:

```bash
cd /Users/damian/Claude/cloudstack/.worktrees/phase5j-package-metadata
git diff --check
git add web/package.json
git commit -m "Mark web package as ESM"
```

If no commit is made, report `no commit; cleanup unsafe or unnecessary`.

## Task 7: Coordinator Merge And Verification

**Files:**
- Modify during merge: `web/messages/en.json`
- Modify during merge: `web/lib/settings-messages.test.ts`
- Modify after green batch: `/Users/damian/Claude/HANDOVER.md`

- [ ] **Step 1: Merge profile first**

Run:

```bash
cd /Users/damian/Claude/cloudstack
git checkout modernize-2026
git merge --no-ff phase5j-profile-settings -m "Merge Phase 5j profile settings"
```

Expected:

```text
Merge made by the 'ort' strategy.
```

- [ ] **Step 2: Merge API token slice**

Run:

```bash
git merge --no-ff phase5j-api-token-settings -m "Merge Phase 5j API token settings"
```

If `web/messages/en.json` conflicts, resolve by preserving both `Settings.pages.profile` and `Settings.pages.apiTokens`.

- [ ] **Step 3: Merge security slice**

Run:

```bash
git merge --no-ff phase5j-security-settings -m "Merge Phase 5j security settings"
```

If `security-settings.ts` duplicated types from `users.ts`, refactor to import from `./users.ts` before committing the merge resolution.

- [ ] **Step 4: Merge settings index**

Run:

```bash
git merge --no-ff phase5j-settings-index -m "Merge Phase 5j settings index"
```

Preserve all `Settings.pages.index`, `profile`, `security`, and `apiTokens` keys.

- [ ] **Step 5: Merge settings e2e**

Run:

```bash
git merge --no-ff phase5j-settings-e2e -m "Merge Phase 5j settings browser coverage"
```

Tighten e2e assertions if the branch used compatibility assertions before the page slices landed.

- [ ] **Step 6: Merge or discard package metadata**

If the package metadata branch has a green commit:

```bash
git merge --no-ff phase5j-package-metadata -m "Merge Phase 5j package metadata cleanup"
```

If it has no commit:

```bash
git branch -D phase5j-package-metadata
```

Only delete the branch if its worktree has already been removed or if Git allows it.

- [ ] **Step 7: Run full web verification**

Run:

```bash
cd /Users/damian/Claude/cloudstack/web
npm run test:unit
npm run typecheck
npm run lint
npm run build
PLAYWRIGHT_PORT=3158 npm run test:e2e
```

Expected:

```text
# all unit tests pass
# typecheck passes
# lint passes
# Next build passes
# Playwright Chromium tests pass
```

- [ ] **Step 8: Run root verification**

Run:

```bash
cd /Users/damian/Claude/cloudstack
git diff --check
git status -sb
git log --oneline -12
```

Expected:

```text
## modernize-2026
```

- [ ] **Step 9: Push**

Run:

```bash
git push origin modernize-2026
```

Expected:

```text
modernize-2026 -> modernize-2026
```

- [ ] **Step 10: Update handover**

Add a new top section to `/Users/damian/Claude/HANDOVER.md`:

```markdown
=== Latest Codex handback (Phase 5j settings surfaces landed) ===

HEAD at handback checkpoint: <new_sha> (`origin/modernize-2026` pushed after verification)

What landed:
- CloudStack-backed Profile settings page via `listUsers`.
- CloudStack-backed Security settings status via `listUsers`.
- CloudStack API-token status and key generation via `getUserKeys`/`registerUserKeys`.
- Practical Settings landing page.
- Settings browser coverage for active and not-configured settings routes.
- Optional package metadata cleanup: <landed/discarded with reason>.

Verification:
- `cd web && npm run test:unit`
- `cd web && npm run typecheck`
- `cd web && npm run lint`
- `cd web && npm run build`
- `cd web && PLAYWRIGHT_PORT=3158 npm run test:e2e`
- `git diff --check`

Next useful work:
1. Implement integrations, notifications, billing settings only once real CloudStack endpoints are selected.
2. Continue converting mock fallback surfaces to explicit unavailable/error UI where operator confidence matters.
3. Clean old Phase 5d/e temporary worktrees in a separate explicit cleanup pass.
```

## Cleanup After Merge

Only after all useful Phase 5j work is merged and pushed:

```bash
git worktree remove .worktrees/phase5j-profile-settings
git worktree remove .worktrees/phase5j-api-token-settings
git worktree remove .worktrees/phase5j-security-settings
git worktree remove .worktrees/phase5j-settings-index
git worktree remove .worktrees/phase5j-settings-e2e
git worktree remove .worktrees/phase5j-package-metadata
git branch -d phase5j-profile-settings phase5j-api-token-settings phase5j-security-settings phase5j-settings-index phase5j-settings-e2e phase5j-package-metadata
```

Do not remove old `.worktrees/phase5d-*` or `.worktrees/phase5e-*` as part of Phase 5j unless the user explicitly asks for that cleanup.

## Acceptance Criteria

Phase 5j is complete when:

- `/settings` is no longer an empty placeholder and links to all settings sections.
- `/settings/profile` renders current user identity/scope from `listUsers` with mock fallback.
- `/settings/security` renders current user security status from `listUsers` with mock fallback.
- `/settings/api-tokens` renders key status from `getUserKeys` and can generate a key pair through `registerUserKeys`, or generation is explicitly deferred with read-only status landed.
- Settings e2e tests cover active settings pages and the still-not-configured pages.
- Full web verification passes.
- `modernize-2026` is pushed.
- `/Users/damian/Claude/HANDOVER.md` points to the new checkpoint and this plan file.

## Self-Review

- Spec coverage: The plan covers the user-requested Phase 5j hold point, the queued settings slices, the optional package warning cleanup, merge/push, handover, and cleanup.
- Placeholder scan: The plan avoids TBD/TODO language. Optional items have explicit abort criteria and do not block completion.
- Type consistency: `users.ts` owns reusable user/listUsers types; `security-settings.ts` imports them during final merge. API-token names match CloudStack commands and response envelope casing already present in the repo.
