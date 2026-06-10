import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const SERVER = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "dist", "index.js");

const EXPECTED_TOOLS = [
  "template_counts", "new_templates", "list_templates", "inspect_template",
  "template_timeseries", "raw_sample", "query_logs", "replay", "get_stats",
];

/** A mock LogSlim API that records every request and returns canned, shape-accurate responses. */
function startMock() {
  const requests = [];
  const srv = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const u = new URL(req.url, "http://x");
      const parsed = body ? JSON.parse(body) : null;
      requests.push({ method: req.method, path: u.pathname, query: Object.fromEntries(u.searchParams), body: parsed });
      const send = (code, obj) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(obj)); };

      switch (`${req.method} ${u.pathname}`) {
        case "GET /api/stats":
          return send(200, { templateCount: 3, logEntryCount: 10, rawLogCount: 2 });
        case "GET /api/templates":
          return send(200, [{ id: 1, pattern: "t1", hits: 5 }, { id: 2, pattern: "t2", hits: 3 }]);
        case "GET /api/template-counts":
          return send(200, []);
        case "POST /api/query-structured":
          return send(200, {
            templateId: 18, template: "{ts} ERROR {user}", slots: ["ts", "user"],
            matchedCount: 2104, scanCapped: false, returned: 250,
            occurrences: Array.from({ length: 250 }, (_, i) => ["t", "u" + i]),
          });
        case "POST /api/query":
          return send(200, Array.from({ length: 250 }, (_, i) => "line " + i));
        case "GET /api/replay":
          return send(200, Array.from({ length: 250 }, (_, i) => "raw " + i));
        default:
          return send(404, { error: "no route" });
      }
    });
  });
  return new Promise((resolve) => {
    srv.listen(0, "127.0.0.1", () => resolve({ srv, requests, url: `http://127.0.0.1:${srv.address().port}` }));
  });
}

/** Minimal MCP stdio client: spawns the server, does the handshake, calls tools. */
class Mcp {
  constructor(apiUrl) {
    this.child = spawn("node", [SERVER], {
      env: { ...process.env, LOGSLIM_API_URL: apiUrl, LOGSLIM_API_KEY: "" },
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.buf = "";
    this.pending = new Map();
    this.id = 0;
    this.child.stdout.on("data", (d) => {
      this.buf += d.toString();
      let nl;
      while ((nl = this.buf.indexOf("\n")) >= 0) {
        const line = this.buf.slice(0, nl).trim();
        this.buf = this.buf.slice(nl + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id != null && this.pending.has(msg.id)) {
          this.pending.get(msg.id)(msg);
          this.pending.delete(msg.id);
        }
      }
    });
  }
  rpc(method, params) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("timeout: " + method)), 8000);
      this.pending.set(id, (m) => { clearTimeout(timer); resolve(m); });
      this.child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
    });
  }
  async init() {
    await this.rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "t", version: "0" } });
    this.child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");
  }
  async callTool(name, args) {
    const r = await this.rpc("tools/call", { name, arguments: args });
    const text = r.result?.content?.[0]?.text;
    let data;
    try { data = text ? JSON.parse(text) : undefined; } catch { data = undefined; }
    return { isError: !!r.result?.isError, data, text };
  }
  close() { this.child.kill(); }
}

test("MCP server integration", async (t) => {
  const { srv, requests, url } = await startMock();
  const mcp = new Mcp(url);
  await mcp.init();
  const lastReq = (p) => requests.filter((r) => r.path === p).at(-1);

  t.after(() => { mcp.close(); srv.close(); });

  await t.test("lists exactly the expected atomic tools", async () => {
    const r = await mcp.rpc("tools/list", {});
    const names = r.result.tools.map((x) => x.name).sort();
    assert.deepEqual(names, [...EXPECTED_TOOLS].sort());
  });

  await t.test("query_logs defaults to structured and hits /api/query-structured (bounded)", async () => {
    const { data } = await mcp.callTool("query_logs", { pattern: "P", limit: 5000 });
    const req = lastReq("/api/query-structured");
    assert.ok(req, "should POST to /api/query-structured by default");
    assert.equal(req.method, "POST");
    assert.equal(req.body.pattern, "P");
    assert.equal(req.body.limit, 200, "client must clamp limit to the 200 hard cap");
    // structured shape preserved, occurrences truncated, returned reconciled, matchedCount intact
    assert.equal(data.matchedCount, 2104);
    assert.equal(data.truncated, true);
    assert.equal(data.returned, 200);
    assert.equal(data.occurrences.length, 200);
  });

  await t.test("query_logs format:'lines' hits /api/query and truncates via cap()", async () => {
    const { data } = await mcp.callTool("query_logs", { pattern: "P", format: "lines" });
    assert.ok(lastReq("/api/query"), "format:lines must POST to /api/query");
    assert.equal(data.truncated, true);
    assert.equal(data.items.length, 200);
    assert.equal(data.totalAvailable, 250);
  });

  await t.test("template_counts baseline:true forwards baseline=true", async () => {
    await mcp.callTool("template_counts", { last: "1h", baseline: true });
    const req = lastReq("/api/template-counts");
    assert.equal(req.method, "GET");
    assert.equal(req.query.baseline, "true");
    assert.equal(req.query.last, "1h");
  });

  await t.test("replay wraps an over-limit window with truncation metadata", async () => {
    const { data } = await mcp.callTool("replay", { last: "1h" });
    assert.ok(lastReq("/api/replay"));
    assert.equal(data.truncated, true);
    assert.equal(data.returned, 200);
    assert.equal(data.items.length, 200);
  });

  await t.test("get_stats passes a small object through unchanged", async () => {
    const { data, isError } = await mcp.callTool("get_stats", {});
    assert.equal(isError, false);
    assert.deepEqual(data, { templateCount: 3, logEntryCount: 10, rawLogCount: 2 });
  });

  await t.test("exposes the investigate_incident prompt", async () => {
    const r = await mcp.rpc("prompts/list", {});
    assert.ok(r.result.prompts.some((p) => p.name === "investigate_incident"));
  });
});

test("tool errors when the API is unreachable are returned as isError, not a crash", async () => {
  const mcp = new Mcp("http://127.0.0.1:1"); // nothing listening
  await mcp.init();
  const { isError, text } = await mcp.callTool("get_stats", {});
  assert.equal(isError, true);
  assert.match(text, /Error talking to LogSlim/);
  mcp.close();
});
