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

export const ROLE_ORDER: Record<Role, number> = {
  USER: 0,
  DOMAIN_ADMIN: 1,
  ADMIN: 2,
  ROOT: 3,
};

export function isRole(value: unknown): value is Role {
  return (
    value === "USER" ||
    value === "DOMAIN_ADMIN" ||
    value === "ADMIN" ||
    value === "ROOT"
  );
}

export function roleMeetsRequirement(userRole: Role, required: Role | Role[]): boolean {
  const roles = Array.isArray(required) ? required : [required];
  const userLevel = ROLE_ORDER[userRole];
  return roles.some((role) => userLevel >= ROLE_ORDER[role]);
}
