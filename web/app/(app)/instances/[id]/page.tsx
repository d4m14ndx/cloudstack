import { headers } from "next/headers";
import { notFound } from "next/navigation";

import { InstanceActions } from "@/components/instances/instance-actions";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getInstanceDetailFromBff, type InstanceDetail } from "@/lib/cloudstack/instance-detail";
import type { Event, Instance, Volume } from "@/lib/mock-data";

export const metadata = { title: "Instance" };
export const dynamic = "force-dynamic";

export default async function Page({ params }: { params: { id: string } }) {
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
        <Metric label="vCPU" value={String(detail.compute.cpu)} subValue={formatNullableSpeed(detail.compute.cpuSpeedMHz)} />
        <Metric label="Memory" value={`${detail.compute.ramGiB} GiB`} subValue={`Usage ${formatPercent(detail.compute.memoryUsage)}`} />
        <Metric label="Network" value={detail.instance.network} subValue={detail.instance.ip} />
        <Metric label="Storage" value={`${detail.storage.length}`} subValue={pluralize("volume", detail.storage.length)} />
      </section>

      <section className="mb-4 grid gap-4 xl:grid-cols-2">
        <DetailCard title="Identity">
          <KeyValue label="Display name" value={detail.identity.displayName} />
          <KeyValue label="Internal name" value={detail.identity.internalName} mono />
          <KeyValue label="Account" value={detail.identity.account} />
          <KeyValue label="Domain" value={detail.identity.domain} />
          <KeyValue label="Project" value={detail.identity.project ?? "-"} />
          <KeyValue label="Created" value={detail.identity.created ?? "-"} mono />
        </DetailCard>

        <DetailCard title="Placement">
          <KeyValue label="Zone" value={detail.placement.zone} />
          <KeyValue label="Pod" value={detail.placement.pod ?? "-"} />
          <KeyValue label="Cluster" value={detail.placement.cluster ?? "-"} />
          <KeyValue label="Host" value={detail.placement.host ?? "-"} />
          <KeyValue label="Hypervisor" value={detail.placement.hypervisor ?? "-"} />
          <KeyValue label="HA" value={detail.compute.haEnabled ? "Enabled" : "Disabled"} />
        </DetailCard>

        <DetailCard title="Compute">
          <KeyValue label="Offering" value={detail.compute.offering} />
          <KeyValue label="CPU" value={`${detail.compute.cpu} vCPU`} />
          <KeyValue label="CPU speed" value={formatNullableSpeed(detail.compute.cpuSpeedMHz)} />
          <KeyValue label="CPU usage" value={formatPercent(detail.compute.cpuUsage)} />
          <KeyValue label="Memory" value={`${detail.compute.ramGiB} GiB`} />
          <KeyValue label="Memory usage" value={formatPercent(detail.compute.memoryUsage)} />
        </DetailCard>

        <DetailCard title="Image">
          <KeyValue label="Template" value={detail.image.template} />
          <KeyValue label="Template text" value={detail.image.templateDisplayText ?? "-"} />
          <KeyValue label="ISO" value={detail.image.iso ?? "-"} />
          <KeyValue label="Service offering" value={detail.image.serviceOffering} />
          <KeyValue label="Disk offering" value={detail.image.diskOffering ?? "-"} />
          <KeyValue label="Security groups" value={formatGroups(detail.securityGroups)} />
        </DetailCard>
      </section>

      <section className="grid gap-4">
        <NetworkingTable detail={detail} />
        <StorageTable volumes={detail.storage} />
        <ActivityTable events={detail.activity} />
      </section>
    </>
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

function NetworkingTable({ detail }: { detail: InstanceDetail }) {
  return (
    <Card className="p-0">
      <SectionTitle title="Networking" />
      <Table className="min-w-[920px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Network</TableHead>
            <TableHead>Role</TableHead>
            <TableHead>Private IP</TableHead>
            <TableHead>Public IP</TableHead>
            <TableHead>Gateway</TableHead>
            <TableHead>Netmask</TableHead>
            <TableHead className="pr-4">MAC</TableHead>
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
                  <Badge variant={nic.isDefault ? "accent" : "default"}>{nic.isDefault ? "Default" : nic.type ?? "NIC"}</Badge>
                </TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.ip}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.publicIp ?? "-"}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.gateway ?? "-"}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{nic.netmask ?? "-"}</TableCell>
                <TableCell className="pr-4 font-mono text-xs">{nic.macAddress ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={7} className="h-24 text-center text-sm text-[color:var(--fg-muted)]">
                No NICs found.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function StorageTable({ volumes }: { volumes: Volume[] }) {
  return (
    <Card className="p-0">
      <SectionTitle title="Storage" />
      <Table className="min-w-[760px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Volume</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Zone</TableHead>
            <TableHead>Tier</TableHead>
            <TableHead className="text-right">Size</TableHead>
            <TableHead className="pr-4">Attached to</TableHead>
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
                <TableCell className="text-right tabular-nums">{volume.sizeGiB} GiB</TableCell>
                <TableCell className="pr-4">{volume.attachedTo ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={6} className="h-24 text-center text-sm text-[color:var(--fg-muted)]">
                No volumes found.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function ActivityTable({ events }: { events: Event[] }) {
  return (
    <Card className="p-0">
      <SectionTitle title="Activity" />
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
            <TableRow>
              <TableCell colSpan={6} className="h-24 text-center text-sm text-[color:var(--fg-muted)]">
                No activity found.
              </TableCell>
            </TableRow>
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

function formatNullableSpeed(value: number | null): string {
  return value === null ? "-" : `${value} MHz`;
}

function pluralize(label: string, count: number): string {
  return count === 1 ? label : `${label}s`;
}

function formatGroups(groups: InstanceDetail["securityGroups"]): string {
  return groups.length > 0 ? groups.map((group) => group.name).join(", ") : "-";
}
