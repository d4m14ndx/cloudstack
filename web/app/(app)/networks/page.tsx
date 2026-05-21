import { headers } from "next/headers";
import Link from "next/link";

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
import { getNetworksFromBff } from "@/lib/cloudstack/networks";
import type { Network } from "@/lib/mock-data";

export const metadata = { title: "Networks" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const networks = await getNetworksFromBff({ requestHeaders: headers() });
  const vpcs = networks.filter((network) => network.type === "VPC").length;
  const isolated = networks.length - vpcs;
  const warnings = networks.filter((network) => network.state === "warning").length;
  const instances = networks.reduce((sum, network) => sum + network.instances, 0);

  return (
    <>
      <PageHeader
        title="Networks"
        description={`${networks.length} networks, ${vpcs} VPCs, and ${instances} attached instances across your scope`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{networks.length} total</Badge>
        <Badge variant="accent" size="md">{vpcs} VPCs</Badge>
        <Badge variant="default" size="md">{isolated} isolated</Badge>
        <Badge variant={warnings > 0 ? "warning" : "success"} size="md">{warnings} warnings</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Network</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>CIDR</TableHead>
              <TableHead>Gateway</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead className="text-right">Instances</TableHead>
              <TableHead className="pr-4">State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {networks.length > 0 ? (
              networks.map((network) => (
                <TableRow key={`${network.type}-${network.id}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <Link
                        href={`/networks/${network.id}`}
                        className="block truncate font-medium text-[color:var(--fg)] hover:text-[color:var(--accent)]"
                      >
                        {network.name}
                      </Link>
                      <Link
                        href={`/networks/${network.id}`}
                        className="block truncate font-mono text-xs text-[color:var(--fg-muted)] hover:text-[color:var(--accent)]"
                      >
                        {network.id}
                      </Link>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={typeVariant(network.type)}>{network.type}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{network.cidr}</TableCell>
                  <TableCell className="font-mono text-xs">{network.gateway}</TableCell>
                  <TableCell>{network.zone}</TableCell>
                  <TableCell className="text-right tabular-nums">{network.instances}</TableCell>
                  <TableCell className="pr-4">
                    <Badge variant={network.state === "running" ? "success" : "warning"}>
                      {network.state === "running" ? "Running" : "Warning"}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No networks found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function typeVariant(type: Network["type"]): "accent" | "info" {
  return type === "VPC" ? "accent" : "info";
}
