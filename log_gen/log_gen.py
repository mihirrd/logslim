import random
from datetime import datetime, timedelta

LOG_LEVELS = ["INFO", "WARN", "ERROR"]

SERVICES = [
    "auth-service", "payment-service", "user-service",
    "order-service", "inventory-service", "notification-service"
]

# 50 different log message templates
MESSAGES = [
    "User login successful",
    "User login failed due to invalid credentials",
    "Database connection established",
    "Database connection timeout",
    "Cache miss for key user_session",
    "Cache hit for key product_list",
    "Payment processed successfully",
    "Payment failed due to insufficient funds",
    "Order created successfully",
    "Order validation failed",
    "Inventory updated for product_id={}",
    "Inventory out of stock for product_id={}",
    "Email notification sent to user_id={}",
    "Email delivery failed for user_id={}",
    "API request received",
    "API request timed out",
    "Service started successfully",
    "Service shutdown initiated",
    "Unhandled exception occurred in module {}",
    "Retrying failed request attempt {}",
    "Circuit breaker opened for service {}",
    "Circuit breaker closed for service {}",
    "Memory usage at {}%",
    "CPU usage at {}%",
    "Disk space warning: {}% used",
    "New user registered with user_id={}",
    "User profile updated for user_id={}",
    "Session expired for user_id={}",
    "Token refreshed successfully",
    "Invalid token detected",
    "Rate limit exceeded for IP {}",
    "Background job started: {}",
    "Background job completed: {}",
    "Queue size is {} messages",
    "Message published to topic {}",
    "Message consumed from topic {}",
    "Connection reset by peer",
    "SSL handshake failed",
    "Configuration loaded successfully",
    "Configuration reload triggered",
    "Feature flag {} enabled",
    "Feature flag {} disabled",
    "Third-party API latency {} ms",
    "Third-party API error response",
    "User permissions updated",
    "Access denied for user_id={}",
    "Transaction rolled back",
    "Transaction committed successfully",
    "Service dependency {} is unhealthy",
    "Service dependency {} recovered",
]

def generate_timestamp(start_time):
    """Generate a random timestamp within last 24 hours."""
    delta = timedelta(seconds=random.randint(0, 24 * 60 * 60))
    return start_time + delta

def generate_log(start_time):
    level = random.choices(
        LOG_LEVELS, weights=[0.7, 0.2, 0.1], k=1
    )[0]

    service = random.choice(SERVICES)
    message_template = random.choice(MESSAGES)

    # Fill placeholders if needed
    message = message_template.format(
        random.randint(1, 100),
        random.randint(1, 10),
        random.randint(0, 100),
        random.choice(SERVICES),
        random.randint(100, 500)
    )

    timestamp = generate_timestamp(start_time).strftime("%Y-%m-%d %H:%M:%S")

    return f"{timestamp} | {level} | {service} | {message}"

def generate_logs(n=100):
    start_time = datetime.now() - timedelta(days=1)
    logs = [generate_log(start_time) for _ in range(n)]
    return logs

COUNT = 100000
if __name__ == "__main__":
    logs = generate_logs(COUNT)

    with open("app_logs.log", "w") as f:
        for log in logs:
            f.write(log + "\n")

    print(f"Generated {COUNT} logs in app_logs.txt")