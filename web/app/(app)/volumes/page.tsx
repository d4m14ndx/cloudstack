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
import { getVolumesFromBff } from "@/lib/cloudstack/volumes";
import type { Volume } from "@/lib/mock-data";

export const metadata = { title: "Volumes" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const volumes = await getVolumesFromBff({ requestHeaders: headers() });
  const attached = volumes.filter((volume) => volume.attachedTo !== null).length;
  const unattached = volumes.length - attached;
  const detaching = volumes.filter((volume) => volume.state === "detaching").length;

  return (
    <>
      <PageHeader
        title="Volumes"
        description={`${volumes.length} block storage volumes across your scope, ${attached} currently attached`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{volumes.length} total</Badge>
        <Badge variant="success" size="md">{attached} attached</Badge>
        <Badge variant="default" size="md">{unattached} unattached</Badge>
        <Badge variant={detaching > 0 ? "warning" : "default"} size="md">{detaching} detaching</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[760px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Volume</TableHead>
              <TableHead>State</TableHead>
              <TableHead>Zone</TableHead>
              <TableHead>Tier</TableHead>
              <TableHead className="text-right">Size</TableHead>
              <TableHead className="pr-4">Attached to</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {volumes.length > 0 ? (
              volumes.map((volume) => (
                <TableRow key={`${volume.id}-${volume.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{volume.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{volume.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={stateVariant(volume.state)}>{stateLabel(volume.state)}</Badge>
                  </TableCell>
                  <TableCell>{volume.zone}</TableCell>
                  <TableCell>{volume.type}</TableCell>
                  <TableCell className="text-right tabular-nums">{volume.sizeGiB} GiB</TableCell>
                  <TableCell className="pr-4">{volume.attachedTo ?? "-"}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No volumes found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function stateVariant(state: Volume["state"]): "success" | "warning" {
  return state === "detaching" ? "warning" : "success";
}

function stateLabel(state: Volume["state"]): string {
  return state.charAt(0).toUpperCase() + state.slice(1);
}
