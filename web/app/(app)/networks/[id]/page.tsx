import type { Metadata } from "next";
import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState, TableEmptyState } from "@/components/ui/empty-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AcquirePublicIpButton, PublicIpRowActions } from "@/components/networks/network-actions";
import { getNetworkDetailFromBff } from "@/lib/cloudstack/network-detail";
import type { Event, Network, NetworkAclList, NetworkDetail, NetworkTier } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

type NetworkDetailLabels = ReturnType<typeof getNetworkDetailLabels>;

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.networkDetail");

  return { title: t("metadataTitle") };
}

function getNetworkDetailLabels(t: Awaited<ReturnType<typeof getTranslations>>) {
  return {
    metrics: {
      cidr: t("metrics.cidr"),
      gatewayPerTier: t("metrics.gatewayPerTier"),
      instances: t("metrics.instances"),
      uniqueVms: t("metrics.uniqueVms"),
      publicIps: t("metrics.publicIps"),
      allocatedAddresses: t("metrics.allocatedAddresses"),
      tiers: t("metrics.tiers"),
      acls: t("metrics.acls"),
    },
    tabs: {
      overview: t("tabs.overview"),
      tiers: t("tabs.tiers"),
      publicIps: t("tabs.publicIps"),
      acls: t("tabs.acls"),
      activity: t("tabs.activity"),
    },
    cards: {
      addressing: t("cards.addressing"),
      ownership: t("cards.ownership"),
      offering: t("cards.offering"),
      flags: t("cards.flags"),
    },
    fields: {
      cidr: t("fields.cidr"),
      gateway: t("fields.gateway"),
      netmask: t("fields.netmask"),
      networkDomain: t("fields.networkDomain"),
      account: t("fields.account"),
      domain: t("fields.domain"),
      project: t("fields.project"),
      zone: t("fields.zone"),
      type: t("fields.type"),
      name: t("fields.name"),
      state: t("fields.state"),
      instances: t("fields.instances"),
      redundant: t("fields.redundant"),
      distributed: t("fields.distributed"),
      restartRequired: t("fields.restartRequired"),
      deployable: t("fields.deployable"),
    },
    tables: {
      tiers: {
        title: t("tables.tiers.title"),
        columns: {
          tier: t("tables.tiers.columns.tier"),
          cidr: t("tables.tiers.columns.cidr"),
          gateway: t("tables.tiers.columns.gateway"),
          netmask: t("tables.tiers.columns.netmask"),
          offering: t("tables.tiers.columns.offering"),
          state: t("tables.tiers.columns.state"),
        },
      },
      publicIps: {
        title: t("tables.publicIps.title"),
        columns: {
          address: t("tables.publicIps.columns.address"),
          state: t("tables.publicIps.columns.state"),
          role: t("tables.publicIps.columns.role"),
          network: t("tables.publicIps.columns.network"),
          vm: t("tables.publicIps.columns.vm"),
          actions: t("tables.publicIps.columns.actions"),
        },
      },
      acls: {
        columns: {
          rule: t("tables.acls.columns.rule"),
          action: t("tables.acls.columns.action"),
          protocol: t("tables.acls.columns.protocol"),
          range: t("tables.acls.columns.range"),
          source: t("tables.acls.columns.source"),
          traffic: t("tables.acls.columns.traffic"),
          state: t("tables.acls.columns.state"),
        },
      },
      activity: {
        title: t("tables.activity.title"),
        columns: {
          time: t("tables.activity.columns.time"),
          level: t("tables.activity.columns.level"),
          action: t("tables.activity.columns.action"),
          target: t("tables.activity.columns.target"),
          user: t("tables.activity.columns.user"),
          description: t("tables.activity.columns.description"),
        },
      },
    },
    badges: {
      sourceNat: t("badges.sourceNat"),
      staticNat: t("badges.staticNat"),
      allocated: t("badges.allocated"),
    },
    state: {
      running: t("state.running"),
      warning: t("state.warning"),
    },
    boolean: {
      yes: t("boolean.yes"),
      no: t("boolean.no"),
    },
    emptyState: {
      tiers: {
        title: t("emptyState.tiers.title"),
        description: t("emptyState.tiers.description"),
      },
      publicIps: {
        title: t("emptyState.publicIps.title"),
        description: t("emptyState.publicIps.description"),
      },
      aclRules: {
        title: t("emptyState.aclRules.title"),
        description: t("emptyState.aclRules.description"),
      },
      aclLists: {
        title: t("emptyState.aclLists.title"),
        description: t("emptyState.aclLists.description"),
      },
      activity: {
        title: t("emptyState.activity.title"),
        description: t("emptyState.activity.description"),
      },
    },
  };
}

