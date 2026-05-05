import random
from datetime import datetime, timedelta

HTTP_METHODS = ["GET", "POST", "PUT", "DELETE"]

ENDPOINTS = [
    "/", "/login", "/logout", "/signup", "/dashboard",
    "/api/user", "/api/order", "/api/payment",
    "/api/products", "/api/cart", "/health",
    "/search", "/checkout", "/profile", "/settings"
]

STATUS_CODES = [
    (200, 0.75),
    (301, 0.05),
    (400, 0.05),
    (401, 0.05),
    (404, 0.07),
    (500, 0.03),
]

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64) Firefox/120.0",
    "curl/7.68.0",
    "PostmanRuntime/7.36.0",
    "Googlebot/2.1 (+http://www.google.com/bot.html)"
]

REFERERS = [
    "-",
    "https://google.com",
    "https://bing.com",
    "https://example.com",
    "https://news.ycombinator.com"
]

def random_ip():
    return ".".join(str(random.randint(1, 255)) for _ in range(4))

def weighted_status():
    r = random.random()
    cumulative = 0
    for code, weight in STATUS_CODES:
        cumulative += weight
        if r < cumulative:
            return code
    return 200

def nginx_timestamp(dt):
    return dt.strftime("%d/%b/%Y:%H:%M:%S +0000")

def random_request():
    method = random.choice(HTTP_METHODS)
    endpoint = random.choice(ENDPOINTS)
    protocol = "HTTP/1.1"
    return f"{method} {endpoint} {protocol}"

def generate_log(start_time):
    dt = start_time + timedelta(seconds=random.randint(0, 86400))

    ip = random_ip()
    user_ident = "-"
    user_auth = "-"

    time_local = nginx_timestamp(dt)
    request = random_request()
    status = weighted_status()
    bytes_sent = random.randint(200, 5000)

    referer = random.choice(REFERERS)
    user_agent = random.choice(USER_AGENTS)

    return (
        f'{ip} {user_ident} {user_auth} [{time_local}] '
        f'"{request}" {status} {bytes_sent} '
        f'"{referer}" "{user_agent}"'
    )

COUNT = 1000
def generate_logs(n=COUNT, output_file="nginx_access.log"):
    start_time = datetime.utcnow() - timedelta(days=1)

    with open(output_file, "w") as f:
        for _ in range(n):
            f.write(generate_log(start_time) + "\n")

    print(f"Generated {n} nginx logs in {output_file}")

if __name__ == "__main__":
    generate_logs(COUNT)