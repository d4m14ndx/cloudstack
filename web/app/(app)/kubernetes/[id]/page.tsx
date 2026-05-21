import { headers } from "next/headers";
import { notFound } from "next/navigation";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  getKubernetesClusterDetailFromBff,
  type KubernetesClusterDetail,
  type KubernetesClusterNode,
  type KubernetesNodeRole,
} from "@/lib/cloudstack/kubernetes";
import type { Event, KubernetesCluster } from "@/lib/mock-data";

export const metadata = { title: "Kubernetes Cluster" };
export const dynamic = "force-dynamic";

export default async function Page({ params }: { params: { id: string } }) {
  const detail = await getKubernetesClusterDetailFromBff(params.id, { requestHeaders: headers() });

  if (!detail) {
    notFound();
  }

  return (
    <>
      <PageHeader
        title={detail.cluster.name}
        description={`${detail.identity.account} · ${detail.placement.zone} · ${detail.cluster.id}`}
        actions={
          <>
            <Badge variant={clusterStateVariant(detail.cluster.state)} size="md">
              {stateLabel(detail.cluster.state)}
            </Badge>
            <Badge variant="info" size="md">{detail.version.semanticVersion ?? detail.version.name}</Badge>
            <Badge variant="default" size="md">{detail.identity.type}</Badge>
            <Badge variant="accent" size="md">{detail.placement.zone}</Badge>
            <Badge variant="default" size="md">{detail.identity.account}</Badge>
          </>
        }
      />

      <section className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric label="Nodes" value={String(detail.nodePools.total)} subValue={formatNodePoolSummary(detail)} />
        <Metric label="Version" value={detail.version.semanticVersion ?? detail.version.name} subValue={detail.version.kubernetesVersionId ?? "No version id"} />
        <Metric label="Endpoint" value={detail.networking.endpoint} subValue={detail.networking.networkName ?? "No network name"} mono />
        <Metric label="Autoscaling" value={detail.autoscaling.enabled ? "Enabled" : "Disabled"} subValue={formatScalingRange(detail)} />
      </section>

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="nodes" count={detail.nodes.length}>Nodes</TabsTrigger>
          <TabsTrigger value="networking">Networking</TabsTrigger>
          <TabsTrigger value="scaling">Scaling</TabsTrigger>
          <TabsTrigger value="activity" count={detail.activity.length}>Activity</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title="Identity">
              <KeyValue label="Name" value={detail.summary.name} />
              <KeyValue label="ID" value={detail.summary.id} mono />
              <KeyValue label="Description" value={detail.summary.description ?? "-"} />
              <KeyValue label="Account" value={detail.identity.account} />
              <KeyValue label="Domain" value={detail.identity.domain} />
              <KeyValue label="Project" value={detail.identity.project ?? "-"} />
              <KeyValue label="Created" value={detail.identity.created ?? "-"} mono />
              <KeyValue label="Type" value={detail.identity.type} />
            </DetailCard>

            <DetailCard title="Placement and Version">
              <KeyValue label="Zone" value={detail.placement.zone} />
              <KeyValue label="Zone ID" value={detail.placement.zoneId ?? "-"} mono />
              <KeyValue label="Version" value={detail.version.name} />
              <KeyValue label="Semantic" value={detail.version.semanticVersion ?? "-"} />
              <KeyValue label="Version ID" value={detail.version.kubernetesVersionId ?? "-"} mono />
              <KeyValue label="State" value={stateLabel(detail.cluster.state)} />
            </DetailCard>

            <DetailCard title="Images and Offerings">
              <KeyValue label="Service offering" value={detail.offerings.serviceOfferingName ?? "-"} />
              <KeyValue label="Offering ID" value={detail.offerings.serviceOfferingId ?? "-"} mono />
              <KeyValue label="Template" value={detail.offerings.templateName ?? "-"} />
              <KeyValue label="Template ID" value={detail.offerings.templateId ?? "-"} mono />
              <KeyValue label="SSH key" value={detail.offerings.sshKeyPair ?? "-"} />
            </DetailCard>

            <DetailCard title="Summary">
              <KeyValue label="Control" value={String(detail.nodePools.control)} />
              <KeyValue label="Workers" value={String(detail.nodePools.worker)} />
              <KeyValue label="Etcd" value={String(detail.nodePools.etcd)} />
              <KeyValue label="External" value={String(detail.nodePools.external)} />
              <KeyValue label="Total" value={String(detail.nodePools.total)} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="nodes">
          <NodesTable nodes={detail.nodes} />
        </TabsContent>

        <TabsContent value="networking">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title="Network">
              <KeyValue label="Network" value={detail.networking.networkName ?? "-"} />
              <KeyValue label="Network ID" value={detail.networking.networkId ?? "-"} mono />
              <KeyValue label="Endpoint" value={detail.networking.endpoint} mono />
              <KeyValue label="Console endpoint" value={detail.networking.consoleEndpoint ?? "-"} mono />
            </DetailCard>
            <DetailCard title="Plugins">
              <KeyValue label="CNI" value={detail.networking.cni ?? "-"} />
              <KeyValue label="CSI" value={detail.networking.csi ?? "-"} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="scaling">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title="Autoscaling">
              <KeyValue label="Enabled" value={detail.autoscaling.enabled ? "Yes" : "No"} />
              <KeyValue label="Minimum" value={formatNullableNumber(detail.autoscaling.min)} />
              <KeyValue label="Maximum" value={formatNullableNumber(detail.autoscaling.max)} />
              <KeyValue label="Current total" value={String(detail.nodePools.total)} />
            </DetailCard>
            <DetailCard title="Pool Counts">
              <KeyValue label="Control" value={String(detail.nodePools.control)} />
              <KeyValue label="Workers" value={String(detail.nodePools.worker)} />
              <KeyValue label="Etcd" value={String(detail.nodePools.etcd)} />
              <KeyValue label="External" value={String(detail.nodePools.external)} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="activity">
          <ActivityTable events={detail.activity} />
        </TabsContent>
      </Tabs>
    </>
  );
}

