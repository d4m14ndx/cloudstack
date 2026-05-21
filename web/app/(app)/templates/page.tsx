import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { TemplateActions } from "@/components/templates/template-actions";
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
import { getTemplatesFromBff } from "@/lib/cloudstack/templates";
import type { Template } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.templates");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.templates");
  const templates = await getTemplatesFromBff({ requestHeaders: headers() });
  const featured = templates.filter((template) => template.featured).length;
  const arm = templates.filter((template) => template.arch === "arm64").length;
  const x86 = templates.length - arm;

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { templates: templates.length, featured })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: templates.length })}</Badge>
        <Badge variant="accent" size="md">{t("badges.featured", { count: featured })}</Badge>
        <Badge variant="default" size="md">{t("badges.x86", { count: x86 })}</Badge>
        <Badge variant="default" size="md">{t("badges.arm", { count: arm })}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.template")}</TableHead>
              <TableHead>{t("table.os")}</TableHead>
              <TableHead>{t("table.arch")}</TableHead>
              <TableHead>{t("table.hypervisors")}</TableHead>
              <TableHead className="text-right">{t("table.size")}</TableHead>
              <TableHead>{t("table.account")}</TableHead>
              <TableHead className="pr-4">{t("table.featured")}</TableHead>
              <TableHead className="pr-4 text-right">{t("table.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {templates.length > 0 ? (
              templates.map((template) => (
                <TableRow key={`${template.id}-${template.name}`}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{template.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{template.id}</div>
                    </div>
                  </TableCell>
                  <TableCell>{template.os}</TableCell>
                  <TableCell>
                    <Badge variant="default">{template.arch}</Badge>
                  </TableCell>
                  <TableCell>
                    <Hypervisors template={template} />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{template.size}</TableCell>
                  <TableCell>{template.account}</TableCell>
                  <TableCell className="pr-4">
                    <Badge variant={template.featured ? "accent" : "default"}>
                      {template.featured ? t("labels.featuredYes") : t("labels.featuredNo")}
                    </Badge>
                  </TableCell>
                  <TableCell className="pr-4">
                    <TemplateActions template={template} />
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

function Hypervisors({ template }: { template: Template }) {
  if (template.hypervisors.length === 0) {
    return <span className="text-[color:var(--fg-muted)]">-</span>;
  }

  return (
    <div className="flex flex-wrap gap-1">
      {template.hypervisors.map((hypervisor) => (
        <Badge key={hypervisor} variant="default">
          {hypervisor}
        </Badge>
      ))}
    </div>
  );
}
