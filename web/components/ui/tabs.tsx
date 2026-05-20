"use client";

import * as React from "react";
import * as RadixTabs from "@radix-ui/react-tabs";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export const Tabs = RadixTabs.Root;

export const TabsList = React.forwardRef<
  React.ElementRef<typeof RadixTabs.List>,
  React.ComponentPropsWithoutRef<typeof RadixTabs.List>
>(({ className, ...props }, ref) => (
  <RadixTabs.List
    ref={ref}
    className={cn(
      "flex items-center gap-5 border-b border-[color:var(--border)]",
      className
    )}
    {...props}
  />
));
TabsList.displayName = RadixTabs.List.displayName;

export const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof RadixTabs.Trigger>,
  React.ComponentPropsWithoutRef<typeof RadixTabs.Trigger> & { count?: number }
>(({ className, children, count, ...props }, ref) => (
  <RadixTabs.Trigger
    ref={ref}
    className={cn(
      "group inline-flex h-10 items-center gap-2 border-b-2 border-transparent px-0 text-sm font-medium text-[color:var(--fg-muted)] transition-colors",
      "hover:text-[color:var(--fg)] data-[state=active]:border-[color:var(--accent)] data-[state=active]:text-[color:var(--fg)]",
      "focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[color:var(--accent)]/30",
      "disabled:pointer-events-none disabled:opacity-50",
      className
    )}
    {...props}
  >
    <span>{children}</span>
    {typeof count === "number" && (
      <Badge
        size="sm"
        className="normal-case tracking-normal group-data-[state=active]:bg-[color:var(--accent-soft)] group-data-[state=active]:text-[color:var(--accent)]"
      >
        {count}
      </Badge>
    )}
  </RadixTabs.Trigger>
));
TabsTrigger.displayName = RadixTabs.Trigger.displayName;

export const TabsContent = React.forwardRef<
  React.ElementRef<typeof RadixTabs.Content>,
  React.ComponentPropsWithoutRef<typeof RadixTabs.Content>
>(({ className, ...props }, ref) => (
  <RadixTabs.Content
    ref={ref}
    className={cn(
      "mt-4 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[color:var(--accent)]/30",
      className
    )}
    {...props}
  />
));
TabsContent.displayName = RadixTabs.Content.displayName;
