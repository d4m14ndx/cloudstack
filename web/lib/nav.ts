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
import type { Role } from "@/lib/auth/mock";

export type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  badge?: string | number;
  /** Minimum role required */
  requires?: Role;
};

export type NavSection = {
  title: string;
  items: NavItem[];
  /** Minimum role required for the whole section to render */
  requires?: Role;
};

export const NAV_SECTIONS: NavSection[] = [
  {
    title: "Workspace",
    items: [
      { href: "/",             label: "Overview",   icon: IconOverview },
      { href: "/instances",    label: "Instances",  icon: IconInstances },
      { href: "/networks",     label: "Networks",   icon: IconNetworks },
      { href: "/volumes",      label: "Volumes",    icon: IconVolumes },
      { href: "/templates",    label: "Templates",  icon: IconTemplates },
      { href: "/kubernetes",   label: "Kubernetes", icon: IconKubernetes, badge: "BETA" },
      { href: "/events",       label: "Events",     icon: IconEvents },
    ],
  },
  {
    title: "Identity",
    items: [
      { href: "/accounts",  label: "Accounts",  icon: IconAccounts },
      { href: "/ssh-keys",  label: "SSH keys",  icon: IconSshKeys },
      { href: "/security",  label: "Security",  icon: IconSecurity },
    ],
  },
  {
    title: "Admin",
    requires: "ADMIN",
    items: [
      { href: "/infrastructure", label: "Infrastructure", icon: IconInfrastructure, requires: "ADMIN" },
      { href: "/domains",        label: "Domains",        icon: IconDomains,        requires: "ADMIN" },
      { href: "/billing",        label: "Billing",        icon: IconBilling },
    ],
  },
];
