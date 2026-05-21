import "server-only";

import { auth } from "@/auth";
import { mockUser } from "@/lib/auth/mock";
import type { CurrentUser, Role } from "@/lib/auth/types";
import { roleMeetsRequirement } from "@/lib/auth/types";

export async function getAuthenticatedUser(): Promise<CurrentUser | null> {
  const session = await auth();
  return session?.user ?? null;
}

export async function getCurrentUser(): Promise<CurrentUser> {
  return (await getAuthenticatedUser()) ?? mockUser;
}

export async function hasRole(required: Role | Role[]): Promise<boolean> {
  const user = await getCurrentUser();
  return roleMeetsRequirement(user.role, required);
}
