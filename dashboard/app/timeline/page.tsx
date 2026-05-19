"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, FrequencyDelta, FrequencyTrend } from "@/lib/api";
import { Sparkline } from "@/components/Sparkline";
import { relativeTime } from "@/lib/api";

const PERIOD_PRESETS = ["1h", "6h", "24h", "7d"];

export default function TimelinePage() {
  const [observed, setObserved] = useState("1h");
  const [baseline, setBaseline] = useState("1h");
  const [deltas, setDeltas] = useState<FrequencyDelta[]>([]);
  const [trends, setTrends] = useState<FrequencyTrend>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function loadData() {
    try {
      setLoading(true);
      setError(null);

      const [deltasData, trendsData] = await Promise.all([
        api.timelineCompare({ observed, baseline, limit: 50 }),
        api.timelineTrend({ window: observed, buckets: 24 }),
      ]);

      setDeltas(deltasData);
      setTrends(trendsData);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load timeline data");
      setDeltas([]);
      setTrends({});
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadData();
  }, [observed, baseline]);

  const getChangeColor = (delta: FrequencyDelta) => {
    if (delta.isNew) return "text-yellow-400";
    if (delta.disappeared) return "text-gray-500";
    if (delta.changePercent >= 0) return "text-red-500";
    return "text-blue-500";
  };

  const getChangeLabel = (delta: FrequencyDelta) => {
    if (delta.isNew) return "[ NEW ]";
    if (delta.disappeared) return "[GONE ]";
    const sign = delta.changePercent >= 0 ? "+" : "";
    return `[${sign}${delta.changePercent.toFixed(1)}%]`;
  };

  return (
    <main className="p-8">
      <div className="max-w-7xl mx-auto">
        {/* Header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-white mb-2">Timeline</h1>
          <p className="text-[#9ca3af]">Analyze template frequency changes for root cause analysis</p>
        </div>

        {/* Controls */}
        <div className="bg-[#1a1a1a] border border-[#333] rounded-lg p-6 mb-6">
          <div className="grid grid-cols-2 gap-6">
            {/* Observed Period */}
            <div>
              <label className="block text-sm font-medium text-[#e5e7eb] mb-2">
                Observed Period
              </label>
              <div className="flex gap-2 flex-wrap">
                {PERIOD_PRESETS.map((p) => (
                  <button
                    key={p}
                    onClick={() => setObserved(p)}
                    className={`px-3 py-1 rounded text-sm transition-colors ${
                      observed === p
                        ? "bg-green-500/20 text-green-400 border border-green-500/50"
                        : "bg-[#222] text-[#6b7280] hover:text-[#e5e7eb] border border-[#333] hover:border-[#444]"
                    }`}
                  >
                    {p}
                  </button>
                ))}
              </div>
            </div>

            {/* Baseline Period */}
            <div>
              <label className="block text-sm font-medium text-[#e5e7eb] mb-2">
                Baseline Period
              </label>
              <div className="flex gap-2 flex-wrap">
                {PERIOD_PRESETS.map((p) => (
                  <button
                    key={p}
                    onClick={() => setBaseline(p)}
                    className={`px-3 py-1 rounded text-sm transition-colors ${
                      baseline === p
                        ? "bg-green-500/20 text-green-400 border border-green-500/50"
                        : "bg-[#222] text-[#6b7280] hover:text-[#e5e7eb] border border-[#333] hover:border-[#444]"
                    }`}
                  >
                    {p}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Error */}
        {error && (
          <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-4 mb-6">
            <p className="text-red-400 text-sm">{error}</p>
          </div>
        )}

        {/* Loading */}
        {loading && (
          <div className="text-center py-12">
            <p className="text-[#6b7280]">Loading...</p>
          </div>
        )}

        {/* Empty State */}
        {!loading && deltas.length === 0 && !error && (
          <div className="text-center py-12">
            <p className="text-[#6b7280]">No frequency changes detected.</p>
          </div>
        )}

        {/* Results Table */}
        {!loading && deltas.length > 0 && (
          <div className="bg-[#1a1a1a] border border-[#333] rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-[#333] bg-[#0d0d0d]">
                  <th className="px-6 py-3 text-left text-[#9ca3af] font-medium w-24">Change</th>
                  <th className="px-6 py-3 text-left text-[#9ca3af] font-medium flex-1">Pattern</th>
                  <th className="px-6 py-3 text-right text-[#9ca3af] font-medium w-24">Observed</th>
                  <th className="px-6 py-3 text-right text-[#9ca3af] font-medium w-24">Baseline</th>
                  <th className="px-6 py-3 text-right text-[#9ca3af] font-medium w-32">Trend</th>
                  <th className="px-6 py-3 text-left text-[#9ca3af] font-medium w-24">Updated</th>
                </tr>
              </thead>
              <tbody>
                {deltas.map((delta) => (
                  <tr
                    key={delta.id}
                    className="border-b border-[#222] hover:bg-[#0a0a0a] transition-colors"
                  >
                    <td className={`px-6 py-4 font-mono text-xs font-medium whitespace-nowrap ${getChangeColor(delta)}`}>
                      {getChangeLabel(delta)}
                    </td>
                    <td className="px-6 py-4">
                      <Link
                        href={`/templates/${delta.id}`}
                        className="text-green-400 hover:text-green-300 hover:underline truncate block"
                        title={delta.pattern}
                      >
                        {delta.pattern.length > 60
                          ? delta.pattern.substring(0, 57) + "..."
                          : delta.pattern}
                      </Link>
                    </td>
                    <td className="px-6 py-4 text-right text-[#e5e7eb]">
                      {delta.observedCount.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 text-right text-[#9ca3af]">
                      {delta.baselineCount.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <Sparkline data={trends[delta.id] || []} width={80} height={20} />
                    </td>
                    <td className="px-6 py-4 text-xs text-[#6b7280]">
                      {relativeTime(delta.updatedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="px-6 py-4 border-t border-[#333] text-xs text-[#6b7280] bg-[#0d0d0d]">
              {deltas.length} template{deltas.length === 1 ? "" : "s"} with frequency changes
            </div>
          </div>
        )}
      </div>
    </main>
  );
}
