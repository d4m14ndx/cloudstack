"use client";

import Link from "next/link";
import { useState } from "react";
import { usePathname } from "next/navigation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Bell,
  HelpCircle,
  Code,
  Plus,
  Sun,
  Moon,
  Search,
  Command,
} from "@/components/icons";
import { useTweaks } from "@/lib/store/tweaks";
import { cn } from "@/lib/utils";

function deriveBreadcrumbs(pathname: string): string[] {
  if (pathname === "/") return ["Overview"];
  return pathname.split("/").filter(Boolean).map((seg) =>
    seg.charAt(0).toUpperCase() + seg.slice(1).replace(/-/g, " ")
  );
}

export function Topbar() {
  const pathname = usePathname();
  const theme = useTweaks((s) => s.theme);
  const setTheme = useTweaks((s) => s.setTheme);
  const setCmdkOpen = useTweaks((s) => s.setCmdkOpen);
  const [deployOpen, setDeployOpen] = useState(false);

  const crumbs = deriveBreadcrumbs(pathname);

  return (
    <>
      <header
        className={cn(
          "col-start-2 flex items-center gap-3 border-b border-[color:var(--border)] bg-[color:var(--bg)] px-4",
          "h-14"
        )}
      >
        {/* Breadcrumb */}
        <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 text-sm text-[color:var(--fg-muted)]">
          {crumbs.map((c, i) => (
            <span key={i} className={cn(i === crumbs.length - 1 && "text-[color:var(--fg)] font-medium")}>
              {c}
              {i < crumbs.length - 1 && <span className="mx-1.5 text-[color:var(--fg-faint)]">/</span>}
            </span>
          ))}
        </nav>

        {/* Command input */}
        <button
          type="button"
          onClick={() => setCmdkOpen(true)}
          className={cn(
            "mx-auto flex h-8 w-full max-w-[360px] items-center gap-2 rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 text-sm text-[color:var(--fg-dim)] transition-colors",
            "hover:border-[color:var(--border-strong)]"
          )}
        >
          <Search size={14} strokeWidth={1.6} />
          <span className="flex-1 text-left">Search resources, accounts, events...</span>
          <kbd className="flex h-5 items-center gap-0.5 rounded border border-[color:var(--border)] bg-[color:var(--surface-2)] px-1.5 font-mono text-[10px]">
            <Command size={10} strokeWidth={2} /> K
          </kbd>
        </button>

        {/* Right actions */}
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            aria-label="Toggle theme"
          >
            {theme === "dark" ? <Sun size={15} strokeWidth={1.6} /> : <Moon size={15} strokeWidth={1.6} />}
          </Button>
          <Button variant="ghost" size="icon" aria-label="Notifications" className="relative">
            <Bell size={15} strokeWidth={1.6} />
            <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-[color:var(--danger)]" />
          </Button>
          <Button asChild variant="ghost" size="icon" aria-label="Help">
            <a
              href="https://docs.cloudstack.apache.org/"
              target="_blank"
              rel="noreferrer"
              title="Open Apache CloudStack docs"
            >
              <HelpCircle size={15} strokeWidth={1.6} />
            </a>
          </Button>
          <Button asChild variant="ghost" size="sm" className="gap-1.5">
            <Link href="/settings/api-tokens" title="Open API token settings">
              <Code size={14} strokeWidth={1.6} />
              API
            </Link>
          </Button>
          <Button
            variant="primary"
            size="sm"
            className="ml-1.5 gap-1.5"
            onClick={() => setDeployOpen(true)}
          >
            <Plus size={14} strokeWidth={2} />
            Deploy
          </Button>
        </div>
      </header>

      <Dialog open={deployOpen} onOpenChange={setDeployOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Deploy wizard</DialogTitle>
            <DialogDescription>
              Phase 5a placeholder for instance, network, and template deployment flows.
            </DialogDescription>
          </DialogHeader>
          <div className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] p-6 text-sm text-[color:var(--fg-muted)]">
            The real CloudStack deployment workflow lands with backend integration in Phase 5c. This scaffold keeps the action wired without calling a backend.
          </div>
          <div className="mt-5 flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setDeployOpen(false)}>
              Close
            </Button>
            <Button asChild variant="primary" size="sm">
              <Link href="/instances">View instances</Link>
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
