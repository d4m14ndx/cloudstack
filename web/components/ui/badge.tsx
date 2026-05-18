import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10.5px] font-medium uppercase tracking-wider whitespace-nowrap",
  {
    variants: {
      variant: {
        default: "bg-[color:var(--surface-2)] text-[color:var(--fg-muted)] border border-[color:var(--border)]",
        success: "bg-[color:var(--success-bg)] text-[color:var(--success)]",
        warning: "bg-[color:var(--warning-bg)] text-[color:var(--warning)]",
        danger:  "bg-[color:var(--danger-bg)]  text-[color:var(--danger)]",
        info:    "bg-[color:var(--info-bg)]    text-[color:var(--info)]",
        accent:  "bg-[color:var(--accent-soft)] text-[color:var(--accent)]",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

export type BadgeProps = React.HTMLAttributes<HTMLSpanElement> &
  VariantProps<typeof badgeVariants>;

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />;
}
