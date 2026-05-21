import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Security settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Security" description="Password, MFA, and session controls." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No security controls available"
          description="This build has no editable password, MFA, or session controls for the current account."
        />
      </section>
    </>
  );
}
