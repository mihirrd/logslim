"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { LogOutput } from "@/components/LogOutput";
import { DateTimePicker } from "@/components/DateTimePicker";

type Mode = "last" | "range";

export default function ReplayPage() {
  const [mode, setMode] = useState<Mode>("last");
  const [last, setLast] = useState("1h");
  const [from, setFrom] = useState<Date | undefined>();
  const [to, setTo] = useState<Date | undefined>();
  const [results, setResults] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(false);

  const run = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      let res: string[];
      if (mode === "last") {
        res = await api.replay({ last });
      } else {
        res = await api.replay({
          from: from?.toISOString(),
          to: to?.toISOString(),
        });
      }
      setResults(res);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-4xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-white">Replay</h1>
        <p className="text-[#6b7280] text-sm mt-1">Retrieve original log lines in chronological order</p>
      </div>

      <form onSubmit={run} className="rounded-xl border border-[#222] bg-[#111] p-6 mb-6 space-y-5">
        {/* Mode toggle */}
        <div className="flex rounded-lg border border-[#222] overflow-hidden w-fit">
          {(["last", "range"] as Mode[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              className={`px-4 py-2 text-sm transition-colors ${
                mode === m
                  ? "bg-green-600 text-white"
                  : "text-[#6b7280] hover:text-white bg-transparent"
              }`}
            >
              {m === "last" ? "Relative window" : "Absolute range"}
            </button>
          ))}
        </div>

        {mode === "last" ? (
          <div className="w-48">
            <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">Window</label>
            <select
              value={last}
              onChange={(e) => setLast(e.target.value)}
              className="w-full bg-[#0d0d0d] border border-[#222] rounded-lg px-3 py-2.5 text-sm text-[#9ca3af] focus:outline-none"
            >
              <option value="10m">Last 10 minutes</option>
              <option value="1h">Last 1 hour</option>
              <option value="6h">Last 6 hours</option>
              <option value="1d">Last 1 day</option>
              <option value="7d">Last 7 days</option>
              <option value="30d">Last 30 days</option>
              <option value="9999d">All time</option>
            </select>
          </div>
        ) : (
          <div className="flex gap-6">
            <div className="flex-1">
              <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">From</label>
              <DateTimePicker value={from} onChange={setFrom} placeholder="Start date & time" />
            </div>
            <div className="flex-1">
              <label className="text-xs text-[#6b7280] font-medium uppercase tracking-wider block mb-1.5">To</label>
              <DateTimePicker value={to} onChange={setTo} placeholder="End date & time" />
            </div>
          </div>
        )}

        <button
          type="submit"
          disabled={loading}
          className="px-6 py-2.5 rounded-lg bg-green-600 hover:bg-green-500 disabled:opacity-40 text-white text-sm font-medium transition-colors"
        >
          {loading ? "Loading…" : "Replay"}
        </button>
      </form>

      {results !== null && (
        results.length === 0
          ? <p className="text-[#4b5563] text-sm">No logs found in the specified window.</p>
          : <LogOutput lines={results} maxHeight="600px" />
      )}
    </div>
  );
}
