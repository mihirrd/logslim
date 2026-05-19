"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const nav = [
  { href: "/", label: "Dashboard", icon: "▦" },
  { href: "/templates", label: "Templates", icon: "≡" },
  { href: "/anomalies", label: "Anomalies", icon: "⚠" },
  { href: "/timeline", label: "Timeline", icon: "⊢" },
  { href: "/replay", label: "Replay", icon: "⏴" },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="fixed top-0 left-0 h-screen w-52 flex flex-col border-r border-[#222] bg-[#0d0d0d] z-10">
      {/* Logo */}
      <div className="px-5 py-5 border-b border-[#222]">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-md bg-green-500 flex items-center justify-center text-black font-bold text-sm">
            L
          </div>
          <span className="text-white font-semibold text-base tracking-tight">LogSlim</span>
        </div>
        <p className="text-[#4b5563] text-xs mt-1">Log compression and analysis engine</p>
      </div>

      {/* Nav */}
      <nav className="flex-1 px-3 py-4 space-y-0.5">
        {nav.map(({ href, label, icon }) => {
          const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
          return (
            <Link
              key={href}
              href={href}
              className={`flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors ${active
                ? "bg-green-500/10 text-green-400 font-medium"
                : "text-[#6b7280] hover:text-[#e5e7eb] hover:bg-[#1a1a1a]"
                }`}
            >
              <span className="text-base w-4 text-center">{icon}</span>
              {label}
            </Link>
          );
        })}
      </nav>

      {/* Footer */}
      <div className="px-5 py-4 border-t border-[#222]">
        <p className="text-[#374151] text-xs">v2.0.0</p>
      </div>
    </aside>
  );
}
