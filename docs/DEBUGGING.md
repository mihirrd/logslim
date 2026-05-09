# Debugging with LogSlim

LogSlim stores logs in two forms. Understanding both is key to using it effectively for debugging.

## What each store contains

**Templates + `log_entries`** — every log line that matched a known pattern, stored as `(template_id, parameter_values)`. The variable parts (IDs, timestamps, durations, status codes) are separated from the fixed structure of the line.

**`raw_logs`** — two kinds of lines end up here:
1. Lines that arrived *before* Drain locked the template (the first ~10 occurrences of any pattern, while the algorithm is still learning)
2. Lines that never matched any template — genuinely novel formats, one-off errors, stack traces without a leading log line

---

## Workflows

### Spike investigation — "something went wrong at 14:32"

Start with `replay`:

```bash
logslim replay --from 2024-01-15T14:30:00Z --to 2024-01-15T14:35:00Z
```

This reconstructs all log lines from both tables in timestamp order, giving you the original stream exactly as it was written. Templates and raw logs are interleaved by time — you see the full context, not just pattern matches.

---

### Pattern-based drilling — "how often does this error happen?"

Find the template:

```bash
logslim templates --search "connection timeout"
```

Inspect its hit count, slot statistics, and recent occurrences:

```bash
logslim inspect <id> --recent 20
```

The slot stats show the top values for each variable position — which hosts, which ports, which durations appear most. You understand the blast radius without scanning raw text.

---

### Filtering to a specific value — "all failures for user 456"

```bash
logslim query "User {id} failed login" --filter id=456 --last 7d
```

Since parameters are stored separately from the template, filtering by slot value is a direct index lookup, not a full text scan. This works across millions of entries.

---

### Anomaly detection — "what's in raw_logs?"

Raw logs are the most interesting signal for unexpected failures. A line that never matched a template means it's structurally novel — the system hasn't seen that format before. These are disproportionately likely to be:

- New exception types
- One-time infrastructure errors (OOM, disk full, network partition)
- Log lines from a newly deployed component with different formatting
- Stack trace bodies (continuation lines are stored verbatim)

`query` against templates finds **known** problems. Raw logs surface **unknown** ones.

---

### Post-mortem — "did this ever happen before?"

```bash
logslim query "Payment failed for order {id}" --last 30d
logslim query "Payment failed for order {id}" --filter id=ORD-9923 --last 30d
```

Every occurrence is queryable with the original parameter values intact, regardless of how long ago it happened or whether the database has been compacted.

---

### Correlating cause and effect

Templates give you the *frequency* of a known failure mode and its parameter distribution. Raw logs give you the *context* — what was happening right before and after in the same time window. Use `replay` with a narrow window around a spike to see both interleaved in timestamp order.

---

## Rule of thumb

> Templates tell you **what normally goes wrong and how often**.  
> Raw logs tell you **what you didn't know could go wrong**.

If an incident only shows up in raw logs and not in any template, the failure mode was novel. That's a signal worth acting on — add explicit logging or alerting so future occurrences get a template and become queryable.
