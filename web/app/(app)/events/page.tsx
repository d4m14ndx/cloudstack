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
import { getEventsFromBff } from "@/lib/cloudstack/events";
import type { Event } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.events");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.events");
  const events = await getEventsFromBff({ requestHeaders: headers() });
  const info = events.filter((event) => event.level === "info").length;
  const warn = events.filter((event) => event.level === "warn").length;
  const error = events.filter((event) => event.level === "error").length;
  const levelLabels: Record<Event["level"], string> = {
    info: t("levels.info"),
    warn: t("levels.warn"),
    error: t("levels.error"),
  };

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { events: events.length })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: events.length })}</Badge>
        <Badge variant="default" size="md">{t("badges.info", { count: info })}</Badge>
        <Badge variant={warn > 0 ? "warning" : "default"} size="md">{t("badges.warn", { count: warn })}</Badge>
        <Badge variant={error > 0 ? "danger" : "default"} size="md">{t("badges.error", { count: error })}</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[980px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("table.time")}</TableHead>
              <TableHead>{t("table.level")}</TableHead>
              <TableHead>{t("table.action")}</TableHead>
              <TableHead>{t("table.target")}</TableHead>
              <TableHead>{t("table.user")}</TableHead>
              <TableHead className="pr-4">{t("table.description")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {events.length > 0 ? (
              events.map((event, index) => (
                <TableRow key={`${event.timestamp}-${event.action}-${event.target}-${index}`}>
                  <TableCell className="pl-4 font-mono text-xs tabular-nums">{event.timestamp}</TableCell>
                  <TableCell>
                    <Badge variant={levelVariant(event.level)}>{levelLabel(event.level, levelLabels)}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{event.action}</TableCell>
                  <TableCell>{event.target}</TableCell>
                  <TableCell>{event.user}</TableCell>
                  <TableCell className="pr-4">{event.description}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyState
                colSpan={6}
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

function levelVariant(level: Event["level"]): "info" | "warning" | "danger" {
  switch (level) {
    case "warn":
      return "warning";
    case "error":
      return "danger";
    default:
      return "info";
  }
}

function levelLabel(level: Event["level"], labels: Record<Event["level"], string>): string {
  return labels[level];
}
