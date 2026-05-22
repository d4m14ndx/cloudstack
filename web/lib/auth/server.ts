import "server-only";

import { auth } from "@/auth";
import type { CurrentUser, Role } from "@/lib/auth/types";
import { roleMeetsRequirement } from "@/lib/auth/types";

/**
 * Resolve the current Auth.js session into a CurrentUser, or null if no
 * session is present. Use this in server components, server actions, and
 * route handlers that need real authentication.
 *
 * Server code MUST NOT fall back to `mockUser` here — silently returning
 * a hardcoded ROOT user when no session exists turns absence-of-auth into
 * presence-of-root and is exactly the shape of bug we want to avoid.
 * Pages that legitimately want a placeholder during Phase 5a shell work
 * can still import `mockUser` from `@/lib/auth/mock` explicitly.
 */
export async function getAuthenticatedUser(): Promise<CurrentUser | null> {
  const session = await auth();
  return session?.user ?? null;
}

/**
 * Backwards-compatible alias for {@link getAuthenticatedUser}. Returns null
 * if no session is present rather than the Phase 5a mock user — callers
 * that genuinely want the mock should import it directly.
 */
export async function getCurrentUser(): Promise<CurrentUser | null> {
  return getAuthenticatedUser();
}

/**
 * Deny-by-default role check. Returns false when no session is present so
 * authorisation gates fail closed.
 */
export async function hasRole(required: Role | Role[]): Promise<boolean> {
  const user = await getAuthenticatedUser();
  if (!user) {
    return false;
  }
  return roleMeetsRequirement(user.role, required);
}
