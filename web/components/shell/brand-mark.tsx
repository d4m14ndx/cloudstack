/**
 * BrandMark — stylized cloud-over-server-bars glyph for the product UI chrome.
 * Drawn in currentColor so the gradient backing can tint it.
 * For formal/legal contexts, use the "Apache CloudStack" wordmark instead.
 */
export function BrandMark({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className={className}
      aria-hidden
    >
      <path
        d="M5 11C5 8.5 6.8 6.5 9.3 6.5C10.7 4.5 13.3 4.5 14.7 6.5C16.7 6.5 18.3 8 18.5 10C19.9 10.3 21 11.5 21 13C21 14.7 19.7 16 18 16H8C6.3 16 5 14.7 5 13C5 12.3 5.2 11.6 5.5 11Z"
        fill="currentColor"
        opacity="0.92"
      />
      <rect x="6" y="18" width="3"  height="1.5" rx="0.5" fill="currentColor" opacity="0.72" />
      <rect x="10.5" y="18" width="3" height="1.5" rx="0.5" fill="currentColor" opacity="0.72" />
      <rect x="15" y="18" width="3"  height="1.5" rx="0.5" fill="currentColor" opacity="0.72" />
    </svg>
  );
}

/**
 * Wordmark — "cloudstack" with bold first half, lighter back half.
 */
export function BrandWordmark({ className }: { className?: string }) {
  return (
    <span className={className}>
      <span className="font-semibold">cloud</span>
      <span className="font-normal opacity-70">stack</span>
    </span>
  );
}
