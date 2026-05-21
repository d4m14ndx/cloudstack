import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Advanced settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Advanced" description="Experimental controls and low-level console settings." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No advanced controls available"
          description="This build has no editable low-level console settings for the current scope."
        />
      </section>
    </>
  );
}
