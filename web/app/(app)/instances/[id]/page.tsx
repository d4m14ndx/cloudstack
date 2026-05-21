import type { Metadata } from "next";
import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { InstanceActions } from "@/components/instances/instance-actions";
import { InstanceConsole } from "@/components/instances/instance-console";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { TableEmptyState } from "@/components/ui/empty-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { getInstanceDetailFromBff, type InstanceDetail } from "@/lib/cloudstack/instance-detail";
import type { Event, Instance, Volume } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

type Translator = Awaited<ReturnType<typeof getTranslations>>;

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Compute.pages.instanceDetail");

  return { title: t("metadataTitle") };
}

export default async function Page({ params }: { params: { id: string } }) {
  const t = await getTranslations("Compute.pages.instanceDetail");
  const detail = await getInstanceDetailFromBff(params.id, { requestHeaders: headers() });

  if (!detail) {
    notFound();
  }

  return (
    <>
      <PageHeader
        title={detail.instance.name}
        description={`${detail.instance.account} · ${detail.instance.zone} · ${detail.instance.id}`}
        actions={
          <>
            <Badge variant={instanceStateVariant(detail.instance.state)} size="md">
              {stateLabel(detail.instance.state)}
            </Badge>
            <Badge variant="default" size="md">{detail.instance.account}</Badge>
            <Badge variant="info" size="md">{detail.instance.zone}</Badge>
            <InstanceActions id={detail.instance.id} name={detail.instance.name} state={detail.instance.state} />
          </>
        }
      />

      <section className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric
          label={t("metrics.vcpu")}
          value={String(detail.compute.cpu)}
          subValue={formatNullableSpeed(detail.compute.cpuSpeedMHz, t)}
        />
        <Metric
          label={t("metrics.memory")}
          value={t("values.gib", { value: detail.compute.ramGiB })}
          subValue={t("metrics.memoryUsage", { usage: formatPercent(detail.compute.memoryUsage) })}
        />
        <Metric label={t("metrics.network")} value={detail.instance.network} subValue={detail.instance.ip} />
        <Metric
          label={t("metrics.storage")}
          value={`${detail.storage.length}`}
          subValue={formatVolumeLabel(detail.storage.length, t)}
        />
      </section>

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{t("tabs.overview")}</TabsTrigger>
          <TabsTrigger value="networking" count={detail.networking.length}>
            {t("tabs.networking")}
          </TabsTrigger>
          <TabsTrigger value="storage" count={detail.storage.length}>{t("tabs.storage")}</TabsTrigger>
          <TabsTrigger value="activity" count={detail.activity.length}>{t("tabs.activity")}</TabsTrigger>
          <TabsTrigger value="console">{t("tabs.console")}</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <Overview detail={detail} t={t} />
        </TabsContent>

        <TabsContent value="networking">
          <NetworkingTable detail={detail} t={t} />
        </TabsContent>

        <TabsContent value="storage">
          <StorageTable volumes={detail.storage} t={t} />
        </TabsContent>

        <TabsContent value="activity">
          <ActivityTable events={detail.activity} t={t} />
        </TabsContent>

        <TabsContent value="console">
          <ConsolePanel detail={detail} t={t} />
        </TabsContent>
      </Tabs>
    </>
  );
}

