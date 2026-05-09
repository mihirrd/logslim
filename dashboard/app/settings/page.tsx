"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { ConfirmDialog } from "@/components/ConfirmDialog";

export default function SettingsPage() {
  const [compactDialog, setCompactDialog] = useState(false);
  const [compactResult, setCompactResult] = useState<string | null>(null);
  const [compactLoading, setCompactLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const doCompact = async () => {
    setCompactDialog(false);
    setCompactLoading(true);
    setError(null);
    try {
      const res = await api.compact();
      setCompactResult(res.message);
    } catch (e) {
      setError("Compact failed: " + String(e));
    } finally {
      setCompactLoading(false);
    }
  };

  return (
    <div className="max-w-2xl">
      <div className="mb-8">
        <h1 className="text-2xl font-semibold text-white">Settings</h1>
        <p className="text-[#6b7280] text-sm mt-1">Database management operations</p>
      </div>

      {error && (
        <div className="mb-6 rounded-xl border border-red-500/20 bg-red-500/5 px-5 py-4">
          <p className="text-red-400 text-sm">{error}</p>
        </div>
      )}

      {/* Compact */}
      <div className="rounded-xl border border-[#222] bg-[#111] p-6 mb-4">
        <div className="flex items-start justify-between">
          <div className="flex-1 mr-6">
            <h2 className="text-white font-medium mb-1">Compact database</h2>
            <p className="text-[#6b7280] text-sm">
              Export all tables to Parquet (zstd) and replace them with read-only views.
              Reduces storage by ~80%. The database becomes read-only after this operation.
            </p>
            {compactResult && (
              <p className="mt-3 text-xs text-green-400 bg-green-500/5 rounded-lg px-3 py-2 border border-green-500/20">
                ✓ {compactResult}
              </p>
            )}
          </div>
          <button
            onClick={() => setCompactDialog(true)}
            disabled={compactLoading}
            className="px-4 py-2 rounded-lg border border-[#333] text-[#9ca3af] hover:border-green-500/50 hover:text-green-400 text-sm transition-colors shrink-0 disabled:opacity-40"
          >
            {compactLoading ? "Compacting…" : "Compact"}
          </button>
        </div>
      </div>

      <ConfirmDialog
        open={compactDialog}
        title="Compact database"
        message="This will export all tables to Parquet files and replace them with read-only views. The database will become read-only. Proceed?"
        confirmLabel="Compact"
        onConfirm={doCompact}
        onCancel={() => setCompactDialog(false)}
      />
    </div>
  );
}
