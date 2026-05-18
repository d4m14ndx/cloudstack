import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merge tailwind classes with conflict resolution.
 * Usage: cn("p-4", isActive && "bg-accent", className)
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Hash a string to a stable integer (used for avatar color picking).
 */
export function hashString(input: string): number {
  let h = 0;
  for (let i = 0; i < input.length; i++) {
    h = (h << 5) - h + input.charCodeAt(i);
    h |= 0;
  }
  return Math.abs(h);
}

/**
 * Format a relative time string (e.g. "2m ago", "3h ago").
 */
export function relativeTime(date: Date | number | string): string {
  const d = typeof date === "object" ? date : new Date(date);
  const seconds = Math.floor((Date.now() - d.getTime()) / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

/**
 * Format bytes -> human-readable (KiB / MiB / GiB / TiB).
 */
export function formatBytes(bytes: number, decimals = 1): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(decimals)} ${units[i]}`;
}

/**
 * Format a price ($0.058/hr).
 */
export function formatHourly(usdPerHour: number): string {
  return `$${usdPerHour.toFixed(3)}/hr`;
}
