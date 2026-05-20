import "server-only";

import { auth } from "@/auth";
import { mockUser } from "@/lib/auth/mock";
import type { CurrentUser, Role } from "@/lib/auth/types";
import { roleMeetsRequirement } from "@/lib/auth/types";

export async function getCurrentUser(): Promise<CurrentUser> {
  const session = await auth();
  return session?.user ?? mockUser;
}

export async function hasRole(required: Role | Role[]): Promise<boolean> {
  const user = await getCurrentUser();
  return roleMeetsRequirement(user.role, required);
}
