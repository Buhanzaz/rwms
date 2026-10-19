#!/usr/bin/env python3
"""Interactive Ubuntu 24.04 single-host deployment. Never run via curl | shell.

configure/plan are non-deploying. install/update/resume require explicit consent.
Existing foreign installations are not adopted. No automatic rollback or deletion.
"""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import secrets
import selectors
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.parse import quote
from urllib.request import urlopen

from configuration import (
    APPS, BRIDGE_IP, CLIENT_SECRETS, DATABASES, INTERNAL_SECRETS, JAVA, NETWORK,
    PLANNER_PROJECT, PROJECT, SECRET_KEYS, SUBNET, compose_json, core_compose,
    db_user, domain_name, fingerprint, kafka_topics, load_env, nginx_config,
    planner_override, private_write, save_env, validate, worker_app_links,
)

REPO = Path(__file__).resolve().parents[2]
HOME = Path("/var/lib/rwms-managed")
CONFIG = Path("/etc/rwms-managed")
NGINX = Path("/etc/nginx/conf.d/rwms-managed.conf")
STATE = HOME / "state.json"
STEPS = [
    ("preflight", "Проверка ОС, исходников, DNS, ресурсов и прав", 5),
    ("tools", "Установка/проверка Docker, Nginx, Certbot", 5),
    ("network", "Приватная сеть и HTTPS Let's Encrypt", 10),
    ("render", "Формирование закрытой конфигурации релиза", 5),
    ("build", "Сборка Java, Go, web и planner образов", 25),
    ("quiesce", "Окно обслуживания и резервные копии БД", 5),
    ("infra", "PostgreSQL, Kafka, MinIO и канонические topics", 10),
    ("routing", "OSM, Valhalla и индекс грузовых ограничений", 10),
    ("migrations", "Ключ подписи и Flyway для media", 5),
    ("services", "Запуск и health checks основных сервисов", 10),
    ("planner", "Запуск planner и всех web-поверхностей", 5),
    ("verify", "Проверка HTTPS, OIDC и публикация релиза", 5),
]


class DeployError(RuntimeError):
    pass


