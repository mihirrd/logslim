#!/usr/bin/env node
/**
 * LogSlim MCP server.
 *
 * Exposes LogSlim's structured-log API to AI agents as tools. The design
 * principle: an agent should navigate logs the way a human SRE does —
 * overview -> anomaly -> drill -> raw — and pull raw lines only for the
 * narrow window it actually needs. Every tool except `replay` returns
 * token-cheap *structure* (templates + counts), not raw log text.
 *
 * Config (env vars):
 *   LOGSLIM_API_URL   base URL of `logslim serve` (default http://localhost:8080)
 *   LOGSLIM_API_KEY   optional; sent as `X-API-Key` if set
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const API_URL = (process.env.LOGSLIM_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const API_KEY = process.env.LOGSLIM_API_KEY;

type Json = unknown;

/** Single point for HTTP calls to the LogSlim API. */
async function api(
  method: "GET" | "POST",
  path: string,
  opts: { query?: Record<string, string | number | undefined>; body?: Json } = {},
): Promise<Json> {
  const url = new URL(API_URL + path);
  for (const [k, v] of Object.entries(opts.query ?? {})) {
    if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
  }

  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (API_KEY) headers["X-API-Key"] = API_KEY;

  const res = await fetch(url, {
    method,
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`LogSlim API ${method} ${path} -> ${res.status} ${res.statusText}${text ? `: ${text.slice(0, 500)}` : ""}`);
  }
  return res.json();
}

/**
 * Truncate an array response to protect the agent's context budget. When the
 * underlying list exceeds `max`, return a wrapper that makes the truncation
 * explicit — so the agent knows the result is partial and should narrow its
 * window/filter rather than assume it saw everything. Non-arrays pass through.
 */
function cap(data: Json, max: number, hint: string): Json {
  if (Array.isArray(data) && data.length > max) {
    return {
      truncated: true,
      returned: max,
      totalAvailable: data.length,
      hint,
      items: data.slice(0, max),
    };
  }
  return data;
}

/** Wrap a tool body so API/network errors come back as a clean MCP error result. */
function tool(fn: (args: any) => Promise<Json>) {
  return async (args: any) => {
    try {
      const result = await fn(args);
      return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      return {
        isError: true,
        content: [
          {
            type: "text" as const,
            text:
              `Error talking to LogSlim at ${API_URL}: ${msg}\n\n` +
              `Is \`logslim serve\` running and reachable? Set LOGSLIM_API_URL to override.`,
          },
        ],
      };
    }
  };
}

const server = new McpServer(
  { name: "logslim", version: "0.1.0" },
  {
    instructions:
      "LogSlim exposes a service's logs as STRUCTURE (templates + counts), not raw text, so " +
      "you can reason over millions of lines without reading them. The tools are atomic " +
      "primitives — you compose them.\n\n" +
      "Default investigation workflow — escalate only as needed:\n" +
      "1. ORIENT: `list_templates` (and `get_stats`) to see what the service logs.\n" +
      "2. DETECT a spike: call `template_counts` for the suspect window AND for an equal-length " +
      "window just before it, then diff — a template frequent in the window but rare/absent in " +
      "the baseline is the anomaly. Also call `new_templates` for the window: a never-before-seen " +
      "pattern is a prime root-cause candidate.\n" +
      "3. LOCALIZE: `template_timeseries` on a suspect template to pinpoint the onset minute.\n" +
      "4. DRILL: `inspect_template` gives slot-value distributions + numeric summaries " +
      "(min/max/avg/p95) WITHOUT pulling rows — use it for any 'which values / how many / what " +
      "distribution' question. Only when you need specific occurrences, `query_logs` (structured " +
      "by default); `raw_sample` for unmatched/novel lines.\n" +
      "5. GROUND TRUTH: `replay` ONLY as a last resort, on a NARROW window, for exact raw lines. " +
      "It is the most token-expensive tool.\n\n" +
      "The root cause is usually a low-volume, early signal (one new template), not the loudest " +
      "one (thousands of downstream errors). Prefer narrowing windows and adding filters over " +
      "raising limits. Results flagged `truncated: true` are partial — narrow, don't assume you saw everything.",
  },
);

