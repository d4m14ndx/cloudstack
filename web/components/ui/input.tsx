"use client";

import { forwardRef } from "react";
import { cn } from "@/lib/utils";

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  iconLeft?: React.ReactNode;
  iconRight?: React.ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, iconLeft, iconRight, ...props }, ref) => {
    if (iconLeft || iconRight) {
      return (
        <div
          className={cn(
            "flex items-center gap-2 rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 text-sm transition-colors h-9",
            "focus-within:border-[color:var(--accent)] focus-within:ring-[3px] focus-within:ring-[color:var(--accent)]/30",
            className
          )}
        >
          {iconLeft && <span className="text-[color:var(--fg-dim)]">{iconLeft}</span>}
          <input
            ref={ref}
            className="flex-1 bg-transparent text-[color:var(--fg)] placeholder:text-[color:var(--fg-dim)] outline-none"
            {...props}
          />
          {iconRight && <span className="text-[color:var(--fg-dim)]">{iconRight}</span>}
        </div>
      );
    }
    return (
      <input
        ref={ref}
        className={cn(
          "h-9 rounded-md border border-[color:var(--border)] bg-[color:var(--surface)] px-3 text-sm text-[color:var(--fg)] placeholder:text-[color:var(--fg-dim)] transition-colors",
          "focus:border-[color:var(--accent)] focus:outline-none focus:ring-[3px] focus:ring-[color:var(--accent)]/30",
          className
        )}
        {...props}
      />
    );
  }
);
Input.displayName = "Input";
