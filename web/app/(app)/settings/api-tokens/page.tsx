import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { ApiTokenActions } from "@/components/settings/api-token-actions";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getMockCurrentUser } from "@/lib/auth/mock";
import { getCurrentUser as getServerCurrentUser } from "@/lib/auth/server";
import { getUserApiTokenSummaryFromBff } from "@/lib/cloudstack/api-tokens";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.apiTokens");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.apiTokens");
  const user = (await getServerCurrentUser()) ?? getMockCurrentUser();
  const summary = await getUserApiTokenSummaryFromBff(user.id, { requestHeaders: headers() });

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <Card>
          <CardHeader>
            <CardTitle>{t("current.title")}</CardTitle>
            <Badge variant={summary.access === "enabled" ? "success" : "warning"}>
              {t(`access.${summary.access}`)}
            </Badge>
          </CardHeader>
          <CardContent className="space-y-3">
            <TokenRow label={t("current.access")} value={t(`access.${summary.access}`)} />
            <TokenRow label={t("current.apiKey")} value={summary.apiKeyMasked} mono />
            <TokenRow label={t("current.secretKey")} value={summary.secretKeyMasked} mono />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("generate.title")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="mb-4 text-sm text-[color:var(--fg-muted)]">{t("generate.description")}</p>
            <ApiTokenActions
              userId={user.id}
              generateLabel={t("generate.action")}
              pendingLabel={t("generate.pending")}
              successLabel={t("generate.success")}
              errorLabel={t("generate.error")}
              apiKeyLabel={t("generate.apiKey")}
              secretKeyLabel={t("generate.secretKey")}
              oneTimeSecretLabel={t("generate.oneTimeSecret")}
            />
          </CardContent>
        </Card>
      </div>
    </>
  );
}

function TokenRow({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="grid gap-1 rounded-md border border-[color:var(--border)] p-3 sm:grid-cols-[150px_1fr]">
      <span className="text-sm text-[color:var(--fg-dim)]">{label}</span>
      <span
        className={
          mono
            ? "min-w-0 break-all font-mono text-xs text-[color:var(--fg)] sm:text-right"
            : "text-sm font-medium text-[color:var(--fg)] sm:text-right"
        }
      >
        {value}
      </span>
    </div>
  );
}
