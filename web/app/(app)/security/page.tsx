import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { SecurityGroupActions } from "@/components/security-groups/security-group-actions";
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
import { getSecurityGroupsFromBff } from "@/lib/cloudstack/security-groups";
import type { SecurityGroup, SecurityGroupRule } from "@/lib/mock-data";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Core.pages.securityGroups");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Core.pages.securityGroups");
  const securityGroups = await getSecurityGroupsFromBff({ requestHeaders: headers() });
  const accounts = new Set(securityGroups.map((group) => group.account)).size;
  const domains = new Set(securityGroups.map((group) => group.domainPath)).size;
  const instances = securityGroups.reduce((sum, group) => sum + group.instances, 0);
  const defaultGroups = securityGroups.filter((group) => group.isDefault).length;

  return (
    <>
      <PageHeader
        title={t("title")}
        description={t("description", { groups: securityGroups.length, accounts, domains })}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{t("badges.total", { count: securityGroups.length })}</Badge>
        <Badge variant="accent" size="md">{t("badges.accounts", { count: accounts })}</Badge>
        <Badge variant="default" size="md">{t("badges.domains", { count: domains })}</Badge>
        <Badge variant={instances > 0 ? "success" : "default"} size="md">{t("badges.instances", { count: instances })}</Badge>
        <Badge variant={defaultGroups > 0 ? "warning" : "default"} size="md">{t("badges.default", { count: defaultGroups })}</Badge>
      </div>

      <SecurityGroupActions securityGroups={securityGroups} />

      <Card className="p-0">
        <Table className="min-w-[1120px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">{t("columns.group")}</TableHead>
              <TableHead>{t("columns.description")}</TableHead>
              <TableHead>{t("columns.scope")}</TableHead>
              <TableHead>{t("columns.ingress")}</TableHead>
              <TableHead>{t("columns.egress")}</TableHead>
              <TableHead className="text-right">{t("columns.instances")}</TableHead>
              <TableHead className="pr-4">{t("columns.flags")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {securityGroups.length > 0 ? (
              securityGroups.map((group) => (
                <TableRow key={group.id}>
                  <TableCell className="pl-4">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{group.name}</div>
                      <div className="truncate font-mono text-xs text-[color:var(--fg-muted)]">{group.id}</div>
                    </div>
                  </TableCell>
                  <TableCell className="max-w-[240px] truncate">{group.description}</TableCell>
                  <TableCell>
                    <Scope group={group} />
                  </TableCell>
                  <TableCell className="max-w-[220px]">
                    <RuleSummary
                      rules={group.ingressRules}
                      fromLabel={t("rules.from")}
                      moreLabel={t("rules.more", { count: group.ingressRules.length - 1 })}
                    />
                  </TableCell>
                  <TableCell className="max-w-[220px]">
                    <RuleSummary
                      rules={group.egressRules}
                      fromLabel={t("rules.from")}
                      moreLabel={t("rules.more", { count: group.egressRules.length - 1 })}
                    />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{group.instances}</TableCell>
                  <TableCell className="pr-4">
                    <GroupFlags
                      group={group}
                      defaultLabel={t("flags.default")}
                      customLabel={t("flags.custom")}
                      projectLabel={t("flags.project")}
                    />
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

function Scope({ group }: { group: SecurityGroup }) {
  return (
    <div className="min-w-0 space-y-1 text-sm">
      <div className="truncate">{group.account}</div>
      <div className="truncate text-xs text-[color:var(--fg-muted)]">{group.domainPath}</div>
      {group.project ? <Badge variant="default">{group.project}</Badge> : null}
    </div>
  );
}

function RuleSummary({
  rules,
  fromLabel,
  moreLabel,
}: {
  rules: SecurityGroupRule[];
  fromLabel: string;
  moreLabel: string;
}) {
  if (rules.length === 0) {
    return <span className="text-[color:var(--fg-muted)]">-</span>;
  }

  const firstRule = rules[0];

  return (
    <div className="min-w-0 space-y-1">
      {firstRule ? (
        <div className="truncate text-sm">
          <span className="font-medium">{firstRule.protocol}</span>{" "}
          <span className="font-mono text-xs">{firstRule.range}</span>
          <span className="text-[color:var(--fg-muted)]"> {fromLabel} </span>
          <span className="font-mono text-xs">{firstRule.source}</span>
        </div>
      ) : null}
      {rules.length > 1 ? (
        <div className="text-xs text-[color:var(--fg-muted)]">{moreLabel}</div>
      ) : null}
    </div>
  );
}

function GroupFlags({
  group,
  defaultLabel,
  customLabel,
  projectLabel,
}: {
  group: SecurityGroup;
  defaultLabel: string;
  customLabel: string;
  projectLabel: string;
}) {
  return (
    <div className="flex flex-wrap gap-1">
      {group.isDefault ? <Badge variant="warning">{defaultLabel}</Badge> : <Badge variant="default">{customLabel}</Badge>}
      {group.project ? <Badge variant="accent">{projectLabel}</Badge> : null}
    </div>
  );
}
