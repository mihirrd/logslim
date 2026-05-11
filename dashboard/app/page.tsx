"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, Stats, TemplateRow, Suggestion, relativeTime } from "@/lib/api";
import { StatCard } from "@/components/StatCard";
import { LogOutput } from "@/components/LogOutput";

interface Filter { key: string; value: string; }

export default function Dashboard() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Query state
  const [pattern, setPattern] = useState("");
  const [filters, setFilters] = useState<Filter[]>([]);
  const [last, setLast] = useState("");
  const [results, setResults] = useState<string[] | null>(null);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [queryLoading, setQueryLoading] = useState(false);

  useEffect(() => {
    Promise.all([api.stats(), api.templates({ limit: 10 })])
      .then(([s, t]) => { setStats(s); setTemplates(t); })
      .catch(() => setError("Cannot reach LogSlim API. Is the server running? (logslim serve)"));
  }, []);

  const addFilter = () => setFilters([...filters, { key: "", value: "" }]);
  const removeFilter = (i: number) => setFilters(filters.filter((_, j) => j !== i));
  const updateFilter = (i: number, field: "key" | "value", val: string) => {
    const f = [...filters];
    f[i] = { ...f[i], [field]: val };
    setFilters(f);
  };

  const runQuery = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pattern.trim()) return;
    setQueryLoading(true);
    setSuggestions([]);
    try {
      const filterMap: Record<string, string> = {};
      filters.forEach((f) => { if (f.key) filterMap[f.key] = f.value; });
      const res = await api.query({
        pattern,
        filters: Object.keys(filterMap).length ? filterMap : undefined,
        last: last || undefined,
      });
      setResults(res);
      if (res.length === 0) {
        const sugg = await api.suggestions(pattern);
        setSuggestions(sugg);
      }
    } finally {
      setQueryLoading(false);
    }
  };

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="text-center">
          <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center mx-auto mb-4">
            <span className="text-red-400 text-xl">!</span>
          </div>
          <p className="text-[#9ca3af] text-sm max-w-sm">{error}</p>
          <code className="mt-3 block text-xs text-green-400 font-mono bg-[#111] px-3 py-1.5 rounded-lg inline-block">
            java -jar logslim.jar serve
          </code>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-5xl">
      <div className="mb-8">
        <h1 className="text-2xl font-semibold text-white">Dashboard</h1>
        <p className="text-[#6b7280] text-sm mt-1">Overview of your log storage</p>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-3 gap-4 mb-10">
        <StatCard label="Templates" value={stats?.templateCount ?? "—"} sub="unique log patterns" />
        <StatCard label="Log Entries" value={stats?.logEntryCount ?? "—"} sub="compressed log lines" />
        <StatCard label="Raw Logs" value={stats?.rawLogCount ?? "—"} sub="pre-lock / unmatched" />
      </div>

      {/* Query */}
      <div className="mb-10">
        <div className="mb-4">
          <h2 className="text-base font-medium text-white">Query</h2>
          <p className="text-[#6b7280] text-xs mt-0.5">Search logs by template pattern with optional filters</p>
        </div>

        <form onSubmit={runQuery} className="rounded-xl border border-[#222] bg-[#111] p-6 mb-4 space-y-4">
          <div>
            <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">Pattern</label>
            <input
              value={pattern}
              onChange={(e) => setPattern(e.target.value)}
              placeholder='e.g. "User {id} failed login"'
              className="w-full bg-[#0d0d0d] border border-[#222] rounded-lg px-4 py-2.5 text-sm font-mono text-[#e5e7eb] placeholder-[#374151] focus:outline-none focus:border-green-500/50"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider">Filters</label>
              <button type="button" onClick={addFilter} className="text-xs text-green-400 hover:text-green-300">+ Add filter</button>
            </div>
            {filters.map((f, i) => (
              <div key={i} className="flex gap-2 mb-2">
                <input
                  value={f.key}
                  onChange={(e) => updateFilter(i, "key", e.target.value)}
                  placeholder="key"
                  className="flex-1 bg-[#0d0d0d] border border-[#222] rounded-lg px-3 py-2 text-sm font-mono text-[#e5e7eb] placeholder-[#374151] focus:outline-none focus:border-green-500/50"
                />
                <input
                  value={f.value}
                  onChange={(e) => updateFilter(i, "value", e.target.value)}
                  placeholder="value"
                  className="flex-1 bg-[#0d0d0d] border border-[#222] rounded-lg px-3 py-2 text-sm font-mono text-[#e5e7eb] placeholder-[#374151] focus:outline-none focus:border-green-500/50"
                />
                <button type="button" onClick={() => removeFilter(i)} className="text-[#4b5563] hover:text-red-400 px-2 transition-colors">×</button>
              </div>
            ))}
          </div>

          <div className="flex items-end gap-4">
            <div className="w-40">
              <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">Time window</label>
              <select
                value={last}
                onChange={(e) => setLast(e.target.value)}
                className="w-full bg-[#0d0d0d] border border-[#222] rounded-lg px-3 py-2.5 text-sm text-[#9ca3af] focus:outline-none"
              >
                <option value="">All time</option>
                <option value="10m">Last 10 min</option>
                <option value="1h">Last 1 hour</option>
                <option value="6h">Last 6 hours</option>
                <option value="1d">Last 1 day</option>
                <option value="7d">Last 7 days</option>
              </select>
            </div>
            <button
              type="submit"
              disabled={queryLoading || !pattern.trim()}
              className="px-6 py-2.5 rounded-lg bg-green-600 hover:bg-green-500 disabled:opacity-40 text-white text-sm font-medium transition-colors"
            >
              {queryLoading ? "Searching…" : "Search"}
            </button>
          </div>
        </form>

        {results !== null && results.length === 0 && (
          <div className="rounded-xl border border-[#333] bg-[#111] p-5 mb-4">
            <p className="text-[#9ca3af] text-sm mb-3">No template matched <span className="font-mono text-white">&quot;{pattern}&quot;</span>.</p>
            {suggestions.length > 0 && (
              <>
                <p className="text-xs text-[#4b5563] mb-2">Did you mean?</p>
                <div className="space-y-1">
                  {suggestions.map((s) => (
                    <button
                      key={s.id}
                      onClick={() => setPattern(s.pattern)}
                      className="flex items-center gap-3 w-full text-left hover:bg-[#1a1a1a] rounded-lg px-3 py-2 transition-colors"
                    >
                      <span className="text-[#4b5563] font-mono text-xs w-12">[{s.id}]</span>
                      <span className="font-mono text-xs text-[#d1d5db] flex-1">{s.pattern}</span>
                      <span className="text-xs text-[#4b5563]">{s.hits.toLocaleString()} hits</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        )}

        {results !== null && results.length > 0 && (
          <LogOutput lines={results} maxHeight="500px" />
        )}
      </div>

      {/* Top templates */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-medium text-white">Top Templates</h2>
          <Link href="/templates" className="text-green-400 text-sm hover:text-green-300">
            View all →
          </Link>
        </div>

        <div className="rounded-xl border border-[#222] overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-[#222] bg-[#111]">
                <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-16">ID</th>
                <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-20">Hits</th>
                <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider w-32">Last Seen</th>
                <th className="text-left px-4 py-3 text-[#6b7280] font-medium text-xs uppercase tracking-wider">Pattern</th>
              </tr>
            </thead>
            <tbody>
              {templates.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">
                    No templates yet. Ingest some logs to get started.
                  </td>
                </tr>
              )}
              {templates.map((t) => (
                <tr key={t.id} className="border-b border-[#1a1a1a] hover:bg-[#111] transition-colors">
                  <td className="px-4 py-3 text-[#4b5563] font-mono text-xs">[{t.id}]</td>
                  <td className="px-4 py-3 text-[#9ca3af] tabular-nums">{t.hits.toLocaleString()}</td>
                  <td className="px-4 py-3 text-[#4b5563] text-xs">{relativeTime(t.updatedAt)}</td>
                  <td className="px-4 py-3">
                    <Link href={`/templates/${t.id}`} className="font-mono text-xs text-[#d1d5db] hover:text-green-400 transition-colors">
                      {t.pattern}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
