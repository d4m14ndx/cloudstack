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
import { getInfrastructureFromBff } from "@/lib/cloudstack/infrastructure";
import type { Host } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.infrastructure");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.infrastructure");
  const hosts = await getInfrastructureFromBff({ requestHeaders: headers() });
  const up = hosts.filter((host) => host.state === "up").length;
  const maintenance = hosts.filter((host) => host.state === "maintenance").length;
  const alert = hosts.filter((host) => host.state === "alert").length;
  const instances = hosts.reduce((total, host) => total + host.instances, 0);

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { hosts: hosts.length, instances })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{hosts.length} total</Badge>
        <Badge variant={up > 0 ? "success" : "default"} size="md">{up} up</Badge>
        <Badge variant={maintenance > 0 ? "warning" : "default"} size="md">{maintenance} maintenance</Badge>
        <Badge variant={alert > 0 ? "danger" : "default"} size="md">{alert} alert</Badge>
        <Badge variant="default" size="md">{instances} instances</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[980px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Host</TableHead>
              <TableHead>State</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead>Cluster</TableHead>
              <TableHead>Hypervisor</TableHead>
              <TableHead className="text-right">CPU</TableHead>
              <TableHead className="text-right">Memory</TableHead>
              <TableHead className="pr-4 text-right">Instances</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {hosts.length > 0 ? (
              hosts.map((host) => (
                <TableRow key={`${host.id}-${host.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{host.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{host.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={stateVariant(host.state)}>{stateLabel(host.state)}</Badge>
                  </TableCell>
                  <TableCell>{host.zone}</TableCell>
                  <TableCell>{host.cluster}</TableCell>
                  <TableCell>
                    <Badge variant="default">{host.hypervisor}</Badge>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{host.cpu}%</TableCell>
                  <TableCell className="text-right tabular-nums">{host.mem}%</TableCell>
                  <TableCell className="pr-4 text-right tabular-nums">{host.instances}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={8}
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

function stateVariant(state: Host["state"]): "success" | "warning" | "danger" {
  switch (state) {
    case "maintenance":
      return "warning";
    case "alert":
      return "danger";
    default:
      return "success";
  }
}

function stateLabel(state: Host["state"]): string {
  return state === "up" ? "Up" : state.charAt(0).toUpperCase() + state.slice(1);
}
