const BASE = "http://localhost:8080/api";

function qs(params: Record<string, string | number | undefined>) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== "") p.set(k, String(v));
  }
  return p.toString();
}

async function post<T>(url: string, body: unknown): Promise<T> {
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  return r.json();
}

export interface Stats {
  templateCount: number;
  logEntryCount: number;
  rawLogCount: number;
}

export interface TemplateRow {
  id: number;
  pattern: string;
  hits: number;
  createdAt: number;
  updatedAt: number;
}

export interface SlotTopValue {
  value: string;
  count: number;
}

export interface SlotStat {
  index: number;
  type: string;
  distinct: number;
  topValues: SlotTopValue[];
}

export interface RecentLog {
  ts: string;
  line: string;
}

export interface TemplateDetail extends TemplateRow {
  slotStats: SlotStat[];
  recentLogs: RecentLog[];
}

export interface Suggestion {
  id: number;
  pattern: string;
  hits: number;
}

export const api = {
  stats: (): Promise<Stats> =>
    fetch(`${BASE}/stats`).then((r) => r.json()),

  templates: (params: {
    search?: string;
    limit?: number;
    last?: string;
  }): Promise<TemplateRow[]> =>
    fetch(`${BASE}/templates?${qs(params)}`).then((r) => r.json()),

  inspect: (id: number, recent = 10): Promise<TemplateDetail> =>
    fetch(`${BASE}/templates/${id}?recent=${recent}`).then((r) => r.json()),

  query: (body: {
    pattern: string;
    filters?: Record<string, string>;
    last?: string;
  }): Promise<string[]> => post(`${BASE}/query`, body),

  replay: (params: {
    from?: string;
    to?: string;
    last?: string;
  }): Promise<string[]> =>
    fetch(`${BASE}/replay?${qs(params)}`).then((r) => r.json()),

  suggestions: (pattern: string): Promise<Suggestion[]> =>
    fetch(`${BASE}/suggestions?pattern=${encodeURIComponent(pattern)}`).then((r) => r.json()),
};

export function relativeTime(epochMs: number): string {
  const secs = Math.floor((Date.now() - epochMs) / 1000);
  if (secs < 0) return "just now";
  if (secs < 60) return `${secs} sec ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)} hrs ago`;
  if (secs < 2592000) return `${Math.floor(secs / 86400)} days ago`;
  if (secs < 31536000) return `${Math.floor(secs / 2592000)} months ago`;
  return `${Math.floor(secs / 31536000)} years ago`;
}
