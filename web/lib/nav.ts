/**
 * Sidebar navigation tree. Sections are gated by role; for Phase 5a all show
 * because mock user is ROOT. Phase 5b will filter via real session role.
 */
import type { LucideIcon } from "lucide-react";
import {
  IconOverview,
  IconInstances,
  IconNetworks,
  IconVolumes,
  IconTemplates,
  IconKubernetes,
  IconEvents,
  IconAccounts,
  IconSshKeys,
  IconSecurity,
  IconInfrastructure,
  IconDomains,
  IconBilling,
} from "@/components/icons";
import {
  NAV_SECTION_DEFINITIONS,
  type NavIconKey,
  type NavigationMessageKey,
} from "@/lib/nav-data";
import type { Role } from "@/lib/auth/mock";

export type NavItem = {
  href: string;
  labelKey: NavigationMessageKey;
  icon: LucideIcon;
  badgeKey?: NavigationMessageKey;
  /** Minimum role required */
  requires?: Role;
};

export type NavSection = {
  titleKey: NavigationMessageKey;
  items: NavItem[];
  /** Minimum role required for the whole section to render */
  requires?: Role;
};

const NAV_ICONS: Record<NavIconKey, LucideIcon> = {
  overview: IconOverview,
  instances: IconInstances,
  networks: IconNetworks,
  volumes: IconVolumes,
  templates: IconTemplates,
  kubernetes: IconKubernetes,
  events: IconEvents,
  accounts: IconAccounts,
  sshKeys: IconSshKeys,
  security: IconSecurity,
  infrastructure: IconInfrastructure,
  domains: IconDomains,
  billing: IconBilling,
};

export const NAV_SECTIONS: NavSection[] = NAV_SECTION_DEFINITIONS.map((section) => ({
  titleKey: section.titleKey,
  requires: section.requires,
  items: section.items.map((item) => ({
    href: item.href,
    labelKey: item.labelKey,
    icon: NAV_ICONS[item.iconKey],
    badgeKey: item.badgeKey,
    requires: item.requires,
  })),
}));
