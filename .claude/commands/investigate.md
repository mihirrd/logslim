Investigate the incident in the LogSlim database. The API is at http://localhost:8080.

Window to investigate: $ARGUMENTS (e.g. "last 5 minutes" → window=5m, or a specific range like "14:25 to 14:35" → from/to params).

---

## Step 1 — Always start here (1 call)

GET http://localhost:8080/api/investigate?window=<window>

Or with absolute times:
GET http://localhost:8080/api/investigate?from=2026-05-15T14:25:00Z&to=2026-05-15T14:35:00Z

The response gives you everything pre-computed:
- **spikes**: templates ranked by ratio (window count ÷ baseline count). `ratio: null` means the template never appeared before the window — these are the most suspicious.
- **newTemplates**: patterns whose first log entry fell inside the window — often the trigger event (a deploy, a new exception type, a migration).
- **rawLogSample**: up to 5 unmatched log lines — look for stack traces, migration output, deployment events.
- **errorRate**: fraction of window entries that matched error-pattern templates.
- **totalWindowEntries**: total structured log entries in the window.

Reason over this response before making any other calls. In most incidents, the root cause is already visible here.

---

## Step 2 — Pinpoint onset (only if timing is unclear)

If you need to know exactly when a spike began, call timeseries for the top spike template with a wider window and fine bucket:

GET http://localhost:8080/api/templates/{id}/timeseries?from=<30 min before window start>&to=<window end>&bucket=1m

Find the minute the count jumped. Compare to `newTemplates[].firstSeenMs` — if they align, that's your cause-and-effect link.

---

## Step 3 — One targeted raw log search (only if cause is still unclear)

GET http://localhost:8080/api/raw-logs?from=<window start>&to=<window end>&search=<keyword>&limit=5

Use the most specific term you found in step 1: an exception class, a table name, a migration file name, a pod name. One search only.

---

## Output

State your finding concisely:

**Symptom**: which template spiked, which service, peak count  
**Onset**: the exact minute it started (from timeseries if needed)  
**Root cause**: the template or raw log line that explains why  
**Evidence chain**: 2–3 data points connecting cause → symptom → impact  
**Recommended action**: what to fix or roll back

---

## Hard constraints

- Maximum 6 API calls total. Stop as soon as you have cause + symptom + onset.
- Do NOT call `/api/replay` — it returns raw log lines and will burn your context.
- Do NOT loop through every template with individual `/api/templates/{id}` calls.
- Do NOT call `/api/investigate` more than once.
