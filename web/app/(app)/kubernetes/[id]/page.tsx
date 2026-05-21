import type { Metadata } from "next";
import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { TableEmptyState } from "@/components/ui/empty-state";
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
  type KubernetesClusterNode,
  type KubernetesNodeRole,
} from "@/lib/cloudstack/kubernetes";
import type { Event, KubernetesCluster } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.kubernetesDetail");

  return { title: t("metadataTitle") };
}

export default async function Page({ params }: { params: { id: string } }) {
  const t = await getTranslations("Core.pages.kubernetesDetail");
  const detail = await getKubernetesClusterDetailFromBff(params.id, { requestHeaders: headers() });

  if (!detail) {
    notFound();
  }

  const clusterStateLabels: Record<KubernetesCluster["state"], string> = {
    running: t("states.running"),
    updating: t("states.updating"),
    degraded: t("states.degraded"),
  };
  const nodeRoleLabels: Record<KubernetesNodeRole, string> = {
    control: t("roles.control"),
    worker: t("roles.worker"),
    etcd: t("roles.etcd"),
    external: t("roles.external"),
  };
  const eventLevelLabels: Record<Event["level"], string> = {
    info: t("levels.info"),
    warn: t("levels.warn"),
    error: t("levels.error"),
  };
  const scalingRange =
    detail.autoscaling.min === null && detail.autoscaling.max === null
      ? t("metrics.autoscaling.noRange")
      : t("metrics.autoscaling.range", {
          min: formatNullableNumber(detail.autoscaling.min),
          max: formatNullableNumber(detail.autoscaling.max),
        });

  return (
    <>
      <PageHeader
        title={detail.cluster.name}
        description={t("description", {
          account: detail.identity.account,
          zone: detail.placement.zone,
          id: detail.cluster.id,
        })}
        actions={
          <>
            <Badge variant={clusterStateVariant(detail.cluster.state)} size="md">
              {clusterStateLabel(detail.cluster.state, clusterStateLabels)}
            </Badge>
            <Badge variant="info" size="md">{detail.version.semanticVersion ?? detail.version.name}</Badge>
            <Badge variant="default" size="md">{detail.identity.type}</Badge>
            <Badge variant="accent" size="md">{detail.placement.zone}</Badge>
            <Badge variant="default" size="md">{detail.identity.account}</Badge>
          </>
        }
      />

      <section className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric
          label={t("metrics.nodes.label")}
          value={String(detail.nodePools.total)}
          subValue={t("metrics.nodes.subValue", {
            control: detail.nodePools.control,
            worker: detail.nodePools.worker,
            etcd: detail.nodePools.etcd,
          })}
        />
        <Metric
          label={t("metrics.version.label")}
          value={detail.version.semanticVersion ?? detail.version.name}
          subValue={detail.version.kubernetesVersionId ?? t("metrics.version.noVersionId")}
        />
        <Metric
          label={t("metrics.endpoint.label")}
          value={detail.networking.endpoint}
          subValue={detail.networking.networkName ?? t("metrics.endpoint.noNetworkName")}
          mono
        />
        <Metric
          label={t("metrics.autoscaling.label")}
          value={detail.autoscaling.enabled ? t("metrics.autoscaling.enabled") : t("metrics.autoscaling.disabled")}
          subValue={scalingRange}
        />
      </section>

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{t("tabs.overview")}</TabsTrigger>
          <TabsTrigger value="nodes" count={detail.nodes.length}>{t("tabs.nodes")}</TabsTrigger>
          <TabsTrigger value="networking">{t("tabs.networking")}</TabsTrigger>
          <TabsTrigger value="scaling">{t("tabs.scaling")}</TabsTrigger>
          <TabsTrigger value="activity" count={detail.activity.length}>{t("tabs.activity")}</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title={t("cards.identity")}>
              <KeyValue label={t("labels.name")} value={detail.summary.name} />
              <KeyValue label={t("labels.id")} value={detail.summary.id} mono />
              <KeyValue label={t("labels.description")} value={detail.summary.description ?? "-"} />
              <KeyValue label={t("labels.account")} value={detail.identity.account} />
              <KeyValue label={t("labels.domain")} value={detail.identity.domain} />
              <KeyValue label={t("labels.project")} value={detail.identity.project ?? "-"} />
              <KeyValue label={t("labels.created")} value={detail.identity.created ?? "-"} mono />
              <KeyValue label={t("labels.type")} value={detail.identity.type} />
            </DetailCard>

            <DetailCard title={t("cards.placementAndVersion")}>
              <KeyValue label={t("labels.zone")} value={detail.placement.zone} />
              <KeyValue label={t("labels.zoneId")} value={detail.placement.zoneId ?? "-"} mono />
              <KeyValue label={t("labels.version")} value={detail.version.name} />
              <KeyValue label={t("labels.semantic")} value={detail.version.semanticVersion ?? "-"} />
              <KeyValue label={t("labels.versionId")} value={detail.version.kubernetesVersionId ?? "-"} mono />
              <KeyValue label={t("labels.state")} value={clusterStateLabel(detail.cluster.state, clusterStateLabels)} />
            </DetailCard>

            <DetailCard title={t("cards.imagesAndOfferings")}>
              <KeyValue label={t("labels.serviceOffering")} value={detail.offerings.serviceOfferingName ?? "-"} />
              <KeyValue label={t("labels.offeringId")} value={detail.offerings.serviceOfferingId ?? "-"} mono />
              <KeyValue label={t("labels.template")} value={detail.offerings.templateName ?? "-"} />
              <KeyValue label={t("labels.templateId")} value={detail.offerings.templateId ?? "-"} mono />
              <KeyValue label={t("labels.sshKey")} value={detail.offerings.sshKeyPair ?? "-"} />
            </DetailCard>

            <DetailCard title={t("cards.summary")}>
              <KeyValue label={t("labels.control")} value={String(detail.nodePools.control)} />
              <KeyValue label={t("labels.workers")} value={String(detail.nodePools.worker)} />
              <KeyValue label={t("labels.etcd")} value={String(detail.nodePools.etcd)} />
              <KeyValue label={t("labels.external")} value={String(detail.nodePools.external)} />
              <KeyValue label={t("labels.total")} value={String(detail.nodePools.total)} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="nodes">
          <NodesTable
            nodes={detail.nodes}
            labels={{
              sectionTitle: t("sections.nodes"),
              table: {
                node: t("nodes.table.node"),
                role: t("nodes.table.role"),
                state: t("nodes.table.state"),
                privateIp: t("nodes.table.privateIp"),
                publicIp: t("nodes.table.publicIp"),
                zone: t("nodes.table.zone"),
                account: t("nodes.table.account"),
              },
              emptyState: {
                title: t("nodes.emptyState.title"),
                description: t("nodes.emptyState.description"),
              },
              roles: nodeRoleLabels,
            }}
          />
        </TabsContent>

        <TabsContent value="networking">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title={t("cards.network")}>
              <KeyValue label={t("labels.network")} value={detail.networking.networkName ?? "-"} />
              <KeyValue label={t("labels.networkId")} value={detail.networking.networkId ?? "-"} mono />
              <KeyValue label={t("labels.endpoint")} value={detail.networking.endpoint} mono />
              <KeyValue label={t("labels.consoleEndpoint")} value={detail.networking.consoleEndpoint ?? "-"} mono />
            </DetailCard>
            <DetailCard title={t("cards.plugins")}>
              <KeyValue label={t("labels.cni")} value={detail.networking.cni ?? "-"} />
              <KeyValue label={t("labels.csi")} value={detail.networking.csi ?? "-"} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="scaling">
          <section className="grid gap-4 xl:grid-cols-2">
            <DetailCard title={t("cards.autoscaling")}>
              <KeyValue label={t("labels.enabled")} value={detail.autoscaling.enabled ? t("values.yes") : t("values.no")} />
              <KeyValue label={t("labels.minimum")} value={formatNullableNumber(detail.autoscaling.min)} />
              <KeyValue label={t("labels.maximum")} value={formatNullableNumber(detail.autoscaling.max)} />
              <KeyValue label={t("labels.currentTotal")} value={String(detail.nodePools.total)} />
            </DetailCard>
            <DetailCard title={t("cards.poolCounts")}>
              <KeyValue label={t("labels.control")} value={String(detail.nodePools.control)} />
              <KeyValue label={t("labels.workers")} value={String(detail.nodePools.worker)} />
              <KeyValue label={t("labels.etcd")} value={String(detail.nodePools.etcd)} />
              <KeyValue label={t("labels.external")} value={String(detail.nodePools.external)} />
            </DetailCard>
          </section>
        </TabsContent>

        <TabsContent value="activity">
          <ActivityTable
            events={detail.activity}
            labels={{
              sectionTitle: t("sections.activity"),
              table: {
                time: t("activity.table.time"),
                level: t("activity.table.level"),
                action: t("activity.table.action"),
                target: t("activity.table.target"),
                user: t("activity.table.user"),
                description: t("activity.table.description"),
              },
              emptyState: {
                title: t("activity.emptyState.title"),
                description: t("activity.emptyState.description"),
              },
              levels: eventLevelLabels,
            }}
          />
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

type NodesTableLabels = {
  sectionTitle: string;
  table: {
    node: string;
    role: string;
    state: string;
    privateIp: string;
    publicIp: string;
    zone: string;
    account: string;
  };
  emptyState: {
    title: string;
    description: string;
  };
  roles: Record<KubernetesNodeRole, string>;
};

type ActivityTableLabels = {
  sectionTitle: string;
  table: {
    time: string;
    level: string;
    action: string;
    target: string;
    user: string;
    description: string;
  };
  emptyState: {
    title: string;
    description: string;
  };
  levels: Record<Event["level"], string>;
};

function NodesTable({ nodes, labels }: { nodes: KubernetesClusterNode[]; labels: NodesTableLabels }) {
  return (
    <Card className="p-0">
      <SectionTitle title={labels.sectionTitle} />
      <Table className="min-w-[900px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{labels.table.node}</TableHead>
            <TableHead>{labels.table.role}</TableHead>
            <TableHead>{labels.table.state}</TableHead>
            <TableHead>{labels.table.privateIp}</TableHead>
            <TableHead>{labels.table.publicIp}</TableHead>
            <TableHead>{labels.table.zone}</TableHead>
            <TableHead className="pr-4">{labels.table.account}</TableHead>
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
                  <Badge variant={nodeRoleVariant(node.role)}>{nodeRoleLabel(node.role, labels.roles)}</Badge>
                </TableCell>
                <TableCell>{node.state}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{node.ip}</TableCell>
                <TableCell className="font-mono text-xs tabular-nums">{node.publicIp ?? "-"}</TableCell>
                <TableCell>{node.zone ?? "-"}</TableCell>
                <TableCell className="pr-4">{node.account ?? "-"}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={7}
              title={labels.emptyState.title}
              description={labels.emptyState.description}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function ActivityTable({ events, labels }: { events: Event[]; labels: ActivityTableLabels }) {
  return (
    <Card className="p-0">
      <SectionTitle title={labels.sectionTitle} />
      <Table className="min-w-[980px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{labels.table.time}</TableHead>
            <TableHead>{labels.table.level}</TableHead>
            <TableHead>{labels.table.action}</TableHead>
            <TableHead>{labels.table.target}</TableHead>
            <TableHead>{labels.table.user}</TableHead>
            <TableHead className="pr-4">{labels.table.description}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {events.length > 0 ? (
            events.map((event, index) => (
              <TableRow key={`${event.timestamp}-${event.action}-${index}`}>
                <TableCell className="pl-4 font-mono text-xs tabular-nums">{event.timestamp}</TableCell>
                <TableCell>
                  <Badge variant={eventLevelVariant(event.level)}>{eventLevelLabel(event.level, labels.levels)}</Badge>
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
              title={labels.emptyState.title}
              description={labels.emptyState.description}
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

function clusterStateLabel(
  state: KubernetesCluster["state"],
  labels: Record<KubernetesCluster["state"], string>,
): string {
  return labels[state];
}

function nodeRoleLabel(role: KubernetesNodeRole, labels: Record<KubernetesNodeRole, string>): string {
  return labels[role];
}

function eventLevelLabel(level: Event["level"], labels: Record<Event["level"], string>): string {
  return labels[level];
}

function formatNullableNumber(value: number | null): string {
  return value === null ? "-" : String(value);
}
