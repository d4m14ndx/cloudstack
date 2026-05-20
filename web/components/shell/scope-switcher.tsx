"use client";

import { useState } from "react";
import { ChevronDown } from "@/components/icons";
import { cn } from "@/lib/utils";

// Phase 5a: mocked. Phase 5c+: real CloudStack projects/domains via /api/cs.
const SCOPES = [
  { id: "pl", name: "platform", role: "root · admin" },
  { id: "dev", name: "development", role: "domain admin" },
  { id: "qa", name: "qa-eu", role: "user" },
];

export function ScopeSwitcher({ compact = false }: { compact?: boolean }) {
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState(SCOPES[0]!);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={`Switch scope: ${current.name}`}
        title={`Scope: ${current.name}`}
        className={cn(
          "flex w-full items-center gap-2.5 rounded-lg border border-[color:var(--border)] bg-[color:var(--surface)] px-2.5 py-2 text-left transition-colors",
          "hover:border-[color:var(--border-strong)]",
          compact && "h-10 justify-center px-0"
        )}
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-[11px] font-semibold uppercase text-white">
          {current.id}
        </span>
        {!compact && (
          <>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-[color:var(--fg)]">{current.name}</span>
              <span className="block truncate text-[11px] text-[color:var(--fg-dim)]">{current.role}</span>
            </span>
            <ChevronDown size={14} strokeWidth={1.6} className="text-[color:var(--fg-dim)]" />
          </>
        )}
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} aria-hidden />
          <ul
            className={cn(
              "absolute left-0 right-0 top-[calc(100%+4px)] z-20 overflow-hidden rounded-lg border border-[color:var(--border)] bg-[color:var(--bg-elevated)] shadow-[var(--shadow-md)]",
              compact && "left-0 w-56"
            )}
          >
            {SCOPES.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  onClick={() => { setCurrent(s); setOpen(false); }}
                  className={cn(
                    "flex w-full items-center gap-2.5 px-2.5 py-2 text-left transition-colors hover:bg-[color:var(--surface-2)]",
                    s.id === current.id && "bg-[color:var(--accent-soft)]/40"
                  )}
                >
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-[11px] font-semibold uppercase text-white">
                    {s.id}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-[color:var(--fg)]">{s.name}</span>
                    <span className="block truncate text-[11px] text-[color:var(--fg-dim)]">{s.role}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
