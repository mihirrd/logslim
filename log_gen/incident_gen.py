"""
Incident log generator: DB connection pool exhaustion from a bad deploy.

Timeline
--------
14:00–14:19  Normal traffic. All services healthy.
14:20        order-service v2.3.1 deployed. Migration drops idx_orders_user_id.
14:21–14:24  Slow query warnings begin on order-service (full-table scans).
14:25–14:29  db-proxy connection pool pressure. Pool >80%, then >90%.
14:30        Pool exhausted. order-service starts timing out.
14:31        payment-service circuit-breaker trips (depends on order-service).
14:32        api-gateway starts returning 503s.
14:33        Alerts fire. OOM on one order-service pod.
14:34–14:42  On-call investigates. Runbook steps. Blind restarts.
14:43        Root cause identified: missing index in migration.
14:44        Hotfix: index recreated via migration patch.
14:45–14:50  Recovery. Pool drains. Error rate falls.
14:51–15:00  Back to baseline.

Root cause discoverability via LogSlim
---------------------------------------
1. `templates` shows "DB query duration" spiking at 14:20.
2. `inspect` on that template: slot `table=orders`, `duration` climbing to 8000ms+.
3. `timeseries` pinpoints spike onset to the minute.
4. `raw-logs --search migration` reveals the migration log at 14:20:03.
5. `query "DB query slow" --filter table=orders` confirms only orders table affected.
6. `raw-logs --search "idx_orders_user_id"` finds the DROP INDEX statement.
"""

import os
import random
import sys
from datetime import datetime, timezone, timedelta

random.seed(42)

# The incident spans ~3600s (1h). Anchor it so the timeline ENDS at "now",
# i.e. it covers the last hour. This keeps the demo fresh: replay --last 1h
# returns the whole incident and the dashboard "Last Seen" reflects real
# event recency instead of drifting weeks into the past.
# Override with INCIDENT_BASE (ISO-8601, e.g. 2026-05-15T14:00:00Z) for a
# fixed, reproducible timeline.
INCIDENT_SPAN_SECONDS = 3600

_base_env = os.environ.get("INCIDENT_BASE")
if _base_env:
    BASE = datetime.fromisoformat(_base_env.replace("Z", "+00:00"))
else:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    BASE = now - timedelta(seconds=INCIDENT_SPAN_SECONDS)

USERS    = [f"usr_{i:05d}" for i in range(1, 401)]
ORDERS   = [f"ord_{i:07d}" for i in range(1000000, 1002001)]
PRODUCTS = [f"prod_{i:04d}" for i in range(1, 201)]
PODS     = {
    "order-service":       ["order-service-7d9f8b-xk2pq", "order-service-7d9f8b-mn4rs"],
    "payment-service":     ["payment-service-5c3a1d-jh7wv"],
    "inventory-service":   ["inventory-service-2b8e4f-lp9xt"],
    "api-gateway":         ["api-gateway-6f2d0c-qr3yz"],
    "auth-service":        ["auth-service-9a1b7e-wn5mk"],
    "notification-service":["notification-service-3c6d2a-bv8uj"],
    "db-proxy":            ["db-proxy-0"],
    "scheduler":           ["scheduler-4e7f1b-ck0st"],
}

lines = []


def ts(offset_seconds, jitter_ms=0):
    t = BASE + timedelta(seconds=offset_seconds, milliseconds=random.randint(0, jitter_ms))
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"


def pod(service):
    pods = PODS[service]
    return random.choice(pods)


def emit(t, level, service, msg):
    lines.append(f"{t} {level:<5} [{service}] {msg}")


def emit_pod(t, level, service, msg):
    p = pod(service)
    lines.append(f"{t} {level:<5} [{service}] pod={p} {msg}")


# ── PHASE 1: Normal baseline (0–19 min = 0–1140s) ─────────────────────────

routes = [
    ("GET",  "/api/v1/orders",          200, 12),
    ("POST", "/api/v1/orders",          201, 45),
    ("GET",  "/api/v1/orders/{id}",     200, 8),
    ("GET",  "/api/v1/users/{id}",      200, 6),
    ("POST", "/api/v1/payments",        200, 38),
    ("GET",  "/api/v1/inventory/{sku}", 200, 5),
]

