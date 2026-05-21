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
import { getEventsFromBff } from "@/lib/cloudstack/events";
import type { Event } from "@/lib/mock-data";

export const metadata = { title: "Events" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const events = await getEventsFromBff({ requestHeaders: headers() });
  const info = events.filter((event) => event.level === "info").length;
  const warn = events.filter((event) => event.level === "warn").length;
  const error = events.filter((event) => event.level === "error").length;

  return (
    <>
      <PageHeader
        title="Events"
        description={`${events.length} audit and lifecycle events across your scope`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{events.length} total</Badge>
        <Badge variant="default" size="md">{info} info</Badge>
        <Badge variant={warn > 0 ? "warning" : "default"} size="md">{warn} warn</Badge>
        <Badge variant={error > 0 ? "danger" : "default"} size="md">{error} error</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[980px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Time</TableHead>
              <TableHead>Level</TableHead>
              <TableHead>Action</TableHead>
              <TableHead>Target</TableHead>
              <TableHead>User</TableHead>
              <TableHead className="pr-4">Description</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {events.length > 0 ? (
              events.map((event, index) => (
                <TableRow key={`${event.timestamp}-${event.action}-${event.target}-${index}`}>
                  <TableCell className="pl-4 font-mono text-xs tabular-nums">{event.timestamp}</TableCell>
                  <TableCell>
                    <Badge variant={levelVariant(event.level)}>{levelLabel(event.level)}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{event.action}</TableCell>
                  <TableCell>{event.target}</TableCell>
                  <TableCell>{event.user}</TableCell>
                  <TableCell className="pr-4">{event.description}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No events found.
                </TableCell>
              </TableRow>
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

function levelLabel(level: Event["level"]): string {
  return level === "warn" ? "Warn" : level.charAt(0).toUpperCase() + level.slice(1);
}
