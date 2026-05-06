"use client";

import { useState, useRef, useEffect } from "react";
import { DayPicker } from "react-day-picker";
import { format, isValid } from "date-fns";
import "react-day-picker/style.css";

interface Props {
  value: Date | undefined;
  onChange: (date: Date | undefined) => void;
  placeholder?: string;
}

export function DateTimePicker({ value, onChange, placeholder = "Pick date & time" }: Props) {
  const [open, setOpen] = useState(false);
  const [timeStr, setTimeStr] = useState(() =>
    value && isValid(value) ? format(value, "HH:mm") : "00:00"
  );
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handler(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  const applyTime = (day: Date, t: string): Date => {
    const [h, m] = t.split(":").map(Number);
    const d = new Date(day);
    d.setHours(h || 0, m || 0, 0, 0);
    return d;
  };

  const handleDaySelect = (day: Date | undefined) => {
    if (!day) { onChange(undefined); return; }
    onChange(applyTime(day, timeStr));
  };

  const handleTimeChange = (t: string) => {
    setTimeStr(t);
    if (value && isValid(value)) onChange(applyTime(value, t));
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="w-full bg-[#0d0d0d] border border-[#222] rounded-lg px-3 py-2.5 text-sm text-left focus:outline-none focus:border-green-500/50 hover:border-[#333] transition-colors flex items-center gap-2"
      >
        <span className="text-[#4b5563] text-base">📅</span>
        {value && isValid(value) ? (
          <span className="text-[#e5e7eb] font-mono">{format(value, "MMM d, yyyy  HH:mm")}</span>
        ) : (
          <span className="text-[#374151]">{placeholder}</span>
        )}
      </button>

      {open && (
        <div className="absolute top-full mt-2 left-0 z-50 bg-[#111] border border-[#333] rounded-xl shadow-2xl overflow-hidden min-w-[280px]">
          <div className="rdp-dark px-2 pt-2">
            <DayPicker
              mode="single"
              selected={value}
              onSelect={handleDaySelect}
              defaultMonth={value}
            />
          </div>
          <div className="border-t border-[#222] px-4 py-3 flex items-center gap-3 bg-[#0d0d0d]">
            <span className="text-xs text-[#6b7280] uppercase tracking-wider font-medium shrink-0">Time</span>
            <input
              type="time"
              value={timeStr}
              onChange={(e) => handleTimeChange(e.target.value)}
              className="flex-1 bg-[#111] border border-[#222] rounded-lg px-3 py-1.5 text-sm text-[#e5e7eb] focus:outline-none focus:border-green-500/50 font-mono"
            />
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="px-4 py-1.5 bg-green-600 hover:bg-green-500 text-white text-xs rounded-lg font-medium transition-colors shrink-0"
            >
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
