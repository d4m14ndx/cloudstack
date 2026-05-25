import { cn } from "@/lib/utils";

export type StatusKind = "running" | "stopped" | "error" | "warning" | "info" | "muted";

const colorMap: Record<StatusKind, string> = {
  running: "var(--success)",
  info:    "var(--info)",
  warning: "var(--warning)",
  error:   "var(--danger)",
  stopped: "var(--fg-faint)",
  muted:   "var(--fg-faint)",
};

export function StatusDot({
  kind,
  pulse = true,
  className,
}: {
  kind: StatusKind;
  pulse?: boolean;
  className?: string;
}) {
  const color = colorMap[kind];
  const animated = pulse && kind === "running";
  return (
    <span className={cn("relative inline-block h-2 w-2", className)}>
      <span
        className="block h-full w-full rounded-full"
        style={{ background: color }}
      />
      {animated && (
        <span
          className="pointer-events-none absolute inset-0 rounded-full"
          style={{
            background: color,
            animation: "status-pulse 2.4s ease-out infinite",
          }}
        />
      )}
    </span>
  );
}