function Overview({ detail, t }: { detail: InstanceDetail; t: Translator }) {
  return (
    <section className="grid gap-4 xl:grid-cols-2">
      <DetailCard title={t("sections.identity")}>
        <KeyValue label={t("labels.displayName")} value={detail.identity.displayName} />
        <KeyValue label={t("labels.internalName")} value={detail.identity.internalName} mono />
        <KeyValue label={t("labels.account")} value={detail.identity.account} />
        <KeyValue label={t("labels.domain")} value={detail.identity.domain} />
        <KeyValue label={t("labels.project")} value={detail.identity.project ?? "-"} />
        <KeyValue label={t("labels.created")} value={detail.identity.created ?? "-"} mono />
      </DetailCard>

      <DetailCard title={t("sections.placement")}>
        <KeyValue label={t("labels.zone")} value={detail.placement.zone} />
        <KeyValue label={t("labels.pod")} value={detail.placement.pod ?? "-"} />
        <KeyValue label={t("labels.cluster")} value={detail.placement.cluster ?? "-"} />
        <KeyValue label={t("labels.host")} value={detail.placement.host ?? "-"} />
        <KeyValue label={t("labels.hypervisor")} value={detail.placement.hypervisor ?? "-"} />
        <KeyValue
          label={t("labels.ha")}
          value={detail.compute.haEnabled ? t("values.enabled") : t("values.disabled")}
        />
      </DetailCard>

      <DetailCard title={t("sections.compute")}>
        <KeyValue label={t("labels.offering")} value={detail.compute.offering} />
        <KeyValue label={t("labels.cpu")} value={t("values.vcpu", { count: detail.compute.cpu })} />
        <KeyValue label={t("labels.cpuSpeed")} value={formatNullableSpeed(detail.compute.cpuSpeedMHz, t)} />
        <KeyValue label={t("labels.cpuUsage")} value={formatPercent(detail.compute.cpuUsage)} />
        <KeyValue label={t("labels.memory")} value={t("values.gib", { value: detail.compute.ramGiB })} />
        <KeyValue label={t("labels.memoryUsage")} value={formatPercent(detail.compute.memoryUsage)} />
      </DetailCard>

      <DetailCard title={t("sections.image")}>
        <KeyValue label={t("labels.template")} value={detail.image.template} />
        <KeyValue label={t("labels.templateText")} value={detail.image.templateDisplayText ?? "-"} />
        <KeyValue label={t("labels.iso")} value={detail.image.iso ?? "-"} />
        <KeyValue label={t("labels.serviceOffering")} value={detail.image.serviceOffering} />
        <KeyValue label={t("labels.diskOffering")} value={detail.image.diskOffering ?? "-"} />
        <KeyValue label={t("labels.securityGroups")} value={formatGroups(detail.securityGroups)} />
      </DetailCard>
    </section>
  );
}

function ConsolePanel({ detail, t }: { detail: InstanceDetail; t: Translator }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("sections.console")}</CardTitle>
      </CardHeader>
      <div className="grid gap-4 text-sm">
        <InstanceConsole
          id={detail.instance.id}
          name={detail.identity.name}
          state={detail.instance.state}
          rawState={detail.console.rawState}
          hostControlState={detail.console.hostControlState}
          externalUrl={detail.console.externalUrl}
        />
        <dl className="grid gap-2">
          <KeyValue label={t("labels.instance")} value={detail.identity.name} />
          <KeyValue label={t("labels.state")} value={stateLabel(detail.instance.state)} />
          <KeyValue label={t("labels.hypervisor")} value={detail.placement.hypervisor ?? "-"} />
          <KeyValue label={t("labels.host")} value={detail.placement.host ?? "-"} />
        </dl>
      </div>
    </Card>
  );
}

function Metric({ label, value, subValue }: { label: string; value: string; subValue: string }) {
  return (
    <Card>
      <div className="text-[11px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">{label}</div>
      <div className="mt-1 truncate text-xl font-semibold text-[color:var(--fg)]">{value}</div>
      <div className="mt-1 truncate text-xs text-[color:var(--fg-muted)]">{subValue}</div>
    </Card>
  );
}

function DetailCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <dl className="grid gap-2 text-sm">{children}</dl>
    </Card>
  );
}

function KeyValue({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[128px_minmax(0,1fr)] gap-3">
      <dt className="text-xs text-[color:var(--fg-muted)]">{label}</dt>
      <dd className={mono ? "truncate font-mono text-xs text-[color:var(--fg)]" : "truncate text-[color:var(--fg)]"}>
        {value}
      </dd>
    </div>
  );
}

