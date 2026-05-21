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
  const [errorText, setErrorText] = useState<string | null>(null);
  const isPending = pendingAction !== null;

  async function runAction(action: Exclude<PendingAction, null>, work: () => Promise<void>) {
    setPendingAction(action);
    setErrorText(null);
    try {
      await work();
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

    void runAction("copy", async () => {
      await copyTemplate({ id: template.id, destZoneId: destZoneId.trim() });
    });
  }

  function handleDelete() {
    if (!window.confirm(`Delete template "${template.name}"?`)) {
      return;
    }

    void runAction("delete", async () => {
      await deleteTemplate({ id: template.id });
    });
  }

  function handleFeaturedChange(nextFeatured: boolean) {
    setFeatured(nextFeatured);
    void runAction("featured", async () => {
      try {
        await updateTemplatePermissions({ id: template.id, isFeatured: nextFeatured });
      } catch (error) {
        setFeatured(!nextFeatured);
        throw error;
      }
    });
  }

  return (
    <div className="flex items-center justify-end gap-1.5">
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
      {errorText ? (
        <span className="sr-only" role="status">
          {errorText}
        </span>
      ) : null}
    </div>
  );
}
