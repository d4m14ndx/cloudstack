import type { CurrentUser, Role } from "@/lib/auth/types";
import { roleMeetsRequirement } from "@/lib/auth/types";

export type { CurrentUser, Role } from "@/lib/auth/types";

export const mockUser: CurrentUser = {
  id: "mock-uuid-alex",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "mock-uuid-domain-root",
};

export function getMockCurrentUser(): CurrentUser {
  return mockUser;
}

/**
 * Client-safe Phase 5a compatibility helper.
 * Server code that needs the Auth.js session should import from
 * "@/lib/auth/server"; client shell components keep using this mock bridge.
 */
export function getCurrentUser(): CurrentUser {
  return getMockCurrentUser();
}

/**
 * Client-safe Phase 5a compatibility helper.
 * Server code that needs Auth.js-backed roles should import from
 * "@/lib/auth/server".
 */
export function hasRole(required: Role | Role[]): boolean {
  const user = getCurrentUser();
  return roleMeetsRequirement(user.role, required);
}
