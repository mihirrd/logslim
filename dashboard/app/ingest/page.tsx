"use client";

import { useState } from "react";
import { api } from "@/lib/api";

export default function IngestPage() {
  const [content, setContent] = useState("");
  const [source, setSource] = useState("paste");
  const [result, setResult] = useState<{ linesProcessed: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!content.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await api.ingest({ content, source });
      setResult(res);
    } catch (err) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-3xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-white">Ingest</h1>
        <p className="text-[#6b7280] text-sm mt-1">Paste log content to process and compress</p>
      </div>

      <form onSubmit={submit} className="rounded-xl border border-[#222] bg-[#111] p-6 space-y-4">
        <div>
          <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">Source name</label>
          <input
            value={source}
            onChange={(e) => setSource(e.target.value)}
            placeholder="e.g. app-server, worker-1"
            className="w-64 bg-[#0d0d0d] border border-[#222] rounded-lg px-4 py-2.5 text-sm text-[#e5e7eb] placeholder-[#374151] focus:outline-none focus:border-green-500/50"
          />
        </div>

        <div>
          <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">Log content</label>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder={"2024-01-15 10:30:45 INFO User alice logged in\n2024-01-15 10:31:12 ERROR Connection timeout from 192.168.1.1\n…"}
            rows={16}
            className="w-full bg-[#0d0d0d] border border-[#222] rounded-lg px-4 py-3 text-xs font-mono text-[#d1d5db] placeholder-[#374151] focus:outline-none focus:border-green-500/50 resize-y"
          />
          <p className="text-xs text-[#374151] mt-1">{content.split("\n").filter(l => l.trim()).length} lines</p>
        </div>

        <div className="flex items-center gap-4">
          <button
            type="submit"
            disabled={loading || !content.trim()}
            className="px-6 py-2.5 rounded-lg bg-green-600 hover:bg-green-500 disabled:opacity-40 text-white text-sm font-medium transition-colors"
          >
            {loading ? "Processing…" : "Ingest logs"}
          </button>
          {content && (
            <button
              type="button"
              onClick={() => { setContent(""); setResult(null); }}
              className="text-sm text-[#4b5563] hover:text-[#9ca3af] transition-colors"
            >
              Clear
            </button>
          )}
        </div>
      </form>

      {result && (
        <div className="mt-4 rounded-xl border border-green-500/20 bg-green-500/5 px-5 py-4">
          <p className="text-green-400 font-medium text-sm">
            ✓ Processed {result.linesProcessed.toLocaleString()} line{result.linesProcessed !== 1 ? "s" : ""}
          </p>
          <p className="text-[#6b7280] text-xs mt-1">Templates updated. Visit the <a href="/templates" className="text-green-400 hover:text-green-300">Templates</a> page to inspect results.</p>
        </div>
      )}

      {error && (
        <div className="mt-4 rounded-xl border border-red-500/20 bg-red-500/5 px-5 py-4">
          <p className="text-red-400 text-sm">{error}</p>
        </div>
      )}
    </div>
  );
}
