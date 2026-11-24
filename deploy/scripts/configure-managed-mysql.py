"""Server-side bootstrap. Receive administrator credentials only via stdin."""
import base64
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys


def main():
    os.umask(0o077)
    settings = json.loads(base64.b64decode(sys.stdin.read().strip(), validate=True).decode("utf-8"))
    host = str(ipaddress.IPv4Address(settings["host"]))
    if not ipaddress.ip_address(host).is_private:
        raise ValueError("Use the managed database's private IPv4 address")
    port = int(settings["port"])
    username = settings["username"]
    if not 1 <= port <= 65535 or not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]{0,31}", username):
        raise ValueError("Invalid port or administrator username")
    password = settings["password"]
    if not password:
        raise ValueError("Administrator password is required")
    root = Path("/opt/streaming")
    shared = root / "shared"
    shared.mkdir(mode=0o700, exist_ok=True)
    with (root / "deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        destination = shared / "backend.env"
        if destination.exists():
            raise RuntimeError("backend.env already exists; preserved without modification")
        with socket.create_connection((host, port), timeout=8):
            pass
        subprocess.run(["docker", "pull", "mysql:8.4"], check=True, stdout=subprocess.DEVNULL)

        def mysql(user, user_password, sql):
            env = dict(os.environ, MYSQL_PWD=user_password)
            result = subprocess.run(
                ["docker", "run", "--rm", "-i", "--network", "host", "--env", "MYSQL_PWD",
                 "mysql:8.4", "mysql", "--host", host, "--port", str(port),
                 "--user", user, "--ssl-mode=REQUIRED", "--batch", "--skip-column-names"],
                input=sql, text=True, capture_output=True, env=env, timeout=120)
            if result.returncode:
                # Never emit SQL text or error context that might contain credentials.
                code = re.search(r"ERROR (\d+)", result.stderr)
                raise RuntimeError("MySQL operation failed" + (" (error " + code.group(1) + ")" if code else ""))
            return result.stdout.strip()

        mysql(username, password, "SELECT 1;")
        # A private draft allows retrying partial setup without losing generated credentials.
        draft_file = shared / "backend-bootstrap.json"
        if draft_file.exists():
            draft = json.loads(draft_file.read_text())
            if draft["host"] != host or draft["port"] != port:
                raise RuntimeError("An unfinished bootstrap exists for a different database")
        else:
            draft = {"host": host, "port": port, "app_password": "Aa1!" + secrets.token_hex(14),
                     "redis_password": secrets.token_hex(32), "sentinel_password": secrets.token_hex(32),
                     "jwt": base64.b64encode(secrets.token_bytes(32)).decode("ascii")}
            with draft_file.open("x") as output:
                json.dump(draft, output)

        mysql(username, password, "CREATE DATABASE IF NOT EXISTS streaming CHARACTER SET utf8mb4;")
        tables = set(mysql(username, password,
                           "SELECT table_name FROM information_schema.tables WHERE table_schema='streaming';").splitlines())
        expected = {"users", "event_entity", "seat_entity", "reservation_entity", "payments"}
        if not tables:
            schema = Path(__file__).resolve().parents[1] / "backend/init/001-schema.sql"
            mysql(username, password, "USE streaming;\n" + schema.read_text())
        elif tables != expected:
            raise RuntimeError("Existing/partial schema requires manual inspection; no tables were dropped")
        app_password = draft["app_password"]  # Generated alphabet excludes SQL quotes/backslashes.
        mysql(username, password,
              "CREATE USER IF NOT EXISTS 'streaming_app'@'%' IDENTIFIED BY '" + app_password + "' REQUIRE SSL;\n"
              "GRANT SELECT, INSERT, UPDATE, DELETE ON streaming.* TO 'streaming_app'@'%';")
        mysql("streaming_app", app_password, "USE streaming; SELECT COUNT(*) FROM users;")
        tls_version = mysql("streaming_app", app_password, "SHOW SESSION STATUS LIKE 'Ssl_version';")
        if not re.fullmatch(r"Ssl_version\tTLSv1\.[23]", tls_version):
            raise RuntimeError("Encrypted application connection could not be verified")
        values = {
            "DB_URL": "jdbc:mysql://" + host + ":" + str(port) + "/streaming?sslMode=REQUIRED&serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
            "DB_USERNAME": "streaming_app", "DB_PASSWORD": app_password,
            "REDIS_PASSWORD": draft["redis_password"], "REDIS_SENTINEL_PASSWORD": draft["sentinel_password"],
            "JWT_SECRET": draft["jwt"], "PAYMENT_GATEWAY": "fake", "TOSS_SECRET_KEY": "", "TOSS_CLIENT_KEY": ""}
        temporary = shared / "backend.env.pending"
        with temporary.open("w") as output:
            for key, value in values.items():
                output.write(key + "='" + value + "'\n")
        os.replace(temporary, destination)
        destination.chmod(0o600)
        draft_file.unlink()
        print("Managed MySQL ready: schema, application account, encrypted connection verified")
        print("Server environment saved with mode 600; administrator password was not saved")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Avoid showing a traceback containing SQL or credential-bearing local variables.
        print("Setup failed: " + str(error), file=sys.stderr)
        sys.exit(1)
