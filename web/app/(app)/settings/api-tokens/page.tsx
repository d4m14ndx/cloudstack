import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "API token settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="API tokens" description="CloudStack API keys and scoped automation tokens." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No API tokens configured"
          description="CloudStack did not return scoped automation tokens for the current account."
        />
      </section>
    </>
  );
}
