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
import { getInstancesFromBff } from "@/lib/cloudstack/instances";
import type { Instance } from "@/lib/mock-data";

export const metadata = { title: "Instances" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const instances = await getInstancesFromBff({ requestHeaders: headers() });
  const running = instances.filter((instance) => instance.state === "running").length;
  const stopped = instances.filter((instance) => instance.state === "stopped").length;
  const attention = instances.filter((instance) => instance.state === "starting" || instance.state === "error").length;

  return (
    <>
      <PageHeader
        title="Instances"
        description={`${instances.length} virtual machines across your scope, ${running} currently running`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="success" size="md">{running} running</Badge>
        <Badge variant="default" size="md">{stopped} stopped</Badge>
        <Badge variant={attention > 0 ? "warning" : "default"} size="md">{attention} attention</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[980px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Instance</TableHead>
              <TableHead>State</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead>Network</TableHead>
              <TableHead>IP</TableHead>
              <TableHead>Offering</TableHead>
              <TableHead className="text-right">CPU</TableHead>
              <TableHead className="text-right">RAM</TableHead>
              <TableHead className="text-right">Usage</TableHead>
              <TableHead className="pr-4">Account</TableHead>
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
                    <UsagePair instance={instance} />
                  </TableCell>
                  <TableCell className="pr-4">{instance.account}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={10} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No instances found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function UsagePair({ instance }: { instance: Instance }) {
  return (
    <div className="inline-flex min-w-20 flex-col items-end gap-0.5 font-mono text-xs tabular-nums">
      <span>CPU {formatPercent(instance.cpuUsage)}</span>
      <span className="text-[color:var(--fg-muted)]">MEM {formatPercent(instance.memUsage)}</span>
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
