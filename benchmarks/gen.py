#!/usr/bin/env python3
"""
Generate synthetic log lines for benchmarking LogSlim.

Output mixes 10 templates with realistic dynamic fields (timestamps, IDs,
IPs, latencies). Output is deterministic for a given line count — useful
for repeatable benchmarks.

Usage:
    python3 gen.py [LINES] > out.log
    python3 gen.py 1000000 > 1m.log
"""
import random
import sys
from datetime import datetime, timedelta

TEMPLATES = [
    "{ts} INFO  user_id={uid} action=login from {ip}",
    "{ts} INFO  user_id={uid} action=logout from {ip}",
    "{ts} ERROR payment_id={pid} status=failed reason='card declined' user={uid}",
    "{ts} DEBUG cache_hit key={key} latency={lat}ms",
    "{ts} WARN  request_id={rid} retry_count={rc} endpoint=/api/{ep}",
    "{ts} INFO  job_id={jid} duration={dur}ms processed {count} items",
    "{ts} ERROR database connection failed: {host}:{port}",
    "{ts} INFO  Server started on port {port}",
    "{ts} DEBUG processing batch of {count} messages from {topic}",
    "{ts} TRACE function={fn} elapsed={lat}us",
]


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100_000
    random.seed(42)

    base = datetime(2024, 1, 1, 0, 0, 0)
    eps = [f"users", "orders", "items", "auth", "metrics"]
    hosts = ["db-primary", "db-replica1", "db-replica2"]
    ports = [5432, 6379, 8080, 9092]
    topics = ["orders", "events", "metrics"]
    fns = ["fetchUser", "writeRow", "encode", "decode", "validate"]

    out = sys.stdout.write
    for i in range(n):
        t = base + timedelta(seconds=i / 100.0)
        ts = t.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        tmpl = TEMPLATES[i % len(TEMPLATES)]
        line = tmpl.format(
            ts=ts,
            uid=random.randint(1000, 99999),
            ip=f"{random.randint(10,99)}.{random.randint(10,99)}."
               f"{random.randint(10,99)}.{random.randint(10,99)}",
            pid=f"pmt_{random.randint(10**9, 10**10 - 1)}",
            key=f"k_{random.randint(0, 100000)}",
            lat=random.randint(1, 1000),
            rid=f"req-{random.randint(10**5, 10**6 - 1)}",
            rc=random.randint(1, 5),
            ep=random.choice(eps),
            jid=f"job-{random.randint(0, 10000)}",
            dur=random.randint(10, 5000),
            count=random.randint(1, 1000),
            host=random.choice(hosts),
            port=random.choice(ports),
            topic=random.choice(topics),
            fn=random.choice(fns),
        )
        out(line)
        out("\n")


if __name__ == "__main__":
    main()
