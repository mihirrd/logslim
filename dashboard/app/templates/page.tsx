"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, TemplateRow, relativeTime } from "@/lib/api";

export default function TemplatesPage() {
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  const load = (q: string) => {
    setLoading(true);
    api.templates({ search: q || undefined, limit: 100 })
      .then(setTemplates)
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(""); }, []);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    load(search);
  };

  return (
    <div className="max-w-5xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-white">Templates</h1>
        <p className="text-[#6b7280] text-sm mt-1">Unique log patterns discovered by Drain</p>
      </div>

      <form onSubmit={handleSearch} className="flex gap-3 mb-6">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search patterns…"
          className="flex-1 bg-[#111] border border-[#222] rounded-lg px-4 py-2 text-sm text-[#e5e7eb] placeholder-[#4b5563] focus:outline-none focus:border-green-500/50 focus:ring-1 focus:ring-green-500/20"
        />
        <button
          type="submit"
          className="px-4 py-2 rounded-lg bg-green-600 hover:bg-green-500 text-white text-sm font-medium transition-colors"
        >
          Search
        </button>
        {search && (
          <button
            type="button"
            onClick={() => { setSearch(""); load(""); }}
            className="px-4 py-2 rounded-lg border border-[#222] text-[#9ca3af] hover:text-white text-sm transition-colors"
          >
            Clear
          </button>
        )}
      </form>

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
            {loading && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">Loading…</td></tr>
            )}
            {!loading && templates.length === 0 && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[#4b5563] text-sm">No templates found.</td></tr>
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
      {!loading && <p className="text-[#4b5563] text-xs mt-3 text-right">{templates.length} templates</p>}
    </div>
  );
}