// ---------------------------------------------------------------------------
// template_counts — the frequency primitive. Per-template occurrence counts in
// a window, highest first. Detect spikes by calling it twice (incident window
// vs. a prior baseline window) and diffing: templates present in the window but
// near-zero in the baseline are your spikes / root-cause candidates.
// ---------------------------------------------------------------------------
server.registerTool(
  "template_counts",
  {
    title: "Count templates in a window",
    description:
      "Per-template occurrence counts within a time window, highest first. The core signal for " +
      "'what is happening / what changed'. To find a SPIKE, call this twice — once for the " +
      "suspect window and once for an equal-length window just before it — and compare: a " +
      "template that is frequent in the window but absent/rare in the baseline is the anomaly. " +
      "Use `window` from/to OR a relative `last`.",
    inputSchema: {
      from: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) start."),
      to: z.string().optional().describe("End (defaults to now)."),
      last: z.string().optional().describe("Relative window if from/to omitted, e.g. '5m', '1h' (default 1h)."),
    },
  },
  tool(async ({ from, to, last }) =>
    cap(
      await api("GET", "/api/template-counts", { query: { from, to, last } }),
      200,
      "More templates exist. Narrow the window to focus on the spike.",
    ),
  ),
);

// ---------------------------------------------------------------------------
// new_templates — the trigger primitive. Templates whose FIRST-EVER occurrence
// (by log-event time) falls inside the window. A pattern the system has never
// emitted before is disproportionately likely to be the root cause.
// ---------------------------------------------------------------------------
server.registerTool(
  "new_templates",
  {
    title: "Find templates first seen in a window",
    description:
      "Templates whose earliest occurrence in all of history falls inside the given window — " +
      "i.e. log shapes that appeared for the first time here. These are prime root-cause " +
      "candidates (a new error type, a new migration line, a circuit-breaker message). Most " +
      "useful with a NARROW window around the suspected onset; a wide window will flag many.",
    inputSchema: {
      from: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) start."),
      to: z.string().optional().describe("End (defaults to now)."),
      last: z.string().optional().describe("Relative window if from/to omitted (default 1h)."),
    },
  },
  tool(async ({ from, to, last }) =>
    cap(
      await api("GET", "/api/new-templates", { query: { from, to, last } }),
      200,
      "Many templates first appeared in this window — narrow it around the suspected onset.",
    ),
  ),
);

// ---------------------------------------------------------------------------
// list_templates — the agent's `ls`. The whole service's behavior as patterns.
// ---------------------------------------------------------------------------
server.registerTool(
  "list_templates",
  {
    title: "List log templates",
    description:
      "Lists the distinct log templates (patterns with the variable parts masked) and their " +
      "hit counts. This is the compact overview of everything the service logs — typically " +
      "tens of patterns standing in for millions of lines. Use `search` to find a pattern by " +
      "text, or `last` to restrict to a recent window.",
    inputSchema: {
      search: z.string().optional().describe("Substring to match against template text."),
      last: z.string().optional().describe("Relative window, e.g. '1h', '7d'. Ignored if `search` is set."),
      limit: z.number().int().min(1).max(200).optional().describe("Max templates to return (default 20)."),
    },
  },
  tool(async ({ search, last, limit }) =>
    cap(
      await api("GET", "/api/templates", { query: { search, last, limit } }),
      200,
      "More templates exist. Use `search` or a smaller `last` window to focus.",
    ),
  ),
);

// ---------------------------------------------------------------------------
// inspect_template — drill into one pattern: slot distributions + examples.
// ---------------------------------------------------------------------------
server.registerTool(
  "inspect_template",
  {
    title: "Inspect a template (slot distributions)",
    description:
      "Per-slot STRUCTURE for one template, no rows pulled: for each variable position, the " +
      "distinct-value count, the top values with counts, and — for numeric slots (durations, " +
      "sizes, counts) — a summary (min / max / avg / p50 / p95). This is the cheapest way to " +
      "answer 'WHICH values / HOW MANY distinct / what DISTRIBUTION' — use it BEFORE `query_logs`, " +
      "which pulls per-occurrence data. By default returns no example lines; set `recent` > 0 " +
      "only when you actually need reconstructed sample lines.",
    inputSchema: {
      templateId: z.number().int().describe("Template id from list_templates / template_counts."),
      topN: z.number().int().min(1).max(50).optional().describe("Top values to return per slot (default 10)."),
      recent: z.number().int().min(0).max(100).optional().describe("Reconstructed example lines (default 0 = none; token-expensive)."),
    },
  },
  tool(({ templateId, topN, recent }) =>
    api("GET", `/api/templates/${templateId}`, { query: { topN: topN ?? 10, recent: recent ?? 0 } }),
  ),
);

