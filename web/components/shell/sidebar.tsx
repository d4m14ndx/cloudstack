"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { BrandMark, BrandWordmark } from "@/components/shell/brand-mark";
import { ScopeSwitcher } from "@/components/shell/scope-switcher";
import { UserFooter } from "@/components/shell/user-footer";
import { Badge } from "@/components/ui/badge";
import { NAV_SECTIONS } from "@/lib/nav";
import { getCurrentUser, hasRole } from "@/lib/auth/mock";
import { useTweaks } from "@/lib/store/tweaks";
import { cn } from "@/lib/utils";

export function Sidebar() {
  const pathname = usePathname();
  const tNav = useTranslations("Navigation");
  const tShell = useTranslations("Shell.environment");
  const user = getCurrentUser();
  const sidebarStyle = useTweaks((s) => s.sidebarStyle);
  const compact = sidebarStyle === "compact";

  return (
    <aside
      className={cn(
        "row-span-2 flex flex-col border-r border-[color:var(--border)] bg-[color:var(--surface-2)]",
        "w-[var(--sidebar-w)]"
      )}
    >
      {/* Brand */}
      <div className={cn("flex items-center gap-2.5 px-4 py-3.5", compact && "justify-center px-0")}>
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-white">
          <BrandMark size={20} />
        </div>
        {!compact && (
          <>
            <BrandWordmark className="text-[15px] tracking-tight" />
            <Badge variant="default" className="ml-auto text-[9.5px]">{tShell("production")}</Badge>
          </>
        )}
      </div>

      {/* Scope */}
      <div className={cn("px-3 pb-2", compact && "px-2")}>
        <ScopeSwitcher compact={compact} />
      </div>

      {/* Nav */}
      <nav className={cn("flex-1 overflow-y-auto px-2 py-2", compact && "px-2")}>
        {NAV_SECTIONS.map((section) => {
          if (section.requires && !hasRole(section.requires)) return null;
          const sectionTitle = tNav(section.titleKey);
          return (
            <div key={section.titleKey} className="mb-4">
              {!compact && (
                <div className="px-3 py-1.5 text-[10.5px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">
                  {sectionTitle}
                </div>
              )}
              <ul className="space-y-0.5">
                {section.items.map((item) => {
                  if (item.requires && !hasRole(item.requires)) return null;
                  const itemLabel = tNav(item.labelKey);
                  const active =
                    item.href === "/"
                      ? pathname === "/"
                      : pathname.startsWith(item.href);
                  const Icon = item.icon;
                  return (
                    <li key={item.href}>
                      <Link
                        href={item.href as never}
                        aria-label={itemLabel}
                        title={itemLabel}
                        className={cn(
                          "relative flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors",
                          compact && "h-10 justify-center px-0",
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
                        <Icon size={16} strokeWidth={1.6} aria-hidden />
                        {!compact && <span className="flex-1">{itemLabel}</span>}
                        {!compact && item.badgeKey && (
                          <Badge
                            variant="accent"
                            className="text-[9.5px]"
                          >
                            {tNav(item.badgeKey)}
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
      <UserFooter user={user} compact={compact} />
    </aside>
  );
}
