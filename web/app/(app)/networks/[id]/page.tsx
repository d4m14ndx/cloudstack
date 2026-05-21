import { headers } from "next/headers";
import { notFound } from "next/navigation";

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

export const metadata = { title: "Network" };
export const dynamic = "force-dynamic";

export default async function Page({ params }: { params: { id: string } }) {
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
              {detail.network.state === "running" ? "Running" : "Warning"}
            </Badge>
            <Badge variant={typeVariant(detail.kind)} size="md">{detail.kind}</Badge>
            <Badge variant="default" size="md">{detail.ownership.account}</Badge>
          </>
        }
      />

      <section className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric label="CIDR" value={detail.addressing.cidr} subValue={detail.addressing.gateway === "-" ? "Gateway per tier" : detail.addressing.gateway} mono />
        <Metric label="Instances" value={String(detail.summary.instanceCount)} subValue="unique VMs" />
        <Metric label="Public IPs" value={String(detail.summary.publicIpCount)} subValue="allocated addresses" />
        <Metric label={detail.kind === "VPC" ? "Tiers" : "ACLs"} value={String(detail.kind === "VPC" ? detail.summary.tierCount : detail.summary.aclCount)} subValue={detail.offering.name ?? "-"} />
      </section>

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          {detail.kind === "VPC" && <TabsTrigger value="tiers" count={detail.tiers.length}>Tiers</TabsTrigger>}
          <TabsTrigger value="public-ips" count={detail.publicIps.length}>Public IPs</TabsTrigger>
          <TabsTrigger value="acls" count={detail.aclLists.length}>ACLs</TabsTrigger>
          <TabsTrigger value="activity" count={detail.activity.length}>Activity</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <Overview detail={detail} />
        </TabsContent>

        {detail.kind === "VPC" && (
          <TabsContent value="tiers">
            <TiersTable tiers={detail.tiers} />
          </TabsContent>
        )}

        <TabsContent value="public-ips">
          <PublicIpsTable detail={detail} />
        </TabsContent>

        <TabsContent value="acls">
          <AclTables aclLists={detail.aclLists} />
        </TabsContent>

        <TabsContent value="activity">
          <ActivityTable events={detail.activity} />
        </TabsContent>
      </Tabs>
    </>
  );
}

function Overview({ detail }: { detail: NetworkDetail }) {
  return (
    <section className="grid gap-4 xl:grid-cols-2">
      <DetailCard title="Addressing">
        <KeyValue label="CIDR" value={detail.addressing.cidr} mono />
        <KeyValue label="Gateway" value={detail.addressing.gateway} mono />
        <KeyValue label="Netmask" value={detail.addressing.netmask ?? "-"} mono />
        <KeyValue label="Network domain" value={detail.addressing.networkDomain ?? "-"} mono />
      </DetailCard>

      <DetailCard title="Ownership">
        <KeyValue label="Account" value={detail.ownership.account} />
        <KeyValue label="Domain" value={detail.ownership.domain} />
        <KeyValue label="Project" value={detail.ownership.project ?? "-"} />
        <KeyValue label="Zone" value={detail.ownership.zone} />
      </DetailCard>

      <DetailCard title="Offering">
        <KeyValue label="Type" value={detail.kind} />
        <KeyValue label="Name" value={detail.offering.name ?? "-"} />
        <KeyValue label="State" value={detail.network.state === "running" ? "Running" : "Warning"} />
        <KeyValue label="Instances" value={String(detail.summary.instanceCount)} />
      </DetailCard>

      <DetailCard title="Flags">
        <KeyValue label="Redundant" value={formatBoolean(detail.flags.redundant)} />
        <KeyValue label="Distributed" value={formatBoolean(detail.flags.distributed)} />
        <KeyValue label="Restart required" value={formatBoolean(detail.flags.restartRequired)} />
        <KeyValue label="Deployable" value={formatBoolean(detail.flags.canUseForDeploy)} />
      </DetailCard>
    </section>
  );
}

function TiersTable({ tiers }: { tiers: NetworkTier[] }) {
  return (
    <Card className="p-0">
      <SectionTitle title="Tiers" />
      <Table className="min-w-[900px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Tier</TableHead>
            <TableHead>CIDR</TableHead>
            <TableHead>Gateway</TableHead>
            <TableHead>Netmask</TableHead>
            <TableHead>Offering</TableHead>
            <TableHead className="pr-4">State</TableHead>
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
                  <Badge variant={tier.state === "running" ? "success" : "warning"}>{tier.state === "running" ? "Running" : "Warning"}</Badge>
                </TableCell>
              </TableRow>
            ))
          ) : (
            <TableEmptyState
              colSpan={6}
              title="No tiers in this VPC"
              description="CloudStack did not return network tiers for this VPC."
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function PublicIpsTable({ detail }: { detail: NetworkDetail }) {
  return (
    <Card className="p-0">
      <SectionTitle
        title="Public IPs"
        actions={<AcquirePublicIpButton networkId={detail.network.id} networkKind={detail.kind} />}
      />
      <Table className="min-w-[980px]">
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Address</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Role</TableHead>
            <TableHead>Network</TableHead>
            <TableHead>VM</TableHead>
            <TableHead className="pr-4 text-right">Actions</TableHead>
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
                    {publicIp.sourceNat && <Badge variant="accent">Source NAT</Badge>}
                    {publicIp.staticNat && <Badge variant="info">Static NAT</Badge>}
                    {!publicIp.sourceNat && !publicIp.staticNat && <Badge variant="default">Allocated</Badge>}
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
              title="No public IPs allocated"
              description="Acquire an address when this network needs source NAT, static NAT, or inbound access."
            />
          )}
        </TableBody>
      </Table>
    </Card>
  );
}

function AclTables({ aclLists }: { aclLists: NetworkAclList[] }) {
  return (
    <section className="grid gap-4">
      {aclLists.length > 0 ? (
        aclLists.map((aclList) => (
          <Card key={aclList.id} className="p-0">
            <SectionTitle title={aclList.name} />
            <Table className="min-w-[960px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Rule</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Protocol</TableHead>
                  <TableHead>Range</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Traffic</TableHead>
                  <TableHead className="pr-4">State</TableHead>
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
                    title="No ACL rules in this list"
                    description="CloudStack did not return network ACL rules for this list."
                  />
                )}
              </TableBody>
            </Table>
          </Card>
        ))
      ) : (
        <Card>
          <EmptyState
            title="No ACL lists on this network"
            description="CloudStack did not return network ACL lists for this network."
            className="py-6"
          />
        </Card>
      )}
    </section>
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
            <TableEmptyState
              colSpan={6}
              title="No network activity"
              description="CloudStack did not return audit or lifecycle events for this network."
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

function formatBoolean(value: boolean): string {
  return value ? "Yes" : "No";
}
