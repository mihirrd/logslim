"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, TemplateRow, relativeTime } from "@/lib/api";

export default function AnomaliesPage() {
  const [anomalies, setAnomalies] = useState<TemplateRow[]>([]);
  const [last, setLast] = useState("1h");
  const [loading, setLoading] = useState(true);
  const [sortBy, setSortBy] = useState<"hits" | "created_at">("created_at");
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("desc");

  const load = (window: string) => {
    setLoading(true);
    api.anomalies({ last: window })
      .then(setAnomalies)
      .finally(() => setLoading(false));
  };

  const toggleSort = (column: "hits" | "created_at") => {
    if (sortBy === column) {
      setSortDirection(sortDirection === "asc" ? "desc" : "asc");
    } else {
      setSortBy(column);
      setSortDirection("desc");
    }
  };

  const sortedAnomalies = [...anomalies].sort((a, b) => {
    let aVal, bVal;
    if (sortBy === "hits") {
      aVal = a.hits;
      bVal = b.hits;
    } else {
      aVal = new Date(a.createdAt).getTime();
      bVal = new Date(b.createdAt).getTime();
    }

    const comparison = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
    return sortDirection === "asc" ? comparison : -comparison;
  });

  useEffect(() => { load(last); }, [last]);

  return (
    <div className="max-w-5xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-white">Anomaly Detection</h1>
        <p className="text-[#6b7280] text-sm mt-1">Newly added templates and unusual log patterns</p>
      </div>

      <div className="flex gap-6 mb-6 items-center">
        <div className="flex gap-3 items-center">
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
        <div className="flex gap-2 items-center">
          <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider">Sort By:</label>
          <button
            onClick={() => toggleSort("created_at")}
            className={`px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
              sortBy === "created_at"
                ? "bg-green-500/20 border border-green-500/50 text-green-400"
                : "bg-[#111] border border-[#222] text-[#9ca3af] hover:border-[#333]"
            }`}
          >
            Created {sortBy === "created_at" && (sortDirection === "asc" ? "↑" : "↓")}
          </button>
          <button
            onClick={() => toggleSort("hits")}
            className={`px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
              sortBy === "hits"
                ? "bg-green-500/20 border border-green-500/50 text-green-400"
                : "bg-[#111] border border-[#222] text-[#9ca3af] hover:border-[#333]"
            }`}
          >
            Hits {sortBy === "hits" && (sortDirection === "asc" ? "↑" : "↓")}
          </button>
        </div>
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
            {!loading && sortedAnomalies.length === 0 && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">No anomalies detected.</td></tr>
            )}
            {sortedAnomalies.map((a) => (
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
      {!loading && <p className="text-[#4b5563] text-xs mt-3 text-right">{sortedAnomalies.length} anomalies</p>}
    </div>
  );
}
