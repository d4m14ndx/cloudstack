"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import {
  AlertCircle,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Cloud,
  Cpu,
  Database,
  Globe,
  IconNetworks as Network,
  IconSecurity as ShieldCheck,
  IconSshKeys as Key,
  Layers,
  Loader2,
} from "@/components/icons";
import {
  deployVirtualMachineFromWizard,
  getDeployWizardCatalogFromBff,
  queryDeployWizardJobResult,
  type DeployWizardJobResult,
} from "@/lib/cloudstack/deploy-wizard";
import type {
  DeployWizardCatalog,
  AffinityGroup,
  DiskOffering,
  Network as DeployNetwork,
  Project,
  SecurityGroup,
  ServiceOffering,
  SshKeyPair,
  Template,
  Zone,
} from "@/lib/mock-data";
import { cn } from "@/lib/utils";

const OPEN_DEPLOY_WIZARD_EVENT = "cloudstack:open-deploy-wizard";

const STEPS = [
  { key: "zone", label: "Zone & template", icon: Globe },
  { key: "size", label: "Size", icon: Cpu },
  { key: "network", label: "Network", icon: Network },
  { key: "storage", label: "Storage", icon: Database },
  { key: "access", label: "Access", icon: Key },
  { key: "review", label: "Review", icon: Check },
] as const;

type DeployWizardProps = {
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
};

type WizardForm = {
  name: string;
  displayName: string;
  projectId: string;
  zoneId: string;
  templateId: string;
  serviceOfferingId: string;
  networkId: string;
  staticIpAddress: string;
  diskOfferingId: string;
  diskOfferingSizeGiB: string;
  sshKeyPairId: string;
  securityGroupId: string;
  affinityGroupId: string;
  userData: string;
  startVm: boolean;
};

type LaunchState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "polling"; jobId: string; progress?: number }
  | {
      status: "success";
      jobId: string;
      virtualMachineId?: string;
      virtualMachineName?: string;
      virtualMachineState?: string;
    }
  | { status: "failed"; error: string; jobId?: string };

const initialForm: WizardForm = {
  name: "",
  displayName: "",
  projectId: "",
  zoneId: "",
  templateId: "",
  serviceOfferingId: "",
  networkId: "",
  staticIpAddress: "",
  diskOfferingId: "",
  diskOfferingSizeGiB: "",
  sshKeyPairId: "",
  securityGroupId: "",
  affinityGroupId: "",
  userData: "#cloud-config\npackage_update: true",
  startVm: true,
};

