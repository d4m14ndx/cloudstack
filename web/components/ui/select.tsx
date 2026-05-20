"use client";

import { forwardRef } from "react";
import { ChevronDown } from "@/components/icons";
import { cn } from "@/lib/utils";

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  placeholder?: string;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, children, placeholder, ...props }, ref) => (
    <div className={cn("relative inline-flex min-w-[11rem]", className)}>
      <select
        ref={ref}
        className={cn(
          "h-9 w-full appearance-none rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 pr-8 text-sm text-[color:var(--fg)] transition-colors",
          "focus:border-[color:var(--accent)] focus:outline-none focus:ring-[3px] focus:ring-[color:var(--accent)]/30",
          "disabled:pointer-events-none disabled:opacity-50"
        )}
        {...props}
      >
        {placeholder && (
          <option value="" disabled>
            {placeholder}
          </option>
        )}
        {children}
      </select>
      <ChevronDown
        aria-hidden="true"
        size={14}
        strokeWidth={1.6}
        className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-[color:var(--fg-dim)]"
      />
    </div>
  )
);
Select.displayName = "Select";
