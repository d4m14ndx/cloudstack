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
import { getDomainsFromBff } from "@/lib/cloudstack/domains";
import type { TenantDomain } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.domains");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.domains");
  const domains = await getDomainsFromBff({ requestHeaders: headers() });
  const active = domains.filter((domain) => domain.state === "active").length;
  const inactive = domains.length - active;
  const childDomains = domains.filter((domain) => domain.level > 0).length;
  const instances = domains.reduce((sum, domain) => sum + domain.instances, 0);
  const projects = domains.reduce((sum, domain) => sum + domain.projects, 0);
  const networks = domains.reduce((sum, domain) => sum + domain.networks, 0);

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", {
          domains: domains.length,
          instances,
          projects,
          networks,
        })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{domains.length} total</Badge>
        <Badge variant="success" size="md">{active} active</Badge>
        <Badge variant={inactive > 0 ? "warning" : "default"} size="md">{inactive} inactive</Badge>
        <Badge variant="accent" size="md">{childDomains} child domains</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[960px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Domain</TableHead>
              <TableHead>Parent</TableHead>
              <TableHead className="text-right">Level</TableHead>
              <TableHead className="text-right">Instances</TableHead>
              <TableHead className="text-right">Projects</TableHead>
              <TableHead className="text-right">Networks</TableHead>
              <TableHead>Children</TableHead>
              <TableHead className="pr-4">State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {domains.length > 0 ? (
              domains.map((domain) => (
                <TableRow key={`${domain.id}-${domain.path}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{domain.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{domain.path}</div>
                    </div>
                  </TableCell>
                  <TableCell>{domain.parent ?? "-"}</TableCell>
                  <TableCell className="text-right tabular-nums">{domain.level}</TableCell>
                  <TableCell className="text-right tabular-nums">{domain.instances}</TableCell>
                  <TableCell className="text-right tabular-nums">{domain.projects}</TableCell>
                  <TableCell className="text-right tabular-nums">{domain.networks}</TableCell>
                  <TableCell>
                    <ChildrenBadge domain={domain} />
                  </TableCell>
                  <TableCell className="pr-4">
                    <Badge variant={domain.state === "active" ? "success" : "warning"}>
                      {domain.state === "active" ? "Active" : "Inactive"}
                    </Badge>
                  </TableCell>
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

function ChildrenBadge({ domain }: { domain: TenantDomain }) {
  if (!domain.hasChildren) {
    return <span className="text-[color:var(--fg-muted)]">-</span>;
  }

  return <Badge variant="default">Has children</Badge>;
}