export function DeployWizard({ open, onOpenChange }: DeployWizardProps) {
  const [internalOpen, setInternalOpen] = useState(false);
  const actualOpen = open ?? internalOpen;
  const setActualOpen = onOpenChange ?? setInternalOpen;
  const [step, setStep] = useState(0);
  const [catalog, setCatalog] = useState<DeployWizardCatalog | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form, setForm] = useState<WizardForm>(initialForm);
  const [launchState, setLaunchState] = useState<LaunchState>({ status: "idle" });
  const nameFieldRef = useRef<HTMLInputElement>(null);
  const hasFocusedInitialControl = useRef(false);

  useEffect(() => {
    const openWizard = () => setActualOpen(true);
    window.addEventListener(OPEN_DEPLOY_WIZARD_EVENT, openWizard);
    return () => window.removeEventListener(OPEN_DEPLOY_WIZARD_EVENT, openWizard);
  }, [setActualOpen]);

  useEffect(() => {
    if (!actualOpen) {
      hasFocusedInitialControl.current = false;
      return;
    }

    let cancelled = false;
    setStep(0);
    setLoading(true);
    setLoadError(null);
    setLaunchState({ status: "idle" });

    getDeployWizardCatalogFromBff()
      .then((nextCatalog) => {
        if (cancelled) {
          return;
        }

        setCatalog(nextCatalog);
        setForm((current) => applyCatalogDefaults(current, nextCatalog));
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }

        setLoadError(error instanceof Error ? error.message : "Catalog unavailable");
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [actualOpen]);

  useEffect(() => {
    if (!actualOpen || loading || !catalog || step !== 0 || hasFocusedInitialControl.current) {
      return;
    }

    const animationFrame = window.requestAnimationFrame(() => {
      nameFieldRef.current?.focus();
      hasFocusedInitialControl.current = true;
    });

    return () => window.cancelAnimationFrame(animationFrame);
  }, [actualOpen, catalog, loading, step]);

  const selections = useMemo(() => resolveSelections(form, catalog), [catalog, form]);
  const canContinue = canContinueFromStep(step, form, selections);
  const canLaunch = Boolean(
    catalog && canLaunchForm(form, selections) && !isLaunchBusy(launchState) && launchState.status !== "success",
  );

  async function handleLaunch() {
    if (!catalog || !canLaunch) {
      return;
    }

    setLaunchState({ status: "submitting" });
    try {
      const launch = await deployVirtualMachineFromWizard({
        name: form.name,
        displayName: form.displayName,
        zoneId: form.zoneId,
        templateId: form.templateId,
        serviceOfferingId: form.serviceOfferingId,
        networkId: form.networkId || undefined,
        projectId: form.projectId || undefined,
        affinityGroupIds: form.affinityGroupId ? [form.affinityGroupId] : undefined,
        ipAddress: form.networkId ? form.staticIpAddress || undefined : undefined,
        diskOfferingId: form.diskOfferingId || undefined,
        diskOfferingCustomized: selections.diskOffering?.customized,
        ...(selections.diskOffering?.customized
          ? { diskOfferingSizeGiB: parsePositiveInteger(form.diskOfferingSizeGiB) ?? undefined }
          : {}),
        securityGroupId: form.networkId ? undefined : form.securityGroupId || undefined,
        sshKeyPairName: selections.sshKeyPair?.name,
        userData: form.userData,
        startVm: form.startVm,
      });
      setLaunchState({ status: "polling", jobId: launch.jobId });

      const job = await waitForDeployJob(launch.jobId, (pending) => {
        setLaunchState({
          status: "polling",
          jobId: pending.jobId,
          ...(pending.progress !== undefined ? { progress: pending.progress } : {}),
        });
      });

      if (job.status === "success") {
        setLaunchState({
          status: "success",
          jobId: job.jobId,
          virtualMachineId: job.virtualMachineId ?? launch.virtualMachineId,
          virtualMachineName: job.virtualMachineName,
          virtualMachineState: job.virtualMachineState,
        });
      } else {
        setLaunchState({
          status: "failed",
          jobId: job.jobId,
          error: job.errorText ?? "CloudStack deployment job failed",
        });
      }
    } catch (error) {
      setLaunchState({
        status: "failed",
        error: error instanceof Error ? error.message : "CloudStack deployment request failed",
      });
    }
  }

  return (
    <Dialog open={actualOpen} onOpenChange={setActualOpen}>
      <DialogContent className="flex max-h-[88vh] max-w-[1060px] flex-col overflow-hidden p-0">
        <DialogHeader className="mb-0 border-b border-[color:var(--border)] px-5 py-4">
          <div className="flex items-start justify-between gap-4 pr-8">
            <div>
              <DialogTitle>Deploy instance</DialogTitle>
              <DialogDescription>Catalog-backed preview</DialogDescription>
            </div>
            <LaunchBadge state={launchState} />
          </div>
        </DialogHeader>

        <div className="grid min-h-0 flex-1 grid-cols-[220px_1fr]">
          <aside className="border-r border-[color:var(--border)] bg-[color:var(--surface)] p-3">
            <div className="space-y-1">
              {STEPS.map((item, index) => {
                const Icon = item.icon;
                return (
                  <button
                    key={item.key}
                    type="button"
                    onClick={() => setStep(index)}
                    className={cn(
                      "flex h-10 w-full items-center gap-2 rounded-md px-2 text-left text-sm transition-colors",
                      index === step
                        ? "bg-[color:var(--accent-soft)] text-[color:var(--accent)]"
                        : "text-[color:var(--fg-muted)] hover:bg-[color:var(--surface-2)] hover:text-[color:var(--fg)]",
                    )}
                  >
                    <span className="flex h-6 w-6 items-center justify-center rounded-md border border-[color:var(--border)] bg-[color:var(--bg)]">
                      <Icon size={13} strokeWidth={1.7} />
                    </span>
                    <span className="truncate">{item.label}</span>
                  </button>
                );
              })}
            </div>
          </aside>

          <div className="min-h-0 overflow-y-auto p-5">
            {loading ? (
              <CatalogLoading />
            ) : loadError ? (
              <CatalogError message={loadError} />
            ) : catalog ? (
              <StepContent
                step={step}
                catalog={catalog}
                form={form}
                selections={selections}
                launchState={launchState}
                onFormChange={setForm}
                nameFieldRef={nameFieldRef}
              />
            ) : null}
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-[color:var(--border)] px-5 py-3">
          <div className="flex items-center gap-2 text-xs text-[color:var(--fg-muted)]">
            <Badge>Catalog preview</Badge>
            <span>{STEPS[step]?.label}</span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={step === 0 || isLaunchBusy(launchState)}
              onClick={() => setStep((value) => Math.max(0, value - 1))}
            >
              <ChevronLeft size={13} strokeWidth={1.7} />
              Back
            </Button>
            {step < STEPS.length - 1 ? (
              <Button
                variant="primary"
                size="sm"
                disabled={!catalog || !canContinue}
                onClick={() => setStep((value) => Math.min(STEPS.length - 1, value + 1))}
              >
                Continue
                <ChevronRight size={13} strokeWidth={1.7} />
              </Button>
            ) : (
              <Button variant="primary" size="sm" disabled={!canLaunch} onClick={handleLaunch}>
                {isLaunchBusy(launchState) ? (
                  <Loader2 size={13} strokeWidth={1.7} className="animate-spin" />
                ) : (
                  <Cloud size={13} strokeWidth={1.7} />
                )}
                Launch instance
              </Button>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function openDeployWizard(): void {
  window.dispatchEvent(new CustomEvent(OPEN_DEPLOY_WIZARD_EVENT));
}

function StepContent({
  step,
  catalog,
  form,
  selections,
  launchState,
  onFormChange,
  nameFieldRef,
}: {
  step: number;
  catalog: DeployWizardCatalog;
  form: WizardForm;
  selections: ResolvedSelections;
  launchState: LaunchState;
  onFormChange: React.Dispatch<React.SetStateAction<WizardForm>>;
  nameFieldRef: React.Ref<HTMLInputElement>;
}) {
  if (step === 0) {
    return (
      <div className="space-y-4">
        <FieldGrid>
          <TextField
            label="Name"
            value={form.name}
            onChange={(name) => onFormChange((current) => ({ ...current, name }))}
            inputRef={nameFieldRef}
          />
          <TextField label="Display name" value={form.displayName} onChange={(displayName) => onFormChange((current) => ({ ...current, displayName }))} />
        </FieldGrid>
        {catalog.projects.length > 0 ? (
          <Card>
            <CardHeader>
              <div>
                <CardTitle>Project</CardTitle>
                <CardDescription>Optional ownership scope</CardDescription>
              </div>
            </CardHeader>
            <CardContent>
              <Select
                aria-label="Project"
                className="w-full"
                value={form.projectId}
                onChange={(event) => onFormChange((current) => ({ ...current, projectId: event.target.value }))}
              >
                <option value="">No project</option>
                {catalog.projects.map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name}
                  </option>
                ))}
              </Select>
            </CardContent>
          </Card>
        ) : null}
        <OptionGrid
          title="Zone"
          options={catalog.zones}
          selectedId={form.zoneId}
          onSelect={(zoneId) => onFormChange((current) => ({ ...current, zoneId }))}
          render={(zone) => <ZoneOption zone={zone} />}
        />
        <OptionGrid
          title="Template"
          options={catalog.templates}
          selectedId={form.templateId}
          onSelect={(templateId) => onFormChange((current) => ({ ...current, templateId }))}
          render={(template) => <TemplateOption template={template} />}
        />
      </div>
    );
  }

  if (step === 1) {
    return (
      <OptionGrid
        title="Service offering"
        options={catalog.serviceOfferings}
        selectedId={form.serviceOfferingId}
        onSelect={(serviceOfferingId) => onFormChange((current) => ({ ...current, serviceOfferingId }))}
        render={(offering) => <ServiceOfferingOption offering={offering} />}
      />
    );
  }

  if (step === 2) {
    return (
      <div className="space-y-4">
        <OptionGrid
          title="Network"
          options={catalog.networks}
          selectedId={form.networkId}
          onSelect={(networkId) => onFormChange((current) => ({ ...current, networkId }))}
          render={(network) => <NetworkOption network={network} />}
        />
        {form.networkId ? (
          <div className="max-w-[260px]">
            <TextField
              label="Static IP"
              value={form.staticIpAddress}
              onChange={(staticIpAddress) => onFormChange((current) => ({ ...current, staticIpAddress }))}
            />
          </div>
        ) : (
          <OptionGrid
            title="Security group"
            options={catalog.securityGroups}
            selectedId={form.securityGroupId}
            onSelect={(securityGroupId) => onFormChange((current) => ({ ...current, securityGroupId }))}
            render={(securityGroup) => <SecurityGroupOption securityGroup={securityGroup} />}
          />
        )}
      </div>
    );
  }

  if (step === 3) {
    return (
      <div className="space-y-4">
        <OptionGrid
          title="Disk offering"
          options={catalog.diskOfferings}
          selectedId={form.diskOfferingId}
          onSelect={(diskOfferingId) => onFormChange((current) => ({ ...current, diskOfferingId }))}
          render={(offering) => <DiskOfferingOption offering={offering} />}
        />
        {selections.diskOffering?.customized ? (
          <div className="max-w-[220px]">
            <label className="block text-sm font-medium text-[color:var(--fg)]">
              Size
              <div className="mt-2 flex items-center gap-2">
                <Input
                  type="number"
                  min={1}
                  step={1}
                  inputMode="numeric"
                  value={form.diskOfferingSizeGiB}
                  onChange={(event) =>
                    onFormChange((current) => ({ ...current, diskOfferingSizeGiB: event.target.value }))
                  }
                />
                <span className="text-xs font-medium text-[color:var(--fg-muted)]">GiB</span>
              </div>
            </label>
          </div>
        ) : null}
      </div>
    );
  }

  if (step === 4) {
    return (
      <div className="space-y-4">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>SSH key</CardTitle>
              <CardDescription>Optional access key</CardDescription>
            </div>
          </CardHeader>
          <CardContent>
            <Select
              aria-label="SSH key"
              className="w-full"
              value={form.sshKeyPairId}
              onChange={(event) => onFormChange((current) => ({ ...current, sshKeyPairId: event.target.value }))}
            >
              <option value="">No SSH key</option>
              {catalog.sshKeyPairs.map((keyPair) => (
                <option key={keyPair.id} value={keyPair.id}>
                  {keyPair.name}
                </option>
              ))}
            </Select>
          </CardContent>
        </Card>
        {catalog.affinityGroups.length > 0 ? (
          <Card>
            <CardHeader>
              <div>
                <CardTitle>Affinity group</CardTitle>
                <CardDescription>Optional placement preference</CardDescription>
              </div>
            </CardHeader>
            <CardContent>
              <Select
                aria-label="Affinity group"
                className="w-full"
                value={form.affinityGroupId}
                onChange={(event) => onFormChange((current) => ({ ...current, affinityGroupId: event.target.value }))}
              >
                <option value="">No affinity group</option>
                {catalog.affinityGroups.map((group) => (
                  <option key={group.id} value={group.id}>
                    {group.name} / {group.type}
                  </option>
                ))}
              </Select>
            </CardContent>
          </Card>
        ) : null}
        <Card>
          <CardHeader>
            <div>
              <CardTitle>Start after deploy</CardTitle>
              <CardDescription>{form.startVm ? "Instance will boot after creation" : "Instance will remain stopped"}</CardDescription>
            </div>
            <Switch
              aria-label="Start after deploy"
              checked={form.startVm}
              onCheckedChange={(startVm) => onFormChange((current) => ({ ...current, startVm }))}
            />
          </CardHeader>
        </Card>
        <label className="block text-sm font-medium text-[color:var(--fg)]">
          User data
          <textarea
            value={form.userData}
            onChange={(event) => onFormChange((current) => ({ ...current, userData: event.target.value }))}
            className="mt-2 min-h-[180px] w-full rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 py-2 font-mono text-xs text-[color:var(--fg)] outline-none transition-colors focus:border-[color:var(--accent)] focus:ring-[3px] focus:ring-[color:var(--accent)]/30"
          />
        </label>
      </div>
    );
  }

  return <Review selections={selections} form={form} launchState={launchState} />;
}

function OptionGrid<T extends { id: string }>({
  title,
  options,
  selectedId,
  onSelect,
  render,
}: {
  title: string;
  options: T[];
  selectedId: string;
  onSelect: (id: string) => void;
  render: (option: T) => React.ReactNode;
}) {
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-[color:var(--fg)]">{title}</h3>
        <Badge>{options.length}</Badge>
      </div>
      <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            onClick={() => onSelect(option.id)}
            aria-pressed={selectedId === option.id}
            className={cn(
              "rounded-lg border bg-[color:var(--surface)] p-3 text-left transition-colors",
              selectedId === option.id
                ? "border-[color:var(--accent)] ring-2 ring-[color:var(--accent)]/20"
                : "border-[color:var(--border)] hover:border-[color:var(--border-strong)]",
            )}
          >
            {render(option)}
          </button>
        ))}
      </div>
    </section>
  );
}