export default async function Page({ params }: { params: { id: string } }) {
  const t = await getTranslations("Core.pages.networkDetail");
  const labels = getNetworkDetailLabels(t);
  const detail = await getNetworkDetailFromBff(params.id, { requestHeaders: headers() });

  if (!detail) {
    notFound();
  }

  return (
    <>
      <PageHeader
        title={detail.network.name}
        description={`${detail.kind} · ${detail.ownership.zone} · ${detail.network.id}`}
        actions={
          <>
            <Badge variant={detail.network.state === "running" ? "success" : "warning"} size="md">
              {detail.network.state === "running" ? labels.state.running : labels.state.warning}
            </Badge>
            <Badge variant={typeVariant(detail.kind)} size="md">{detail.kind}</Badge>
            <Badge variant="default" size="md">{detail.ownership.account}</Badge>
          </>
        }
      />

      <section className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric label={labels.metrics.cidr} value={detail.addressing.cidr} subValue={detail.addressing.gateway === "-" ? labels.metrics.gatewayPerTier : detail.addressing.gateway} mono />
        <Metric label={labels.metrics.instances} value={String(detail.summary.instanceCount)} subValue={labels.metrics.uniqueVms} />
        <Metric label={labels.metrics.publicIps} value={String(detail.summary.publicIpCount)} subValue={labels.metrics.allocatedAddresses} />
        <Metric label={detail.kind === "VPC" ? labels.metrics.tiers : labels.metrics.acls} value={String(detail.kind === "VPC" ? detail.summary.tierCount : detail.summary.aclCount)} subValue={detail.offering.name ?? "-"} />
      </section>

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">{labels.tabs.overview}</TabsTrigger>
          {detail.kind === "VPC" && <TabsTrigger value="tiers" count={detail.tiers.length}>{labels.tabs.tiers}</TabsTrigger>}
          <TabsTrigger value="public-ips" count={detail.publicIps.length}>{labels.tabs.publicIps}</TabsTrigger>
          <TabsTrigger value="acls" count={detail.aclLists.length}>{labels.tabs.acls}</TabsTrigger>
          <TabsTrigger value="activity" count={detail.activity.length}>{labels.tabs.activity}</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <Overview detail={detail} labels={labels} />
        </TabsContent>

        {detail.kind === "VPC" && (
          <TabsContent value="tiers">
            <TiersTable tiers={detail.tiers} labels={labels} />
          </TabsContent>
        )}

        <TabsContent value="public-ips">
          <PublicIpsTable detail={detail} labels={labels} />
        </TabsContent>

        <TabsContent value="acls">
          <AclTables aclLists={detail.aclLists} labels={labels} />
        </TabsContent>

        <TabsContent value="activity">
          <ActivityTable events={detail.activity} labels={labels} />
        </TabsContent>
      </Tabs>
    </>
  );
}

function Overview({ detail, labels }: { detail: NetworkDetail; labels: NetworkDetailLabels }) {
  return (
    <section className="grid gap-4 xl:grid-cols-2">
      <DetailCard title={labels.cards.addressing}>
        <KeyValue label={labels.fields.cidr} value={detail.addressing.cidr} mono />
        <KeyValue label={labels.fields.gateway} value={detail.addressing.gateway} mono />
        <KeyValue label={labels.fields.netmask} value={detail.addressing.netmask ?? "-"} mono />
        <KeyValue label={labels.fields.networkDomain} value={detail.addressing.networkDomain ?? "-"} mono />
      </DetailCard>

      <DetailCard title={labels.cards.ownership}>
        <KeyValue label={labels.fields.account} value={detail.ownership.account} />
        <KeyValue label={labels.fields.domain} value={detail.ownership.domain} />
        <KeyValue label={labels.fields.project} value={detail.ownership.project ?? "-"} />
        <KeyValue label={labels.fields.zone} value={detail.ownership.zone} />
      </DetailCard>

      <DetailCard title={labels.cards.offering}>
        <KeyValue label={labels.fields.type} value={detail.kind} />
        <KeyValue label={labels.fields.name} value={detail.offering.name ?? "-"} />
        <KeyValue label={labels.fields.state} value={detail.network.state === "running" ? labels.state.running : labels.state.warning} />
        <KeyValue label={labels.fields.instances} value={String(detail.summary.instanceCount)} />
      </DetailCard>

      <DetailCard title={labels.cards.flags}>
        <KeyValue label={labels.fields.redundant} value={formatBoolean(detail.flags.redundant, labels)} />
        <KeyValue label={labels.fields.distributed} value={formatBoolean(detail.flags.distributed, labels)} />
        <KeyValue label={labels.fields.restartRequired} value={formatBoolean(detail.flags.restartRequired, labels)} />
        <KeyValue label={labels.fields.deployable} value={formatBoolean(detail.flags.canUseForDeploy, labels)} />
      </DetailCard>
    </section>
  );
}

