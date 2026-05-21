"use client";

import { useMemo, useState } from "react";
import { Play, RotateCw, Square, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import type { Instance } from "@/lib/mock-data";
import {
  destroyVirtualMachine,
  queryInstanceActionJobResult,
  rebootVirtualMachine,
  startVirtualMachine,
  stopVirtualMachine,
  type InstanceActionJobResult,
} from "@/lib/cloudstack/instance-actions";

type ActionKind = "start" | "stop" | "reboot" | "destroy";

type ActionStatus =
  | { state: "idle" }
  | { state: "submitting"; action: ActionKind }
  | { state: "polling"; action: ActionKind; jobId: string; progress?: number }
  | { state: "success"; action: ActionKind; jobId: string; vmState?: string }
  | { state: "failed"; action: ActionKind; error: string };

type InstanceActionsProps = {
  id: string;
  name: string;
  state: Instance["state"];
};

export function InstanceActions({ id, name, state }: InstanceActionsProps) {
  const router = useRouter();
  const [status, setStatus] = useState<ActionStatus>({ state: "idle" });
  const availableActions = useMemo(() => actionsForState(state), [state]);
  const busy = status.state === "submitting" || status.state === "polling";

  async function runAction(action: ActionKind) {
    if (busy) {
      return;
    }

    if (action === "destroy" && !window.confirm(`Destroy ${name}?`)) {
      return;
    }

    setStatus({ state: "submitting", action });
    try {
      const launch = await invokeAction(action, id);
      setStatus({ state: "polling", action, jobId: launch.jobId });
      const job = await waitForInstanceActionJob(launch.jobId, (pending) => {
        setStatus({
          state: "polling",
          action,
          jobId: pending.jobId,
          ...(pending.progress !== undefined ? { progress: pending.progress } : {}),
        });
      });

      if (job.status === "success") {
        setStatus({
          state: "success",
          action,
          jobId: job.jobId,
          vmState: job.virtualMachineState,
        });
        router.refresh();
        return;
      }

      setStatus({
        state: "failed",
        action,
        error: job.errorText ?? "CloudStack job failed",
      });
    } catch (error) {
      setStatus({
        state: "failed",
        action,
        error: error instanceof Error ? error.message : "CloudStack request failed",
      });
    }
  }

  return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      {availableActions.map((action) => (
        <Button
          key={action}
          type="button"
          size="sm"
          variant={action === "destroy" ? "danger" : "secondary"}
          disabled={busy}
          title={actionLabels[action]}
          aria-label={`${actionLabels[action]} ${name}`}
          onClick={() => void runAction(action)}
        >
          {actionIcons[action]}
          <span>{actionLabels[action]}</span>
        </Button>
      ))}
      {status.state !== "idle" && (
        <span
          aria-live="polite"
          className={status.state === "failed" ? "max-w-[260px] truncate text-xs text-[color:var(--danger)]" : "text-xs text-[color:var(--fg-muted)]"}
          title={statusText(status)}
        >
          {statusText(status)}
        </span>
      )}
    </div>
  );
}

const actionLabels: Record<ActionKind, string> = {
  start: "Start",
  stop: "Stop",
  reboot: "Reboot",
  destroy: "Destroy",
};

const actionIcons: Record<ActionKind, React.ReactNode> = {
  start: <Play className="h-3.5 w-3.5" aria-hidden="true" />,
  stop: <Square className="h-3.5 w-3.5" aria-hidden="true" />,
  reboot: <RotateCw className="h-3.5 w-3.5" aria-hidden="true" />,
  destroy: <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />,
};

function actionsForState(state: Instance["state"]): ActionKind[] {
  switch (state) {
    case "running":
      return ["stop", "reboot", "destroy"];
    case "stopped":
      return ["start", "destroy"];
    case "error":
      return ["destroy"];
    default:
      return [];
  }
}

function statusText(status: ActionStatus): string {
  switch (status.state) {
    case "submitting":
      return `${actionLabels[status.action]} submitting`;
    case "polling":
      return status.progress === undefined
        ? `${actionLabels[status.action]} polling`
        : `${actionLabels[status.action]} ${status.progress}%`;
    case "success":
      return status.vmState ? `${actionLabels[status.action]} complete · ${status.vmState}` : `${actionLabels[status.action]} complete`;
    case "failed":
      return status.error;
    default:
      return "";
  }
}

async function invokeAction(action: ActionKind, id: string): Promise<{ jobId: string }> {
  switch (action) {
    case "start":
      return startVirtualMachine(id);
    case "stop":
      return stopVirtualMachine(id);
    case "reboot":
      return rebootVirtualMachine(id);
    case "destroy":
      return destroyVirtualMachine(id);
  }
}

async function waitForInstanceActionJob(
  jobId: string,
  onPending: (pending: InstanceActionJobResult) => void,
): Promise<InstanceActionJobResult> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const result = await queryInstanceActionJobResult(jobId);
    if (result.status !== "pending") {
      return result;
    }

    onPending(result);
    await delay(Math.min(1_000 + attempt * 250, 3_000));
  }

  throw new Error("CloudStack instance action job did not complete before the polling timeout");
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
