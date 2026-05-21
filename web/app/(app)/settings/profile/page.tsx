import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Profile settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Profile" description="Personal details and console preferences." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No profile controls available"
          description="This build has no editable personal details or console preferences for the current account."
        />
      </section>
    </>
  );
}