function ZoneOption({ zone }: { zone: Zone }) {
  return (
    <OptionShell icon={<Globe size={15} strokeWidth={1.7} />} title={zone.name} meta={zone.region}>
      <span>{zone.hosts} hosts</span>
      <span>{zone.instances} VMs</span>
      <Badge variant={zone.state === "enabled" ? "success" : "warning"}>{zone.state}</Badge>
    </OptionShell>
  );
}

function TemplateOption({ template }: { template: Template }) {
  return (
    <OptionShell icon={<Cloud size={15} strokeWidth={1.7} />} title={template.name} meta={`${template.os} / ${template.arch}`}>
      <span>{template.size}</span>
      <span>{template.account}</span>
    </OptionShell>
  );
}

function ServiceOfferingOption({ offering }: { offering: ServiceOffering }) {
  return (
    <OptionShell icon={<Cpu size={15} strokeWidth={1.7} />} title={offering.name} meta={offering.description}>
      <span>{offering.cpu} vCPU</span>
      <span>{offering.ram} GiB RAM</span>
    </OptionShell>
  );
}

function NetworkOption({ network }: { network: DeployNetwork }) {
  return (
    <OptionShell icon={<Layers size={15} strokeWidth={1.7} />} title={network.name} meta={`${network.type} / ${network.zone}`}>
      <span>{network.cidr}</span>
      <span>{network.instances} VMs</span>
    </OptionShell>
  );
}

