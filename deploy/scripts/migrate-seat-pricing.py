"""Apply the additive seat-pricing migration; receive administrator credentials via stdin."""
import base64
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    os.umask(0o077)
    raw = sys.stdin.buffer.read(65537)
    if len(raw) > 65536:
        raise ValueError("Credential payload is too large")
    settings = json.loads(base64.b64decode(raw.strip(), validate=True))
    host = str(ipaddress.IPv4Address(settings["host"]))
    port = int(settings["port"])
    user = settings["username"]
    if not ipaddress.ip_address(host).is_private or not 1 <= port <= 65535:
        raise ValueError("A private database IPv4 address and valid port are required")
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]{0,31}", user) or not settings["password"]:
        raise ValueError("Administrator credentials are required")
    migration = Path(__file__).resolve().parents[1] / "backend/migrations/002-seat-grade-pricing.sql"
    sql = migration.read_text(encoding="utf-8")
    # Serialize with deployment/bootstrap. Do not modify the application environment or credentials.
    with Path("/opt/streaming/deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = subprocess.run(
            ["docker", "run", "--rm", "-i", "--network", "host", "--env", "MYSQL_PWD",
             "mysql:8.4", "mysql", "--host", host, "--port", str(port), "--user", user,
             "--ssl-mode=REQUIRED", "--batch", "--skip-column-names", "--connect-timeout=8"],
            input=sql, text=True, capture_output=True, timeout=180,
            env=dict(os.environ, MYSQL_PWD=settings["password"]))
        if result.returncode:
            code = re.search(r"ERROR (\d+)", result.stderr)
            raise RuntimeError("MySQL migration failed" + (" (error " + code.group(1) + ")" if code else ""))
    print("Seat pricing migration completed; existing rows and credentials preserved")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not echo SQL, credential values, subprocess output, or decoded input on failures.
        code = re.search(r"\(error \d+\)", str(error))
        print("Migration failed" + (" " + code.group(0) if code else " (check input, connectivity and schema)"),
              file=sys.stderr)
        sys.exit(1)