function TiersTable({ tiers, labels }: { tiers: NetworkTier[]; labels: NetworkDetailLabels }) {
  return (
    <Card className="p-0">
      <SectionTitle title={labels.tables.tiers.title} />
      <Table className="min-w-[900px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{labels.tables.tiers.columns.tier}</TableHead>
            <TableHead>{labels.tables.tiers.columns.cidr}</TableHead>
            <TableHead>{labels.tables.tiers.columns.gateway}</TableHead>
            <TableHead>{labels.tables.tiers.columns.netmask}</TableHead>
            <TableHead>{labels.tables.tiers.columns.offering}</TableHead>
            <TableHead className="pr-4">{labels.tables.tiers.columns.state}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {tiers.length > 0 ? (
            tiers.map((tier) => (
              <TableRow key={tier.id}>
                <TableCell className="pl-4">
                  <div className="font-medium">{tier.name}</div>
                  <div className="font-mono text-xs text-[color:var(--fg-muted)]">{tier.id}</div>
                </TableCell>
                <TableCell className="font-mono text-xs">{tier.cidr}</TableCell>
                <TableCell className="font-mono text-xs">{tier.gateway}</TableCell>
                <TableCell className="font-mono text-xs">{tier.netmask ?? "-"}</TableCell>
                <TableCell>{tier.offering ?? "-"}</TableCell>
                <TableCell className="pr-4">
                  <Badge variant={tier.state === "running" ? "success" : "warning"}>{tier.state === "running" ? labels.state.running : labels.state.warning}</Badge>
                </TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={6}
              title={labels.emptyState.tiers.title}
              description={labels.emptyState.tiers.description}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function PublicIpsTable({ detail, labels }: { detail: NetworkDetail; labels: NetworkDetailLabels }) {
  return (
    <Card className="p-0">
      <SectionTitle
        title={labels.tables.publicIps.title}
        actions={<AcquirePublicIpButton networkId={detail.network.id} networkKind={detail.kind} />}
      />
      <Table className="min-w-[980px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{labels.tables.publicIps.columns.address}</TableHead>
            <TableHead>{labels.tables.publicIps.columns.state}</TableHead>
            <TableHead>{labels.tables.publicIps.columns.role}</TableHead>
            <TableHead>{labels.tables.publicIps.columns.network}</TableHead>
            <TableHead>{labels.tables.publicIps.columns.vm}</TableHead>
            <TableHead className="pr-4 text-right">{labels.tables.publicIps.columns.actions}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {detail.publicIps.length > 0 ? (
            detail.publicIps.map((publicIp) => (
              <TableRow key={publicIp.id}>
                <TableCell className="pl-4 font-mono text-xs">{publicIp.address}</TableCell>
                <TableCell>{publicIp.state}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-2">
                    {publicIp.sourceNat && <Badge variant="accent">{labels.badges.sourceNat}</Badge>}
                    {publicIp.staticNat && <Badge variant="info">{labels.badges.staticNat}</Badge>}
                    {!publicIp.sourceNat && !publicIp.staticNat && <Badge variant="default">{labels.badges.allocated}</Badge>}
                  </div>
                </TableCell>
                <TableCell>{publicIp.networkName ?? "-"}</TableCell>
                <TableCell>{publicIp.vmName ?? "-"}</TableCell>
                <TableCell className="pr-4">
                  <PublicIpRowActions publicIp={publicIp} networkId={detail.network.id} networkKind={detail.kind} />
                </TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={6}
              title={labels.emptyState.publicIps.title}
              description={labels.emptyState.publicIps.description}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function AclTables({ aclLists, labels }: { aclLists: NetworkAclList[]; labels: NetworkDetailLabels }) {
  return (
    <section className="grid gap-4">
      {aclLists.length > 0 ? (
        aclLists.map((aclList) => (
          <Card key={aclList.id} className="p-0">
            <SectionTitle title={aclList.name} />
            <Table className="min-w-[960px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">{labels.tables.acls.columns.rule}</TableHead>
                  <TableHead>{labels.tables.acls.columns.action}</TableHead>
                  <TableHead>{labels.tables.acls.columns.protocol}</TableHead>
                  <TableHead>{labels.tables.acls.columns.range}</TableHead>
                  <TableHead>{labels.tables.acls.columns.source}</TableHead>
                  <TableHead>{labels.tables.acls.columns.traffic}</TableHead>
                  <TableHead className="pr-4">{labels.tables.acls.columns.state}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {aclList.rules.length > 0 ? (
                  aclList.rules.map((rule) => (
                    <TableRow key={rule.id}>
                      <TableCell className="pl-4 font-mono text-xs">{rule.number}</TableCell>
                      <TableCell>
                        <Badge variant={rule.action.toLowerCase() === "allow" ? "success" : "danger"}>{rule.action}</Badge>
                      </TableCell>
                      <TableCell className="font-mono text-xs">{rule.protocol}</TableCell>
                      <TableCell className="font-mono text-xs">{rule.range}</TableCell>
                      <TableCell className="font-mono text-xs">{rule.source}</TableCell>
                      <TableCell>{rule.trafficType}</TableCell>
                      <TableCell className="pr-4">{rule.state}</TableCell>
                    </TableRow>
                  ))
                ) : (
                  <TableEmptyState
                    colSpan={7}
                    title={labels.emptyState.aclRules.title}
                    description={labels.emptyState.aclRules.description}
                  />
                )}
              </TableBody>
            </Table>
          </Card>
        ))
      ) : (
        <Card>
          <EmptyState
            title={labels.emptyState.aclLists.title}
            description={labels.emptyState.aclLists.description}
            className="py-6"
          />
        </Card>
      )}
    </section>
  );
}

function ActivityTable({ events, labels }: { events: Event[]; labels: NetworkDetailLabels }) {
  return (
    <Card className="p-0">
      <SectionTitle title={labels.tables.activity.title} />
      <Table className="min-w-[980px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{labels.tables.activity.columns.time}</TableHead>
            <TableHead>{labels.tables.activity.columns.level}</TableHead>
            <TableHead>{labels.tables.activity.columns.action}</TableHead>
            <TableHead>{labels.tables.activity.columns.target}</TableHead>
            <TableHead>{labels.tables.activity.columns.user}</TableHead>
            <TableHead className="pr-4">{labels.tables.activity.columns.description}</TableHead>
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
              title={labels.emptyState.activity.title}
              description={labels.emptyState.activity.description}
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function Metric({ label, value, subValue, mono = false }: { label: string; value: string; subValue: string; mono?: boolean }) {
  return (
    <Card>
      <div className="text-[11px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">{label}</div>
      <div className={mono ? "mt-1 truncate font-mono text-lg font-semibold text-[color:var(--fg)]" : "mt-1 truncate text-xl font-semibold text-[color:var(--fg)]"}>{value}</div>
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
    <div className="grid grid-cols-[132px_minmax(0,1fr)] gap-3">
      <dt className="text-xs text-[color:var(--fg-muted)]">{label}</dt>
      <dd className={mono ? "truncate font-mono text-xs text-[color:var(--fg)]" : "truncate text-[color:var(--fg)]"}>
        {value}
      </dd>
    </div>
  );
}

function SectionTitle({ title, actions }: { title: string; actions?: React.ReactNode }) {
  return (
    <div className="flex min-h-14 items-center justify-between gap-3 border-b border-[color:var(--border)] px-[var(--card-pad)] py-3">
      <h2 className="text-sm font-semibold text-[color:var(--fg)]">{title}</h2>
      {actions ? <div className="shrink-0">{actions}</div> : null}
    </div>
  );
}

function typeVariant(type: Network["type"]): "accent" | "info" {
  return type === "VPC" ? "accent" : "info";
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

function formatBoolean(value: boolean, labels: NetworkDetailLabels): string {
  return value ? labels.boolean.yes : labels.boolean.no;
}
