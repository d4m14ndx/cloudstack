import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { InstanceActions } from "@/components/instances/instance-actions";
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
import { getInstancesFromBff } from "@/lib/cloudstack/instances";
import type { Instance } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Compute.pages.instances");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Compute.pages.instances");
  const instances = await getInstancesFromBff({ requestHeaders: headers() });
  const running = instances.filter((instance) => instance.state === "running").length;
  const stopped = instances.filter((instance) => instance.state === "stopped").length;
  const attention = instances.filter(
    (instance) => instance.state === "starting" || instance.state === "error",
  ).length;
  const usageLabels = { cpu: t("usage.cpu"), memory: t("usage.memory") };

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { instances: instances.length, running })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="success" size="md">{t("badges.running", { count: running })}</Badge>
        <Badge variant="default" size="md">{t("badges.stopped", { count: stopped })}</Badge>
        <Badge variant={attention > 0 ? "warning" : "default"} size="md">
          {t("badges.attention", { count: attention })}
        </Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[980px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.instance")}</TableHead>
              <TableHead>{t("table.state")}</TableHead>
              <TableHead>{t("table.zone")}</TableHead>
              <TableHead>{t("table.network")}</TableHead>
              <TableHead>{t("table.ip")}</TableHead>
              <TableHead>{t("table.offering")}</TableHead>
              <TableHead className="text-right">{t("table.cpu")}</TableHead>
              <TableHead className="text-right">{t("table.ram")}</TableHead>
              <TableHead className="text-right">{t("table.usage")}</TableHead>
              <TableHead className="pr-4">{t("table.account")}</TableHead>
              <TableHead className="pr-4 text-right">{t("table.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {instances.length > 0 ? (
              instances.map((instance) => (
                <TableRow key={`${instance.id}-${instance.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{instance.name}</div>
                      <div className="truncate text-xs text-[color:var(--fg-muted)]">{instance.template}</div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={stateVariant(instance.state)}>{stateLabel(instance.state)}</Badge>
                  </TableCell>
                  <TableCell>{instance.zone}</TableCell>
                  <TableCell>{instance.network}</TableCell>
                  <TableCell>
                    <div className="font-mono text-xs tabular-nums">{instance.ip}</div>
                    {instance.publicIp && (
                      <div className="font-mono text-[11px] text-[color:var(--fg-muted)] tabular-nums">
                        {instance.publicIp}
                      </div>
                    )}
                  </TableCell>
                  <TableCell>{instance.offering}</TableCell>
                  <TableCell className="text-right tabular-nums">{instance.cpu}</TableCell>
                  <TableCell className="text-right tabular-nums">{instance.ram} GiB</TableCell>
                  <TableCell className="text-right">
                    <UsagePair instance={instance} labels={usageLabels} />
                  </TableCell>
                  <TableCell className="pr-4">{instance.account}</TableCell>
                  <TableCell className="pr-4">
                    <InstanceActions id={instance.id} name={instance.name} state={instance.state} />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={11}
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

function UsagePair({
  instance,
  labels,
}: {
  instance: Instance;
  labels: { cpu: string; memory: string };
}) {
  return (
    <div className="inline-flex min-w-20 flex-col items-end gap-0.5 font-mono text-xs tabular-nums">
      <span>{labels.cpu} {formatPercent(instance.cpuUsage)}</span>
      <span className="text-[color:var(--fg-muted)]">
        {labels.memory} {formatPercent(instance.memUsage)}
      </span>
    </div>
  );
}

function formatPercent(value: number): string {
  return `${Math.max(0, Math.min(100, Math.round(value)))}%`;
}

function stateVariant(state: Instance["state"]): "success" | "warning" | "danger" | "default" {
  switch (state) {
    case "running":
      return "success";
    case "starting":
      return "warning";
    case "error":
      return "danger";
    default:
      return "default";
  }
}

function stateLabel(state: Instance["state"]): string {
  return state.charAt(0).toUpperCase() + state.slice(1);
}
