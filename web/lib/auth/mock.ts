/**
 * Mock auth for Phase 5a.
 * Phase 5b replaces this with Auth.js v5 + Authentik OIDC.
 *
 * Shape matches what the real session callback will return so the
 * call sites don't need to change in 5b.
 */
export type Role = "USER" | "DOMAIN_ADMIN" | "ADMIN" | "ROOT";

export type CurrentUser = {
  id: string;
  username: string;
  email: string;
  name: string;
  role: Role;
  domain: string;
  domainId: string;
};

export const mockUser: CurrentUser = {
  id: "mock-uuid-alex",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "mock-uuid-domain-root",
};

/**
 * Phase 5a stub. Phase 5b: replace body with
 *   const session = await auth();
 *   return session?.user ?? null;
 */
export function getCurrentUser(): CurrentUser {
  return mockUser;
}

/**
 * Phase 5a stub. Phase 5b: roles come from session callback after Authentik
 * group → CloudStack role mapping.
 */
export function hasRole(required: Role | Role[]): boolean {
  const user = getCurrentUser();
  const set = Array.isArray(required) ? required : [required];
  const order: Record<Role, number> = { USER: 0, DOMAIN_ADMIN: 1, ADMIN: 2, ROOT: 3 };
  const userLevel = order[user.role];
  return set.some((r) => userLevel >= order[r]);
}
