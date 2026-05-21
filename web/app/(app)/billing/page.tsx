import { headers } from "next/headers";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getBillingQuotaSummariesFromBff } from "@/lib/cloudstack/billing";

export const metadata = { title: "Billing quota summary" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const summaries = await getBillingQuotaSummariesFromBff({ requestHeaders: headers() });
  const enabled = summaries.filter((summary) => summary.quotaState === "enabled").length;
  const disabled = summaries.length - enabled;
  const removed = summaries.filter((summary) => summary.lifecycle === "removed").length;
  const currencies = [...new Set(summaries.map((summary) => summary.currency).filter(Boolean))];

  return (
    <>
      <PageHeader
        title="Billing quota summary"
        description={`${summaries.length} quota summaries from CloudStack quotaSummary across active accounts and projects`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{summaries.length} summaries</Badge>
        <Badge variant="success" size="md">{enabled} quota enabled</Badge>
        <Badge variant={disabled > 0 ? "warning" : "default"} size="md">{disabled} disabled</Badge>
        <Badge variant={removed > 0 ? "danger" : "default"} size="md">{removed} removed</Badge>
        <Badge variant="accent" size="md">{currencyLabel(currencies)}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[1040px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Account/project</TableHead>
              <TableHead>Domain</TableHead>
              <TableHead>Account state</TableHead>
              <TableHead>Quota state</TableHead>
              <TableHead className="text-right">Balance</TableHead>
              <TableHead className="text-right">Current-period usage</TableHead>
              <TableHead className="pr-4">Period</TableHead>
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
                        {summary.lifecycle === "removed" && <Badge variant="danger">Removed</Badge>}
                      </div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{summary.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>{summary.domain}</TableCell>
                  <TableCell>
                    <Badge variant={accountStateVariant(summary.accountState)}>
                      {stateLabel(summary.accountState)}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={summary.quotaState === "enabled" ? "success" : "warning"}>
                      {summary.quotaState === "enabled" ? "Enabled" : "Disabled"}
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
              <TableRow>
                <TableCell colSpan={7} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No quota summaries found.
                </TableCell>
              </TableRow>
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

function stateLabel(state: string): string {
  return state
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ") || "Unknown";
}

function currencyLabel(currencies: string[]): string {
  if (currencies.length === 0) {
    return "No currency";
  }

  if (currencies.length === 1) {
    return currencies[0] ?? "No currency";
  }

  return `${currencies.length} currencies`;
}
