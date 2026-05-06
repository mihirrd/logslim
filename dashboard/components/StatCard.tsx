interface StatCardProps {
  label: string;
  value: number | string;
  sub?: string;
}

export function StatCard({ label, value, sub }: StatCardProps) {
  return (
    <div className="rounded-xl border border-[#222] bg-[#111] p-5">
      <p className="text-[#6b7280] text-xs font-medium uppercase tracking-wider mb-1">{label}</p>
      <p className="text-3xl font-semibold text-white tabular-nums">
        {typeof value === "number" ? value.toLocaleString() : value}
      </p>
      {sub && <p className="text-[#4b5563] text-xs mt-1">{sub}</p>}
    </div>
  );
}
