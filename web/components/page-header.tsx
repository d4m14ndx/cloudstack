import { cn } from "@/lib/utils";

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <header className={cn("mb-6 flex flex-wrap items-start justify-between gap-3", className)}>
      <div className="min-w-0">
        <h1 className="text-[22px] font-semibold tracking-[-0.015em] text-[color:var(--fg)]">
          {title}
        </h1>
        {description && (
          <p className="mt-1 max-w-2xl text-sm text-[color:var(--fg-muted)]">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </header>
  );
}

export function PageScaffold({ title, description }: { title: string; description?: string }) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <div className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] p-12 text-center text-sm text-[color:var(--fg-muted)]">
        Screen scaffold — content lands in Phase 5c.
      </div>
    </>
  );
}
