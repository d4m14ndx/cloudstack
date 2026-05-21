"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Copy, Loader2, Trash2 } from "@/components/icons";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  copyTemplate,
  deleteTemplate,
  updateTemplatePermissions,
} from "@/lib/cloudstack/template-actions";
import type { Template } from "@/lib/mock-data";

type TemplateActionsProps = {
  template: Pick<Template, "id" | "name" | "featured">;
};

type PendingAction = "copy" | "delete" | "featured" | null;

export function TemplateActions({ template }: TemplateActionsProps) {
  const router = useRouter();
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const [featured, setFeatured] = useState(template.featured);
  const [statusText, setStatusText] = useState<string | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);
  const isPending = pendingAction !== null;

  async function runAction(action: Exclude<PendingAction, null>, successMessage: string, work: () => Promise<void>) {
    setPendingAction(action);
    setErrorText(null);
    setStatusText(null);
    try {
      await work();
      setStatusText(successMessage);
      router.refresh();
    } catch (error) {
      setErrorText(error instanceof Error ? error.message : "CloudStack template action failed");
    } finally {
      setPendingAction(null);
    }
  }

  function handleCopy() {
    const destZoneId = window.prompt("Destination zone ID");
    if (!destZoneId?.trim()) {
      return;
    }

    void runAction("copy", "Copy queued", async () => {
      await copyTemplate({ id: template.id, destZoneId: destZoneId.trim() });
    });
  }

  function handleDelete() {
    if (!window.confirm(`Delete template "${template.name}"?`)) {
      return;
    }

    void runAction("delete", "Delete queued", async () => {
      await deleteTemplate({ id: template.id });
    });
  }

  function handleFeaturedChange(nextFeatured: boolean) {
    setFeatured(nextFeatured);
    void runAction("featured", "Featured permission updated", async () => {
      try {
        await updateTemplatePermissions({ id: template.id, isFeatured: nextFeatured });
      } catch (error) {
        setFeatured(!nextFeatured);
        throw error;
      }
    });
  }

  const visibleStatusText = errorText ?? statusText;

  return (
    <div className="flex items-center justify-end gap-1.5">
      {visibleStatusText ? (
        <span
          className={`max-w-44 truncate text-xs ${
            errorText ? "text-[color:var(--danger)]" : "text-[color:var(--success)]"
          }`}
          role="status"
          title={visibleStatusText}
        >
          {visibleStatusText}
        </span>
      ) : null}
      <Switch
        size="sm"
        checked={featured}
        disabled={isPending}
        onCheckedChange={handleFeaturedChange}
        aria-label={`Set ${template.name} featured permission`}
        title="Featured"
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-8 w-8"
        disabled={isPending}
        onClick={handleCopy}
        aria-label={`Copy ${template.name}`}
        title="Copy"
      >
        {pendingAction === "copy" ? (
          <Loader2 size={15} strokeWidth={1.8} className="animate-spin" />
        ) : (
          <Copy size={15} strokeWidth={1.8} />
        )}
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-8 w-8 text-[color:var(--danger)] hover:bg-[color:var(--danger)]/10"
        disabled={isPending}
        onClick={handleDelete}
        aria-label={`Delete ${template.name}`}
        title="Delete"
      >
        {pendingAction === "delete" ? (
          <Loader2 size={15} strokeWidth={1.8} className="animate-spin" />
        ) : (
          <Trash2 size={15} strokeWidth={1.8} />
        )}
      </Button>
    </div>
  );
}