function SecurityGroupOption({ securityGroup }: { securityGroup: SecurityGroup }) {
  return (
    <OptionShell icon={<ShieldCheck size={15} strokeWidth={1.7} />} title={securityGroup.name} meta={securityGroup.description}>
      <span>{securityGroup.ingressRules.length} ingress</span>
      <span>{securityGroup.egressRules.length} egress</span>
    </OptionShell>
  );
}

function DiskOfferingOption({ offering }: { offering: DiskOffering }) {
  return (
    <OptionShell icon={<Database size={15} strokeWidth={1.7} />} title={offering.name} meta={offering.type}>
      <span>{offering.customized ? "Custom size" : `${offering.sizeGiB ?? 0} GiB`}</span>
    </OptionShell>
  );
}

function OptionShell({
  icon,
  title,
  meta,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  meta: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-[color:var(--border)] bg-[color:var(--bg)] text-[color:var(--fg-muted)]">
          {icon}
        </span>
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-[color:var(--fg)]">{title}</div>
          <div className="truncate text-xs text-[color:var(--fg-muted)]">{meta}</div>
        </div>
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-[color:var(--fg-muted)]">{children}</div>
    </div>
  );
}

function Review({
  selections,
  form,
  launchState,
}: {
  selections: ResolvedSelections;
  form: WizardForm;
  launchState: LaunchState;
}) {
  const rows = [
    ["Name", form.name],
    ["Display name", form.displayName || form.name],
    ["Project", selections.project?.name ?? "None"],
    ["Zone", selections.zone?.name ?? "-"],
    ["Template", selections.template?.name ?? "-"],
    ["Service offering", selections.serviceOffering?.name ?? "-"],
    ["Network", selections.network?.name ?? "-"],
    ["Static IP", form.networkId && form.staticIpAddress ? form.staticIpAddress : "None"],
    ["Security group", selections.securityGroup?.name ?? "-"],
    ["Disk offering", selections.diskOffering?.name ?? "-"],
    ["Disk size", readDiskSizeLabel(selections.diskOffering, form.diskOfferingSizeGiB)],
    ["SSH key", selections.sshKeyPair?.name ?? "None"],
    ["Affinity group", selections.affinityGroup?.name ?? "None"],
    ["Start after deploy", form.startVm ? "Yes" : "No"],
  ];

  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Review</CardTitle>
          <CardDescription>Ready to submit through the CloudStack BFF</CardDescription>
        </div>
        <LaunchBadge state={launchState} />
      </CardHeader>
      <CardContent>
        <Table>
          <TableBody>
            {rows.map(([label, value]) => (
              <TableRow key={label}>
                <TableCell className="w-44 text-xs uppercase tracking-wider text-[color:var(--fg-dim)]">{label}</TableCell>
                <TableCell className="font-mono text-xs">{value}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <LaunchStatus state={launchState} />
      </CardContent>
    </Card>
  );
}

function LaunchBadge({ state }: { state: LaunchState }) {
  if (state.status === "success") {
    return <Badge variant="success">Launched</Badge>;
  }

  if (state.status === "failed") {
    return <Badge variant="danger">Failed</Badge>;
  }

  if (isLaunchBusy(state)) {
    return <Badge variant="warning">Launching</Badge>;
  }

  return <Badge variant="success">Launch ready</Badge>;
}

function LaunchStatus({ state }: { state: LaunchState }) {
  if (state.status === "idle") {
    return null;
  }

  if (state.status === "submitting") {
    return (
      <StatusLine icon={<Loader2 size={14} strokeWidth={1.7} className="animate-spin" />} tone="muted">
        Submitting deployment
      </StatusLine>
    );
  }

  if (state.status === "polling") {
    return (
      <StatusLine icon={<Loader2 size={14} strokeWidth={1.7} className="animate-spin" />} tone="muted">
        Job {state.jobId}
        {state.progress !== undefined ? ` / ${state.progress}%` : ""}
      </StatusLine>
    );
  }

  if (state.status === "success") {
    return (
      <StatusLine icon={<CheckCircle2 size={14} strokeWidth={1.7} />} tone="success">
        {state.virtualMachineName ?? state.virtualMachineId ?? "Instance"} {state.virtualMachineState ?? "deployed"}
      </StatusLine>
    );
  }

  return (
    <StatusLine icon={<AlertCircle size={14} strokeWidth={1.7} />} tone="danger">
      {state.error}
    </StatusLine>
  );
}

function StatusLine({
  icon,
  tone,
  children,
}: {
  icon: React.ReactNode;
  tone: "muted" | "success" | "danger";
  children: React.ReactNode;
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        "mt-4 flex min-h-10 items-center gap-2 rounded-md border px-3 py-2 text-sm",
        tone === "muted" && "border-[color:var(--border)] bg-[color:var(--surface)] text-[color:var(--fg-muted)]",
        tone === "success" && "border-[color:var(--success)] bg-[color:var(--success-bg)] text-[color:var(--success)]",
        tone === "danger" && "border-[color:var(--danger)] bg-[color:var(--danger-bg)] text-[color:var(--danger)]",
      )}
    >
      {icon}
      <span className="min-w-0 break-words">{children}</span>
    </div>
  );
}

