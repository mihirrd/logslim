import random
from datetime import datetime, timedelta

HOSTNAME = "server-01"

FACILITIES = [
    "auth", "cron", "daemon", "kernel", "syslog",
    "network", "sshd", "nginx", "postgres", "docker"
]

PROCESSES = [
    "sshd", "systemd", "cron", "kernel", "nginx",
    "docker", "postgres", "NetworkManager", "dbus-daemon",
    "rsyslogd", "auth-service", "backup-service"
]

SEVERITIES = [
    "info", "notice", "warning", "err", "crit"
]

MESSAGES = [
    "Accepted password for user {} from 192.168.1.{} port {} ssh2",
    "Failed password for invalid user {} from 10.0.0.{} port {} ssh2",
    "Connection closed by authenticating user {} 192.168.1.{} port {}",
    "pam_unix(sshd:auth): authentication failure",
    "session opened for user {} by (uid=0)",
    "session closed for user {}",
    "system boot completed in {} seconds",
    "Started Daily Cleanup Service",
    "Started Docker Application Container Engine",
    "Stopped target Multi-User System",
    "kernel: CPU temperature above threshold, cpu clock throttled",
    "kernel: Out of memory: Kill process {} ({}) score {}",
    "kernel: TCP connection reset by peer",
    "nginx: worker process {} exited on signal 11",
    "nginx: *{} client {} requested invalid URL",
    "postgres: connection authorized: user={}",
    "postgres: checkpoint complete: wrote {} buffers",
    "docker: Container {} started",
    "docker: Container {} stopped",
    "network: DHCP lease acquired for eth0",
    "network: Interface eth0 down",
    "network: Interface eth0 up",
    "systemd: Starting {} service...",
    "systemd: Started {} service",
    "cron: Job '{}' executed successfully",
    "cron: Error running job '{}'",
    "rsyslogd: action 'action-0-builtin' resumed",
    "rsyslogd: imklog lost {} messages from kernel",
    "auth: user {} added to group {}",
    "auth: invalid sudo attempt for user {}",
    "disk: /dev/sda1 usage at {}%",
    "disk: filesystem mounted on /var/log",
    "backup: backup completed successfully in {} minutes",
    "backup: backup failed due to IO error",
    "ssh: Received disconnect from 192.168.1.{}",
    "sshd: PAM service(sshd) ignoring max retries",
    "kernel: segfault at address {} ip {}",
    "kernel: module {} loaded successfully",
    "kernel: module {} verification failed",
    "systemd: Dependency failed for {}",
    "systemd: Job {} started",
    "network: DNS resolution failed for {}",
    "network: DNS resolved {} -> 8.8.8.8",
    "postgres: deadlock detected",
    "postgres: autovacuum launched",
    "docker: image {} pulled successfully",
    "docker: network bridge created",
    "auth: user {} password expired",
    "auth: account locked for user {}",
]

def syslog_timestamp(dt):
    # Linux syslog format: "May  4 23:12:33"
    return dt.strftime("%b %d %H:%M:%S")

def random_process():
    return random.choice(PROCESSES)

def random_pid():
    return random.randint(100, 50000)

def format_message(template):
    return template.format(
        random.randint(1, 1000),
        random.randint(1, 255),
        random.randint(1024, 65535),
        random.choice(PROCESSES),
        random.randint(100, 999),
        random.randint(1, 100),
        random.choice(PROCESSES),
        random.randint(1, 100),
        random.randint(1000, 9999),
        random.choice(["alice", "bob", "charlie", "david", "eve"]),
        random.choice(["admin", "sudo", "users", "wheel"]),
        random.randint(1, 100)
    )

def generate_log(start_time):
    dt = start_time + timedelta(seconds=random.randint(0, 24 * 60 * 60))

    timestamp = syslog_timestamp(dt)
    facility = random.choice(FACILITIES)
    severity = random.choice(SEVERITIES)
    process = random_process()
    pid = random_pid()

    template = random.choice(MESSAGES)
    message = format_message(template)

    return f"{timestamp} {HOSTNAME} {process}[{pid}]: {facility}.{severity}: {message}"

def generate_logs(n=200):
    start_time = datetime.now() - timedelta(days=1)
    return [generate_log(start_time) for _ in range(n)]

COUNT = 1000
if __name__ == "__main__":
    logs = generate_logs(COUNT)

    with open("linux_syslog.log", "w") as f:
        for log in logs:
            f.write(log + "\n")

    print(f"Generated linux_syslog.log with {COUNT} entries")