def prompt(label: str, default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    return input(f"{label}{suffix}: ").strip() or default


def ask_secret(label: str, *, generate: bool = False, optional: bool = False, minimum: int = 1) -> str:
    print(label)
    print("  Ввод скрыт. " + ("Enter — сгенерировать криптографически случайное значение." if generate else
                             "Enter — пропустить эту необязательную интеграцию." if optional else "Значение обязательно."))
    while True:
        value = getpass.getpass("  Значение: ")
        if not value and generate:
            return base64.b64encode(secrets.token_bytes(36)).decode("ascii")
        if not value and optional:
            return ""
        if len(value) < minimum or any(ord(c) < 32 for c in value):
            print(f"  Нужна одна строка, не менее {minimum} символов.")
            continue
        if value == getpass.getpass("  Повторите: "):
            return value
        print("  Значения не совпали.")


def wizard(path: Path) -> None:
    if path.exists():
        raise DeployError(".env уже существует. Не перезаписываем пароли и DB credentials. Используйте plan/update.")
    if not sys.stdin.isatty():
        raise DeployError("configure требует интерактивный терминал; секреты не передавайте в аргументах")
    print("\nRWMS: мастер конфигурации. Пока ничего не устанавливаем и не запускаем.")
    print("Домен задается один раз: origin, issuer, callbacks, TLS и planner будут согласованы.")
    c = {"DOMAIN": domain_name(prompt("1. Домен, например wms.company.ru")),
         "PUBLIC_IPV4": prompt("2. Публичный IPv4, назначенный сетевому интерфейсу этого VPS"),
         "ACME_EMAIL": prompt("3. Email администратора для Let's Encrypt"),
         "AUTH_BOOTSTRAP_ADMIN_USERNAME": prompt("4. Логин первоначального SYSTEM_ADMIN", "admin")}
    c["AUTH_BOOTSTRAP_ADMIN_PASSWORD"] = ask_secret("Пароль администратора (вход в panel/admin)", generate=True, minimum=12)
    print("\n5. Внутренние секреты: отдельные БД, OAuth-клиенты, подпись ссылок, MinIO.")
    print("Для новой установки рекомендуются независимые случайные значения. Они сохраняются при обновлении.")
    custom = prompt("Вводить каждый внутренний секрет самостоятельно? yes/NO", "no") == "yes"
    for key in INTERNAL_SECRETS:
        if custom and key == "TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET":
            print("  Этот ключ: standard Base64, минимум 32 decoded bytes; Enter генерирует корректный ключ.")
        c[key] = ask_secret(key, generate=True, minimum=32) if custom else base64.b64encode(secrets.token_bytes(36)).decode("ascii")
    print("\n6. Ассистент требует совместимый LLM API. API-ключ не является паролем администратора.")
    c["LLM_BASE_URL"] = prompt("HTTPS base URL провайдера", "https://api.mistral.ai/v1")
    c["LLM_MODEL"] = prompt("Модель", "mistral-large-latest")
    c["LLM_API_KEY"] = ask_secret("LLM_API_KEY: получите в кабинете выбранного провайдера")
    print("\n7. Yandex — необязательно. Без ключей маршруты Valhalla работают, поиск адресов Yandex — нет.")
    c["YANDEX_GEOSUGGEST_API_KEY"] = ask_secret("Ключ Geosuggest", optional=True)
    c["YANDEX_GEOCODER_API_KEY"] = ask_secret("Ключ Geocoder", optional=True)
    print("Ключ карты Yandex Maps v3 настраивается отдельно через /admin/logistics после входа.")
    print("\n8. Синхронизация planner включает получение заказов и публикацию capacity в RWMS.")
    c["PLANNER_SYNC_ENABLED"] = "true" if prompt("Включить интеграцию? YES/no", "yes") == "yes" else "false"
    validate(c)
    save_env(path, c)
    print(f"\nСохранено: {path} (0600). Секреты не выводятся. Сохраните файл в защищенном backup.")
    print("Следующий шаг: deploy.sh plan; затем после проверки — sudo deploy.sh install.")


class Runner:
    """Bounded subprocesses with heartbeat, redaction and exact exit-code propagation."""
    def __init__(self, directory: Path, values: dict[str, str], heartbeat: float = 5):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.tokens = sorted({variant for key in SECRET_KEYS for variant in
                              (values.get(key, ""), quote(values.get(key, ""), safe=""),
                               json.dumps(values.get(key, ""))[1:-1]) if variant}, key=len, reverse=True)
        self.heartbeat = heartbeat
        self.number = 0
        # Do not inherit exported secrets or Compose interpolation from the login shell.
        self.env = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "HOME": "/root", "LANG": "C.UTF-8", "DEBIAN_FRONTEND": "noninteractive",
                    "DOCKER_BUILDKIT": "1"}

    def redact(self, text: str) -> str:
        text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)
        for value in self.tokens:
            text = text.replace(value, "[REDACTED]")
        return text

    def run(self, title: str, args: list[str], *, timeout: int = 1800, cwd: Path = REPO,
            binary_output: Path | None = None) -> None:
        self.number += 1
        log_path = self.directory / f"{self.number:03d}.log"
        started, last_beat, count = time.monotonic(), 0.0, 0
        print(f"  → {title}\n    Журнал: {log_path}", flush=True)
        with log_path.open("w") as log:
            os.chmod(log_path, 0o600)
            sink = binary_output.open("wb") if binary_output else None
            try:
                process = subprocess.Popen(args, cwd=cwd, env=self.env, stdin=subprocess.DEVNULL,
                    stdout=sink if sink else subprocess.PIPE, stderr=subprocess.PIPE if sink else subprocess.STDOUT,
                    start_new_session=True)
                pipe = process.stderr if sink else process.stdout
                pending = b""
                dropping_line = False
                with selectors.DefaultSelector() as selector:
                    selector.register(pipe, selectors.EVENT_READ)
                    try:
                        while selector.get_map():
                            now = time.monotonic()
                            if now - started > timeout:
                                raise DeployError(f"Таймаут {timeout}s: {title}. Смотрите {log_path}")
                            for key, _ in selector.select(timeout=0.5):
                                chunk = os.read(key.fileobj.fileno(), 65536)
                                if not chunk:
                                    selector.unregister(key.fileobj)
                                    continue
                                count += len(chunk)
                                if dropping_line:
                                    if b"\n" not in chunk:
                                        continue
                                    chunk = chunk.split(b"\n", 1)[1]
                                    dropping_line = False
                                pending += chunk
                                lines = pending.split(b"\n")
                                pending = lines.pop()
                                for line in lines:
                                    log.write(self.redact(line.decode(errors="replace")) + "\n")
                                # Never cut a secret across a log flush. Discard oversized
                                # unterminated lines until their delimiter, keeping memory bounded.
                                if len(pending) > 262144:
                                    log.write("[oversized unterminated output omitted]\n")
                                    pending = b""
                                    dropping_line = True
                                log.flush()
                            if now - last_beat >= self.heartbeat:
                                size = sink.tell() if sink else count
                                print(f"    Работа выполняется: {int(now-started)}s; получено {size} байт вывода. "
                                      "Это heartbeat, не гарантия полезного прогресса.", flush=True)
                                last_beat = now
                        log.write(self.redact(pending.decode(errors="replace")))
                        remaining = max(1, timeout - int(time.monotonic() - started))
                        code = process.wait(timeout=remaining)
                        if code:
                            raise DeployError(f"{title}: exit={code}; журнал {log_path}")
                    except BaseException:
                        if process.poll() is None:
                            os.killpg(process.pid, signal.SIGTERM)
                            try:
                                process.wait(timeout=10)
                            except subprocess.TimeoutExpired:
                                os.killpg(process.pid, signal.SIGKILL)
                                process.wait()
                        raise
                    finally:
                        pipe.close()
            finally:
                if sink:
                    sink.close()
                    os.chmod(binary_output, 0o600)
        print(f"    ✓ {title}: {int(time.monotonic()-started)}s", flush=True)


