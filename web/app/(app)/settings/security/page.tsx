import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { mockUser } from "@/lib/auth/mock";
import { getCurrentUser } from "@/lib/auth/server";
import { getCurrentUserSecuritySettingsFromBff } from "@/lib/cloudstack/security-settings";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.security");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.security");
  const authenticatedUser = await getCurrentUser();
  const user = authenticatedUser ?? mockUser;
  const settings = await getCurrentUserSecuritySettingsFromBff(user, { requestHeaders: headers() });

  const rows = [
    { label: t("fields.source"), value: formatStatusText(settings.source) },
    { label: t("fields.state"), value: formatStatusText(settings.state) },
    { label: t("fields.apiKeyAccess"), value: t(`access.${settings.apiKeyAccess}`) },
    { label: t("fields.twoFactorEnabled"), value: settings.twoFactorEnabled ? t("states.enabled") : t("states.disabled") },
    { label: t("fields.twoFactorMandated"), value: settings.twoFactorMandated ? t("states.enabled") : t("states.disabled") },
    { label: t("fields.passwordChangeRequired"), value: settings.passwordChangeRequired ? t("states.required") : t("states.notRequired") },
  ] as const;

  const twoFactorStatus = settings.twoFactorEnabled ? t("states.enabled") : t("states.disabled");
  const apiKeyStatus = t(`access.${settings.apiKeyAccess}`);

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <Card>
        <CardHeader>
          <CardTitle>{t("summary.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {rows.map((row) => (
              <div key={row.label} className="min-h-[76px] rounded-md border border-[color:var(--border)] p-3">
                <dt className="text-xs text-[color:var(--fg-dim)]">{row.label}</dt>
                <dd className="mt-1 text-sm font-medium text-[color:var(--fg)]">{row.value}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-4 flex flex-wrap gap-2">
            <Badge
              variant={settings.twoFactorEnabled ? "success" : "warning"}
              size="md"
              aria-label={`${t("badges.twoFactor")}: ${twoFactorStatus}`}
            >
              {t("badges.twoFactor")}
            </Badge>
            <Badge
              variant={settings.apiKeyAccess === "enabled" ? "success" : "warning"}
              size="md"
              aria-label={`${t("badges.apiKeyAccess")}: ${apiKeyStatus}`}
            >
              {t("badges.apiKeyAccess")}
            </Badge>
          </div>
        </CardContent>
      </Card>
    </>
  );
}

function formatStatusText(value: string): string {
  return value
    .split(/[\s_-]+/)
    .filter(Boolean)
    .map((part) => part[0]?.toUpperCase() + part.slice(1))
    .join(" ");
}
