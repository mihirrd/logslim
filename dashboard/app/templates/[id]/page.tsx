"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api, TemplateDetail, relativeTime } from "@/lib/api";
import { LogOutput } from "@/components/LogOutput";

function absDate(ms: number) {
  return new Date(ms).toLocaleDateString("en-US", {
    month: "short", day: "numeric", year: "numeric", timeZone: "UTC",
  });
}

export default function InspectPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [detail, setDetail] = useState<TemplateDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [recent, setRecent] = useState(10);

  const load = (n: number) => {
    setLoading(true);
    api.inspect(Number(id), n)
      .then(setDetail)
      .catch(() => router.push("/templates"))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(recent); }, [id]);

  if (loading) return <div className="text-[#4b5563] text-sm p-8">Loading…</div>;
  if (!detail) return null;

  const logLines = detail.recentLogs.map((l) => `${l.ts}  ${l.line}`);

  return (
    <div className="max-w-4xl">
      {/* Back */}
      <button onClick={() => router.push("/templates")} className="text-[#4b5563] hover:text-[#9ca3af] text-sm mb-6 flex items-center gap-1 transition-colors">
        ← Templates
      </button>

      {/* Header */}
      <div className="rounded-xl border border-[#222] bg-[#111] p-6 mb-6">
        <div className="flex items-start justify-between mb-4">
          <div>
            <span className="text-xs text-[#4b5563] font-mono">Template #{detail.id}</span>
            <h1 className="text-lg font-mono text-white mt-1 break-all">{detail.pattern}</h1>
          </div>
          <span className="text-2xl font-semibold text-white tabular-nums ml-6 shrink-0">
            {detail.hits.toLocaleString()}
            <span className="text-xs text-[#4b5563] font-normal ml-1">hits</span>
          </span>
        </div>
        <div className="flex gap-6 text-xs text-[#4b5563]">
          <span>Created: <span className="text-[#9ca3af]">{absDate(detail.createdAt)}</span> ({relativeTime(detail.createdAt)})</span>
          <span>Updated: <span className="text-[#9ca3af]">{absDate(detail.updatedAt)}</span> ({relativeTime(detail.updatedAt)})</span>
        </div>
      </div>

      {/* Slot stats */}
      {detail.slotStats.length > 0 && (
        <div className="rounded-xl border border-[#222] bg-[#111] p-6 mb-6">
          <h2 className="text-sm font-medium text-white mb-4">Parameters</h2>
          <div className="space-y-3">
            {detail.slotStats.map((s) => (
              <div key={s.index} className="flex items-start gap-4">
                <span className="font-mono text-xs text-green-400 w-20 shrink-0 pt-0.5">
                  [{s.index}] {"{"}
                  {s.type}
                  {"}"}
                </span>
                <div className="flex-1">
                  <span className="text-xs text-[#6b7280]">{s.distinct.toLocaleString()} distinct</span>
                  {s.topValues.length > 0 && (
                    <span className="text-xs text-[#4b5563] ml-2">
                      — top:{" "}
                      {s.topValues.map((v, i) => (
                        <span key={i}>
                          <span className="text-[#d1d5db] font-mono">{v.value}</span>
                          <span className="text-[#374151]"> ({v.count}×)</span>
                          {i < s.topValues.length - 1 ? ", " : ""}
                        </span>
                      ))}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Recent logs */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-medium text-white">
            Recent logs <span className="text-[#4b5563] font-normal">({detail.recentLogs.length} of {detail.hits.toLocaleString()})</span>
          </h2>
          <select
            value={recent}
            onChange={(e) => { const n = Number(e.target.value); setRecent(n); load(n); }}
            className="bg-[#111] border border-[#222] rounded-lg px-3 py-1.5 text-xs text-[#9ca3af] focus:outline-none"
          >
            {[10, 25, 50, 100].map((n) => <option key={n} value={n}>Show {n}</option>)}
          </select>
        </div>
        <LogOutput lines={logLines} maxHeight="500px" emptyMessage="No log entries for this template." />
      </div>
    </div>
  );
}