function FieldGrid({ children }: { children: React.ReactNode }) {
  return <div className="grid gap-3 md:grid-cols-2">{children}</div>;
}

function TextField({
  label,
  value,
  onChange,
  inputRef,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  inputRef?: React.Ref<HTMLInputElement>;
}) {
  return (
    <label className="block text-sm font-medium text-[color:var(--fg)]">
      {label}
      <Input ref={inputRef} className="mt-2 w-full" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  );
}

function CatalogLoading() {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      className="flex h-[360px] items-center justify-center text-sm text-[color:var(--fg-muted)]"
    >
      <Loader2 size={16} strokeWidth={1.7} className="mr-2 animate-spin" />
      Loading catalog
    </div>
  );
}

function CatalogError({ message }: { message: string }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Catalog unavailable</CardTitle>
        <CardDescription>{message}</CardDescription>
      </CardHeader>
    </Card>
  );
}

type ResolvedSelections = {
  project?: Project;
  zone?: Zone;
  template?: Template;
  serviceOffering?: ServiceOffering;
  network?: DeployNetwork;
  diskOffering?: DiskOffering;
  securityGroup?: SecurityGroup;
  sshKeyPair?: SshKeyPair;
  affinityGroup?: AffinityGroup;
};

function resolveSelections(form: WizardForm, catalog: DeployWizardCatalog | null): ResolvedSelections {
  return {
    project: catalog?.projects.find((project) => project.id === form.projectId),
    zone: catalog?.zones.find((zone) => zone.id === form.zoneId),
    template: catalog?.templates.find((template) => template.id === form.templateId),
    serviceOffering: catalog?.serviceOfferings.find((offering) => offering.id === form.serviceOfferingId),
    network: catalog?.networks.find((network) => network.id === form.networkId),
    diskOffering: catalog?.diskOfferings.find((offering) => offering.id === form.diskOfferingId),
    securityGroup: catalog?.securityGroups.find((group) => group.id === form.securityGroupId),
    sshKeyPair: catalog?.sshKeyPairs.find((keyPair) => keyPair.id === form.sshKeyPairId),
    affinityGroup: catalog?.affinityGroups.find((group) => group.id === form.affinityGroupId),
  };
}

