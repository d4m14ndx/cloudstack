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
import { getNetworksFromBff } from "@/lib/cloudstack/networks";
import type { Network } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.networks");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.networks");
  const networks = await getNetworksFromBff({ requestHeaders: headers() });
  const vpcs = networks.filter((network) => network.type === "VPC").length;
  const isolated = networks.length - vpcs;
  const warnings = networks.filter((network) => network.state === "warning").length;
  const instances = networks.reduce((sum, network) => sum + network.instances, 0);

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { networks: networks.length, vpcs, instances })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: networks.length })}</Badge>
        <Badge variant="accent" size="md">{t("badges.vpcs", { count: vpcs })}</Badge>
        <Badge variant="default" size="md">{t("badges.isolated", { count: isolated })}</Badge>
        <Badge variant={warnings > 0 ? "warning" : "success"} size="md">{t("badges.warnings", { count: warnings })}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("columns.network")}</TableHead>
              <TableHead>{t("columns.type")}</TableHead>
              <TableHead>{t("columns.cidr")}</TableHead>
              <TableHead>{t("columns.gateway")}</TableHead>
              <TableHead>{t("columns.zone")}</TableHead>
              <TableHead className="text-right">{t("columns.instances")}</TableHead>
              <TableHead className="pr-4">{t("columns.state")}</TableHead>
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
                      {network.state === "running" ? t("state.running") : t("state.warning")}
                    </Badge>
                  </TableCell>
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

function typeVariant(type: Network["type"]): "accent" | "info" {
  return type === "VPC" ? "accent" : "info";
}
