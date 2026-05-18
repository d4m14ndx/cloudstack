"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { BrandMark, BrandWordmark } from "@/components/shell/brand-mark";
import { ScopeSwitcher } from "@/components/shell/scope-switcher";
import { UserFooter } from "@/components/shell/user-footer";
import { Badge } from "@/components/ui/badge";
import { NAV_SECTIONS } from "@/lib/nav";
import { getCurrentUser, hasRole } from "@/lib/auth/mock";
import { cn } from "@/lib/utils";

export function Sidebar() {
  const pathname = usePathname();
  const user = getCurrentUser();

  return (
    <aside
      className={cn(
        "row-span-2 flex flex-col border-r border-[color:var(--border)] bg-[color:var(--surface-2)]",
        "w-[var(--sidebar-w)]"
      )}
    >
      {/* Brand */}
      <div className="flex items-center gap-2.5 px-4 py-3.5">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-white">
          <BrandMark size={20} />
        </div>
        <BrandWordmark className="text-[15px] tracking-tight" />
        <Badge variant="default" className="ml-auto text-[9.5px]">prod</Badge>
      </div>

      {/* Scope */}
      <div className="px-3 pb-2">
        <ScopeSwitcher />
      </div>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto px-2 py-2">
        {NAV_SECTIONS.map((section) => {
          if (section.requires && !hasRole(section.requires)) return null;
          return (
            <div key={section.title} className="mb-4">
              <div className="px-3 py-1.5 text-[10.5px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">
                {section.title}
              </div>
              <ul className="space-y-0.5">
                {section.items.map((item) => {
                  if (item.requires && !hasRole(item.requires)) return null;
                  const active =
                    item.href === "/"
                      ? pathname === "/"
                      : pathname.startsWith(item.href);
                  const Icon = item.icon;
                  return (
                    <li key={item.href}>
                      <Link
                        href={item.href as never}
                        className={cn(
                          "relative flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors",
                          active
                            ? "bg-[color:var(--surface-3)] text-[color:var(--fg)]"
                            : "text-[color:var(--fg-muted)] hover:bg-[color:var(--surface-3)]/60 hover:text-[color:var(--fg)]"
                        )}
                      >
                        {active && (
                          <span
                            className="absolute -left-2 top-1/2 h-5 w-[2.5px] -translate-y-1/2 rounded-r-full"
                            style={{ background: "var(--accent)" }}
                            aria-hidden
                          />
                        )}
                        <Icon size={16} strokeWidth={1.6} />
                        <span className="flex-1">{item.label}</span>
                        {item.badge && (
                          <Badge
                            variant={typeof item.badge === "string" ? "accent" : "default"}
                            className="text-[9.5px]"
                          >
                            {item.badge}
                          </Badge>
                        )}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </nav>

      {/* User footer */}
      <UserFooter user={user} />
    </aside>
  );
}