// ---------------------------------------------------------------------------
// template_timeseries — the onset primitive. Bucketed counts for ONE template
// over time, to pinpoint exactly when it started spiking.
// ---------------------------------------------------------------------------
server.registerTool(
  "template_timeseries",
  {
    title: "Time series for one template",
    description:
      "Bucketed occurrence counts for a single template over time. Use it to pinpoint the " +
      "minute a pattern started spiking (the onset), which then bounds where to look with " +
      "`new_templates` or `replay`.",
    inputSchema: {
      templateId: z.number().int().describe("Template id from template_counts / list_templates."),
      from: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) start."),
      to: z.string().optional().describe("End (defaults to now)."),
      last: z.string().optional().describe("Relative window if from/to omitted (default 1h)."),
      bucket: z.string().optional().describe("Bucket size, e.g. '1m', '5m', '1h' (default '1m')."),
    },
  },
  tool(({ templateId, from, to, last, bucket }) =>
    api("GET", `/api/templates/${templateId}/timeseries`, { query: { from, to, last, bucket } }),
  ),
);

// ---------------------------------------------------------------------------
// raw_sample — the unmatched-lines primitive. A small sample of raw_logs lines
// (novel formats, stack-trace bodies, pre-lock lines) in a window. Cheaper and
// more targeted than a full `replay`.
// ---------------------------------------------------------------------------
server.registerTool(
  "raw_sample",
  {
    title: "Sample unmatched raw logs",
    description:
      "Returns a small sample of raw log lines that never matched a template (novel formats, " +
      "one-off errors, stack-trace bodies) in a window, optionally filtered by `search`. These " +
      "are the 'unknown unknowns'. Use this before a full `replay` — it is far cheaper.",
    inputSchema: {
      from: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) start."),
      to: z.string().optional().describe("End (defaults to now)."),
      last: z.string().optional().describe("Relative window if from/to omitted (default 1h)."),
      search: z.string().optional().describe("Substring filter on the raw line."),
      limit: z.number().int().min(1).max(500).optional().describe("Max lines (default 20)."),
    },
  },
  tool(async ({ from, to, last, search, limit }) =>
    cap(
      await api("GET", "/api/raw-logs", { query: { from, to, last, search, limit: limit ?? 20 } }),
      100,
      "More unmatched lines exist. Add a `search` term or narrow the window.",
    ),
  ),
);

// ---------------------------------------------------------------------------
// query_logs — filter occurrences of a specific pattern by slot value.
// ---------------------------------------------------------------------------
server.registerTool(
  "query_logs",
  {
    title: "Query occurrences of a pattern (structured)",
    description:
      "Returns occurrences of a template, optionally filtered by slot value (an indexed lookup, " +
      "not a text scan). By DEFAULT returns STRUCTURE — the template stated once plus the " +
      "per-occurrence parameter tuples — which is far cheaper than reconstructed lines because " +
      "the static skeleton isn't repeated per row. Use `slots` to project to just the columns " +
      "you need (e.g. ['user']). Set `format:'lines'` only if you genuinely need rendered text. " +
      "For 'which values / how many', prefer `inspect_template` (slot distributions) over pulling occurrences.",
    inputSchema: {
      pattern: z.string().describe("Template pattern text, e.g. 'User {id} failed login'."),
      filters: z.record(z.string()).optional().describe("Slot filters as {name: value}, e.g. {\"id\": \"456\"}."),
      last: z.string().optional().describe("Relative window, e.g. '24h', '7d'."),
      limit: z.number().int().min(1).max(5000).optional().describe("Max occurrences (default 200). Narrow with `filters`/`slots` rather than raising this."),
      slots: z.array(z.string()).optional().describe("Project to only these slot names, e.g. ['user','pool_wait']. Omit for all slots."),
      format: z.enum(["structured", "lines"]).optional().describe("'structured' (default): template + param tuples. 'lines': reconstructed full text (token-expensive)."),
    },
  },
  tool(async ({ pattern, filters, last, limit, slots, format }) => {
    if (format === "lines") {
      return cap(
        await api("POST", "/api/query", { body: { pattern, filters: filters ?? {}, last, limit: limit ?? 200 } }),
        200,
        "More matches exist. Add a slot `filter`, or use the default structured format to see more cheaply.",
      );
    }
    // Structured (default): template stated once + parameter tuples; cap the occurrence list.
    const res: any = await api("POST", "/api/query-structured", {
      body: { pattern, filters: filters ?? {}, last, limit: limit ?? 200, slots },
    });
    if (res && Array.isArray(res.occurrences) && res.occurrences.length > 200) {
      res.occurrences = res.occurrences.slice(0, 200);
      res.truncated = true;
      res.hint = "Occurrences truncated to 200. Add a slot `filter`, project with `slots`, or narrow `last`.";
    }
    return res;
  }),
);

