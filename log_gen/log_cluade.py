#!/usr/bin/env python3
"""
Simulated App Log Generator
Produces realistic application logs following an 80/20 Pareto distribution:
  - ~20% of log patterns account for ~80% of log volume (high repetition)
  - The remaining 80% of patterns appear occasionally or rarely

Usage examples:
  python3 generate_logs.py                         # 500 logs to stdout
  python3 generate_logs.py -n 5000 -o app.log      # write to file
  python3 generate_logs.py --realtime --rate 10    # stream at 10 logs/sec
  python3 generate_logs.py --seed 42               # reproducible output
  python3 generate_logs.py --stats                 # show pattern weights
"""

import random
import time
import argparse
from datetime import datetime, timedelta

# ──────────────────────────────────────────────────────────────────────────────
# Shared pools  (kept small so the same values repeat convincingly)
# ──────────────────────────────────────────────────────────────────────────────

SERVICES = [
    "api-gateway", "auth-service", "user-service",
    "payment-service", "notification-service", "db-pool",
    "cache-layer", "scheduler",
]

# Small fixed pools → same IDs / keys recur throughout the log
_USER_IDS     = [random.randint(1000, 9999) for _ in range(30)]
_SESSION_KEYS = [f"session:{uid}" for uid in _USER_IDS]
_REQ_IDS      = [f"req-{random.randint(100000,999999)}" for _ in range(80)]
_PARTITIONS   = list(range(8))

def _uid():  return random.choice(_USER_IDS)
def _sid():  return random.choice(_SESSION_KEYS)
def _rid():  return random.choice(_REQ_IDS)
def _part(): return random.choice(_PARTITIONS)

def _ip():
    return (f"{random.randint(10,203)}."
            f"{random.randint(0,255)}."
            f"{random.randint(0,255)}."
            f"{random.randint(1,254)}")

