import { headers } from "next/headers";

import { PageHeader } from "@/components/page-header";
import { SshKeyActions, SshKeyDeleteButton } from "@/components/ssh-keys/ssh-key-actions";
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

      <SshKeyActions />

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Key pair</TableHead>
              <TableHead>Fingerprint</TableHead>
              <TableHead>Account</TableHead>
              <TableHead>Domain</TableHead>
              <TableHead>Project</TableHead>
              <TableHead className="pr-4 text-right">Actions</TableHead>
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
                  <TableCell>
                    <ProjectBadge keyPair={keyPair} />
                  </TableCell>
                  <TableCell className="pr-4 text-right">
                    <SshKeyDeleteButton name={keyPair.name} />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={6}
                title="No SSH key pairs in this scope"
                description="Register a key pair before deploying instances that require SSH access."
              />
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
