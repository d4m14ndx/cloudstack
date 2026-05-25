import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { TableEmptyState } from "@/components/ui/empty-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getBillingQuotaSummariesFromBff } from "@/lib/cloudstack/billing";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.billing");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.billing");
  const summaries = await getBillingQuotaSummariesFromBff({ requestHeaders: headers() });
  const enabled = summaries.filter((summary) => summary.quotaState === "enabled").length;
  const disabled = summaries.length - enabled;
  const removed = summaries.filter((summary) => summary.lifecycle === "removed").length;
  const currencies = [...new Set(summaries.map((summary) => summary.currency).filter(Boolean))];
  const currencyBadge =
    currencies.length === 0
      ? t("currency.noCurrency")
      : currencies.length === 1
        ? currencies[0] ?? t("currency.noCurrency")
        : t("currency.multipleCurrencies", { count: currencies.length });

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { summaries: summaries.length })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.summaries", { count: summaries.length })}</Badge>
        <Badge variant="success" size="md">{t("badges.quotaEnabled", { count: enabled })}</Badge>
        <Badge variant={disabled > 0 ? "warning" : "default"} size="md">{t("badges.disabled", { count: disabled })}</Badge>
        <Badge variant={removed > 0 ? "danger" : "default"} size="md">{t("badges.removed", { count: removed })}</Badge>
        <Badge variant="accent" size="md">{currencyBadge}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[1040px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.accountProject")}</TableHead>
              <TableHead>{t("table.domain")}</TableHead>
              <TableHead>{t("table.accountState")}</TableHead>
              <TableHead>{t("table.quotaState")}</TableHead>
              <TableHead className="text-right">{t("table.balance")}</TableHead>
              <TableHead className="text-right">{t("table.currentPeriodUsage")}</TableHead>
              <TableHead className="pr-4">{t("table.period")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {summaries.length > 0 ? (
              summaries.map((summary) => (
                <TableRow key={summary.id}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="flex min-w-0 items-center gap-2">
                        <span className="truncate font-medium">{summary.name}</span>
                        {summary.lifecycle === "removed" && <Badge variant="danger">{t("states.removed")}</Badge>}
                      </div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{summary.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>{summary.domain}</TableCell>
                  <TableCell>
                    <Badge variant={accountStateVariant(summary.accountState)}>
                      {stateLabel(summary.accountState, t("states.unknown"))}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={summary.quotaState === "enabled" ? "success" : "warning"}>
                      {summary.quotaState === "enabled" ? t("states.enabled") : t("states.disabled")}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    {amountLabel(summary.balance, summary.currency)}
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    {amountLabel(summary.periodUsage, summary.currency)}
                  </TableCell>
                  <TableCell className="pr-4 font-mono text-xs">{summary.period}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={7}
                title={t("emptyState.title")}
                description={t("emptyState.description")}
              />
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function amountLabel(amount: string, currency: string): string {
  if (!amount) {
    return "-";
  }

  return currency ? `${currency} ${amount}` : amount;
}

function accountStateVariant(state: string): "success" | "warning" | "default" {
  switch (state) {
    case "active":
    case "enabled":
      return "success";
    case "disabled":
    case "locked":
    case "suspended":
      return "warning";
    default:
      return "default";
  }
}

function stateLabel(state: string, unknownLabel: string): string {
  return state
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ") || unknownLabel;
}
