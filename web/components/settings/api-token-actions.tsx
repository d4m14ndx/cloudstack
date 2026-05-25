"use client";

import { useId, useState } from "react";
import { KeyRound } from "lucide-react";

import { Button } from "@/components/ui/button";
import { registerUserApiToken, type GeneratedUserApiToken } from "@/lib/cloudstack/api-tokens";

type ApiTokenActionsProps = {
  userId: string;
  generateLabel: string;
  pendingLabel: string;
  successLabel: string;
  errorLabel: string;
  apiKeyLabel: string;
  secretKeyLabel: string;
  oneTimeSecretLabel: string;
};

type GenerateStatus = "idle" | "pending" | "success" | "error";

export function ApiTokenActions({
  userId,
  generateLabel,
  pendingLabel,
  successLabel,
  errorLabel,
  apiKeyLabel,
  secretKeyLabel,
  oneTimeSecretLabel,
}: ApiTokenActionsProps) {
  const statusId = useId();
  const [status, setStatus] = useState<GenerateStatus>("idle");
  const [generated, setGenerated] = useState<GeneratedUserApiToken | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleGenerate() {
    setStatus("pending");
    setGenerated(null);
    setError(null);

    try {
      const token = await registerUserApiToken({
        userId,
        name: "CloudStack UI automation",
        description: "Generated from modern CloudStack settings",
      });
      setGenerated(token);
      setStatus("success");
    } catch (err) {
      setError(err instanceof Error ? err.message : errorLabel);
      setStatus("error");
    }
  }

  const hasStatusDescription = status !== "idle";

  return (
    <div className="space-y-3">
      <Button
        type="button"
        variant="primary"
        disabled={status === "pending"}
        aria-busy={status === "pending"}
        aria-describedby={hasStatusDescription ? statusId : undefined}
        onClick={() => {
          void handleGenerate();
        }}
      >
        <KeyRound size={15} strokeWidth={1.8} aria-hidden="true" />
        {status === "pending" ? pendingLabel : generateLabel}
      </Button>

      {status === "pending" ? (
        <p id={statusId} role="status" aria-live="polite" className="text-sm text-[color:var(--fg-muted)]">
          {pendingLabel}
        </p>
      ) : null}

      {generated ? (
        <div
          id={statusId}
          role="status"
          aria-live="polite"
          className="rounded-md border border-[color:var(--success)]/30 bg-[color:var(--success-bg)] p-3 text-sm"
        >
          <p className="font-medium text-[color:var(--success)]">{successLabel}</p>
          <p className="mt-1 text-xs text-[color:var(--fg-muted)]">{oneTimeSecretLabel}</p>
          <dl className="mt-3 space-y-2">
            <div className="grid gap-1 sm:grid-cols-[90px_1fr]">
              <dt className="text-xs font-medium text-[color:var(--fg-dim)]">{apiKeyLabel}</dt>
              <dd className="min-w-0 break-all font-mono text-xs text-[color:var(--fg)]">{generated.apiKey}</dd>
            </div>
            <div className="grid gap-1 sm:grid-cols-[90px_1fr]">
              <dt className="text-xs font-medium text-[color:var(--fg-dim)]">{secretKeyLabel}</dt>
              <dd className="min-w-0 break-all font-mono text-xs text-[color:var(--fg)]">{generated.secretKey}</dd>
            </div>
          </dl>
        </div>
      ) : null}

      {error ? (
        <p id={statusId} role="alert" className="text-sm text-[color:var(--danger)]">
          {errorLabel}: {error}
        </p>
      ) : null}
    </div>
  );
}