function applyCatalogDefaults(form: WizardForm, catalog: DeployWizardCatalog): WizardForm {
  const generatedName = form.name || `vm-${catalog.zones[0]?.name ?? "zone"}-preview`;

  return {
    ...form,
    name: generatedName,
    displayName: form.displayName || generatedName,
    zoneId: form.zoneId || catalog.zones.find((zone) => zone.state === "enabled")?.id || catalog.zones[0]?.id || "",
    templateId: form.templateId || catalog.templates[0]?.id || "",
    serviceOfferingId: form.serviceOfferingId || catalog.serviceOfferings[0]?.id || "",
    networkId: form.networkId || catalog.networks[0]?.id || "",
    diskOfferingId: form.diskOfferingId || catalog.diskOfferings[0]?.id || "",
    securityGroupId: form.securityGroupId || catalog.securityGroups.find((group) => group.isDefault)?.id || catalog.securityGroups[0]?.id || "",
    sshKeyPairId: form.sshKeyPairId || catalog.sshKeyPairs[0]?.id || "",
  };
}

function canContinueFromStep(step: number, form: WizardForm, selections: ResolvedSelections): boolean {
  switch (step) {
    case 0:
      return Boolean(form.name && form.zoneId && form.templateId);
    case 1:
      return Boolean(form.serviceOfferingId);
    case 2:
      return Boolean(form.networkId);
    case 3:
      return Boolean(form.diskOfferingId && isDiskSizeValid(form, selections));
    default:
      return true;
  }
}