def _ts(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond//1000:03d}Z"


# ──────────────────────────────────────────────────────────────────────────────
# Pattern registry  –  list of (weight, generator_fn)
#
# Weight breakdown:
#   Top-5 dominant patterns  → weight 400 / ~500 total  ≈ 80 % of volume
#   Occasional patterns      → weight  75 / ~500 total  ≈ 15 % of volume
#   Rare / important         → weight  10 / ~500 total  ≈  5 % of volume (1 each)
# ──────────────────────────────────────────────────────────────────────────────

_EP_COMMON = ["/api/v1/users", "/api/v1/orders", "/api/v1/products"]
_EP_ALL    = _EP_COMMON + ["/api/v1/auth/login", "/api/v1/auth/refresh",
                            "/health", "/api/v1/payments", "/api/v1/notifications"]
_TBL_COMM  = ["users", "orders", "sessions"]
_TBL_ALL   = _TBL_COMM + ["products", "audit_log", "notifications"]

PATTERNS = [

    # ── DOMINANT (top-5, total weight 400 → ~80 % of output) ─────────────────

    # 1. HTTP GET handled – the single most common log in any web service
    (160, lambda ts, svc: (
        f'{ts} INFO  [{svc}] HTTP GET {random.choice(_EP_COMMON)} '
        f'{random.choice([200,200,200,304])} '
        f'{random.randint(8,120)}ms '
        f'req_id={_rid()}'
    )),

    # 2. Cache HIT – fires for nearly every authenticated request
    (100, lambda ts, svc: (
        f'{ts} DEBUG [{svc}] Cache HIT key={_sid()} '
        f'ttl={random.choice([300,600,1800,3600])}s'
    )),

    # 3. DB SELECT – every request touches the database
    (80, lambda ts, svc: (
        f'{ts} DEBUG [{svc}] DB SELECT '
        f'table={random.choice(_TBL_COMM)} '
        f'rows={random.randint(1,50)} '
        f'duration={random.randint(2,45)}ms'
    )),

    # 4. Queue message consumed – background workers churn constantly
    (35, lambda ts, svc: (
        f'{ts} INFO  [{svc}] Message consumed '
        f'topic=app-events partition={_part()} '
        f'offset={random.randint(900000,1200000)} lag=0'
    )),

    # 5. Token validated – every authenticated request checks the token
    (25, lambda ts, svc: (
        f'{ts} DEBUG [{svc}] Token valid '
        f'user_id={_uid()} '
        f'expires_in={random.choice([300,900,1800,3600])}s'
    )),

    # ── OCCASIONAL (~15 % of output) ─────────────────────────────────────────

    (10, lambda ts, svc: (
        f'{ts} INFO  [{svc}] HTTP POST {random.choice(_EP_ALL)} '
        f'{random.choice([200,201,204])} '
        f'{random.randint(20,350)}ms '
        f'req_id={_rid()}'
    )),

    (8, lambda ts, svc: (
        f'{ts} INFO  [{svc}] Health check OK '
        f'uptime={random.randint(3600,864000)}s '
        f'memory_mb={random.randint(180,460)}'
    )),

    (7, lambda ts, svc: (
        f'{ts} DEBUG [{svc}] Connection pool '
        f'active={random.randint(2,18)} idle={random.randint(5,28)} max=50'
    )),

    (6, lambda ts, svc: (
        f'{ts} INFO  [{svc}] Scheduled job ran '
        f'job=cleanup_expired_sessions '
        f'removed={random.randint(0,30)} '
        f'duration={random.randint(15,400)}ms'
    )),

    (5, lambda ts, svc: (
        f'{ts} INFO  [{svc}] User login '
        f'user_id={_uid()} ip={_ip()} '
        f'method=password status=success'
    )),

    (5, lambda ts, svc: (
        f'{ts} INFO  [{svc}] Payment processed '
        f'amount=${random.randint(5,499)}.{random.randint(0,99):02d} '
        f'currency=USD gateway=stripe '
        f'txn_id=txn-{random.randint(100000,999999)}'
    )),

    (4, lambda ts, svc: (
        f'{ts} DEBUG [{svc}] Response serialized '
        f'bytes={random.randint(300,6000)} '
        f'content_type=application/json compress=gzip'
    )),

    (4, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Cache MISS '
        f'key=product:{random.randint(1,200)} fallback=db'
    )),

    (3, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Slow DB query '
        f'table={random.choice(_TBL_ALL)} '
        f'duration={random.randint(501,2500)}ms threshold=500ms'
    )),

    (3, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Downstream retry '
        f'attempt={random.randint(1,3)} '
        f'target={random.choice(SERVICES)} '
        f'delay_ms={random.choice([100,250,500,1000])}'
    )),

    (2, lambda ts, svc: (
        f'{ts} INFO  [{svc}] Feature flag evaluated '
        f'flag=new_checkout_flow '
        f'user_id={_uid()} '
        f'result={random.choice(["enabled","disabled","enabled","enabled"])}'
    )),

    (2, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Rate limit approaching '
        f'user_id={_uid()} '
        f'count={random.randint(45,58)}/60 window=1m'
    )),

    (2, lambda ts, svc: (
        f'{ts} WARN  [{svc}] High memory '
        f'rss_mb={random.randint(480,590)} '
        f'threshold=480mb gc_pressure=high'
    )),

    (2, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Deprecated endpoint called '
        f'path=/api/v0/users req_id={_rid()}'
    )),

    # ── RARE / IMPORTANT (~5 % of output, weight 1 each) ─────────────────────

    (1, lambda ts, svc: (
        f'{ts} ERROR [{svc}] Unhandled exception '
        f'req_id={_rid()} '
        f'error="NullPointerException at UserService.java:142" '
        f'stack_id=st-{random.randint(1000,9999)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} ERROR [{svc}] DB connection failed '
        f'host=db-primary:5432 '
        f'error="connection refused" '
        f'attempt={random.randint(1,5)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} ERROR [{svc}] Auth failure '
        f'user_id={_uid()} ip={_ip()} '
        f'reason=invalid_token '
        f'consecutive={random.randint(3,10)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} ERROR [{svc}] Payment declined '
        f'amount=${random.randint(50,4999)}.00 '
        f'reason=insufficient_funds '
        f'card_last4={random.randint(1000,9999)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} ERROR [{svc}] Email delivery failed '
        f'user_id={_uid()} '
        f'error="SMTP timeout 30s" '
        f'attempt={random.randint(1,3)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} WARN  [{svc}] Disk usage high '
        f'partition=/var/log '
        f'used={random.randint(81,95)}% threshold=80%'
    )),

    (1, lambda ts, svc: (
        f'{ts} WARN  [{svc}] SSL cert expiring '
        f'domain=api.example.com '
        f'days_left={random.randint(1,14)}'
    )),

    (1, lambda ts, svc: (
        f'{ts} CRITICAL [{svc}] Circuit breaker OPEN '
        f'target=payment-service '
        f'failure_rate={random.randint(55,95)}% threshold=50%'
    )),

    (1, lambda ts, svc: (
        f'{ts} CRITICAL [{svc}] OOM killer triggered '
        f'process=worker-{random.randint(1,8)} rss_mb=1024'
    )),

    (1, lambda ts, svc: (
        f'{ts} CRITICAL [{svc}] Data integrity check FAILED '
        f'table=transactions '
        f'mismatched_rows={random.randint(1,20)} '
        f'alerting=on-call'
    )),
]

