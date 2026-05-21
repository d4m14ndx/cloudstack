import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Integration settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Integrations" description="Identity, monitoring, and automation integrations." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No integrations configured"
          description="CloudStack did not return identity, monitoring, or automation integrations for this scope."
        />
      </section>
    </>
  );
}
