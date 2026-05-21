"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Command as CommandPrimitive } from "cmdk";
import { openDeployWizard } from "@/components/deploy-wizard/deploy-wizard";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Search, ChevronRight } from "@/components/icons";
import { useTweaks } from "@/lib/store/tweaks";
import { NAV_SECTIONS } from "@/lib/nav";
import { cn } from "@/lib/utils";
import { mockCommandActions, mockInstances, mockNetworks } from "@/lib/mock-data";

export function CommandPalette() {
  const router = useRouter();
  const open = useTweaks((s) => s.cmdkOpen);
  const setOpen = useTweaks((s) => s.setCmdkOpen);

  // Global keyboard shortcut
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen(!open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, setOpen]);

  const allPages = NAV_SECTIONS.flatMap((s) =>
    s.items.map((i) => ({ href: i.href, label: i.label, section: s.title }))
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        hideCloseButton
        className="top-[12vh] max-w-[620px] translate-y-0 border-0 bg-transparent p-0 shadow-none"
      >
        <DialogTitle className="sr-only">Command palette</DialogTitle>
        <div className="px-4">
          <CommandPrimitive
            label="Command palette"
            className={cn(
              "overflow-hidden rounded-[var(--radius-xl)] border border-[color:var(--border)] bg-[color:var(--bg-elevated)] shadow-[var(--shadow-lg)]",
              "data-[state=open]:animate-in data-[state=open]:fade-in-0"
            )}
          >
            <div className="flex items-center gap-2 border-b border-[color:var(--border)] px-4">
              <Search size={16} strokeWidth={1.6} className="text-[color:var(--fg-dim)]" />
              <CommandPrimitive.Input
                placeholder="Search resources, accounts, events…"
                className="flex-1 bg-transparent py-4 text-sm text-[color:var(--fg)] placeholder:text-[color:var(--fg-dim)] outline-none"
              />
            </div>

            <CommandPrimitive.List className="max-h-[420px] overflow-y-auto p-2">
              <CommandPrimitive.Empty className="px-3 py-6 text-center text-sm text-[color:var(--fg-muted)]">
                No results found.
              </CommandPrimitive.Empty>

              <CommandGroup heading="Pages">
                {allPages.map((p) => (
                  <Item
                    key={p.href}
                    value={`${p.label} ${p.section}`}
                    onSelect={() => {
                      router.push(p.href as never);
                      setOpen(false);
                    }}
                  >
                    <ChevronRight size={14} strokeWidth={1.6} className="text-[color:var(--fg-dim)]" />
                    <span>{p.label}</span>
                    <span className="ml-auto text-[11px] text-[color:var(--fg-dim)]">{p.section}</span>
                  </Item>
                ))}
              </CommandGroup>

              <CommandGroup heading="Actions">
                {mockCommandActions.map((a) => (
                  <Item
                    key={a}
                    value={a}
                    onSelect={() => {
                      if (a === "Deploy instance") {
                        openDeployWizard();
                      }
                      setOpen(false);
                    }}
                  >
                    {a}
                  </Item>
                ))}
              </CommandGroup>

              <CommandGroup heading="Instances">
                {mockInstances.slice(0, 6).map((instance) => (
                  <Item
                    key={instance.id}
                    value={`instance ${instance.name} ${instance.account} ${instance.zone}`}
                    onSelect={() => {
                      router.push(`/instances` as never);
                      setOpen(false);
                    }}
                  >
                    <span className="font-mono text-[12.5px]">{instance.name}</span>
                    <span className="ml-auto text-[11px] text-[color:var(--fg-dim)]">{instance.state}</span>
                  </Item>
                ))}
              </CommandGroup>

              <CommandGroup heading="Networks">
                {mockNetworks.slice(0, 3).map((network) => (
                  <Item
                    key={network.id}
                    value={`network ${network.name} ${network.cidr} ${network.zone}`}
                    onSelect={() => {
                      router.push(`/networks` as never);
                      setOpen(false);
                    }}
                  >
                    <span className="font-mono text-[12.5px]">{network.name}</span>
                    <span className="ml-auto text-[11px] text-[color:var(--fg-dim)]">{network.cidr}</span>
                  </Item>
                ))}
              </CommandGroup>
            </CommandPrimitive.List>
          </CommandPrimitive>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function CommandGroup({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <CommandPrimitive.Group
      heading={heading}
      className={cn(
        "[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:py-1.5",
        "[&_[cmdk-group-heading]]:text-[10.5px] [&_[cmdk-group-heading]]:font-medium",
        "[&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider",
        "[&_[cmdk-group-heading]]:text-[color:var(--fg-dim)]"
      )}
    >
      {children}
    </CommandPrimitive.Group>
  );
}

function Item({
  value,
  onSelect,
  children,
}: {
  value: string;
  onSelect: () => void;
  children: React.ReactNode;
}) {
  return (
    <CommandPrimitive.Item
      value={value}
      onSelect={onSelect}
      className={cn(
        "flex cursor-pointer items-center gap-2 rounded-md px-3 py-2 text-sm text-[color:var(--fg)] transition-colors",
        "data-[selected=true]:bg-[color:var(--surface-2)] data-[selected=true]:text-[color:var(--fg)]"
      )}
    >
      {children}
    </CommandPrimitive.Item>
  );
}