// ---------------------------------------------------------------------------
// replay — the losslessness escape hatch. Raw bytes. Token-expensive: keep it narrow.
// ---------------------------------------------------------------------------
server.registerTool(
  "replay",
  {
    title: "Replay exact raw log lines",
    description:
      "Reconstructs the EXACT original log lines for a window, in timestamp order (templates " +
      "and unmatched raw lines interleaved). This is the ground-truth escape hatch — but it " +
      "returns raw text and is the most TOKEN-EXPENSIVE tool. Use it LAST and on a NARROW " +
      "window, after `investigate`/`list_templates` have told you where to look. Keep `limit` small.",
    inputSchema: {
      from: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) start."),
      to: z.string().optional().describe("ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC) end."),
      last: z.string().optional().describe("Relative window if from/to omitted, e.g. '5m'."),
      limit: z.number().int().min(1).max(5000).optional().describe("Max lines (default 200; hard cap protects your context budget)."),
    },
  },
  tool(async ({ from, to, last, limit }) =>
    cap(
      await api("GET", "/api/replay", { query: { from, to, last, limit: limit ?? 200 } }),
      200,
      "Window truncated to the first 200 lines. Replay a narrower time range to see all of it in order.",
    ),
  ),
);

// ---------------------------------------------------------------------------
// get_stats — store sizes; cheap orientation.
// ---------------------------------------------------------------------------
server.registerTool(
  "get_stats",
  {
    title: "Get store statistics",
    description: "Returns counts: number of templates, structured log entries, and unmatched raw logs. Cheap orientation call.",
    inputSchema: {},
  },
  tool(() => api("GET", "/api/stats")),
);

// ---------------------------------------------------------------------------
// Prompt: scaffolds the structured-first investigation workflow for the agent.
// ---------------------------------------------------------------------------
server.registerPrompt(
  "investigate_incident",
  {
    title: "Investigate an incident",
    description: "Guided, token-efficient incident investigation over LogSlim's structured logs.",
    argsSchema: {
      window: z.string().optional().describe("Relative window, e.g. '15m', '1h'."),
      from: z.string().optional().describe("Absolute start (ISO-8601 or 'yyyy-MM-dd HH:mm:ss' UTC)."),
      to: z.string().optional().describe("Absolute end."),
    },
  },
  ({ window, from, to }) => {
    const scope = from || to
      ? `the window from ${from ?? "the beginning"} to ${to ?? "now"}`
      : `the last ${window ?? "15m"}`;
    return {
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text:
              `Investigate what went wrong in ${scope} using the LogSlim tools. Compose the ` +
              `atomic primitives — do not expect a single answer call.\n\n` +
              `1. SPIKE: call \`template_counts\` for the window, then again for an equal-length ` +
              `window immediately before it, and diff them. Templates frequent in the window but ` +
              `rare/absent in the baseline are the anomalies.\n` +
              `2. TRIGGER: call \`new_templates\` for the window — a pattern first seen here ` +
              `(a new error, a migration line, a circuit-breaker message) is the likely root cause. ` +
              `The root cause is usually low-volume and early, not the loudest signal.\n` +
              `3. ONSET: use \`template_timeseries\` on a suspect template to find the exact minute ` +
              `it began.\n` +
              `4. DRILL: \`inspect_template\` for slot distributions, \`query_logs\` to filter by ` +
              `slot value, \`raw_sample\` for unmatched lines.\n` +
              `5. Only if needed, \`replay\` a NARROW window around the onset for exact raw lines.\n\n` +
              `Then state: the root cause, the causal chain from cause to user-visible symptom, ` +
              `and the exact log lines that prove it.`,
          },
        },
      ],
    };
  },
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // stderr is safe; stdout is the MCP transport channel.
  console.error(`logslim-mcp connected (API: ${API_URL}${API_KEY ? ", auth on" : ""})`);
}

main().catch((err) => {
  console.error("logslim-mcp fatal:", err);
  process.exit(1);
});
