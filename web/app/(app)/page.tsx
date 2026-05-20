import { PageHeader } from "@/components/page-header";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Sparkline } from "@/components/ui/sparkline";
import { Button } from "@/components/ui/button";
import { Download, Plus, IconInstances, Cpu, MemoryStick, Database } from "@/components/icons";
import { getCurrentUser } from "@/lib/auth/mock";
import { mockDashboardMetrics, mockDashboardSummary } from "@/lib/mock-data";

export default function OverviewPage() {
  const user = getCurrentUser();
  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";

  return (
    <>
      <PageHeader
        title={`${greeting}, ${user.name.split(" ")[0]}`}
        description={`${mockDashboardSummary.onlineZones} zones online · ${mockDashboardSummary.totalHosts} hosts · ${mockDashboardSummary.runningInstances} instances running across your platform`}
        actions={
          <>
            <Button variant="secondary" size="md" className="gap-1.5">
              <Download size={14} strokeWidth={1.6} /> Export report
            </Button>
            <Button variant="primary" size="md" className="gap-1.5">
              <Plus size={14} strokeWidth={2} /> Deploy instance
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-[repeat(auto-fit,minmax(170px,1fr))] gap-3">
        {mockDashboardMetrics.map((s) => {
          const Icon = s.label.startsWith("Instances")
            ? IconInstances
            : s.label.startsWith("vCPUs")
            ? Cpu
            : s.label.startsWith("Memory")
            ? MemoryStick
            : Database;
          return (
            <Card key={s.label}>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-[12px] font-medium text-[color:var(--fg-muted)] normal-case">
                  <Icon size={14} strokeWidth={1.6} />
                  {s.label}
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="flex items-baseline gap-1">
                  <span className="text-[26px] font-semibold tracking-[-0.02em] tabular-nums">
                    {s.value}
                  </span>
                  <span className="text-sm text-[color:var(--fg-dim)]">{s.denom}</span>
                </div>
                <div className="mt-2 flex items-end justify-between">
                  <span className="text-xs text-[color:var(--success)]">{s.delta}</span>
                  <div style={{ color: s.color }}>
                    <Sparkline values={s.series} width={90} height={28} />
                  </div>
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      <div className="mt-6 rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] p-12 text-center text-sm text-[color:var(--fg-muted)]">
        Zones, activity feed, top instances, and quick actions land in Phase 5c.
      </div>
    </>
  );
}
