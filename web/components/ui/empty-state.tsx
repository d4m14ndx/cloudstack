import type { ReactNode } from "react";

import { cn } from "@/lib/utils";
import { TableCell, TableRow } from "@/components/ui/table";

type EmptyStateProps = {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
};

export function EmptyState({ title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cn("mx-auto flex max-w-md flex-col items-center gap-2 text-center", className)}>
      <div className="text-sm font-medium text-[color:var(--fg)]">{title}</div>
      {description ? (
        <div className="text-xs leading-5 text-[color:var(--fg-muted)]">{description}</div>
      ) : null}
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  );
}

type TableEmptyStateProps = EmptyStateProps & {
  colSpan: number;
};

export function TableEmptyState({ colSpan, className, ...props }: TableEmptyStateProps) {
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={colSpan} className="h-32 px-4">
        <EmptyState className={className} {...props} />
      </TableCell>
    </TableRow>
  );
}
