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
import { getKubernetesClustersFromBff } from "@/lib/cloudstack/kubernetes";
import type { KubernetesCluster } from "@/lib/mock-data";

export const metadata = { title: "Kubernetes" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const clusters = await getKubernetesClustersFromBff({ requestHeaders: headers() });
  const running = clusters.filter((cluster) => cluster.state === "running").length;
  const updating = clusters.filter((cluster) => cluster.state === "updating").length;
  const degraded = clusters.filter((cluster) => cluster.state === "degraded").length;
  const nodes = clusters.reduce((sum, cluster) => sum + cluster.nodes, 0);

  return (
    <>
      <PageHeader
        title="Kubernetes"
        description={`${clusters.length} CloudStack-managed clusters with ${nodes} nodes across your scope`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{clusters.length} total</Badge>
        <Badge variant="success" size="md">{running} running</Badge>
        <Badge variant={updating > 0 ? "warning" : "default"} size="md">{updating} updating</Badge>
        <Badge variant={degraded > 0 ? "danger" : "default"} size="md">{degraded} degraded</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[960px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Cluster</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead>Account</TableHead>
              <TableHead className="text-right">Nodes</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="pr-4">Endpoint</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {clusters.length > 0 ? (
              clusters.map((cluster) => (
                <TableRow key={`${cluster.id}-${cluster.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{cluster.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{cluster.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>{cluster.version}</TableCell>
                  <TableCell>{cluster.zone}</TableCell>
                  <TableCell>{cluster.account}</TableCell>
                  <TableCell className="text-right tabular-nums">{cluster.nodes}</TableCell>
                  <TableCell>
                    <Badge variant={stateVariant(cluster.state)}>{stateLabel(cluster.state)}</Badge>
                  </TableCell>
                  <TableCell className="pr-4 font-mono text-xs">{cluster.endpoint}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No Kubernetes clusters found.
                </TableCell>
              </TableRow>
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

function stateLabel(state: KubernetesCluster["state"]): string {
  return state.charAt(0).toUpperCase() + state.slice(1);
}
