"use client";

import { Sliders, X, Check } from "@/components/icons";
import { Button } from "@/components/ui/button";
import { useTweaks, ACCENT_SWATCHES, type Density, type Theme, type SidebarStyle } from "@/lib/store/tweaks";
import { cn } from "@/lib/utils";

const THEMES: { value: Theme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark",  label: "Dark"  },
];

const DENSITIES: { value: Density; label: string }[] = [
  { value: "compact",     label: "Compact"     },
  { value: "cozy",        label: "Cozy"        },
  { value: "comfortable", label: "Comfortable" },
];

const SIDEBAR_STYLES: { value: SidebarStyle; label: string }[] = [
  { value: "compact", label: "Compact" },
  { value: "default", label: "Default" },
  { value: "wide",    label: "Wide"    },
];

export function TweaksPanel() {
  const open = useTweaks((s) => s.tweaksOpen);
  const toggle = useTweaks((s) => s.toggleTweaks);
  const setOpen = useTweaks((s) => s.setTweaksOpen);

  const theme = useTweaks((s) => s.theme);
  const setTheme = useTweaks((s) => s.setTheme);
  const density = useTweaks((s) => s.density);
  const setDensity = useTweaks((s) => s.setDensity);
  const accent = useTweaks((s) => s.accent);
  const setAccent = useTweaks((s) => s.setAccent);
  const sidebarStyle = useTweaks((s) => s.sidebarStyle);
  const setSidebarStyle = useTweaks((s) => s.setSidebarStyle);

  return (
    <>
      {/* Floating trigger button (bottom-right) */}
      <button
        type="button"
        onClick={toggle}
        className={cn(
          "fixed bottom-5 right-5 z-30 flex h-10 w-10 items-center justify-center rounded-full border border-[color:var(--border)] bg-[color:var(--bg-elevated)] shadow-[var(--shadow-md)] transition-colors",
          "hover:border-[color:var(--border-strong)]"
        )}
        aria-label="Open tweaks panel"
        title="Tweaks"
      >
        <Sliders size={16} strokeWidth={1.6} />
      </button>

      {/* Panel */}
      {open && (
        <>
          <div className="fixed inset-0 z-30 bg-black/10" onClick={() => setOpen(false)} aria-hidden />
          <aside
            className={cn(
              "fixed bottom-20 right-5 z-40 w-[300px] rounded-[var(--radius-xl)] border border-[color:var(--border)] bg-[color:var(--bg-elevated)] p-4 shadow-[var(--shadow-lg)]",
              "animate-in fade-in-0 slide-in-from-bottom-2 duration-200"
            )}
          >
            <header className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold">Tweaks</h2>
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="rounded-md p-1 text-[color:var(--fg-muted)] transition-colors hover:bg-[color:var(--surface-2)] hover:text-[color:var(--fg)]"
                aria-label="Close"
              >
                <X size={14} strokeWidth={1.6} />
              </button>
            </header>

            <Section label="Theme">
              <RadioGroup
                value={theme}
                options={THEMES}
                onChange={(v) => setTheme(v as Theme)}
              />
            </Section>

            <Section label="Accent">
              <div className="flex gap-1.5">
                {ACCENT_SWATCHES.map((swatch) => (
                  <button
                    key={swatch}
                    type="button"
                    onClick={() => setAccent(swatch)}
                    className={cn(
                      "relative flex h-7 w-7 items-center justify-center rounded-full transition-transform",
                      accent === swatch && "ring-2 ring-offset-2 ring-offset-[color:var(--bg-elevated)]"
                    )}
                    style={{ background: swatch, ...({ "--tw-ring-color": swatch } as React.CSSProperties) }}
                    aria-label={`Accent ${swatch}`}
                  >
                    {accent === swatch && <Check size={12} strokeWidth={2.4} className="text-white" />}
                  </button>
                ))}
              </div>
            </Section>

            <Section label="Density">
              <RadioGroup
                value={density}
                options={DENSITIES}
                onChange={(v) => setDensity(v as Density)}
              />
            </Section>

            <Section label="Sidebar">
              <RadioGroup
                value={sidebarStyle}
                options={SIDEBAR_STYLES}
                onChange={(v) => setSidebarStyle(v as SidebarStyle)}
              />
            </Section>

            <div className="mt-2 pt-3 border-t border-[color:var(--border)]">
              <Button variant="secondary" size="sm" className="w-full">
                Open Deploy wizard
              </Button>
            </div>
          </aside>
        </>
      )}
    </>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-3">
      <div className="mb-1.5 text-[10.5px] font-medium uppercase tracking-wider text-[color:var(--fg-dim)]">
        {label}
      </div>
      {children}
    </div>
  );
}

function RadioGroup({
  value,
  options,
  onChange,
}: {
  value: string;
  options: { value: string; label: string }[];
  onChange: (v: string) => void;
}) {
  return (
    <div className="flex gap-1 rounded-md bg-[color:var(--surface-2)] p-0.5">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={cn(
            "flex-1 rounded px-2 py-1 text-xs font-medium transition-colors",
            value === opt.value
              ? "bg-[color:var(--surface)] text-[color:var(--fg)] shadow-sm"
              : "text-[color:var(--fg-muted)] hover:text-[color:var(--fg)]"
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
