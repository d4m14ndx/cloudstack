import { headers } from "next/headers";

import { PageHeader } from "@/components/page-header";
import { SecurityGroupActions } from "@/components/security-groups/security-group-actions";
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
import { getSecurityGroupsFromBff } from "@/lib/cloudstack/security-groups";
import type { SecurityGroup, SecurityGroupRule } from "@/lib/mock-data";

export const metadata = { title: "Security groups" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const securityGroups = await getSecurityGroupsFromBff({ requestHeaders: headers() });
  const accounts = new Set(securityGroups.map((group) => group.account)).size;
  const domains = new Set(securityGroups.map((group) => group.domainPath)).size;
  const instances = securityGroups.reduce((sum, group) => sum + group.instances, 0);
  const defaultGroups = securityGroups.filter((group) => group.isDefault).length;

  return (
    <>
      <PageHeader
        title="Security groups"
        description={`${securityGroups.length} security groups across ${accounts} accounts and ${domains} domains`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{securityGroups.length} total</Badge>
        <Badge variant="accent" size="md">{accounts} accounts</Badge>
        <Badge variant="default" size="md">{domains} domains</Badge>
        <Badge variant={instances > 0 ? "success" : "default"} size="md">{instances} instances</Badge>
        <Badge variant={defaultGroups > 0 ? "warning" : "default"} size="md">{defaultGroups} default</Badge>
      </div>

      <SecurityGroupActions securityGroups={securityGroups} />

      <Card className="p-0">
        <Table className="min-w-[1120px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Group</TableHead>
              <TableHead>Description</TableHead>
              <TableHead>Scope</TableHead>
              <TableHead>Ingress</TableHead>
              <TableHead>Egress</TableHead>
              <TableHead className="text-right">Instances</TableHead>
              <TableHead className="pr-4">Flags</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {securityGroups.length > 0 ? (
              securityGroups.map((group) => (
                <TableRow key={group.id}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{group.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{group.id}</div>
                    </div>
                  </TableCell>
                  <TableCell className="max-w-[240px] truncate">{group.description}</TableCell>
                  <TableCell>
                    <Scope group={group} />
                  </TableCell>
                  <TableCell className="max-w-[220px]">
                    <RuleSummary rules={group.ingressRules} />
                  </TableCell>
                  <TableCell className="max-w-[220px]">
                    <RuleSummary rules={group.egressRules} />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{group.instances}</TableCell>
                  <TableCell className="pr-4">
                    <GroupFlags group={group} />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={7}
                title="No security groups in this scope"
                description="Create a group above, then add ingress or egress rules as needed."
              />
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function Scope({ group }: { group: SecurityGroup }) {
  return (
    <div className="min-w-0 space-y-1 text-sm">
      <div className="truncate">{group.account}</div>
      <div className="truncate text-xs text-[color:var(--fg-muted)]">{group.domainPath}</div>
      {group.project ? <Badge variant="default">{group.project}</Badge> : null}
    </div>
  );
}

function RuleSummary({ rules }: { rules: SecurityGroupRule[] }) {
  if (rules.length === 0) {
    return <span className="text-[color:var(--fg-muted)]">-</span>;
  }

  const firstRule = rules[0];

  return (
    <div className="min-w-0 space-y-1">
      {firstRule ? (
        <div className="truncate text-sm">
          <span className="font-medium">{firstRule.protocol}</span>{" "}
          <span className="font-mono text-xs">{firstRule.range}</span>
          <span className="text-[color:var(--fg-muted)]"> from </span>
          <span className="font-mono text-xs">{firstRule.source}</span>
        </div>
      ) : null}
      {rules.length > 1 ? (
        <div className="text-xs text-[color:var(--fg-muted)]">+{rules.length - 1} more</div>
      ) : null}
    </div>
  );
}

function GroupFlags({ group }: { group: SecurityGroup }) {
  return (
    <div className="flex flex-wrap gap-1">
      {group.isDefault ? <Badge variant="warning">Default</Badge> : <Badge variant="default">Custom</Badge>}
      {group.project ? <Badge variant="accent">Project</Badge> : null}
    </div>
  );
}