function canLaunchForm(form: WizardForm, selections: ResolvedSelections): boolean {
  return Boolean(form.name && form.zoneId && form.templateId && form.serviceOfferingId && isDiskSizeValid(form, selections));
}

function isLaunchBusy(state: LaunchState): boolean {
  return state.status === "submitting" || state.status === "polling";
}

async function waitForDeployJob(
  jobId: string,
  onPending: (pending: DeployWizardJobResult) => void,
): Promise<DeployWizardJobResult> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const result = await queryDeployWizardJobResult(jobId);
    if (result.status !== "pending") {
      return result;
    }

    onPending(result);
    await delay(Math.min(1_000 + attempt * 250, 3_000));
  }

  throw new Error("CloudStack deployment job did not complete before the polling timeout");
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function isDiskSizeValid(form: WizardForm, selections: ResolvedSelections): boolean {
  return !selections.diskOffering?.customized || parsePositiveInteger(form.diskOfferingSizeGiB) !== null;
}

function parsePositiveInteger(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }

  const parsed = Number.parseInt(trimmed, 10);
  return parsed > 0 ? parsed : null;
}

function readDiskSizeLabel(offering: DiskOffering | undefined, customSizeGiB: string): string {
  if (!offering) {
    return "-";
  }

  if (offering.customized) {
    return parsePositiveInteger(customSizeGiB) === null ? "-" : `${customSizeGiB.trim()} GiB`;
  }

  return offering.sizeGiB === null ? "-" : `${offering.sizeGiB} GiB`;
}
