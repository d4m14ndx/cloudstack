"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

import { CheckCircle2, Copy, IconSshKeys, Loader2, Trash2, Upload } from "@/components/icons";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  createSshKeyPairFromBff,
  deleteSshKeyPairFromBff,
  registerSshKeyPairFromBff,
  type CreateSshKeyPairResult,
} from "@/lib/cloudstack/ssh-keys";

type FormStatus =
  | { state: "idle" }
  | { state: "loading"; message: string }
  | { state: "success"; message: string }
  | { state: "error"; message: string };

export function SshKeyActions() {
  const router = useRouter();
  const [mode, setMode] = useState<"create" | "register">("create");
  const [name, setName] = useState("");
  const [publicKey, setPublicKey] = useState("");
  const [createdKey, setCreatedKey] = useState<CreateSshKeyPairResult | null>(null);
  const [status, setStatus] = useState<FormStatus>({ state: "idle" });

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreatedKey(null);
    setStatus({ state: "loading", message: mode === "create" ? "Creating key pair" : "Registering key pair" });

    try {
      if (mode === "create") {
        const keyPair = await createSshKeyPairFromBff({ name: name.trim() });
        setCreatedKey(keyPair);
        setStatus({ state: "success", message: `Created ${keyPair.name}` });
      } else {
        const keyPair = await registerSshKeyPairFromBff({
          name: name.trim(),
          publicKey: publicKey.trim(),
        });
        setStatus({ state: "success", message: `Registered ${keyPair.name}` });
        setPublicKey("");
      }
      setName("");
      router.refresh();
    } catch (error) {
      setStatus({ state: "error", message: error instanceof Error ? error.message : "SSH key request failed" });
    }
  }

  const busy = status.state === "loading";
  const canSubmit = Boolean(name.trim() && (mode === "create" || publicKey.trim()) && !busy);

  return (
    <Card className="mb-4 p-4">
      <CardHeader className="mb-3">
        <CardTitle className="flex items-center gap-2">
          <IconSshKeys size={16} strokeWidth={1.8} />
          Key actions
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs value={mode} onValueChange={(value) => setMode(value as "create" | "register")}>
          <TabsList className="mb-3">
            <TabsTrigger value="create">Create</TabsTrigger>
            <TabsTrigger value="register">Register</TabsTrigger>
          </TabsList>
          <form onSubmit={handleSubmit} className="grid gap-3 lg:grid-cols-[minmax(180px,260px)_1fr_auto] lg:items-start">
            <label className="grid gap-1.5 text-xs font-medium text-[color:var(--fg-muted)]">
              Name
              <Input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="ops-key"
                required
                disabled={busy}
              />
            </label>

            <TabsContent value="create" className="m-0 min-h-9 lg:self-end">
              <StatusLine status={status} />
            </TabsContent>

            <TabsContent value="register" className="m-0">
              <label className="grid gap-1.5 text-xs font-medium text-[color:var(--fg-muted)]">
                Public key
                <textarea
                  value={publicKey}
                  onChange={(event) => setPublicKey(event.target.value)}
                  placeholder="ssh-rsa AAAA..."
                  required={mode === "register"}
                  disabled={busy}
                  className="min-h-20 rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 py-2 font-mono text-xs text-[color:var(--fg)] outline-none transition-colors placeholder:text-[color:var(--fg-dim)] focus:border-[color:var(--accent)] focus:ring-[3px] focus:ring-[color:var(--accent)]/30"
                />
              </label>
              <StatusLine status={status} className="mt-2" />
            </TabsContent>

            <Button
              type="submit"
              variant="primary"
              disabled={!canSubmit}
              className="lg:mt-5"
              aria-label={mode === "create" ? "Create SSH key" : "Register SSH key"}
            >
              {busy ? <Loader2 size={14} className="animate-spin" /> : mode === "create" ? <IconSshKeys size={14} /> : <Upload size={14} />}
              {mode === "create" ? "Create" : "Register"}
            </Button>
          </form>
        </Tabs>

        {createdKey?.privateKey && (
          <div className="mt-3 rounded-md border border-[color:var(--border)] bg-[color:var(--surface-2)] p-3">
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-[color:var(--fg-muted)]">Private key</span>
              <CopyPrivateKeyButton privateKey={createdKey.privateKey} />
            </div>
            <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all font-mono text-xs text-[color:var(--fg)]">
              {createdKey.privateKey}
            </pre>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function SshKeyDeleteButton({ name }: { name: string }) {
  const router = useRouter();
  const [status, setStatus] = useState<FormStatus>({ state: "idle" });

  async function handleDelete() {
    if (!window.confirm(`Delete SSH key "${name}"?`)) {
      return;
    }

    setStatus({ state: "loading", message: "Deleting" });
    try {
      const deleted = await deleteSshKeyPairFromBff({ name });
      if (!deleted) {
        throw new Error("CloudStack did not delete the SSH key");
      }
      setStatus({ state: "success", message: `Deleted ${name}` });
      router.refresh();
    } catch (error) {
      setStatus({ state: "error", message: error instanceof Error ? error.message : "Delete failed" });
    }
  }

  const busy = status.state === "loading";

  return (
    <div className="flex items-center justify-end gap-2">
      {status.state !== "idle" && (
        <span
          className={`max-w-40 truncate text-xs ${
            status.state === "error"
              ? "text-[color:var(--danger)]"
              : status.state === "success"
                ? "text-[color:var(--success)]"
                : "text-[color:var(--fg-muted)]"
          }`}
          role={status.state === "error" ? "alert" : "status"}
          aria-live={status.state === "error" ? undefined : "polite"}
          aria-busy={status.state === "loading" ? "true" : undefined}
          title={status.message}
        >
          {status.message}
        </span>
      )}
      <Button
        type="button"
        variant="ghost"
        size="icon"
        onClick={handleDelete}
        disabled={busy}
        aria-label={`Delete SSH key ${name}`}
        title={`Delete SSH key ${name}`}
      >
        {busy ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
      </Button>
    </div>
  );
}

function StatusLine({ status, className = "" }: { status: FormStatus; className?: string }) {
  if (status.state === "idle") {
    return <div className={className} />;
  }

  const color = status.state === "error" ? "text-[color:var(--danger)]" : "text-[color:var(--fg-muted)]";
  return (
    <div
      role={status.state === "error" ? "alert" : "status"}
      aria-live={status.state === "error" ? undefined : "polite"}
      aria-busy={status.state === "loading" ? "true" : undefined}
      className={`flex min-h-9 items-center gap-2 text-xs ${color} ${className}`}
    >
      {status.state === "loading" && <Loader2 size={14} className="animate-spin" />}
      {status.state === "success" && <CheckCircle2 size={14} className="text-[color:var(--success)]" />}
      <span>{status.message}</span>
    </div>
  );
}

function CopyPrivateKeyButton({ privateKey }: { privateKey: string }) {
  const [copied, setCopied] = useState(false);

  async function copyKey() {
    await navigator.clipboard.writeText(privateKey);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  }

  return (
    <Button type="button" size="sm" onClick={copyKey}>
      {copied ? <CheckCircle2 size={14} /> : <Copy size={14} />}
      {copied ? "Copied" : "Copy"}
    </Button>
  );
}