function Metric({ label, value, subValue, mono = false }: { label: string; value: string; subValue: string; mono?: boolean }) {
  return (
    <Card>
      <div className="text-[11px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">{label}</div>
      <div className={mono ? "mt-1 truncate font-mono text-sm font-semibold text-[color:var(--fg)]" : "mt-1 truncate text-xl font-semibold text-[color:var(--fg)]"}>
        {value}
      </div>
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
    <div className="grid grid-cols-[136px_minmax(0,1fr)] gap-3">
      <dt className="text-xs text-[color:var(--fg-muted)]">{label}</dt>
      <dd className={mono ? "truncate font-mono text-xs text-[color:var(--fg)]" : "truncate text-[color:var(--fg)]"}>
        {value}
      </dd>
    </div>
  );
}

function NodesTable({ nodes }: { nodes: KubernetesClusterNode[] }) {
  return (
    <Card className="p-0">
      <SectionTitle title="Nodes" />
      <Table className="min-w-[900px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Node</TableHead>
            <TableHead>Role</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Private IP</TableHead>
            <TableHead>Public IP</TableHead>
            <TableHead>Zone</TableHead>
            <TableHead className="pr-4">Account</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {nodes.length > 0 ? (
            nodes.map((node) => (
              <TableRow key={node.id}>
                <TableCell className="pl-4">
                  <div className="font-medium">{node.name}</div>
                  <div className="font-mono text-xs text-[color:var(--fg-muted)]">{node.id}</div>
                </TableCell>
                <TableCell>
                  <Badge variant={nodeRoleVariant(node.role)}>{stateLabel(node.role)}</Badge>
                </TableCell>
                <TableCell>{node.state}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{node.ip}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{node.publicIp ?? "-"}</TableCell>
                <TableCell>{node.zone ?? "-"}</TableCell>
                <TableCell className="pr-4">{node.account ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={7} className="h-24 text-center text-sm text-[color:var(--fg-muted)]">
                No node inventory returned for this cluster.
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
                No Kubernetes cluster events found.
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

function clusterStateVariant(state: KubernetesCluster["state"]): "success" | "warning" | "danger" {
  switch (state) {
    case "running":
      return "success";
    case "updating":
      return "warning";
    default:
      return "danger";
  }
}

function nodeRoleVariant(role: KubernetesNodeRole): "accent" | "success" | "info" | "default" {
  switch (role) {
    case "control":
      return "accent";
    case "worker":
      return "success";
    case "etcd":
      return "info";
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

function formatNodePoolSummary(detail: KubernetesClusterDetail): string {
  return `${detail.nodePools.control} control, ${detail.nodePools.worker} worker, ${detail.nodePools.etcd} etcd`;
}

function formatScalingRange(detail: KubernetesClusterDetail): string {
  if (detail.autoscaling.min === null && detail.autoscaling.max === null) {
    return "No range reported";
  }

  return `${formatNullableNumber(detail.autoscaling.min)} min / ${formatNullableNumber(detail.autoscaling.max)} max`;
}

function formatNullableNumber(value: number | null): string {
  return value === null ? "-" : String(value);
}
