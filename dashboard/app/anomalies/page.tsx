"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, TemplateRow, relativeTime } from "@/lib/api";

export default function AnomaliesPage() {
  const [anomalies, setAnomalies] = useState<TemplateRow[]>([]);
  const [last, setLast] = useState("1h");
  const [loading, setLoading] = useState(true);

  const load = (window: string) => {
    setLoading(true);
    api.anomalies({ last: window })
      .then(setAnomalies)
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(last); }, [last]);

  return (
    <div className="max-w-5xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-white">Anomaly Detection</h1>
        <p className="text-[#6b7280] text-sm mt-1">Newly added templates and unusual log patterns</p>
      </div>

      <div className="flex gap-3 mb-6 items-center">
        <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider">Time Window:</label>
        <select
          value={last}
          onChange={(e) => setLast(e.target.value)}
          className="bg-[#111] border border-[#222] rounded-lg px-4 py-2 text-sm text-[#9ca3af] focus:outline-none focus:border-green-500/50 focus:ring-1 focus:ring-green-500/20"
        >
          <option value="1h">Last 1 hour</option>
          <option value="6h">Last 6 hours</option>
          <option value="24h">Last 24 hours</option>
          <option value="7d">Last 7 days</option>
        </select>
      </div>

      <div className="rounded-xl border border-[#222] overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[#222] bg-[#111]">
              <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-16">ID</th>
              <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-20">Hits</th>
              <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-32">Created</th>
              <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider">Pattern</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">Loading…</td></tr>
            )}
            {!loading && anomalies.length === 0 && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">No anomalies detected.</td></tr>
            )}
            {anomalies.map((a) => (
              <tr key={a.id} className="border-b border-[#1a1a1a] hover:bg-[#111] transition-colors">
                <td className="px-4 py-3 text-[#4b5563] font-mono text-xs">[{a.id}]</td>
                <td className="px-4 py-3 text-[#9ca3af] tabular-nums">{a.hits.toLocaleString()}</td>
                <td className="px-4 py-3 text-[#4b5563] text-xs">{relativeTime(a.createdAt)}</td>
                <td className="px-4 py-3">
                  <Link href={`/templates/${a.id}`} className="font-mono text-xs text-[#d1d5db] hover:text-green-400 transition-colors">
                    {a.pattern}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!loading && <p className="text-[#4b5563] text-xs mt-3 text-right">{anomalies.length} anomalies</p>}
    </div>
  );
}
