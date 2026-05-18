"use client";

import * as RadixDialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;
export const DialogPortal = RadixDialog.Portal;

export function DialogOverlay({ className, ...props }: React.ComponentProps<typeof RadixDialog.Overlay>) {
  return (
    <RadixDialog.Overlay
      className={cn(
        "fixed inset-0 z-50 bg-black/25 backdrop-blur-[6px] data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0",
        className
      )}
      {...props}
    />
  );
}

export function DialogContent({
  className,
  children,
  hideCloseButton = false,
  ...props
}: React.ComponentProps<typeof RadixDialog.Content> & { hideCloseButton?: boolean }) {
  return (
    <DialogPortal>
      <DialogOverlay />
      <RadixDialog.Content
        className={cn(
          "fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2",
          "w-full max-w-lg rounded-[var(--radius-xl)] bg-[color:var(--bg-elevated)]",
          "border border-[color:var(--border)] shadow-[var(--shadow-lg)] p-6",
          "data-[state=open]:animate-in data-[state=closed]:animate-out",
          "data-[state=open]:fade-in-0 data-[state=closed]:fade-out-0",
          "data-[state=open]:slide-in-from-bottom-2 data-[state=closed]:slide-out-to-bottom-2",
          "data-[state=open]:duration-200",
          className
        )}
        {...props}
      >
        {children}
        {!hideCloseButton && (
          <RadixDialog.Close
            className="absolute right-4 top-4 rounded-md p-1 text-[color:var(--fg-muted)] transition-colors hover:bg-[color:var(--surface-2)] hover:text-[color:var(--fg)]"
            aria-label="Close"
          >
            <X size={16} strokeWidth={1.6} />
          </RadixDialog.Close>
        )}
      </RadixDialog.Content>
    </DialogPortal>
  );
}

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("mb-4", className)} {...props} />;
}

export function DialogTitle({ className, ...props }: React.ComponentProps<typeof RadixDialog.Title>) {
  return (
    <RadixDialog.Title
      className={cn("text-base font-semibold tracking-[-0.005em] text-[color:var(--fg)]", className)}
      {...props}
    />
  );
}

export function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof RadixDialog.Description>) {
  return (
    <RadixDialog.Description
      className={cn("mt-1 text-sm text-[color:var(--fg-muted)]", className)}
      {...props}
    />
  );
}
