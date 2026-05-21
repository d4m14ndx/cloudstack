"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Plus, Power, PowerOff, Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  associateIpAddress,
  disableStaticNat,
  disassociateIpAddress,
  enableStaticNat,
  queryNetworkActionJobResult,
  type NetworkActionJobResult,
} from "@/lib/cloudstack/network-actions";
import type { NetworkDetail, NetworkPublicIp } from "@/lib/mock-data";

type ActionState =
  | { status: "idle" }
  | { status: "submitting"; label: string }
  | { status: "polling"; jobId: string; progress?: number }
  | { status: "success"; message: string }
  | { status: "failed"; message: string };

type AcquirePublicIpButtonProps = {
  networkId: string;
  networkKind: NetworkDetail["kind"];
};

type PublicIpRowActionsProps = {
  publicIp: NetworkPublicIp;
  networkId: string;
  networkKind: NetworkDetail["kind"];
};

export function AcquirePublicIpButton({ networkId, networkKind }: AcquirePublicIpButtonProps) {
  const router = useRouter();
  const [state, setState] = useState<ActionState>({ status: "idle" });
  const busy = isBusy(state);

  async function handleAcquire() {
    if (busy) {
      return;
    }

    setState({ status: "submitting", label: "Acquiring" });
    try {
      const queued = await associateIpAddress(
        networkKind === "VPC" ? { kind: "VPC", vpcId: networkId } : { kind: "Isolated", networkId },
      );
      setState({ status: "polling", jobId: queued.jobId });
      const job = await waitForNetworkActionJob(queued.jobId, (pending) => {
        setState({
          status: "polling",
          jobId: pending.jobId,
          ...(pending.progress !== undefined ? { progress: pending.progress } : {}),
        });
      });

      if (job.status === "success") {
        setState({ status: "success", message: "Acquired" });
        router.refresh();
      } else if (job.status === "failed") {
        setState({ status: "failed", message: job.errorText ?? "Acquire failed" });
      } else {
        setState({ status: "failed", message: "Acquire did not complete" });
      }
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Acquire failed") });
    }
  }

  return (
    <div className="flex items-center gap-2">
      <Button type="button" variant="secondary" size="sm" onClick={handleAcquire} disabled={busy}>
        {busy ? <Loader2 size={14} strokeWidth={1.8} className="animate-spin" /> : <Plus size={14} strokeWidth={1.8} />}
        Acquire IP
      </Button>
      <ActionStatus state={state} />
    </div>
  );
}

export function PublicIpRowActions({ publicIp, networkId, networkKind }: PublicIpRowActionsProps) {
  const router = useRouter();
  const [state, setState] = useState<ActionState>({ status: "idle" });
  const busy = isBusy(state);
  const canRelease = !publicIp.sourceNat;

  async function handleRelease() {
    if (busy || !canRelease || !window.confirm(`Release public IP ${publicIp.address}?`)) {
      return;
    }

    setState({ status: "submitting", label: "Releasing" });
    try {
      const queued = await disassociateIpAddress(publicIp.id);
      setState({ status: "polling", jobId: queued.jobId });
      const job = await waitForNetworkActionJob(queued.jobId, (pending) => {
        setState({
          status: "polling",
          jobId: pending.jobId,
          ...(pending.progress !== undefined ? { progress: pending.progress } : {}),
        });
      });

      if (job.status === "success") {
        setState({ status: "success", message: "Released" });
        router.refresh();
      } else if (job.status === "failed") {
        setState({ status: "failed", message: job.errorText ?? "Release failed" });
      } else {
        setState({ status: "failed", message: "Release did not complete" });
      }
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Release failed") });
    }
  }

  async function handleEnableStaticNat() {
    if (busy) {
      return;
    }

    const virtualMachineId = window.prompt("Virtual machine ID");
    if (!virtualMachineId?.trim()) {
      return;
    }

    setState({ status: "submitting", label: "Enabling" });
    try {
      await enableStaticNat({
        ipAddressId: publicIp.id,
        virtualMachineId: virtualMachineId.trim(),
        ...(networkKind === "Isolated" ? { networkId } : {}),
      });
      setState({ status: "success", message: "Static NAT enabled" });
      router.refresh();
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Enable failed") });
    }
  }

  async function handleDisableStaticNat() {
    if (busy || !window.confirm(`Disable static NAT for ${publicIp.address}?`)) {
      return;
    }

    setState({ status: "submitting", label: "Disabling" });
    try {
      await disableStaticNat(publicIp.id);
      setState({ status: "success", message: "Static NAT disabled" });
      router.refresh();
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Disable failed") });
    }
  }

  return (
    <div className="flex min-w-[212px] items-center justify-end gap-1.5">
      {publicIp.staticNat ? (
        <Button type="button" variant="ghost" size="icon" className="h-8 w-8" onClick={handleDisableStaticNat} disabled={busy} title="Disable static NAT">
          <PowerOff size={15} strokeWidth={1.8} />
        </Button>
      ) : (
        <Button type="button" variant="ghost" size="icon" className="h-8 w-8" onClick={handleEnableStaticNat} disabled={busy} title="Enable static NAT">
          <Power size={15} strokeWidth={1.8} />
        </Button>
      )}
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-8 w-8 text-[color:var(--danger)] hover:bg-[color:var(--danger)]/10"
        onClick={handleRelease}
        disabled={busy || !canRelease}
        title={canRelease ? "Release IP" : "Source NAT IPs cannot be released here"}
      >
        <Trash2 size={15} strokeWidth={1.8} />
      </Button>
      <ActionStatus state={state} />
    </div>
  );
}

function ActionStatus({ state }: { state: ActionState }) {
  if (state.status === "idle") {
    return null;
  }

  if (state.status === "submitting") {
    return (
      <span aria-live="polite" className="whitespace-nowrap text-xs text-[color:var(--fg-muted)]">
        {state.label}
      </span>
    );
  }

  if (state.status === "polling") {
    return (
      <span aria-live="polite" className="whitespace-nowrap text-xs text-[color:var(--fg-muted)]">
        {state.progress !== undefined ? `Polling ${state.progress}%` : "Polling"}
      </span>
    );
  }

  return (
    <span
      aria-live="polite"
      className={
        state.status === "success"
          ? "whitespace-nowrap text-xs text-[color:var(--success)]"
          : "max-w-[128px] truncate text-xs text-[color:var(--danger)]"
      }
      title={state.message}
    >
      {state.message}
    </span>
  );
}

async function waitForNetworkActionJob(
  jobId: string,
  onPending: (pending: NetworkActionJobResult & { status: "pending" }) => void,
): Promise<NetworkActionJobResult> {
  for (let attempt = 0; attempt < 24; attempt += 1) {
    const result = await queryNetworkActionJobResult(jobId);
    if (result.status !== "pending") {
      return result;
    }

    onPending(result);
    await delay(Math.min(1_000 + attempt * 250, 3_000));
  }

  throw new Error("CloudStack network action job did not complete before the polling timeout");
}

function isBusy(state: ActionState): boolean {
  return state.status === "submitting" || state.status === "polling";
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function readErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
