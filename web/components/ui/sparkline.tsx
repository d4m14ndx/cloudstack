import { useId } from "react";

/**
 * Tiny inline SVG sparkline. No deps. Pass an array of numbers and an
 * accent color (defaults to --accent via inherited currentColor + opacity).
 */
export function Sparkline({
  values,
  width = 120,
  height = 40,
  stroke = "currentColor",
  fill = "currentColor",
  fillOpacity = 0.18,
  strokeWidth = 1.5,
  className,
}: {
  values: number[];
  width?: number;
  height?: number;
  stroke?: string;
  fill?: string;
  fillOpacity?: number;
  strokeWidth?: number;
  className?: string;
}) {
  const gradientId = useId();

  if (values.length === 0) {
    return <svg width={width} height={height} className={className} aria-hidden />;
  }

  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const stepX = width / (values.length - 1 || 1);

  const points = values.map((v, i) => {
    const x = i * stepX;
    const y = height - ((v - min) / range) * height;
    return [x, y] as const;
  });

  const linePath = points
    .map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`)
    .join(" ");

  const areaPath = `${linePath} L${width.toFixed(2)} ${height} L0 ${height} Z`;

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      aria-hidden
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={fill} stopOpacity={fillOpacity} />
          <stop offset="100%" stopColor={fill} stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={areaPath} fill={`url(#${gradientId})`} />
      <path
        d={linePath}
        stroke={stroke}
        strokeWidth={strokeWidth}
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
