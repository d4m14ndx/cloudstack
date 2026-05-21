import type { Role } from "@/lib/auth/mock";

export type NavigationMessageKey =
  | "sections.workspace"
  | "sections.identity"
  | "sections.admin"
  | "items.overview"
  | "items.instances"
  | "items.networks"
  | "items.volumes"
  | "items.templates"
  | "items.kubernetes"
  | "items.events"
  | "items.accounts"
  | "items.sshKeys"
  | "items.security"
  | "items.infrastructure"
  | "items.domains"
  | "items.billing"
  | "badges.beta";

export type NavIconKey =
  | "overview"
  | "instances"
  | "networks"
  | "volumes"
  | "templates"
  | "kubernetes"
  | "events"
  | "accounts"
  | "sshKeys"
  | "security"
  | "infrastructure"
  | "domains"
  | "billing";

export type NavItemDefinition = {
  href: string;
  labelKey: NavigationMessageKey;
  iconKey: NavIconKey;
  badgeKey?: NavigationMessageKey;
  /** Minimum role required */
  requires?: Role;
};

export type NavSectionDefinition = {
  titleKey: NavigationMessageKey;
  items: NavItemDefinition[];
  /** Minimum role required for the whole section to render */
  requires?: Role;
};

export const NAV_SECTION_DEFINITIONS: NavSectionDefinition[] = [
  {
    titleKey: "sections.workspace",
    items: [
      { href: "/",             labelKey: "items.overview",   iconKey: "overview" },
      { href: "/instances",    labelKey: "items.instances",  iconKey: "instances" },
      { href: "/networks",     labelKey: "items.networks",   iconKey: "networks" },
      { href: "/volumes",      labelKey: "items.volumes",    iconKey: "volumes" },
      { href: "/templates",    labelKey: "items.templates",  iconKey: "templates" },
      { href: "/kubernetes",   labelKey: "items.kubernetes", iconKey: "kubernetes", badgeKey: "badges.beta" },
      { href: "/events",       labelKey: "items.events",     iconKey: "events" },
    ],
  },
  {
    titleKey: "sections.identity",
    items: [
      { href: "/accounts", labelKey: "items.accounts", iconKey: "accounts" },
      { href: "/ssh-keys", labelKey: "items.sshKeys",  iconKey: "sshKeys" },
      { href: "/security", labelKey: "items.security", iconKey: "security" },
    ],
  },
  {
    titleKey: "sections.admin",
    requires: "ADMIN",
    items: [
      { href: "/infrastructure", labelKey: "items.infrastructure", iconKey: "infrastructure", requires: "ADMIN" },
      { href: "/domains",        labelKey: "items.domains",        iconKey: "domains",        requires: "ADMIN" },
      { href: "/billing",        labelKey: "items.billing",        iconKey: "billing" },
    ],
  },
];
