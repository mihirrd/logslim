"use client";

interface LogOutputProps {
  lines: string[];
  maxHeight?: string;
  emptyMessage?: string;
}

export function LogOutput({ lines, maxHeight = "400px", emptyMessage = "No logs to display." }: LogOutputProps) {
  if (lines.length === 0) {
    return (
      <div className="rounded-lg border border-[#222] bg-[#0d0d0d] p-6 text-center text-[#4b5563] text-sm font-mono">
        {emptyMessage}
      </div>
    );
  }

  return (
    <div
      className="rounded-lg border border-[#222] bg-[#0d0d0d] overflow-auto"
      style={{ maxHeight }}
    >
      <div className="p-4 space-y-0.5">
        {lines.map((line, i) => (
          <div
            key={i}
            className="font-mono text-xs text-[#d1d5db] leading-5 whitespace-pre-wrap break-all hover:bg-[#1a1a1a] px-1 rounded"
          >
            {line}
          </div>
        ))}
      </div>
      <div className="sticky bottom-0 right-0 text-right px-4 py-2 border-t border-[#222] text-xs text-[#4b5563]">
        {lines.length} line{lines.length !== 1 ? "s" : ""}
      </div>
    </div>
  );
}