def capture(args: list[str], cwd: Path = REPO) -> str:
    """Only for small, non-secret metadata, never Compose resolved configuration."""
    if args and args[0] == "git":
        args = ["git", "-c", f"safe.directory={REPO}", *args[1:]]
    result = subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=30,
                            env={"PATH": os.environ.get("PATH", ""), "HOME": "/root", "LANG": "C.UTF-8"})
    if result.returncode:
        raise DeployError(f"Не удалось выполнить {args[0]} (код {result.returncode}; проверьте вручную)")
    return result.stdout.strip()


def health(url: str, timeout: int = 600, *, json_key: str | None = None, expected: str | None = None) -> None:
    end, started = time.monotonic() + timeout, time.monotonic()
    while True:
        try:
            with urlopen(url, timeout=5) as response:
                body = response.read(1048576)
                if response.status == 200:
                    if json_key is None or json.loads(body).get(json_key) == expected:
                        print(f"    ✓ {url}")
                        return
        except (URLError, OSError, ValueError):
            pass
        if time.monotonic() >= end:
            raise DeployError(f"Не дождались health: {url} за {timeout}s. Откат не выполнялся.")
        print(f"    Ожидание {url}: {int(time.monotonic()-started)}s / лимит {timeout}s", flush=True)
        time.sleep(5)


def read_state() -> dict:
    if not STATE.is_file() or STATE.is_symlink():
        raise DeployError("Нет managed state. Чужую установку не обновляем; сначала отдельный план миграции.")
    data = json.loads(STATE.read_text())
    if data.get("schema") != 1 or data.get("repo") != str(REPO):
        raise DeployError("State принадлежит другой схеме/checkout. Автоматическое принятие запрещено.")
    return data


def store_state(state: dict) -> None:
    private_write(STATE, json.dumps(state, indent=2) + "\n")


