"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Trash2, Unplug } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  deleteVolumeFromBff,
  detachVolumeFromBff,
  queryVolumeActionJobResult,
  type VolumeActionJobResult,
} from "@/lib/cloudstack/volume-actions";
import type { Volume } from "@/lib/mock-data";

type ActionState =
  | { status: "idle" }
  | { status: "submitting"; action: "detach" | "delete" }
  | { status: "polling"; jobId: string; progress?: number }
  | { status: "success"; message: string }
  | { status: "failed"; message: string };

type VolumeActionsProps = {
  volume: Volume;
};

export function VolumeActions({ volume }: VolumeActionsProps) {
  const router = useRouter();
  const [state, setState] = useState<ActionState>({ status: "idle" });
  const busy = state.status === "submitting" || state.status === "polling";
  const detaching = volume.state === "detaching";
  const canDetach = volume.state === "ready" && volume.attachedTo !== null;
  const canDelete = volume.state === "ready" && volume.attachedTo === null;

  async function handleDetach() {
    if (!canDetach || busy || detaching) {
      return;
    }

    setState({ status: "submitting", action: "detach" });
    try {
      const detach = await detachVolumeFromBff(volume.id);
      setState({ status: "polling", jobId: detach.jobId });
      const job = await waitForVolumeActionJob(detach.jobId, (pending) => {
        setState({
          status: "polling",
          jobId: pending.jobId,
          ...(pending.progress !== undefined ? { progress: pending.progress } : {}),
        });
      });

      if (job.status === "success") {
        setState({ status: "success", message: "Detached" });
        router.refresh();
      } else if (job.status === "failed") {
        setState({ status: "failed", message: job.errorText ?? "Detach failed" });
      } else {
        setState({ status: "failed", message: "Detach did not complete" });
      }
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Detach failed") });
    }
  }

  async function handleDelete() {
    if (!canDelete || busy || detaching || !window.confirm(`Delete volume "${volume.name}"?`)) {
      return;
    }

    setState({ status: "submitting", action: "delete" });
    try {
      await deleteVolumeFromBff(volume.id);
      setState({ status: "success", message: "Deleted" });
      router.refresh();
    } catch (error) {
      setState({ status: "failed", message: readErrorMessage(error, "Delete failed") });
    }
  }

  if (detaching) {
    return <span className="text-xs text-[color:var(--fg-muted)]">Detaching</span>;
  }

  return (
    <div className="flex min-w-[148px] items-center justify-end gap-2">
      {canDetach && (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={handleDetach}
          disabled={busy}
          aria-label={`Detach ${volume.name}`}
        >
          <Unplug size={14} strokeWidth={1.8} aria-hidden="true" />
          Detach
        </Button>
      )}
      {canDelete && (
        <Button
          type="button"
          variant="danger"
          size="sm"
          onClick={handleDelete}
          disabled={busy}
          aria-label={`Delete ${volume.name}`}
        >
          <Trash2 size={14} strokeWidth={1.8} aria-hidden="true" />
          Delete
        </Button>
      )}
      {!canDetach && !canDelete && <span className="text-xs text-[color:var(--fg-muted)]">-</span>}
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
      <span role="status" aria-live="polite" aria-busy="true" className="whitespace-nowrap text-xs text-[color:var(--fg-muted)]">
        Submitting
      </span>
    );
  }

  if (state.status === "polling") {
    return (
      <span role="status" aria-live="polite" aria-busy="true" className="whitespace-nowrap text-xs text-[color:var(--fg-muted)]">
        {state.progress !== undefined ? `Polling ${state.progress}%` : "Polling"}
      </span>
    );
  }

  return (
    <span
      role={state.status === "failed" ? "alert" : "status"}
      aria-live={state.status === "failed" ? undefined : "polite"}
      className={
        state.status === "success"
          ? "whitespace-nowrap text-xs text-[color:var(--success)]"
          : "max-w-[140px] truncate text-xs text-[color:var(--danger)]"
      }
      title={state.message}
    >
      {state.message}
    </span>
  );
}

async function waitForVolumeActionJob(
  jobId: string,
  onPending: (pending: VolumeActionJobResult & { status: "pending" }) => void,
): Promise<VolumeActionJobResult> {
  for (let attempt = 0; attempt < 24; attempt += 1) {
    const result = await queryVolumeActionJobResult(jobId);
    if (result.status !== "pending") {
      return result;
    }

    onPending(result);
    await delay(Math.min(1_000 + attempt * 250, 3_000));
  }

  throw new Error("CloudStack volume action job did not complete before the polling timeout");
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function readErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
