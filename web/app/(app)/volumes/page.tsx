import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { TableEmptyState } from "@/components/ui/empty-state";
import { VolumeActions } from "@/components/volumes/volume-actions";
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

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.volumes");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.volumes");
  const volumes = await getVolumesFromBff({ requestHeaders: headers() });
  const attached = volumes.filter((volume) => volume.attachedTo !== null).length;
  const unattached = volumes.length - attached;
  const detaching = volumes.filter((volume) => volume.state === "detaching").length;

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { volumes: volumes.length, attached })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: volumes.length })}</Badge>
        <Badge variant="success" size="md">{t("badges.attached", { count: attached })}</Badge>
        <Badge variant="default" size="md">{t("badges.unattached", { count: unattached })}</Badge>
        <Badge variant={detaching > 0 ? "warning" : "default"} size="md">{t("badges.detaching", { count: detaching })}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[880px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.volume")}</TableHead>
              <TableHead>{t("table.state")}</TableHead>
              <TableHead>{t("table.zone")}</TableHead>
              <TableHead>{t("table.tier")}</TableHead>
              <TableHead className="text-right">{t("table.size")}</TableHead>
              <TableHead>{t("table.attachedTo")}</TableHead>
              <TableHead className="pr-4 text-right">{t("table.actions")}</TableHead>
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
                  <TableCell className="text-right tabular-nums">{volume.sizeGiB} {t("units.gib")}</TableCell>
                  <TableCell>{volume.attachedTo ?? "-"}</TableCell>
                  <TableCell className="pr-4 text-right">
                    <VolumeActions volume={volume} />
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

function stateVariant(state: Volume["state"]): "success" | "warning" {
  return state === "detaching" ? "warning" : "success";
}

function stateLabel(state: Volume["state"]): string {
  return state.charAt(0).toUpperCase() + state.slice(1);
}