@contextmanager
def lock():
    HOME.mkdir(parents=True, exist_ok=True, mode=0o700)
    CONFIG.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (HOME / "deploy.lock").open("w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DeployError("Другое развертывание уже работает") from None
        yield


class Deployment:
    def __init__(self, mode: str, c: dict[str, str], backup: str):
        self.c, self.mode, self.backup = c, mode, backup
        self.sha = capture(["git", "rev-parse", "HEAD"])
        if mode == "install":
            if STATE.exists():
                raise DeployError("Установка уже зарегистрирована. Используйте update или resume после ошибки.")
            self.state = {"schema": 1, "repo": str(REPO), "config_hash": fingerprint(c),
                          "phase": "installing", "mode": "install", "previous": None,
                          "tag": self.sha[:12] + "-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"),
                          "sha": self.sha, "backup_reference": "", "maintenance": False, "percent": 0, "stage": "pending"}
        else:
            self.state = read_state()
            if self.state["config_hash"] != fingerprint(c):
                raise DeployError(".env изменен. Автоматическая смена домена/паролей не поддерживается; не ротируйте DB env без самой БД.")
            if mode == "update":
                if self.state["phase"] != "ready":
                    raise DeployError("Предыдущий запуск не завершен. Сначала диагностика и resume.")
                if not backup or not Path(backup).is_file() or Path(backup).stat().st_size == 0:
                    raise DeployError("update требует --backup-reference: файл-отчет о проверенном полном backup вне репозитория")
                if Path(backup).resolve().is_relative_to(REPO):
                    raise DeployError("Backup/отчет хранить вне Git checkout")
                self.state = {**self.state, "phase": "updating", "mode": "update",
                    "previous": self.state["tag"], "tag": self.sha[:12] + "-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"),
                    "sha": self.sha, "backup_reference": str(Path(backup).resolve()), "maintenance": False, "percent": 0, "stage": "pending"}
            elif self.state["phase"] == "ready":
                raise DeployError("Нет незавершенного развертывания. Используйте update.")
            elif self.state["sha"] != self.sha:
                raise DeployError("Для resume требуется тот же SHA. Не собирайте смешанный релиз.")
        self.tag = self.state["tag"]
        self.release = HOME / "releases" / self.tag
        self.runner = Runner(HOME / "logs" / (self.tag + "-" + str(time.time_ns())), c)
        self.state["log_directory"] = str(self.runner.directory)
        self.core = self.release / "core.json"
        self.planner = self.release / "planner.json"

    def core_cmd(self, *args: str, release: Path | None = None) -> list[str]:
        file = (release / "core.json") if release else self.core
        return ["docker", "compose", "--project-name", PROJECT, "--env-file", "/dev/null", "-f", str(file), *args]

    def planner_cmd(self, *args: str, release: Path | None = None) -> list[str]:
        file = (release / "planner.json") if release else self.planner
        return ["docker", "compose", "--project-name", PLANNER_PROJECT, "--env-file", "/dev/null", "-f", str(file), *args]

    def preflight(self) -> None:
        if platform.machine() != "x86_64":
            raise DeployError("v1 поддерживает Ubuntu 24.04 amd64; другие архитектуры не проверены")
        os_release = Path("/etc/os-release").read_text()
        if 'ID=ubuntu\n' not in os_release or 'VERSION_ID="24.04"' not in os_release:
            raise DeployError("Поддерживается только Ubuntu 24.04")
        if capture(["git", "status", "--porcelain"]):
            raise DeployError("Рабочее дерево не чистое. Сначала согласуйте/зафиксируйте все изменения.")
        if any(part.isspace() for part in str(REPO)):
            raise DeployError("Checkout path не должен содержать пробелы")
        if not str(REPO).startswith("/home/") and not str(REPO).startswith("/opt/"):
            raise DeployError("Используйте постоянный checkout в /home или /opt, не временную папку")
        if shutil.disk_usage(HOME).free < 40 * 1024**3:
            raise DeployError("Нужно минимум 40 GiB свободного места для начала; OSM/media/backup могут потребовать больше")
        memory = int(re.search(r"MemTotal:\s+(\d+)", Path("/proc/meminfo").read_text()).group(1))
        if memory < 16 * 1024**2:
            raise DeployError("Минимальная защита v1: 16 GiB RAM; это не гарантия sizing для вашей нагрузки")
        records = socket.getaddrinfo(self.c["DOMAIN"], 443, type=socket.SOCK_STREAM)
        addresses = {item[4][0] for item in records}
        if addresses != {self.c["PUBLIC_IPV4"]}:
            raise DeployError("DNS должен содержать только указанный IPv4. Удалите ошибочный AAAA/другие A; v1 IPv4-only.")
        interfaces = json.loads(capture(["ip", "-j", "address", "show"]))
        if self.c["PUBLIC_IPV4"] not in {a["local"] for nic in interfaces for a in nic.get("addr_info", [])}:
            raise DeployError("PUBLIC_IPV4 должен быть назначен интерфейсу VPS. NAT/external proxy в v1 не поддерживается.")
        worker_app_links()  # Fail before host changes if the public WorkerApp signer pin is broken.
        if self.mode == "install":
            occupied = [NGINX, CONFIG / "auth.p12",
                        Path("/etc/letsencrypt/live") / self.c["DOMAIN"],
                        Path("/etc/systemd/system/nginx.service.d/rwms-managed.conf"),
                        Path("/etc/letsencrypt/renewal-hooks/deploy/rwms-managed.sh")]
            if any(p.exists() for p in occupied):
                raise DeployError("Обнаружена существующая конфигурация/ключи. Не принимаем и не перезаписываем их автоматически.")
            foreign = list(Path("/etc/nginx/conf.d").glob("*.conf"))
            foreign += [p for p in Path("/etc/nginx/sites-enabled").glob("*") if p.name != "default"]
            if foreign:
                raise DeployError("Обнаружены существующие Nginx vhosts. Требуется ручное согласование, автоматическая установка остановлена.")
            ports = [p for pair in DATABASES.values() for p in pair] + [443, 8088, 8000, 5173, 8002, 9000, 18080, 9443, 9444]
            for port in ports:
                with socket.socket() as probe:
                    try:
                        probe.bind(("0.0.0.0", port))
                    except OSError:
                        raise DeployError(f"Порт {port} занят; возможно, RWMS уже установлен. Ничего не останавливаем.") from None
            listeners = capture(["ss", "-ltnp", "sport", "=", ":80"])
            if len(listeners.splitlines()) > 1 and '"nginx"' not in listeners:
                raise DeployError("Порт 80 занят не Nginx. HTTP-01 потребует отдельного согласования.")
            if shutil.which("docker"):
                for kind in ("container", "volume", "network"):
                    names = capture(["docker", kind, "ls", "--format", "{{.Name}}" if kind != "container" else "{{.Names}}", *(["-a"] if kind == "container" else [])])
                    if any("rwms" in line.lower() or "logistics" in line.lower() for line in names.splitlines()):
                        raise DeployError("Обнаружены существующие RWMS/logistics Docker resources. Нужна ручная миграция, не fresh install.")
        print("  DNS/ресурсы проверены. Входящие TCP 80/443 должны быть разрешены у провайдера и в firewall.")

    def tools(self) -> None:
        if self.state["mode"] == "install":
            self.runner.run("apt index", ["apt-get", "update"], timeout=600)
            self.runner.run("Ubuntu packages (без изменения firewall)", ["apt-get", "install", "-y",
                "docker.io", "docker-compose-v2", "docker-buildx", "nginx", "certbot", "ca-certificates", "openssl"], timeout=1800)
        self.runner.run("Docker/Nginx autostart", ["systemctl", "enable", "--now", "docker", "nginx"], timeout=120)
        self.runner.run("Compose v2", ["docker", "compose", "version"], timeout=30)

    def nginx(self, *, tls: bool = True, maintenance: bool = False) -> None:
        if NGINX.exists() and not NGINX.read_text().startswith("# Managed by RWMS installer v1."):
            raise DeployError("Nginx fragment больше не принадлежит installer; остановка без перезаписи")
        previous = NGINX.read_text() if NGINX.exists() else None
        private_write(NGINX, nginx_config(self.c, tls, maintenance), 0o644)
        try:
            self.runner.run("nginx syntax", ["nginx", "-t"], timeout=30)
        except BaseException:
            if previous is not None:
                private_write(NGINX, previous, 0o644)
            else:
                NGINX.unlink()
            raise
        self.runner.run("nginx reload", ["systemctl", "reload", "nginx"], timeout=60)

    def network(self) -> None:
        existing = capture(["docker", "network", "ls", "--format", "{{.Name}}"])
        if NETWORK not in existing.splitlines():
            for name in existing.splitlines():
                info = json.loads(capture(["docker", "network", "inspect", name]))[0]
                for entry in info.get("IPAM", {}).get("Config", []):
                    net = entry.get("Subnet")
                    if net and ipaddress.ip_network(net).version == 4 and ipaddress.ip_network(net).overlaps(ipaddress.ip_network(SUBNET)):
                        raise DeployError("172.21.0.0/16 пересекается с существующей Docker network")
            self.runner.run("Создание private bridge", ["docker", "network", "create", "--driver", "bridge",
                "--label", "rwms.managed=1", "--subnet", SUBNET, "--gateway", BRIDGE_IP, NETWORK])
        info = json.loads(capture(["docker", "network", "inspect", NETWORK]))[0]
        if info.get("Labels", {}).get("rwms.managed") != "1" or not any(
                x.get("Subnet") == SUBNET and x.get("Gateway") == BRIDGE_IP for x in info["IPAM"]["Config"]):
            raise DeployError("Private network не соответствует managed topology")
        private_write(Path("/etc/systemd/system/nginx.service.d/rwms-managed.conf"),
                      "[Unit]\nWants=docker.service\nAfter=docker.service\n", 0o644)
        self.runner.run("systemd reload", ["systemctl", "daemon-reload"], timeout=30)
        cert = Path("/etc/letsencrypt/live") / self.c["DOMAIN"] / "fullchain.pem"
        if not cert.exists():
            (HOME / "acme").mkdir(exist_ok=True, mode=0o755)
            (HOME / "acme").chmod(0o755)
            # Nginx worker must traverse this one public directory, not secret releases.
            os.chmod(HOME, 0o711)
            self.nginx(tls=False)
            self.runner.run("Let's Encrypt HTTP-01 (DNS и входящий порт 80)", ["certbot", "certonly",
                "--webroot", "-w", str(HOME / "acme"), "--non-interactive", "--agree-tos",
                "--email", self.c["ACME_EMAIL"], "--cert-name", self.c["DOMAIN"], "-d", self.c["DOMAIN"]], timeout=600)
        private_write(Path("/etc/letsencrypt/renewal-hooks/deploy/rwms-managed.sh"),
                      "#!/bin/sh\nset -eu\nnginx -t\nsystemctl reload nginx\n", 0o700)
        self.runner.run("Certificate renewal timer", ["systemctl", "enable", "--now", "certbot.timer"], timeout=60)
        maintenance = self.state["maintenance"] or self.state["mode"] == "install"
        self.nginx(maintenance=maintenance)
        self.state["maintenance"] = maintenance

    def render(self) -> None:
        self.release.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_write(self.core, compose_json(core_compose(self.c, self.release, CONFIG, self.tag)))
        policy = {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": ["s3:*"],
                  "Resource": ["arn:aws:s3:::rwms-media", "arn:aws:s3:::rwms-media/*"]}]}
        private_write(self.release / "bucket-policy.json", json.dumps(policy), 0o644)
        migrations = self.release / "media-migrations"
        migrations.mkdir(exist_ok=True, mode=0o755)
        migrations.chmod(0o755)
        for source in (REPO / "services/media-service/db/migration").glob("*.sql"):
            target = migrations / source.name
            target.write_bytes(source.read_bytes())
            target.chmod(0o644)
        override = self.release / "planner-override.json"
        private_write(override, compose_json(planner_override(self.c, self.tag)))
        # Resolve the canonical planner manifest into this immutable release record.
        # Never print resolved config: it includes secrets. Empty env prevents local .env loading.
        self.runner.run("Проверка core compose", self.core_cmd("config", "--quiet"), timeout=60)
        self.runner.run("Формирование planner compose", ["docker", "compose", "--project-name", PLANNER_PROJECT,
            "--env-file", "/dev/null", "-f", str(REPO / "logistics/docker-compose.yml"), "-f", str(override),
            "config", "--format", "json", "--output", str(self.planner)], timeout=60)
        self.planner.chmod(0o600)
        # Resolved JSON is parsed once more by Compose; preserve literal dollars in credentials.
        data = json.loads(self.planner.read_text())
        self.planner_data = data
        private_write(self.planner, compose_json(data))
        private_write(self.release / "topics.txt", "\n".join(kafka_topics(REPO)) + "\n")
        self.runner.run("Проверка planner compose", self.planner_cmd("config", "--quiet"), timeout=60)

    def build(self) -> None:
        for component in ("java", "media", "web"):
            self.runner.run(f"Build {component} (точный прогресс — в журнале Docker)",
                ["docker", "build", "--progress=plain", "-f", f"tools/deploy/Dockerfile.{component}",
                 "-t", f"rwms-managed-{component}:{self.tag}", "."], timeout=7200)
        self.runner.run("Build planner API/indexer/frontend", self.planner_cmd("build", "backend", "truck-restrictions-indexer", "frontend"), timeout=7200)

    def quiesce(self) -> None:
        self.nginx(maintenance=True)
        self.state["maintenance"] = True
        store_state(self.state)
        if self.state["mode"] != "update":
            return
        old = HOME / "releases" / self.state["previous"]
        self.runner.run("Остановка planner writers (данные сохраняются)",
                        self.planner_cmd("stop", "frontend", "backend", release=old), timeout=180)
        self.runner.run("Остановка основных writers (не БД и не broker)",
                        self.core_cmd("stop", "--timeout", "60", *APPS, release=old), timeout=600)
        backup_dir = HOME / "backups" / (self.tag + "-" + str(int(time.time())))
        backup_dir.mkdir(parents=True, mode=0o700)
        for name in DATABASES:
            db = "rwms_" + name.replace("-", "_")
            target = backup_dir / (name + ".dump")
            self.runner.run(f"pg_dump {name} (дополнение, не полный backup)",
                self.core_cmd("exec", "-T", name + "-db", "pg_dump", "-U", db_user(name), "-d", db, "-Fc", release=old),
                binary_output=target, timeout=1800)
        self.runner.run("pg_dump planner", self.planner_cmd("exec", "-T", "db", "pg_dump", "-U", "logistics",
                        "-d", "logistics", "-Fc", release=old), binary_output=backup_dir / "planner.dump", timeout=1800)
        print(f"  БД snapshots: {backup_dir}. Backup MinIO/Kafka/keys остается ответственностью оператора.")

    def infra(self) -> None:
        self.runner.run("Запуск изолированной инфраструктуры", self.core_cmd("up", "-d", "--wait", "--wait-timeout", "300",
            *[name + "-db" for name in DATABASES], "kafka", "minio"), timeout=900)
        self.runner.run("Versioned media bucket и отдельный пользователь", self.core_cmd("run", "--rm", "bucket-init"))
        topics = kafka_topics(REPO)
        script = '\n'.join('/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists '
                           '--replication-factor 1 --partitions 3 --topic ' + name for name in topics)
        self.runner.run(f"Создание {len(topics)} канонических topics (single-node RF=1)",
                        self.core_cmd("exec", "-T", "kafka", "/bin/bash", "-ec", script), timeout=900)

    def routing(self) -> None:
        self.runner.run("Planner DB и OSM download", self.planner_cmd("up", "-d", "db", "osrm-download"), timeout=1800)
        self.wait_job("osrm-download", timeout=7200)
        # The source manifest is authoritative, not a date hard-coded by the installer.
        output = self.release / "osm-manifest.txt"
        self.runner.run("Чтение PBF manifest", self.planner_cmd("run", "--rm", "--no-deps", "--entrypoint", "cat",
            "osrm-download", "/data/rwms-pbf-manifest.sha256"), binary_output=output, timeout=120)
        manifest = output.read_bytes()
        if not re.search(rb"[0-9a-f]{64}\s+region.osm.pbf", manifest) or not re.search(rb"[0-9a-f]{64}\s+northwestern-fed-district.osm.pbf", manifest):
            raise DeployError("Некорректный OSM manifest")
        version = "osm-" + hashlib.sha256(manifest).hexdigest()[:24]
        data = self.planner_data
        for service in ("backend", "truck-restrictions-indexer"):
            data["services"][service]["environment"]["OSM_DATA_VERSION"] = version
        private_write(self.planner, compose_json(data))
        self.runner.run("Valhalla build и PostGIS indexer (может занять часы)",
                        self.planner_cmd("up", "-d", "valhalla", "truck-restrictions-indexer"), timeout=7200)
        self.wait_job("truck-restrictions-indexer", timeout=7200)
        health("http://127.0.0.1:8002/status", timeout=7200)

    def wait_job(self, name: str, timeout: int) -> None:
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            ids = capture(self.planner_cmd("ps", "-a", "-q", name))
            if not ids:
                raise DeployError(f"Не найден job {name}")
            state = json.loads(capture(["docker", "inspect", "--format", "{{json .State}}", ids.splitlines()[0]]))
            if state["Status"] == "exited":
                self.runner.run(f"Журнал {name}", self.planner_cmd("logs", "--no-color", "--tail", "200", name), timeout=30)
                if state["ExitCode"] != 0:
                    raise DeployError(f"Job {name}: exit={state['ExitCode']}")
                return
            if state["Status"] in ("dead", "restarting"):
                raise DeployError(f"Job {name}: {state['Status']}")
            print(f"    {name}: {state['Status']}; {int(time.monotonic()-started)}s / {timeout}s. "
                  f"Логи: docker compose -p {PLANNER_PROJECT} -f {self.planner} logs -f {name}", flush=True)
            time.sleep(5)
        raise DeployError(f"Job {name}: превышен лимит {timeout}s; контейнер может продолжать работу — проверьте его перед resume")

    def migrations(self) -> None:
        key = CONFIG / "auth.p12"
        if not key.exists():
            if self.state["mode"] == "update":
                raise DeployError("Signing key утрачен. Новый ключ автоматически не создаем при update")
            self.runner.run("Постоянный RSA signing key (только первая установка)", self.core_cmd("run", "--rm", "keygen"))
            os.chown(key, 10001, 10001)
            key.chmod(0o600)
        self.runner.run("Flyway media migrate (без baseline/repair)", self.core_cmd("run", "--rm", "media-migrate"))
        self.runner.run("Flyway media validate", self.core_cmd("run", "--rm", "media-migrate", "validate"))

    def services(self) -> None:
        for names in (["auth-service", "api-gateway-service"], [name for name in APPS if name not in ("auth-service", "api-gateway-service", "web")]):
            self.runner.run("Запуск " + ", ".join(names), self.core_cmd("up", "-d", "--no-build", *names), timeout=300)
            if names[0] == "auth-service":
                health("http://127.0.0.1:9002/actuator/health", json_key="status", expected="UP")
                health("http://127.0.0.1:8088/actuator/health", json_key="status", expected="UP")
            else:
                for name, (port, _) in DATABASES.items():
                    if name == "auth":
                        continue
                    path = "/health/ready" if name == "media" else "/actuator/health"
                    health(f"http://127.0.0.1:{port}{path}")

    def planner_start(self) -> None:
        self.runner.run("Planner API и frontend", self.planner_cmd("up", "-d", "--no-build", "backend", "frontend"), timeout=1800)
        self.runner.run("Panel/admin/manager", self.core_cmd("up", "-d", "--no-build", "web"), timeout=300)
        health("http://127.0.0.1:8000/api/health")
        health("http://127.0.0.1:18080/healthz")
        health("http://127.0.0.1:5173/healthz")

    def verify(self) -> None:
        origin = "https://" + self.c["DOMAIN"]
        # Private-bridge requests test the normal routing while public stays in maintenance.
        for path in ("/", "/admin/", "/manager/", "/auth/callback", "/logistics-panel/", "/logistics-panel/yandex-map.html"):
            self.runner.run("HTTPS smoke " + path, ["docker", "run", "--rm", "--network", NETWORK,
                "--add-host", self.c["DOMAIN"] + ":" + BRIDGE_IP, "curlimages/curl:8.12.1",
                "--fail", "--silent", "--show-error", "--output", "/dev/null", origin + path], timeout=180)
        # Collect image evidence before opening public traffic.
        images = {kind: capture(["docker", "image", "inspect", "--format", "{{.Id}}", f"rwms-managed-{kind}:{self.tag}"])
                  for kind in ("java", "media", "web", "planner", "indexer", "planner-web")}
        self.nginx(maintenance=False)
        try:
            health(origin + "/auth/.well-known/openid-configuration", json_key="issuer", expected=origin + "/auth", timeout=60)
            for path in ("/", "/admin/", "/manager/", "/logistics-panel/", "/logistics-panel/api/health"):
                health(origin + path, timeout=60)
        except BaseException:
            self.nginx(maintenance=True)
            raise
        try:
            # Release evidence, not a source checkout.
            private_write(self.release / "receipt.json", json.dumps({"sha": self.sha, "images": images,
                "domain": self.c["DOMAIN"], "verified_at": datetime.now(timezone.utc).isoformat()}, indent=2))
            self.state.update(phase="ready", maintenance=False, completed_at=datetime.now(timezone.utc).isoformat())
            store_state(self.state)
        except BaseException:
            self.nginx(maintenance=True)
            raise

    def run(self) -> None:
        methods = [self.preflight, self.tools, self.network, self.render, self.build, self.quiesce,
                   self.infra, self.routing, self.migrations, self.services, self.planner_start, self.verify]
        completed = 0
        for index, ((key, title, weight), action) in enumerate(zip(STEPS, methods), 1):
            print(f"\n[{index}/{len(STEPS)}] {completed}% → {completed+weight}% · {title}", flush=True)
            started = time.monotonic()
            self.state.update(stage=key, percent=completed)
            if index > 1:
                store_state(self.state)
            action()
            completed += weight
            self.state["percent"] = completed
            # Initial ownership is persisted only after non-mutating preflight succeeds.
            store_state(self.state)
            print(f"✓ Этап {index}: {completed}% · {int(time.monotonic()-started)}s", flush=True)
        print(f"\n100%: технические этапы завершены. https://{self.c['DOMAIN']}/")
        print("Теперь проверьте вход, роли, загрузку медиа, маршруты и LLM вручную. HTTP 200 не заменяет бизнес-приемку.")


