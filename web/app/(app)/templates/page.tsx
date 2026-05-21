import { headers } from "next/headers";

import { PageHeader } from "@/components/page-header";
import { TemplateActions } from "@/components/templates/template-actions";
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
import { getTemplatesFromBff } from "@/lib/cloudstack/templates";
import type { Template } from "@/lib/mock-data";

export const metadata = { title: "Templates" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const templates = await getTemplatesFromBff({ requestHeaders: headers() });
  const featured = templates.filter((template) => template.featured).length;
  const arm = templates.filter((template) => template.arch === "arm64").length;
  const x86 = templates.length - arm;

  return (
    <>
      <PageHeader
        title="Templates"
        description={`${templates.length} deployable templates across your scope, ${featured} featured`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{templates.length} total</Badge>
        <Badge variant="accent" size="md">{featured} featured</Badge>
        <Badge variant="default" size="md">{x86} x86_64</Badge>
        <Badge variant="default" size="md">{arm} arm64</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Template</TableHead>
              <TableHead>OS</TableHead>
              <TableHead>Arch</TableHead>
              <TableHead>Hypervisors</TableHead>
              <TableHead className="text-right">Size</TableHead>
              <TableHead>Account</TableHead>
              <TableHead className="pr-4">Featured</TableHead>
              <TableHead className="pr-4 text-right">Actions</TableHead>
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
                      {template.featured ? "Yes" : "No"}
                    </Badge>
                  </TableCell>
                  <TableCell className="pr-4">
                    <TemplateActions template={template} />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={8} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No templates found.
                </TableCell>
              </TableRow>
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
