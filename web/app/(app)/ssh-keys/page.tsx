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
import { getSshKeyPairsFromBff } from "@/lib/cloudstack/ssh-keys";
import type { SshKeyPair } from "@/lib/mock-data";

export const metadata = { title: "SSH keys" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const keyPairs = await getSshKeyPairsFromBff({ requestHeaders: headers() });
  const accounts = new Set(keyPairs.map((keyPair) => keyPair.account)).size;
  const domains = new Set(keyPairs.map((keyPair) => keyPair.domain)).size;
  const projects = keyPairs.filter((keyPair) => keyPair.project).length;

  return (
    <>
      <PageHeader
        title="SSH keys"
        description={`${keyPairs.length} SSH key pairs across ${accounts} accounts and ${domains} domains`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{keyPairs.length} total</Badge>
        <Badge variant="accent" size="md">{accounts} accounts</Badge>
        <Badge variant="default" size="md">{domains} domains</Badge>
        <Badge variant={projects > 0 ? "success" : "default"} size="md">{projects} project scoped</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Key pair</TableHead>
              <TableHead>Fingerprint</TableHead>
              <TableHead>Account</TableHead>
              <TableHead>Domain</TableHead>
              <TableHead className="pr-4">Project</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {keyPairs.length > 0 ? (
              keyPairs.map((keyPair) => (
                <TableRow key={`${keyPair.id}-${keyPair.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{keyPair.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{keyPair.id}</div>
                    </div>
                  </TableCell>
                  <TableCell className="max-w-[360px] truncate font-mono text-xs">
                    {keyPair.fingerprint}
                  </TableCell>
                  <TableCell>{keyPair.account}</TableCell>
                  <TableCell>{keyPair.domain}</TableCell>
                  <TableCell className="pr-4">
                    <ProjectBadge keyPair={keyPair} />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={5} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No SSH key pairs found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function ProjectBadge({ keyPair }: { keyPair: SshKeyPair }) {
  if (!keyPair.project) {
    return <span className="text-[color:var(--fg-muted)]">-</span>;
  }

  return <Badge variant="default">{keyPair.project}</Badge>;
}
