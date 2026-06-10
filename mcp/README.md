# LogSlim MCP server

Gives an AI agent **structured, token-cheap access to your logs** instead of a raw
log dump. The agent navigates the way a human SRE does — overview → anomaly → drill
→ raw — and pulls exact raw lines only for the narrow window it actually needs.

Why this matters: a busy service emits millions of log lines. Feeding those to an
agent doesn't fit a context window, costs a fortune per glance, and buries the one
line that matters among thousands of near-duplicates. LogSlim collapses them into a
few dozen templates + counts. On a real 13.7k-line incident log that's a ~200×
token reduction for the overview — and the root-cause chain (a deploy → OOM →
circuit-breaker cascade) surfaces at the *top* of the anomaly list instead of being
needle-in-haystack.

This server is a thin adapter over the `logslim serve` HTTP API.

## Prerequisites

`logslim serve` must be running and reachable (default `http://localhost:8080`):

```bash
logslim serve   # → LogSlim API server running on http://localhost:8080
```

## Build

```bash
cd mcp
npm install        # builds dist/ automatically via the `prepare` script
```

(`npm run build` is also available; `npm ci` honors the committed lockfile and builds the
same way.)

The package is publish-ready (`npm pack` ships only `dist/` + this README; `prepublishOnly`
runs the test suite). It is not published to npm yet — run it from source as above. Once
published, `npx logslim-mcp` would work via the declared `bin`. The server speaks MCP over
**stdio**, so it runs locally alongside your agent; a remote/HTTP transport is future work.

## Configure your agent

Add to your MCP client config (Claude Desktop, Claude Code, Cursor, …):

```json
{
  "mcpServers": {
    "logslim": {
      "command": "node",
      "args": ["/absolute/path/to/logslim/mcp/dist/index.js"],
      "env": {
        "LOGSLIM_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

Claude Code, one-liner:

```bash
claude mcp add logslim -- node /absolute/path/to/logslim/mcp/dist/index.js
```

### Environment variables

| Var | Default | Purpose |
|-----|---------|---------|
| `LOGSLIM_API_URL` | `http://localhost:8080` | Base URL of `logslim serve`. |
| `LOGSLIM_API_KEY` | _(unset)_ | If set, sent as `X-API-Key` header. |

## Tools

The tools are **atomic primitives the agent composes** — there is no single "answer"
call. Spike detection, for example, is `template_counts` with `baseline: true`: the
server compares the window against the equal-length window just before it and returns
windowCount + baselineCount + ratio per template in one call (no hand-diffing).

| Tool | Cost | Use when | Role |
|------|------|----------|------|
| `list_templates` | low | The whole service's behavior as patterns + counts; search by text. | orient |
| `get_stats` | low | Store sizes (template / entry / raw counts). | orient |
| `template_counts` | low | Per-template counts in a window; set `baseline: true` for server-side window-vs-baseline ratios to find spikes. | detect |
| `new_templates` | low | Templates first seen (by event time) in a window — prime root-cause candidates. **Caveat:** "new" means new to the *ingested dataset*, not the running service, so it only signals true novelty when pre-incident baseline data is also ingested. | detect |
| `template_timeseries` | low | Bucketed counts for one template; pinpoint the onset minute. | localize |
| `inspect_template` | low | Per-slot **distributions + numeric summaries** (min/max/avg/p50/p95), no rows pulled. Use for "which / how many / what distribution" **before** `query_logs`. | drill |
| `query_logs` | low–med | Occurrences of a pattern, filtered by slot value. Returns **structure** (template once + param tuples), not reconstructed lines; `slots` projects to specific columns. | drill |
| `raw_sample` | medium | Sample of unmatched/novel raw lines in a window (cheaper than replay). | drill |
| `replay` | **high** | Ground-truth escape hatch: exact raw lines for a **narrow** window. Use last. | raw |

The server also exposes an `investigate_incident` **prompt** that scaffolds the full
detect → localize → drill → replay workflow, and server-level `instructions` that steer
the agent to start with structure and treat `replay` as a last resort — which is what
keeps token usage low.

## Tests

```bash
npm test
```

No external services or network required — `node:test` (built in) drives:

- **unit** — `buildUrl` query assembly and the `cap()` truncation wrapper (`test/lib.test.mjs`);
- **integration** — the compiled server spawned over real stdio JSON-RPC against an in-process mock LogSlim API (`test/server.test.mjs`): the tool list, that `query_logs` defaults to structured and clamps its limit, `format:'lines'` routing, `template_counts baseline:true` forwarding, `replay` truncation, and that an unreachable API surfaces as an `isError` result rather than a crash. The mock records requests, so it doubles as a contract guard against API drift.

## Smoke test (no agent needed)

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | node dist/index.js
```
