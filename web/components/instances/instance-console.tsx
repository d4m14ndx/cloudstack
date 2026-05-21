"use client";

import { ExternalLink, Monitor } from "lucide-react";
import { useMemo, useState } from "react";

import { Button } from "@/components/ui/button";
import { createConsoleEndpoint } from "@/lib/cloudstack/instance-console";
import type { Instance } from "@/lib/mock-data";

type ConsoleStatus =
  | { state: "idle" }
  | { state: "opening" }
  | { state: "failed"; message: string };

type InstanceConsoleProps = {
  id: string;
  name: string;
  state: Instance["state"];
  rawState?: string | null;
  hostControlState?: string | null;
  externalUrl?: string | null;
};

export function InstanceConsole({
  id,
  name,
  state,
  rawState,
  hostControlState,
  externalUrl,
}: InstanceConsoleProps) {
  const [status, setStatus] = useState<ConsoleStatus>({ state: "idle" });
  const unavailableReason = useMemo(
    () => consoleUnavailableReason({ state, rawState, hostControlState }),
    [state, rawState, hostControlState],
  );
  const busy = status.state === "opening";
  const disabled = busy || Boolean(unavailableReason);

  async function launchConsole() {
    if (disabled) {
      return;
    }

    setStatus({ state: "opening" });
    try {
      const url = externalUrl ?? (await createConsoleEndpoint(id)).url;
      openConsoleWindow(url);
      setStatus({ state: "idle" });
    } catch (error) {
      setStatus({
        state: "failed",
        message: sanitizeSensitiveError(error instanceof Error ? error.message : "Console launch failed"),
      });
    }
  }

  return (
    <div className="grid gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="text-sm font-medium text-[color:var(--fg)]">{name}</div>
          <div className="mt-1 text-xs text-[color:var(--fg-muted)]">{consoleStatusText(unavailableReason, hostControlState)}</div>
        </div>
        <Button
          type="button"
          variant="primary"
          size="md"
          disabled={disabled}
          onClick={() => void launchConsole()}
          aria-label={`Open console for ${name}`}
          title={unavailableReason ?? "Open console"}
        >
          {externalUrl ? <ExternalLink className="h-4 w-4" aria-hidden="true" /> : <Monitor className="h-4 w-4" aria-hidden="true" />}
          <span>{busy ? "Opening" : "Open console"}</span>
        </Button>
      </div>

      {unavailableReason && (
        <div className="rounded-md border border-[color:var(--border)] bg-[color:var(--surface-2)] px-3 py-2 text-sm text-[color:var(--fg-muted)]">
          {unavailableReason}
        </div>
      )}

      {status.state === "failed" && (
        <div
          role="alert"
          className="rounded-md border border-[color:var(--danger)]/40 bg-[color:var(--danger)]/10 px-3 py-2 text-sm text-[color:var(--danger)]"
        >
          {status.message}
        </div>
      )}
    </div>
  );
}

function consoleUnavailableReason({
  state,
  rawState,
  hostControlState,
}: Pick<InstanceConsoleProps, "state" | "rawState" | "hostControlState">): string | null {
  if (hostControlState?.toLowerCase() === "offline") {
    return "Console is unavailable while the host control state is offline.";
  }

  const normalizedState = (rawState ?? state).toLowerCase();
  switch (normalizedState) {
    case "stopped":
      return "Console is unavailable while the instance is stopped.";
    case "restoring":
      return "Console is unavailable while the instance is restoring.";
    case "error":
      return "Console is unavailable while the instance is in error.";
    case "destroyed":
      return "Console is unavailable after the instance has been destroyed.";
    default:
      return null;
  }
}

function consoleStatusText(unavailableReason: string | null, hostControlState?: string | null): string {
  if (unavailableReason) {
    return "Unavailable";
  }

  return hostControlState ? `Host control: ${hostControlState}` : "Console access is issued on demand.";
}

function openConsoleWindow(url: string): void {
  const opened = window.open(url, "_blank", "noopener,noreferrer");
  if (opened) {
    opened.opener = null;
  }
}

function sanitizeSensitiveError(message: string): string {
  return message.replace(/\b(?:https?|wss?):\/\/\S+/gi, "[redacted console endpoint]");
}
