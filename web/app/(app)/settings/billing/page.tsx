import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Billing settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Billing" description="Invoices, usage exports, and payment settings." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No billing settings available"
          description="This build has no editable invoice, usage export, or payment settings for the current scope."
        />
      </section>
    </>
  );
}
