import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export function LoadingPage({
  title = "Loading",
  description = "Fetching the latest CloudStack data.",
}: {
  title?: string;
  description?: string;
}) {
  return (
    <>
      <PageHeader
        title={title}
        description={description}
        actions={
          <div className="flex items-center gap-2" aria-hidden="true">
            <Skeleton className="h-9 w-24" />
            <Skeleton className="h-9 w-32" />
          </div>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-2" aria-hidden="true">
        <Skeleton className="h-7 w-24 rounded-full" />
        <Skeleton className="h-7 w-28 rounded-full" />
        <Skeleton className="h-7 w-20 rounded-full" />
      </div>

      <Card className="p-0" aria-busy="true" aria-label="Loading page content">
        <div className="overflow-hidden">
          <div className="grid grid-cols-[1.5fr_0.8fr_0.8fr_1fr_0.8fr] gap-4 border-b border-[color:var(--border)] px-4 py-3">
            {Array.from({ length: 5 }, (_, index) => (
              <Skeleton key={index} className="h-4 w-full max-w-28" />
            ))}
          </div>
          <div className="divide-y divide-[color:var(--border)]">
            {Array.from({ length: 7 }, (_, rowIndex) => (
              <div
                key={rowIndex}
                className="grid grid-cols-[1.5fr_0.8fr_0.8fr_1fr_0.8fr] gap-4 px-4 py-4"
              >
                <div className="space-y-2">
                  <Skeleton className="h-4 w-40" />
                  <Skeleton className="h-3 w-28" />
                </div>
                <Skeleton className="h-5 w-20 rounded-full" />
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-4 w-32" />
                <Skeleton className="h-4 w-20 justify-self-end" />
              </div>
            ))}
          </div>
        </div>
      </Card>
    </>
  );
}