# Pre-build weighted population for O(1) sampling
_WEIGHTS, _POPULATION = zip(*PATTERNS)
_TOTAL_WEIGHT = sum(_WEIGHTS)


def _pick_pattern():
    """Single-pass weighted random selection."""
    r = random.randint(1, _TOTAL_WEIGHT)
    cumulative = 0
    for fn, w in zip(_POPULATION, _WEIGHTS):
        cumulative += w
        if r <= cumulative:
            return fn
    return _POPULATION[-1]


# ──────────────────────────────────────────────────────────────────────────────
# Generator
# ──────────────────────────────────────────────────────────────────────────────

def generate_logs(
    count: int = 500,
    start_time: datetime = None,
    time_spread_seconds: int = 3600,
    output_file: str = None,
    realtime: bool = False,
    realtime_rate: float = 5.0,
):
    if start_time is None:
        now = datetime.utcnow()
        start_time = now - timedelta(seconds=time_spread_seconds)

    timestamps = sorted(
        start_time + timedelta(seconds=random.uniform(0, time_spread_seconds))
        for _ in range(count)
    )

    lines = []
    for dt in timestamps:
        line = _pick_pattern()(_ts(dt), random.choice(SERVICES))
        if realtime:
            print(line, flush=True)
            time.sleep(1 / realtime_rate)
        else:
            lines.append(line)

    if not realtime:
        output = "\n".join(lines)
        if output_file:
            with open(output_file, "w") as f:
                f.write(output + "\n")
            print(f"✓ Wrote {count} log lines to '{output_file}'")
        else:
            print(output)


# ──────────────────────────────────────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate realistic app logs with 80/20 Pareto repetition."
    )
    parser.add_argument("-n", "--count",  type=int,   default=500,
                        help="Number of log lines (default: 500)")
    parser.add_argument("-o", "--output", type=str,   default=None,
                        help="Output file path (default: stdout)")
    parser.add_argument("--spread",       type=int,   default=3600,
                        help="Time window in seconds (default: 3600)")
    parser.add_argument("--realtime",     action="store_true",
                        help="Stream logs in real time")
    parser.add_argument("--rate",         type=float, default=5.0,
                        help="Logs per second for --realtime (default: 5)")
    parser.add_argument("--seed",         type=int,   default=None,
                        help="Random seed for reproducible output")
    parser.add_argument("--stats",        action="store_true",
                        help="Show pattern weight distribution and exit")
    args = parser.parse_args()

    if args.stats:
        print(f"\n{'Weight':>7}  {'Share':>6}  Sample")
        print("─" * 80)
        for fn, w in sorted(zip(_POPULATION, _WEIGHTS), key=lambda x: -x[1]):
            sample = fn("...", "svc")[:72]
            print(f"{w:>7}  {w/_TOTAL_WEIGHT*100:>5.1f}%  {sample}")
        print(f"\nTotal weight: {_TOTAL_WEIGHT}  |  Patterns: {len(PATTERNS)}")
        top5 = sum(sorted(_WEIGHTS, reverse=True)[:5])
        print(f"Top-5 share: {top5/_TOTAL_WEIGHT*100:.1f}% of all output")
        return

    if args.seed is not None:
        random.seed(args.seed)

    generate_logs(
        count=args.count,
        time_spread_seconds=args.spread,
        output_file=args.output,
        realtime=args.realtime,
        realtime_rate=args.rate,
    )

if __name__ == "__main__":
    main()