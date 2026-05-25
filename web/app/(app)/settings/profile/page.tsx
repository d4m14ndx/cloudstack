import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getCurrentUser } from "@/lib/auth/server";
import { getCurrentUserProfileFromBff } from "@/lib/cloudstack/users";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.profile");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.profile");
  const currentUser = await getCurrentUser();
  const profile = await getCurrentUserProfileFromBff(currentUser, { requestHeaders: headers() });
  const rows = [
    [t("fields.username"), profile.username],
    [t("fields.email"), profile.email],
    [t("fields.account"), profile.account],
    [t("fields.domain"), profile.domain],
    [t("fields.timezone"), profile.timezone],
    [t("fields.source"), profile.source],
  ] as const;
  const normalizedState = profile.state.toLowerCase();

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <Card>
          <CardHeader>
            <CardTitle>{profile.displayName}</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-3 sm:grid-cols-2">
              {rows.map(([label, value]) => (
                <div key={label} className="rounded-md border border-[color:var(--border)] p-3">
                  <dt className="text-xs text-[color:var(--fg-dim)]">{label}</dt>
                  <dd className="mt-1 break-words text-sm font-medium text-[color:var(--fg)]">{value}</dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("summary.title")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <SummaryRow label={t("summary.role")} value={profile.role} />
            <SummaryRow label={t("summary.state")} value={profile.state} />
            <SummaryRow label={t("summary.apiKeyAccess")} value={t(`apiKeyAccess.${profile.apiKeyAccess}`)} />
            <SummaryRow
              label={t("summary.twoFactor")}
              value={profile.twoFactorEnabled ? t("states.enabled") : t("states.disabled")}
            />
            <Badge variant={normalizedState === "enabled" || normalizedState === "active" ? "success" : "warning"}>
              {profile.state}
            </Badge>
          </CardContent>
        </Card>
      </div>
    </>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-[color:var(--fg-dim)]">{label}</span>
      <span className="break-words text-right font-medium text-[color:var(--fg)]">{value}</span>
    </div>
  );
}
