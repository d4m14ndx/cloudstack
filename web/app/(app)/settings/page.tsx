import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { Bell, Building2, KeyRound, Languages, ShieldCheck, UserRound, MonitorCheck } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const SECTIONS = [
  { key: "profile", href: "/settings/profile", state: "available", icon: UserRound },
  { key: "security", href: "/settings/security", state: "available", icon: ShieldCheck },
  { key: "apiTokens", href: "/settings/api-tokens", state: "available", icon: KeyRound },
  { key: "localization", href: "/settings/profile", state: "related", icon: Languages },
  { key: "notifications", href: "/settings/notifications", state: "notConfigured", icon: Bell },
  { key: "sessions", href: "/settings/security", state: "related", icon: MonitorCheck },
  { key: "account", href: "/accounts", state: "related", icon: Building2 },
] as const;

type SectionState = (typeof SECTIONS)[number]["state"];

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.index");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.index");

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {SECTIONS.map((section) => {
          const Icon = section.icon;

          return (
            <Link
              key={section.key}
              href={section.href}
              className="group block h-full rounded-[var(--radius-lg)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--accent)]"
            >
              <Card className="flex h-full flex-col gap-3 p-4 transition-colors group-hover:border-[color:var(--border-strong)]">
                <CardHeader className="mb-0 items-start gap-3">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-[color:var(--surface-2)] text-[color:var(--fg-muted)]">
                      <Icon aria-hidden="true" className="h-4 w-4" />
                    </span>
                    <CardTitle className="text-sm tracking-normal">
                      {t(`sections.${section.key}.title`)}
                    </CardTitle>
                  </div>
                  <Badge variant={stateVariant(section.state)}>{t(`states.${section.state}`)}</Badge>
                </CardHeader>
                <CardContent>
                  <p className="text-sm leading-6 text-[color:var(--fg-muted)]">
                    {t(`sections.${section.key}.description`)}
                  </p>
                </CardContent>
              </Card>
            </Link>
          );
        })}
      </div>
    </>
  );
}

function stateVariant(state: SectionState): "success" | "accent" | "default" {
  switch (state) {
    case "available":
      return "success";
    case "related":
      return "accent";
    case "notConfigured":
      return "default";
  }
}
