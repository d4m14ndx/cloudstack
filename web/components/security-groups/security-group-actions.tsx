"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { AlertCircle, Loader2, Plus, RefreshCw, Trash2, X } from "@/components/icons";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import {
  authorizeSecurityGroupEgress,
  authorizeSecurityGroupIngress,
  createSecurityGroup,
  deleteSecurityGroup,
  revokeSecurityGroupEgress,
  revokeSecurityGroupIngress,
} from "@/lib/cloudstack/security-group-actions";
import type { SecurityGroup, SecurityGroupRule } from "@/lib/mock-data";

type SecurityGroupActionsProps = {
  securityGroups: SecurityGroup[];
};

type RuleDirection = "ingress" | "egress";
type Protocol = "tcp" | "udp" | "icmp";
type BusyAction = "create" | "rule" | "delete" | `ingress:${string}` | `egress:${string}`;

type CreateForm = {
  name: string;
  description: string;
};

type RuleForm = {
  groupId: string;
  direction: RuleDirection;
  protocol: Protocol;
  cidrList: string;
  startPort: string;
  endPort: string;
  icmpType: string;
  icmpCode: string;
};

const initialCreateForm: CreateForm = {
  name: "",
  description: "",
};

const initialRuleForm: RuleForm = {
  groupId: "",
  direction: "ingress",
  protocol: "tcp",
  cidrList: "0.0.0.0/0",
  startPort: "443",
  endPort: "443",
  icmpType: "8",
  icmpCode: "0",
};