def normal_traffic(start, end, rate_per_min=55):
    for sec in range(start, end):
        count = random.randint(max(1, rate_per_min // 60 - 1),
                               rate_per_min // 60 + 2)
        for _ in range(count):
            t = ts(sec, 900)
            method, route, status, base_ms = random.choice(routes)
            dur = base_ms + random.randint(-3, 8)
            uid = random.choice(USERS)
            emit(t,
                 "INFO ",
                 "api-gateway",
                 f"HTTP {method} {route} status={status} user={uid} duration={dur}ms")

        # DB activity from order-service
        if random.random() < 0.4:
            t = ts(sec, 900)
            table = random.choice(["orders", "orders", "payments", "inventory"])
            dur = random.randint(2, 18)
            rows = random.randint(1, 40)
            emit(t, "DEBUG", "order-service",
                 f"DB query table={table} rows={rows} duration={dur}ms")

        # Cache activity
        if random.random() < 0.25:
            t = ts(sec, 900)
            key = f"user:{random.choice(USERS)}"
            hit = random.random() < 0.82
            emit(t, "DEBUG", "order-service",
                 f"Cache {'HIT' if hit else 'MISS'} key={key} ttl=3600s")

        # Auth token validation
        if random.random() < 0.2:
            t = ts(sec, 900)
            uid = random.choice(USERS)
            emit(t, "DEBUG", "auth-service",
                 f"Token validated user={uid} scope=read,write latency=2ms")

        # Health checks every 30s
        if sec % 30 == 0:
            for svc in ["order-service", "payment-service", "inventory-service"]:
                t = ts(sec, 100)
                emit(t, "INFO ", "api-gateway",
                     f"Health check {svc} status=UP latency=3ms")

        # Periodic DB pool stats
        if sec % 60 == 0:
            pool_used = random.randint(8, 18)
            emit(ts(sec, 50), "INFO ", "db-proxy",
                 f"Connection pool stats active={pool_used} idle={50-pool_used} max=50 wait_queue=0")


normal_traffic(0, 1200)

# ── PHASE 2: Deploy at 14:20:00 ───────────────────────────────────────────

deploy_t = 1200  # 14:20:00

emit(ts(deploy_t + 2),  "INFO ", "scheduler",
     "Deployment started service=order-service version=v2.3.1 triggered_by=ci-pipeline-447")
emit(ts(deploy_t + 3),  "INFO ", "order-service",
     "pod=order-service-7d9f8b-xk2pq Graceful shutdown initiated connections_draining=12")
emit(ts(deploy_t + 5),  "INFO ", "order-service",
     "pod=order-service-7d9f8b-xk2pq Shutdown complete")
emit(ts(deploy_t + 6),  "INFO ", "order-service",
     "pod=order-service-7d9f8b-mn4rs Graceful shutdown initiated connections_draining=7")
emit(ts(deploy_t + 8),  "INFO ", "order-service",
     "pod=order-service-7d9f8b-mn4rs Shutdown complete")
emit(ts(deploy_t + 10), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Starting version=v2.3.1")
emit(ts(deploy_t + 11), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Starting version=v2.3.1")
emit(ts(deploy_t + 12), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Running DB migrations migration_count=3")
emit(ts(deploy_t + 13), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Migration 001_add_status_column: OK duration=12ms")
emit(ts(deploy_t + 14), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Migration 002_backfill_status: OK duration=4823ms rows_updated=2847301")
emit(ts(deploy_t + 18), "WARN ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Migration 003_cleanup_indexes: OK duration=203ms indexes_dropped=2 WARNING: idx_orders_user_id idx_orders_created_at removed — verify query plans")
emit(ts(deploy_t + 19), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Migrations complete. Starting HTTP server port=8080")
emit(ts(deploy_t + 20), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Running DB migrations migration_count=3")
emit(ts(deploy_t + 21), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 001_add_status_column: OK duration=8ms")
emit(ts(deploy_t + 22), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 002_backfill_status: SKIPPED already_applied=true")
emit(ts(deploy_t + 23), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 003_cleanup_indexes: SKIPPED already_applied=true")
emit(ts(deploy_t + 24), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migrations complete. Starting HTTP server port=8080")
emit(ts(deploy_t + 26), "INFO ", "scheduler",
     "Deployment complete service=order-service version=v2.3.1 duration=26s pods_replaced=2")
emit(ts(deploy_t + 27), "INFO ", "api-gateway",
     "Health check order-service status=UP latency=4ms")

# ── PHASE 3: Slow queries begin (14:21–14:24 = 1260–1440s) ───────────────

PODS["order-service"] = ["order-service-8a2c1f-pv5nw", "order-service-8a2c1f-rv7qx"]

def slow_query_phase(start, end, base_dur, dur_growth, rate_per_min=55):
    for sec in range(start, end):
        elapsed = sec - start
        dur_orders = base_dur + elapsed * dur_growth + random.randint(-20, 30)

        count = random.randint(max(1, rate_per_min // 60 - 1),
                               rate_per_min // 60 + 2)
        for _ in range(count):
            t = ts(sec, 900)
            method, route, status, base_ms = random.choice(routes)
            # order routes start getting slow
            if "orders" in route:
                resp_ms = min(dur_orders // 10 + base_ms, 29900)
                status_out = 200 if resp_ms < 5000 else 503
            else:
                resp_ms = base_ms + random.randint(-3, 8)
                status_out = status
            uid = random.choice(USERS)
            emit(t, "INFO ", "api-gateway",
                 f"HTTP {method} {route} status={status_out} user={uid} duration={resp_ms}ms")

        # DB queries — orders table is now very slow
        for _ in range(random.randint(2, 5)):
            t = ts(sec, 900)
            if random.random() < 0.6:
                table = "orders"
                dur = dur_orders + random.randint(-50, 100)
                rows = random.randint(1000, 2850000)  # full table scan
                level = "WARN " if dur > 500 else "DEBUG"
                msg = f"DB query table={table} rows={rows} duration={dur}ms"
                if dur > 1000:
                    msg += " plan=SeqScan"
                emit(t, level, "order-service", msg)
            else:
                table = random.choice(["payments", "inventory", "users"])
                dur = random.randint(2, 18)
                rows = random.randint(1, 40)
                emit(t, "DEBUG", "order-service",
                     f"DB query table={table} rows={rows} duration={dur}ms")

        if sec % 30 == 0:
            pool_used = min(50, 18 + (sec - start) * 2 // 3)
            wait = max(0, pool_used - 40)
            emit(ts(sec, 50), "INFO " if pool_used < 40 else "WARN ",
                 "db-proxy",
                 f"Connection pool stats active={pool_used} idle={50-pool_used} max=50 wait_queue={wait}")

        if dur_orders > 800 and random.random() < 0.3:
            t = ts(sec, 900)
            oid = random.choice(ORDERS)
            uid = random.choice(USERS)
            emit(t, "WARN ", "order-service",
                 f"Slow request order_id={oid} user={uid} handler=GetOrdersByUser duration={dur_orders}ms threshold=500ms")


slow_query_phase(1260, 1440, base_dur=120, dur_growth=8)   # 14:21–14:24: 120ms→2400ms
slow_query_phase(1440, 1500, base_dur=2400, dur_growth=40) # 14:24–14:25: 2400ms→4800ms

# ── PHASE 4: Pool exhaustion (14:25–14:29 = 1500–1740s) ──────────────────

for sec in range(1500, 1560):  # 14:25–14:26
    t = ts(sec, 500)
    pool_used = min(50, 44 + (sec - 1500) // 10)
    wait = max(0, pool_used - 40)
    emit(t, "WARN ", "db-proxy",
         f"Connection pool stats active={pool_used} idle={50-pool_used} max=50 wait_queue={wait}")
    if pool_used >= 48:
        emit(ts(sec, 600), "WARN ", "db-proxy",
             f"Pool near capacity active={pool_used}/50 oldest_conn_age=42s")

    for _ in range(random.randint(3, 6)):
        t2 = ts(sec, 900)
        dur = 4800 + random.randint(0, 3200)
        rows = random.randint(1000000, 2850000)
        emit(t2, "WARN ", "order-service",
             f"DB query table=orders rows={rows} duration={dur}ms plan=SeqScan")

    for _ in range(random.randint(1, 3)):
        t2 = ts(sec, 900)
        oid = random.choice(ORDERS)
        uid = random.choice(USERS)
        resp = 5000 + random.randint(0, 2000)
        emit(t2, "WARN ", "api-gateway",
             f"HTTP GET /api/v1/orders status=503 user={uid} duration={resp}ms")

for sec in range(1560, 1740):  # 14:26–14:29: full exhaustion
    t = ts(sec, 200)
    emit(t, "ERROR", "db-proxy",
         f"Connection pool exhausted active=50/50 wait_queue={random.randint(8,35)} oldest_wait={random.randint(4,18)}s")

    for _ in range(random.randint(4, 8)):
        t2 = ts(sec, 900)
        dur = 8000 + random.randint(0, 4000)
        emit(t2, "WARN ", "order-service",
             f"DB query table=orders rows={random.randint(2000000,2850000)} duration={dur}ms plan=SeqScan")

    for _ in range(random.randint(2, 5)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        emit(t2, "ERROR", "order-service",
             f"DB connection timeout after=30000ms user={uid} handler=GetOrdersByUser pool_wait={random.randint(15,30)}s")

    for _ in range(random.randint(3, 7)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        route = random.choice(["/api/v1/orders", "/api/v1/orders/{id}", "POST /api/v1/orders"])
        emit(t2, "ERROR", "api-gateway",
             f"HTTP GET {route} status=503 user={uid} duration=30001ms upstream=order-service")

# ── PHASE 5: Cascade — payment-service circuit breaker (14:30 = 1800s) ───

cb_t = 1800

emit(ts(cb_t + 2),  "ERROR", "payment-service",
     "Dependency health check failed service=order-service consecutive_failures=5")
emit(ts(cb_t + 3),  "WARN ", "payment-service",
     "Circuit breaker OPEN service=order-service threshold=5 window=60s")
emit(ts(cb_t + 4),  "ERROR", "payment-service",
     "Rejecting requests: circuit breaker open for order-service estimated_recovery=30s")

for sec in range(cb_t, cb_t + 240):  # 14:30–14:34
    for _ in range(random.randint(2, 5)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        emit(t2, "ERROR", "api-gateway",
             f"HTTP POST /api/v1/payments status=503 user={uid} duration=2ms reason=circuit_breaker_open")
    for _ in range(random.randint(1, 3)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        emit(t2, "ERROR", "order-service",
             f"DB connection timeout after=30000ms user={uid} handler=GetOrdersByUser pool_wait={random.randint(25,30)}s")
    if sec % 15 == 0:
        emit(ts(sec), "ERROR", "db-proxy",
             f"Connection pool exhausted active=50/50 wait_queue={random.randint(20,45)} oldest_wait={random.randint(18,32)}s")

# OOM at 14:33
oom_t = 1980
emit(ts(oom_t),     "ERROR", "order-service",
     "pod=order-service-8a2c1f-pv5nw OutOfMemoryError: Java heap space — pod terminating")
emit(ts(oom_t + 1), "WARN ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Thread dump: 847 threads BLOCKED waiting on db-pool-lock")
emit(ts(oom_t + 2), "ERROR", "order-service",
     "pod=order-service-8a2c1f-pv5nw Process exited code=137 (OOM Kill)")
emit(ts(oom_t + 3), "INFO ", "scheduler",
     "Pod restart triggered pod=order-service-8a2c1f-pv5nw reason=OOM restarts=1")
emit(ts(oom_t + 8), "INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Starting version=v2.3.1")
emit(ts(oom_t + 10),"INFO ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Migrations complete. Starting HTTP server port=8080")
emit(ts(oom_t + 12),"WARN ", "order-service",
     "pod=order-service-8a2c1f-pv5nw Readiness probe FAIL: DB connection unavailable pool_exhausted=true")
emit(ts(oom_t + 15),"WARN ", "api-gateway",
     "Health check order-service status=DEGRADED healthy_pods=1/2 latency=30001ms")

# Alert at 14:33
alert_t = 1990
emit(ts(alert_t),   "ERROR", "scheduler",
     "ALERT FIRING alert=HighErrorRate service=order-service error_rate=0.94 threshold=0.10 window=5m")
emit(ts(alert_t+1), "ERROR", "scheduler",
     "ALERT FIRING alert=DBPoolExhausted service=db-proxy active=50/50 duration=4m23s")
emit(ts(alert_t+2), "ERROR", "scheduler",
     "ALERT FIRING alert=CircuitBreakerOpen service=payment-service dependency=order-service")
emit(ts(alert_t+3), "INFO ", "scheduler",
     "PagerDuty incident created incident_id=INC-20847 oncall=@eng-platform-oncall")

# ── PHASE 6: Investigation (14:34–14:42 = 2040–2520s) ────────────────────

for sec in range(2040, 2520):
    # Still erroring
    for _ in range(random.randint(3, 7)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        emit(t2, "ERROR", "api-gateway",
             f"HTTP GET /api/v1/orders status=503 user={uid} duration=30001ms upstream=order-service")
    for _ in range(random.randint(1, 3)):
        t2 = ts(sec, 900)
        emit(t2, "ERROR", "order-service",
             f"DB connection timeout after=30000ms user={random.choice(USERS)} handler=GetOrdersByUser pool_wait={random.randint(25,30)}s")
    if sec % 30 == 0:
        emit(ts(sec), "ERROR", "db-proxy",
             f"Connection pool exhausted active=50/50 wait_queue={random.randint(30,60)} oldest_wait={random.randint(25,40)}s")

# Runbook steps — on-call running commands, visible in logs
emit(ts(2055), "INFO ", "scheduler",
     "oncall=eng-platform scaling order-service replicas=2->4")
emit(ts(2060), "INFO ", "order-service",
     "pod=order-service-8a2c1f-tz3mv Starting version=v2.3.1")
emit(ts(2062), "INFO ", "order-service",
     "pod=order-service-8a2c1f-tz3mv Migrations complete. Starting HTTP server port=8080")
emit(ts(2065), "WARN ", "order-service",
     "pod=order-service-8a2c1f-tz3mv Readiness probe FAIL: DB connection unavailable pool_exhausted=true")
emit(ts(2075), "INFO ", "order-service",
     "pod=order-service-8a2c1f-wq9pr Starting version=v2.3.1")
emit(ts(2077), "WARN ", "order-service",
     "pod=order-service-8a2c1f-wq9pr Readiness probe FAIL: DB connection unavailable pool_exhausted=true")
emit(ts(2080), "WARN ", "scheduler",
     "Scale-up ineffective: new pods failing readiness probe reason=DB pool still exhausted")

emit(ts(2120), "INFO ", "scheduler",
     "oncall=eng-platform increasing db-proxy pool_max 50->80")
emit(ts(2122), "INFO ", "db-proxy",
     "Pool reconfigured max=80 active=50 releasing_waiters=true")
emit(ts(2125), "INFO ", "db-proxy",
     "Connection pool stats active=62 idle=18 max=80 wait_queue=12")
emit(ts(2128), "INFO ", "order-service",
     "DB connection acquired after wait=63s handler=GetOrdersByUser")
emit(ts(2130), "WARN ", "order-service",
     f"DB query table=orders rows=2847301 duration=9843ms plan=SeqScan")
emit(ts(2135), "WARN ", "order-service",
     f"DB query table=orders rows=2847301 duration=10211ms plan=SeqScan")
emit(ts(2138), "INFO ", "db-proxy",
     "Connection pool stats active=74 idle=6 max=80 wait_queue=22")
emit(ts(2140), "ERROR","db-proxy",
     "Connection pool exhausted active=80/80 wait_queue=31 oldest_wait=8s")
emit(ts(2142), "WARN ", "scheduler",
     "Pool increase insufficient: queries still blocking on SeqScan of orders table")

# Slow-query log reveals the pattern
emit(ts(2200), "WARN ", "order-service",
     "Slow query log: SELECT * FROM orders WHERE user_id = ? duration=11204ms rows_examined=2847301 rows_returned=12 index_used=none")
emit(ts(2205), "WARN ", "order-service",
     "Slow query log: SELECT * FROM orders WHERE user_id = ? duration=10897ms rows_examined=2847301 rows_returned=7 index_used=none")
emit(ts(2210), "WARN ", "order-service",
     "Slow query log: SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at > ? duration=12034ms rows_examined=2847301 index_used=none")
emit(ts(2220), "INFO ", "scheduler",
     "oncall=eng-platform checking EXPLAIN output for slow queries")
emit(ts(2225), "WARN ", "order-service",
     "EXPLAIN: Seq Scan on orders cost=0.00..198432.01 rows=2847301 filter=(user_id=?) — missing index")
emit(ts(2230), "INFO ", "scheduler",
     "oncall=eng-platform checking migration history version=v2.3.1")
emit(ts(2240), "INFO ", "scheduler",
     "oncall=eng-platform found DROP INDEX idx_orders_user_id in migration 003_cleanup_indexes — ROOT CAUSE IDENTIFIED")
emit(ts(2245), "INFO ", "scheduler",
     "Incident update INC-20847: Root cause = migration 003 dropped idx_orders_user_id causing SeqScan on 2.8M row table")

# ── PHASE 7: Hotfix (14:44–14:45 = 2640–2700s) ───────────────────────────

fix_t = 2640

emit(ts(fix_t),     "INFO ", "scheduler",
     "Hotfix deployment started service=order-service version=v2.3.1-hotfix migration=004_restore_indexes")
emit(ts(fix_t + 4), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Running DB migrations migration_count=1")
emit(ts(fix_t + 8), "INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 004_restore_indexes: CREATE INDEX CONCURRENTLY idx_orders_user_id ON orders(user_id) — started")
emit(ts(fix_t + 47),"INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 004_restore_indexes: OK duration=43120ms rows_indexed=2847301")
emit(ts(fix_t + 48),"INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 004_restore_indexes: CREATE INDEX CONCURRENTLY idx_orders_created_at ON orders(created_at) — started")
emit(ts(fix_t + 72),"INFO ", "order-service",
     "pod=order-service-8a2c1f-rv7qx Migration 004_restore_indexes: OK duration=23810ms rows_indexed=2847301")
emit(ts(fix_t + 73),"INFO ", "scheduler",
     "Hotfix migration complete. Indexes restored.")

# ── PHASE 8: Recovery (14:45–14:50 = 2700–3000s) ─────────────────────────

for sec in range(2713, 2760):  # First 45s: queries getting fast again
    frac = (sec - 2713) / 47.0
    dur_orders = int(10000 * (1 - frac) + 15 * frac) + random.randint(-50, 50)
    for _ in range(random.randint(2, 4)):
        t2 = ts(sec, 900)
        uid = random.choice(USERS)
        rows = max(12, int(2847301 * (1 - frac)) + random.randint(-5, 5))
        plan = "SeqScan" if frac < 0.3 else "IndexScan"
        if dur_orders > 500:
            emit(t2, "WARN ", "order-service",
                 f"DB query table=orders rows={rows} duration={dur_orders}ms plan={plan}")
        else:
            emit(t2, "DEBUG", "order-service",
                 f"DB query table=orders rows={rows} duration={dur_orders}ms plan={plan}")

emit(ts(2760), "INFO ", "db-proxy",
     "Connection pool stats active=42 idle=38 max=80 wait_queue=0")
emit(ts(2780), "INFO ", "db-proxy",
     "Connection pool stats active=28 idle=52 max=80 wait_queue=0")
emit(ts(2800), "INFO ", "payment-service",
     "Circuit breaker HALF-OPEN probing service=order-service")
emit(ts(2805), "INFO ", "payment-service",
     "Circuit breaker CLOSED service=order-service probe_latency=22ms")
emit(ts(2810), "INFO ", "api-gateway",
     "Health check order-service status=UP latency=18ms")
emit(ts(2820), "INFO ", "api-gateway",
     "Health check payment-service status=UP latency=9ms")
emit(ts(2830), "INFO ", "scheduler",
     "ALERT RESOLVED alert=HighErrorRate service=order-service error_rate=0.02 threshold=0.10")
emit(ts(2835), "INFO ", "scheduler",
     "ALERT RESOLVED alert=DBPoolExhausted service=db-proxy")
emit(ts(2840), "INFO ", "scheduler",
     "ALERT RESOLVED alert=CircuitBreakerOpen service=payment-service")
emit(ts(2845), "INFO ", "scheduler",
     "Incident resolved INC-20847 duration=55m2s affected_services=order-service,payment-service,api-gateway")

# ── PHASE 9: Back to baseline (14:51–15:00 = 3060–3600s) ─────────────────

normal_traffic(3060, 3600, rate_per_min=55)

# Final pool reset
emit(ts(3120), "INFO ", "scheduler",
     "oncall=eng-platform resetting db-proxy pool_max 80->50")
emit(ts(3122), "INFO ", "db-proxy",
     "Pool reconfigured max=50 active=14 idle=36 wait_queue=0")

# ── Output ────────────────────────────────────────────────────────────────

lines.sort(key=lambda l: l[:24])  # sort by timestamp prefix

output = "\n".join(lines)
print(output, file=sys.stdout)
