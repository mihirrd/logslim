import random
from datetime import datetime, timedelta

SERVICES = [
    "auth-service", "payment-service", "order-service",
    "user-service", "inventory-service", "notification-service"
]

LOG_LEVELS = ["INFO", "WARN", "ERROR"]

MESSAGES = [
    "User login successful",
    "Payment processed successfully",
    "Order created successfully",
    "Cache miss for key user_session",
    "API request received",
    "Background job started",
    "Background job completed",
    "Database connection established",
    "Configuration loaded successfully",
]

EXCEPTIONS = [
    "NullPointerException",
    "TimeoutException",
    "SQLException",
    "IOException",
    "IllegalArgumentException",
    "RuntimeException",
    "AuthenticationException",
    "PaymentProcessingException"
]

FUNCTIONS = [
    "processRequest", "handleLogin", "validateUser",
    "executeTransaction", "loadConfiguration", "fetchData",
    "updateInventory", "sendNotification", "persistOrder"
]

FILES = [
    "AuthController.java", "PaymentService.java", "UserRepository.java",
    "OrderService.java", "DatabaseClient.java", "CacheManager.java"
]

def random_timestamp(start):
    dt = start + timedelta(seconds=random.randint(0, 86400))
    return dt.strftime("%Y-%m-%d %H:%M:%S")

def generate_stack_trace(exception):
    depth = random.randint(4, 10)
    trace = [f"{exception}: Something went wrong while processing request"]

    for i in range(depth):
        file = random.choice(FILES)
        func = random.choice(FUNCTIONS)
        line = random.randint(10, 500)

        trace.append(f"\tat com.example.{func}({file}:{line})")

    # Caused by chain (sometimes)
    if random.random() < 0.4:
        cause = random.choice(EXCEPTIONS)
        trace.append(f"Caused by: {cause}: Underlying failure detected")
        for i in range(3):
            file = random.choice(FILES)
            func = random.choice(FUNCTIONS)
            line = random.randint(10, 500)
            trace.append(f"\tat com.example.{func}({file}:{line})")

    return "\n".join(trace)

def generate_log(start_time):
    timestamp = random_timestamp(start_time)
    service = random.choice(SERVICES)
    level = random.choices(LOG_LEVELS, weights=[0.7, 0.2, 0.1])[0]

    # 20% chance of error with stack trace
    is_error = random.random() < 0.2

    if is_error:
        exception = random.choice(EXCEPTIONS)
        message = random.choice(MESSAGES)

        stack_trace = generate_stack_trace(exception)

        return (
            f"{timestamp} | {service} | ERROR | {message}\n"
            f"{stack_trace}"
        )
    else:
        message = random.choice(MESSAGES)
        return f"{timestamp} | {service} | {level} | {message}"


COUNT = 1000
def generate_logs(n=COUNT, output_file="app_multiline.log"):
    start_time = datetime.now() - timedelta(days=1)

    logs = []
    for _ in range(n):
        logs.append(generate_log(start_time))

    with open(output_file, "w") as f:
        f.write("\n\n".join(logs))  # double newline separates log events

    print(f"Generated {n} multiline logs in {output_file}")

if __name__ == "__main__":
    generate_logs(COUNT)