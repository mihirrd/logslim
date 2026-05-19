"use client";

import { FrequencyBucketData } from "@/lib/api";

interface SparklineProps {
  data: FrequencyBucketData[];
  width?: number;
  height?: number;
}

export function Sparkline({ data, width = 50, height = 20 }: SparklineProps) {
  if (!data || data.length === 0) {
    return null;
  }

  const frequencies = data.map(b => b.frequency);
  const maxFreq = Math.max(...frequencies);
  const minFreq = Math.min(...frequencies);
  const range = maxFreq - minFreq;

  const points = data.map((bucket, i) => {
    const x = (i / (data.length - 1 || 1)) * width;
    const normalized = range === 0 ? 0 : (bucket.frequency - minFreq) / range;
    const y = height - normalized * height;
    return `${x},${y}`;
  });

  const pointsStr = points.join(" ");

  // Determine color based on overall trend
  const isUptrend = data[data.length - 1].frequency >= data[0].frequency;
  const color = isUptrend ? "#ef4444" : "#3b82f6";

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className="inline"
      style={{ display: "inline-block" }}
    >
      <polyline
        points={pointsStr}
        fill="none"
        stroke={color}
        strokeWidth="1"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}