export function SecurityGroupActions({ securityGroups }: SecurityGroupActionsProps) {
  const router = useRouter();
  const [createForm, setCreateForm] = useState<CreateForm>(initialCreateForm);
  const [ruleForm, setRuleForm] = useState<RuleForm>(() => ({
    ...initialRuleForm,
    groupId: securityGroups[0]?.id ?? "",
  }));
  const [busyAction, setBusyAction] = useState<BusyAction | null>(null);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  const selectedGroup = useMemo(
    () => securityGroups.find((group) => group.id === ruleForm.groupId) ?? securityGroups[0] ?? null,
    [ruleForm.groupId, securityGroups],
  );
  const canSubmitRule = Boolean(selectedGroup && ruleForm.protocol && ruleForm.cidrList.trim());
  const canCreate = Boolean(createForm.name.trim());

  async function handleCreate() {
    if (!canCreate) {
      return;
    }

    setBusyAction("create");
    setMessage(null);
    try {
      await createSecurityGroup({
        name: createForm.name,
        description: createForm.description,
        account: selectedGroup?.account,
        domainId: selectedGroup?.domainId ?? undefined,
        projectId: selectedGroup?.projectId ?? undefined,
      });
      setCreateForm(initialCreateForm);
      setMessage({ kind: "success", text: "Security group created." });
      router.refresh();
    } catch (error) {
      setMessage({ kind: "error", text: readError(error, "Create security group failed") });
    } finally {
      setBusyAction(null);
    }
  }

  async function handleAuthorizeRule() {
    if (!selectedGroup || !canSubmitRule) {
      return;
    }

    setBusyAction("rule");
    setMessage(null);
    try {
      const authorize = ruleForm.direction === "ingress" ? authorizeSecurityGroupIngress : authorizeSecurityGroupEgress;
      await authorize({
        securityGroupId: selectedGroup.id,
        protocol: ruleForm.protocol,
        cidrList: ruleForm.cidrList,
        account: selectedGroup.account,
        domainId: selectedGroup.domainId ?? undefined,
        projectId: selectedGroup.projectId ?? undefined,
        startPort: ruleForm.protocol === "icmp" ? undefined : ruleForm.startPort,
        endPort: ruleForm.protocol === "icmp" ? undefined : ruleForm.endPort,
        icmpType: ruleForm.protocol === "icmp" ? ruleForm.icmpType : undefined,
        icmpCode: ruleForm.protocol === "icmp" ? ruleForm.icmpCode : undefined,
      });
      setMessage({ kind: "success", text: "Rule authorized." });
      router.refresh();
    } catch (error) {
      setMessage({ kind: "error", text: readError(error, "Authorize rule failed") });
    } finally {
      setBusyAction(null);
    }
  }

  async function handleRevokeRule(direction: RuleDirection, rule: SecurityGroupRule) {
    const key = `${direction}:${rule.id}` as const;
    setBusyAction(key);
    setMessage(null);
    try {
      const revoke = direction === "ingress" ? revokeSecurityGroupIngress : revokeSecurityGroupEgress;
      await revoke({ ruleId: rule.id });
      setMessage({ kind: "success", text: "Rule revoked." });
      router.refresh();
    } catch (error) {
      setMessage({ kind: "error", text: readError(error, "Revoke rule failed") });
    } finally {
      setBusyAction(null);
    }
  }

  async function handleDeleteGroup() {
    if (!selectedGroup) {
      return;
    }

    const confirmed = window.confirm(`Delete security group "${selectedGroup.name}"?`);
    if (!confirmed) {
      return;
    }

    setBusyAction("delete");
    setMessage(null);
    try {
      await deleteSecurityGroup({
        id: selectedGroup.id,
        account: selectedGroup.account,
        domainId: selectedGroup.domainId ?? undefined,
        projectId: selectedGroup.projectId ?? undefined,
      });
      setRuleForm((current) => ({ ...current, groupId: "" }));
      setMessage({ kind: "success", text: "Security group deleted." });
      router.refresh();
    } catch (error) {
      setMessage({ kind: "error", text: readError(error, "Delete security group failed") });
    } finally {
      setBusyAction(null);
    }
  }

  return (
    <Card className="mb-4">
      <CardHeader className="mb-3 items-start gap-3 sm:flex-row">
        <div>
          <CardTitle>Actions</CardTitle>
          <CardDescription>Create groups and manage rules</CardDescription>
        </div>
        {message ? (
          <div
            className={
              message.kind === "error"
                ? "flex max-w-full items-center gap-1 rounded-md border border-[color:var(--danger)]/30 bg-[color:var(--danger)]/10 px-2 py-1 text-xs text-[color:var(--danger)]"
                : "flex max-w-full items-center gap-1 rounded-md border border-[color:var(--success)]/30 bg-[color:var(--success)]/10 px-2 py-1 text-xs text-[color:var(--success)]"
            }
          >
            {message.kind === "error" ? <AlertCircle size={13} strokeWidth={1.7} /> : <RefreshCw size={13} strokeWidth={1.7} />}
            <span className="truncate">{message.text}</span>
          </div>
        ) : null}
      </CardHeader>
      <CardContent className="grid gap-4 xl:grid-cols-[minmax(260px,0.75fr)_minmax(0,1.65fr)]">
        <div className="space-y-3">
          <div className="grid gap-2 sm:grid-cols-[1fr_1.2fr_auto] lg:grid-cols-1 xl:grid-cols-[1fr_1.2fr_auto]">
            <Input
              aria-label="Security group name"
              placeholder="name"
              value={createForm.name}
              onChange={(event) => setCreateForm((current) => ({ ...current, name: event.target.value }))}
            />
            <Input
              aria-label="Security group description"
              placeholder="description"
              value={createForm.description}
              onChange={(event) => setCreateForm((current) => ({ ...current, description: event.target.value }))}
            />
            <Button type="button" size="sm" variant="primary" disabled={!canCreate || busyAction === "create"} onClick={handleCreate}>
              {busyAction === "create" ? <Loader2 size={13} strokeWidth={1.7} className="animate-spin" /> : <Plus size={13} strokeWidth={1.7} />}
              Create
            </Button>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Select
              aria-label="Security group"
              className="min-w-[220px] flex-1"
              value={selectedGroup?.id ?? ""}
              onChange={(event) => setRuleForm((current) => ({ ...current, groupId: event.target.value }))}
            >
              {securityGroups.length === 0 ? <option value="">No groups</option> : null}
              {securityGroups.map((group) => (
                <option key={group.id} value={group.id}>
                  {group.name}
                </option>
              ))}
            </Select>
            {selectedGroup ? (
              <Button type="button" size="sm" variant="danger" disabled={busyAction === "delete"} onClick={handleDeleteGroup}>
                {busyAction === "delete" ? <Loader2 size={13} strokeWidth={1.7} className="animate-spin" /> : <Trash2 size={13} strokeWidth={1.7} />}
                Delete
              </Button>
            ) : null}
          </div>
        </div>

        <div className="space-y-3">
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-[minmax(0,0.7fr)_minmax(0,0.7fr)_minmax(0,1fr)_minmax(0,0.7fr)_minmax(0,0.7fr)_auto]">
            <Select
              aria-label="Rule direction"
              className="min-w-0"
              value={ruleForm.direction}
              onChange={(event) => setRuleForm((current) => ({ ...current, direction: event.target.value as RuleDirection }))}
            >
              <option value="ingress">Ingress</option>
              <option value="egress">Egress</option>
            </Select>
            <Select
              aria-label="Rule protocol"
              className="min-w-0"
              value={ruleForm.protocol}
              onChange={(event) => setRuleForm((current) => ({ ...current, protocol: event.target.value as Protocol }))}
            >
              <option value="tcp">TCP</option>
              <option value="udp">UDP</option>
              <option value="icmp">ICMP</option>
            </Select>
            <Input
              aria-label="Rule CIDR"
              placeholder="cidr"
              value={ruleForm.cidrList}
              onChange={(event) => setRuleForm((current) => ({ ...current, cidrList: event.target.value }))}
            />
            {ruleForm.protocol === "icmp" ? (
              <>
                <Input
                  aria-label="ICMP type"
                  placeholder="type"
                  value={ruleForm.icmpType}
                  onChange={(event) => setRuleForm((current) => ({ ...current, icmpType: event.target.value }))}
                />
                <Input
                  aria-label="ICMP code"
                  placeholder="code"
                  value={ruleForm.icmpCode}
                  onChange={(event) => setRuleForm((current) => ({ ...current, icmpCode: event.target.value }))}
                />
              </>
            ) : (
              <>
                <Input
                  aria-label="Start port"
                  placeholder="start"
                  value={ruleForm.startPort}
                  onChange={(event) => setRuleForm((current) => ({ ...current, startPort: event.target.value }))}
                />
                <Input
                  aria-label="End port"
                  placeholder="end"
                  value={ruleForm.endPort}
                  onChange={(event) => setRuleForm((current) => ({ ...current, endPort: event.target.value }))}
                />
              </>
            )}
            <Button type="button" size="sm" variant="primary" disabled={!canSubmitRule || busyAction === "rule"} onClick={handleAuthorizeRule}>
              {busyAction === "rule" ? <Loader2 size={13} strokeWidth={1.7} className="animate-spin" /> : <Plus size={13} strokeWidth={1.7} />}
              Add
            </Button>
          </div>

          {selectedGroup ? (
            <div className="grid gap-2 md:grid-cols-2">
              <RuleList
                title="Ingress"
                direction="ingress"
                rules={selectedGroup.ingressRules}
                busyAction={busyAction}
                onRevoke={handleRevokeRule}
              />
              <RuleList
                title="Egress"
                direction="egress"
                rules={selectedGroup.egressRules}
                busyAction={busyAction}
                onRevoke={handleRevokeRule}
              />
            </div>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

function RuleList({
  title,
  direction,
  rules,
  busyAction,
  onRevoke,
}: {
  title: string;
  direction: RuleDirection;
  rules: SecurityGroupRule[];
  busyAction: BusyAction | null;
  onRevoke(direction: RuleDirection, rule: SecurityGroupRule): void;
}) {
  return (
    <div className="rounded-md border border-[color:var(--border)] bg-[color:var(--surface-2)] p-2">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-[color:var(--fg-muted)]">{title}</span>
        <Badge>{rules.length}</Badge>
      </div>
      <div className="max-h-28 space-y-1 overflow-y-auto">
        {rules.length === 0 ? <div className="text-xs text-[color:var(--fg-muted)]">No rules</div> : null}
        {rules.map((rule) => {
          const key = `${direction}:${rule.id}` as const;
          return (
            <div key={rule.id} className="flex min-w-0 items-center gap-2 rounded-md bg-[color:var(--surface)] px-2 py-1 text-xs">
              <span className="font-medium">{rule.protocol}</span>
              <span className="truncate font-mono">{rule.range}</span>
              <span className="truncate font-mono text-[color:var(--fg-muted)]">{rule.source}</span>
              <Button
                type="button"
                aria-label={`Revoke ${title.toLowerCase()} rule ${rule.id}`}
                size="icon"
                variant="ghost"
                className="ml-auto h-6 w-6 shrink-0"
                disabled={busyAction === key}
                onClick={() => onRevoke(direction, rule)}
              >
                {busyAction === key ? <Loader2 size={12} strokeWidth={1.7} className="animate-spin" /> : <X size={12} strokeWidth={1.7} />}
              </Button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function readError(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
