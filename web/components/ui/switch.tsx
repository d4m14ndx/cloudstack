"use client";

import * as RadixSwitch from "@radix-ui/react-switch";
import { cn } from "@/lib/utils";

export interface SwitchProps
  extends React.ComponentPropsWithoutRef<typeof RadixSwitch.Root> {
  size?: "sm" | "md";
}

export function Switch({ className, size = "md", ...props }: SwitchProps) {
  const dims =
    size === "sm"
      ? "h-4 w-7 [&>[data-state=checked]]:translate-x-3"
      : "h-5 w-9 [&>[data-state=checked]]:translate-x-4";
  const thumb = size === "sm" ? "h-3 w-3" : "h-3.5 w-3.5";
  return (
    <RadixSwitch.Root
      className={cn(
        "relative inline-flex shrink-0 cursor-pointer items-center rounded-full bg-[color:var(--surface-3)] transition-colors duration-150 data-[state=checked]:bg-[color:var(--accent)] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[color:var(--accent)]/30 disabled:cursor-not-allowed disabled:opacity-50",
        dims,
        className
      )}
      {...props}
    >
      <RadixSwitch.Thumb
        className={cn(
          "pointer-events-none translate-x-0.5 rounded-full bg-white shadow-sm transition-transform duration-150 data-[state=checked]:translate-x-[calc(100%-2px)]",
          thumb
        )}
      />
    </RadixSwitch.Root>
  );
}
