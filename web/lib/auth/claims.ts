import type { CurrentUser, Role } from "@/lib/auth/types";
import { isRole } from "@/lib/auth/types";

type Claims = Record<string, unknown>;

function firstString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value === "string" && value.trim().length > 0) {
      return value;
    }
  }
  return undefined;
}

function roleFromClaims(claims: Claims): Role {
  const direct = firstString(claims.cloudstack_role, claims.role);
  if (direct && isRole(direct.toUpperCase())) {
    return direct.toUpperCase() as Role;
  }

  const groups = Array.isArray(claims.groups) ? claims.groups : [];
  const normalizedGroups = groups
    .filter((group): group is string => typeof group === "string")
    .map((group) => group.toLowerCase());

  if (normalizedGroups.some((group) => group.includes("root"))) return "ROOT";
  if (normalizedGroups.some((group) => group.includes("admin"))) return "ADMIN";
  if (normalizedGroups.some((group) => group.includes("domain"))) return "DOMAIN_ADMIN";
  return "USER";
}

export function currentUserFromClaims(claims: Claims): CurrentUser {
  const id = firstString(claims.sub, claims.id) ?? "unknown";
  const username =
    firstString(claims.preferred_username, claims.nickname, claims.email, claims.name, claims.sub) ??
    id;
  const email = firstString(claims.email) ?? `${username}@cloudstack.local`;
  const name = firstString(claims.name, claims.given_name, claims.preferred_username) ?? username;

  return {
    id,
    username,
    email,
    name,
    role: roleFromClaims(claims),
    domain: firstString(claims.cloudstack_domain, claims.domain) ?? "ROOT",
    domainId: firstString(claims.cloudstack_domain_id, claims.domainid, claims.domainId) ?? "ROOT",
  };
}