def main() -> int:
    parser = argparse.ArgumentParser(description="RWMS Ubuntu 24.04 installer. Сначала configure → plan → install.")
    parser.add_argument("command", choices=["configure", "plan", "install", "update", "resume", "status"])
    parser.add_argument("--env-file", type=Path, default=CONFIG / "system.env")
    parser.add_argument("--backup-reference", default="", help="update: абсолютный путь к отчету о проверенном полном backup")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.env_file.resolve().is_relative_to(REPO):
            raise DeployError(".env хранить вне checkout; секреты не должны попадать в Git/build context")
        if args.command == "configure":
            wizard(args.env_file)
            return 0
        if not args.env_file.is_file():
            raise DeployError("Нет .env. Сначала выполните configure с тем же --env-file.")
        c = load_env(args.env_file)
        if args.command == "plan":
            print(f"Домен: {c['DOMAIN']}\nIssuer: https://{c['DOMAIN']}/auth\nСекреты: скрыты")
            print(f"WorkerApp App Links SHA-256 (публичные, из worker-download-site/release-trust-policy.json): "
                  f"{worker_app_links()}")
            print("Новый single-host managed Docker stack + host Nginx; чужие ресурсы не принимаются.")
            print("PostgreSQL × 11 + planner PostGIS, Kafka RF=1, private MinIO TLS, Valhalla.")
            completed = 0
            for i, (_, title, weight) in enumerate(STEPS, 1):
                completed += weight
                print(f"{i:2}. {completed:3}% — {title}")
            print("Процент — вес завершенных этапов, не прогноз времени. plan не устанавливает пакеты и не проверяет DNS/runtime.")
            return 0
        if os.geteuid() != 0:
            raise DeployError("Для install/update/resume/status необходим sudo/root")
        if args.env_file.stat().st_uid != 0:
            raise DeployError("Production .env должен принадлежать root (chown root:root; chmod 600)")
        if args.command == "status":
            state = read_state()
            print(json.dumps({k: state[k] for k in ("phase", "tag", "sha", "maintenance", "percent", "stage", "log_directory")}, indent=2))
            return 0
        if not sys.stdin.isatty():
            raise DeployError("Развертывание требует терминал и явное подтверждение; unattended --yes намеренно отсутствует")
        print("Будут изменены host packages, managed Nginx, Docker resources и схемы БД.")
        print("На update: остановка writers и окно обслуживания; автоматического отката БД нет.")
        print("Проект Docker: rwms-managed + rwms-managed-planner. Чужая установка не поддерживается автоматически.")
        if prompt(f"Для продолжения введите {args.command.upper()} {c['DOMAIN']}") != f"{args.command.upper()} {c['DOMAIN']}":
            raise DeployError("Операция не подтверждена")
        with lock():
            deployment = Deployment(args.command, c, args.backup_reference)
            deployment.run()
        return 0
    except (DeployError, ValueError, OSError, subprocess.SubprocessError, KeyboardInterrupt) as exc:
        # Exception values from subprocess output/JSON could contain credentials: use controlled errors only.
        reason = str(exc) if isinstance(exc, (DeployError, ValueError)) else (
            "Операция прервана пользователем" if isinstance(exc, KeyboardInterrupt) else
            f"Ошибка {type(exc).__name__}; проверьте конфигурацию и закрытые журналы")
        print(f"\nОСТАНОВКА: {reason}\n100% не достигнуто. Данные/volumes не удалялись; откат не выполнялся.\n"
              "Проверьте status и логи. Контейнерные jobs могут продолжать работу. После диагностики — resume с тем же SHA и .env.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
