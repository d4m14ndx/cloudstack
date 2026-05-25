import type { Metadata } from "next";
import { headers } from "next/headers";
import Link from "next/link";
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
import { getKubernetesClustersFromBff } from "@/lib/cloudstack/kubernetes";
import type { KubernetesCluster } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.kubernetes");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.kubernetes");
  const clusters = await getKubernetesClustersFromBff({ requestHeaders: headers() });
  const running = clusters.filter((cluster) => cluster.state === "running").length;
  const updating = clusters.filter((cluster) => cluster.state === "updating").length;
  const degraded = clusters.filter((cluster) => cluster.state === "degraded").length;
  const nodes = clusters.reduce((sum, cluster) => sum + cluster.nodes, 0);
  const stateLabels: Record<KubernetesCluster["state"], string> = {
    running: t("states.running"),
    updating: t("states.updating"),
    degraded: t("states.degraded"),
  };

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { clusters: clusters.length, nodes })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: clusters.length })}</Badge>
        <Badge variant="success" size="md">{t("badges.running", { count: running })}</Badge>
        <Badge variant={updating > 0 ? "warning" : "default"} size="md">{t("badges.updating", { count: updating })}</Badge>
        <Badge variant={degraded > 0 ? "danger" : "default"} size="md">{t("badges.degraded", { count: degraded })}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[960px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.cluster")}</TableHead>
              <TableHead>{t("table.version")}</TableHead>
              <TableHead>{t("table.zone")}</TableHead>
              <TableHead>{t("table.account")}</TableHead>
              <TableHead className="text-right">{t("table.nodes")}</TableHead>
              <TableHead>{t("table.state")}</TableHead>
              <TableHead className="pr-4">{t("table.endpoint")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {clusters.length > 0 ? (
              clusters.map((cluster) => (
                <TableRow key={`${cluster.id}-${cluster.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <Link
                        href={`/kubernetes/${cluster.id}`}
                        className="truncate font-medium text-[color:var(--accent)] hover:underline"
                      >
                        {cluster.name}
                      </Link>
                      <Link
                        href={`/kubernetes/${cluster.id}`}
                        className="block truncate font-mono text-xs text-[color:var(--fg-muted)] hover:text-[color:var(--fg)]"
                      >
                        {cluster.id}
                      </Link>
                    </div>
                  </TableCell>
                  <TableCell>{cluster.version}</TableCell>
                  <TableCell>{cluster.zone}</TableCell>
                  <TableCell>{cluster.account}</TableCell>
                  <TableCell className="text-right tabular-nums">{cluster.nodes}</TableCell>
                  <TableCell>
                    <Badge variant={stateVariant(cluster.state)}>{stateLabel(cluster.state, stateLabels)}</Badge>
                  </TableCell>
                  <TableCell className="pr-4 font-mono text-xs">{cluster.endpoint}</TableCell>
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

function stateVariant(state: KubernetesCluster["state"]): "success" | "warning" | "danger" {
  switch (state) {
    case "running":
      return "success";
    case "updating":
      return "warning";
    default:
      return "danger";
  }
}

function stateLabel(
  state: KubernetesCluster["state"],
  labels: Record<KubernetesCluster["state"], string>,
): string {
  return labels[state];
}
