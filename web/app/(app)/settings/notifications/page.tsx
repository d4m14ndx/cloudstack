import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata = { title: "Notification settings" };

export default function Page() {
  return (
    <>
      <PageHeader title="Notifications" description="Email, event, and operational alert preferences." />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title="No notification preferences configured"
          description="This build has no editable email, event, or operational alert preferences for the current scope."
        />
      </section>
    </>
  );
}
