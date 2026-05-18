"use client";

import { IconLogout } from "@/components/icons";
import { hashString } from "@/lib/utils";
import type { CurrentUser } from "@/lib/auth/mock";

const AVATAR_PALETTE = [
  "#5b5bf5", "#ec4899", "#10b981", "#f59e0b", "#06b6d4", "#a855f7",
];

function initialsFor(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join("");
}

export function UserFooter({ user }: { user: CurrentUser }) {
  const initials = initialsFor(user.name);
  const color = AVATAR_PALETTE[hashString(user.email) % AVATAR_PALETTE.length]!;
  const roleLabel =
    user.role === "ROOT" ? "Root admin"
    : user.role === "ADMIN" ? "Admin"
    : user.role === "DOMAIN_ADMIN" ? "Domain admin"
    : "User";

  return (
    <div className="flex items-center gap-2.5 border-t border-[color:var(--border)] px-3 py-2.5">
      <span
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold text-white"
        style={{ background: color }}
      >
        {initials}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-[color:var(--fg)]">{user.name}</span>
        <span className="block truncate text-[11px] text-[color:var(--fg-dim)]">{roleLabel}</span>
      </span>
      <button
        type="button"
        className="rounded-md p-1.5 text-[color:var(--fg-dim)] transition-colors hover:bg-[color:var(--surface-3)] hover:text-[color:var(--fg)]"
        aria-label="Log out"
        title="Log out"
      >
        <IconLogout size={15} strokeWidth={1.6} />
      </button>
    </div>
  );
}
