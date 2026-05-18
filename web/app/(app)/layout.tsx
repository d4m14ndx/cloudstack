import { Sidebar } from "@/components/shell/sidebar";
import { Topbar } from "@/components/shell/topbar";
import { CommandPalette } from "@/components/shell/command-palette";
import { TweaksPanel } from "@/components/tweaks-panel";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid h-screen grid-cols-[var(--sidebar-w)_1fr] grid-rows-[56px_1fr]">
      <Sidebar />
      <Topbar />
      <main className="col-start-2 row-start-2 overflow-y-auto px-7 pt-6 pb-12">
        <div className="mx-auto max-w-[1480px]">{children}</div>
      </main>
      <CommandPalette />
      <TweaksPanel />
    </div>
  );
}