function NetworkingTable({ detail, t }: { detail: InstanceDetail; t: Translator }) {
  return (
    <Card className="p-0">
      <SectionTitle title={t("sections.networking")} />
      <Table className="min-w-[920px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{t("networking.table.network")}</TableHead>
            <TableHead>{t("networking.table.role")}</TableHead>
            <TableHead>{t("networking.table.privateIp")}</TableHead>
            <TableHead>{t("networking.table.publicIp")}</TableHead>
            <TableHead>{t("networking.table.gateway")}</TableHead>
            <TableHead>{t("networking.table.netmask")}</TableHead>
            <TableHead className="pr-4">{t("networking.table.mac")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {detail.networking.length > 0 ? (
            detail.networking.map((nic) => (
              <TableRow key={nic.id}>
                <TableCell className="pl-4">
                  <div className="font-medium">{nic.name}</div>
                  <div className="font-mono text-xs text-[color:var(--fg-muted)]">{nic.networkId ?? nic.id}</div>
                </TableCell>
                <TableCell>
                  <Badge variant={nic.isDefault ? "accent" : "default"}>
                    {nic.isDefault
                      ? t("networking.badges.default")
                      : nic.type ?? t("networking.badges.nic")}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.ip}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.publicIp ?? "-"}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.gateway ?? "-"}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.netmask ?? "-"}</TableCell>
                <TableCell className="pr-4 font-mono text-xs">{nic.macAddress ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={7}
              title={t("networking.emptyState.title")}
              description={t("networking.emptyState.description")}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function StorageTable({ volumes, t }: { volumes: Volume[]; t: Translator }) {
  return (
    <Card className="p-0">
      <SectionTitle title={t("sections.storage")} />
      <Table className="min-w-[760px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{t("storage.table.volume")}</TableHead>
            <TableHead>{t("storage.table.state")}</TableHead>
            <TableHead>{t("storage.table.zone")}</TableHead>
            <TableHead>{t("storage.table.tier")}</TableHead>
            <TableHead className="text-right">{t("storage.table.size")}</TableHead>
            <TableHead className="pr-4">{t("storage.table.attachedTo")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {volumes.length > 0 ? (
            volumes.map((volume) => (
              <TableRow key={`${volume.id}-${volume.name}`}>
                <TableCell className="pl-4">
                  <div className="font-medium">{volume.name}</div>
                  <div className="font-mono text-xs text-[color:var(--fg-muted)]">{volume.id}</div>
                </TableCell>
                <TableCell>
                  <Badge variant={volume.state === "detaching" ? "warning" : "success"}>{stateLabel(volume.state)}</Badge>
                </TableCell>
                <TableCell>{volume.zone}</TableCell>
                <TableCell>{volume.type}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {t("values.gib", { value: volume.sizeGiB })}
                </TableCell>
                <TableCell className="pr-4">{volume.attachedTo ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={6}
              title={t("storage.emptyState.title")}
              description={t("storage.emptyState.description")}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function ActivityTable({ events, t }: { events: Event[]; t: Translator }) {
  return (
    <Card className="p-0">
      <SectionTitle title={t("sections.activity")} />
      <Table className="min-w-[980px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{t("activity.table.time")}</TableHead>
            <TableHead>{t("activity.table.level")}</TableHead>
            <TableHead>{t("activity.table.action")}</TableHead>
            <TableHead>{t("activity.table.target")}</TableHead>
            <TableHead>{t("activity.table.user")}</TableHead>
            <TableHead className="pr-4">{t("activity.table.description")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {events.length > 0 ? (
            events.map((event, index) => (
              <TableRow key={`${event.timestamp}-${event.action}-${index}`}>
                <TableCell className="pl-4 font-mono text-xs tabular-nums">{event.timestamp}</TableCell>
                <TableCell>
                  <Badge variant={eventLevelVariant(event.level)}>{stateLabel(event.level)}</Badge>
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
              title={t("activity.emptyState.title")}
              description={t("activity.emptyState.description")}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function SectionTitle({ title }: { title: string }) {
  return (
    <div className="border-b border-[color:var(--border)] px-[var(--card-pad)] py-3">
      <h2 className="text-sm font-semibold text-[color:var(--fg)]">{title}</h2>
    </div>
  );
}

function instanceStateVariant(state: Instance["state"]): "success" | "warning" | "danger" | "default" {
  switch (state) {
    case "running":
      return "success";
    case "starting":
      return "warning";
    case "error":
      return "danger";
    default:
      return "default";
  }
}

function eventLevelVariant(level: Event["level"]): "info" | "warning" | "danger" {
  switch (level) {
    case "warn":
      return "warning";
    case "error":
      return "danger";
    default:
      return "info";
  }
}

function stateLabel(state: string): string {
  return state.charAt(0).toUpperCase() + state.slice(1);
}

function formatPercent(value: number): string {
  return `${Math.max(0, Math.min(100, Math.round(value)))}%`;
}

function formatNullableSpeed(value: number | null, t: Translator): string {
  return value === null ? "-" : t("values.speedMHz", { value });
}

function formatVolumeLabel(count: number, t: Translator): string {
  return count === 1 ? t("metrics.volume") : t("metrics.volumes");
}

function formatGroups(groups: InstanceDetail["securityGroups"]): string {
  return groups.length > 0 ? groups.map((group) => group.name).join(", ") : "-";
}
