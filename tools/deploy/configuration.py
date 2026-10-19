"""Pure configuration rendering for the single-host managed RWMS installer.

No shell evaluation, secret logging, host changes or network calls in this module.
The master .env uses KEY=<JSON string>; only this installer reads that file.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, urlsplit

DATABASES = {
    "auth": (9002, 5433), "task-board": (8081, 5434),
    "warehouse": (8083, 5435), "asset": (8086, 5436),
    "maintenance": (8087, 5437), "media": (8085, 5438),
    "inventory": (8089, 5439), "logistics": (8090, 5440),
    "dossier": (8091, 5441), "assistant": (8092, 5442),
    "analytics": (8093, 5443),
}
JAVA = [f"{name}-service" for name in DATABASES if name != "media"] + ["api-gateway-service"]
APPS = JAVA + ["media-service", "web"]
PROJECT = "rwms-managed"
PLANNER_PROJECT = "rwms-managed-planner"
NETWORK = "rwms-managed-private"
SUBNET = "172.21.0.0/16"
BRIDGE_IP = "172.21.0.1"
CLIENT_SECRETS = ["TASK_BOARD_CLIENT_SECRET", "AUTH_WAREHOUSE_CLIENT_SECRET",
                  "ASSET_WAREHOUSE_CLIENT_SECRET", "MAINTENANCE_CLIENT_SECRET",
                  "INVENTORY_CLIENT_SECRET", "LOGISTICS_CLIENT_SECRET",
                  "LOGISTICS_PLANNER_CLIENT_SECRET"]
INTERNAL_SECRETS = [f"{name.upper().replace('-', '_')}_DB_PASSWORD" for name in DATABASES] + [
    "PLANNER_DB_PASSWORD", "MINIO_ROOT_PASSWORD", "MEDIA_MINIO_SECRET_KEY",
    "AUTH_SIGNING_KEY_STORE_PASSWORD", "TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET",
    "DOSSIER_CURSOR_SECRET", "CLIENT_PRESENTATION_TOKEN_SECRET", *CLIENT_SECRETS,
]
SECRET_KEYS = INTERNAL_SECRETS + ["AUTH_BOOTSTRAP_ADMIN_PASSWORD", "LLM_API_KEY",
                                 "YANDEX_GEOSUGGEST_API_KEY", "YANDEX_GEOCODER_API_KEY"]
PUBLIC_KEYS = ["DOMAIN", "PUBLIC_IPV4", "ACME_EMAIL", "AUTH_BOOTSTRAP_ADMIN_USERNAME",
               "LLM_BASE_URL", "LLM_MODEL", "PLANNER_SYNC_ENABLED"]
WORKER_APP_ID = "dev.buhanzaz.rwms.worker"
WORKER_TRUST_POLICY = Path(__file__).resolve().parents[2] / "worker-download-site" / "release-trust-policy.json"


def worker_app_links(policy: Path = WORKER_TRUST_POLICY) -> str:
    """Gateway App Links fingerprints of the public, already pinned WorkerApp release signers.

    Certificate fingerprints are public and maintained in Git for the download site, so the
    operator is never asked for them. Only PRODUCTION signers are published: internal test
    builds use another package and must not gain App Links trust.
    """
    try:
        data = json.loads(policy.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = None
    if not isinstance(data, dict) or data.get("schemaVersion") != 1 or data.get("applicationId") != WORKER_APP_ID:
        raise ValueError(f"{policy}: нужна release trust policy v1 для {WORKER_APP_ID}")
    signers = data.get("productionSignerSha256Allowlist")
    if not isinstance(signers, list) or not signers or not all(
            isinstance(item, str) and re.fullmatch(r"[0-9a-f]{64}", item) for item in signers):
        raise ValueError(f"{policy}: нет production SHA-256 сертификата подписи WorkerApp")
    return ",".join(dict.fromkeys(":".join(item[i:i + 2] for i in range(0, 64, 2)).upper() for item in signers))


def domain_name(raw: str) -> str:
    """Accept a DNS name or a bare https origin, but never paths/config injection."""
    value = raw.strip().lower()
    if value.startswith("https://"):
        parsed = urlsplit(value)
        if parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username or parsed.port:
            raise ValueError("Введите домен без пути, порта, логина и параметров")
        value = parsed.hostname or ""
    value = value.encode("idna").decode("ascii")
    if len(value) > 253 or "." not in value or not all(
        re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", part)
        for part in value.split(".")
    ):
        raise ValueError("Некорректный DNS-домен")
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return value
    raise ValueError("Нужен домен, а не IP")


def private_write(path: Path, text: str, mode: int = 0o600) -> None:
    """Atomic replacement. Callers must own the destination directory."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink():
        raise ValueError(f"Отказ записи через symlink: {path}")
    fd, temp = tempfile.mkstemp(prefix=".rwms-", dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def save_env(path: Path, values: dict[str, str]) -> None:
    private_write(path, "# RWMS installer .env v1. JSON-quoted strings. DO NOT source this file.\n" +
                  "".join(f"{key}={json.dumps(value, ensure_ascii=True)}\n" for key, value in sorted(values.items())))


def load_env(path: Path) -> dict[str, str]:
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError(".env должен быть обычным файлом с правами 0600")
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        key, sep, raw = line.partition("=")
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values:
            raise ValueError("Неверная/повторная строка .env (значение скрыто)")
        try:
            value = json.loads(raw)
        except json.JSONDecodeError:
            raise ValueError(f"{key}: требуется JSON-строка в двойных кавычках") from None
        if not isinstance(value, str) or any(ord(c) < 32 for c in value):
            raise ValueError(f"{key}: требуется однострочное текстовое значение")
        values[key] = value
    validate(values)
    return values


def validate(c: dict[str, str]) -> None:
    if set(c) != set(PUBLIC_KEYS + SECRET_KEYS):
        raise ValueError("Набор ключей .env отличается от схемы v1; выполните configure для нового файла")
    for key, value in c.items():
        if any(ord(ch) < 32 for ch in value):
            raise ValueError(f"{key}: управляющие символы запрещены")
    for key in SECRET_KEYS:
        if "${" in c[key]:
            raise ValueError(f"{key}: последовательность ${{ запрещена из-за Spring placeholder resolution")
    if domain_name(c["DOMAIN"]) != c["DOMAIN"]:
        raise ValueError("DOMAIN должен быть нормализованным DNS-именем")
    ip = ipaddress.IPv4Address(c["PUBLIC_IPV4"])
    if not ip.is_global:
        raise ValueError("PUBLIC_IPV4 должен быть публичным IPv4 этого сервера")
    if not re.fullmatch(r"[A-Za-z0-9._+%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", c["ACME_EMAIL"]):
        raise ValueError("Некорректный ACME_EMAIL")
    for key in INTERNAL_SECRETS:
        if len(c[key]) < 32 or not c[key].isascii() or any(ord(x) < 33 for x in c[key]):
            raise ValueError(f"{key}: минимум 32 печатных ASCII-символа без пробелов")
    try:
        lease_key = base64.b64decode(c["TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET"], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET: нужен standard Base64, не URL-safe") from None
    if len(lease_key) < 32:
        raise ValueError("TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET: минимум 32 байта после Base64 decode")
    if len(c["AUTH_BOOTSTRAP_ADMIN_PASSWORD"]) < 12:
        raise ValueError("Пароль администратора: минимум 12 символов")
    for key in ("AUTH_BOOTSTRAP_ADMIN_USERNAME", "LLM_MODEL", "LLM_API_KEY"):
        if not c[key].strip():
            raise ValueError(f"{key} обязателен")
    url = urlsplit(c["LLM_BASE_URL"])
    if url.scheme != "https" or not url.hostname or url.username or url.query or url.fragment:
        raise ValueError("LLM_BASE_URL: HTTPS URL без credentials/query/fragment")
    if c["PLANNER_SYNC_ENABLED"] not in ("true", "false"):
        raise ValueError("PLANNER_SYNC_ENABLED: true или false")


def fingerprint(c: dict[str, str]) -> str:
    return hashlib.sha256(json.dumps(c, sort_keys=True).encode()).hexdigest()


def compose_json(data: dict) -> str:
    """Escape Compose interpolation, including user-entered $ and ${...} secrets."""
    def escaped(value):
        if isinstance(value, str):
            return value.replace("$", "$$")
        if isinstance(value, dict):
            return {k: escaped(v) for k, v in value.items()}
        if isinstance(value, list):
            return [escaped(v) for v in value]
        return value
    return json.dumps(escaped(data), indent=2) + "\n"


def db_user(name: str) -> str:
    # auth explicitly rejects its historical local username outside dev/test.
    return "rwms_auth_runtime" if name == "auth" else "rwms_" + name.replace("-", "_")


def service_env(name: str, c: dict[str, str]) -> dict[str, str]:
    origin = f"https://{c['DOMAIN']}"
    env = {"SPRING_PROFILES_ACTIVE": "production", "SERVER_ADDRESS": "0.0.0.0",
           "AUTH_ISSUER": origin + "/auth", "AUTH_AUDIENCE": "rwms-services", "PANEL_ORIGIN": origin}
    urls = {f"{n.upper().replace('-', '_')}_SERVICE_URL": f"http://{n}-service:{p[0]}"
            for n, p in DATABASES.items()}
    if name == "api-gateway-service":
        env.update(urls)
        env.update(GATEWAY_PUBLIC_BASE_URI=origin, API_GATEWAY_PORT="8088",
                   GATEWAY_TRUSTED_PROXY_ADDRESSES=BRIDGE_IP,
                   LOGISTICS_PLANNER_URL="http://logistics-planner:8000",
                   RWMS_GATEWAY_APPLINKS_SHA256CERTFINGERPRINTS=worker_app_links())
        return env
    domain = name.removesuffix("-service")
    prefix = domain.upper().replace("-", "_")
    dbname = "rwms_" + domain.replace("-", "_")
    env.update({f"{prefix}_SERVICE_PORT": str(DATABASES[domain][0]),
                f"{prefix}_DB_URL": f"jdbc:postgresql://{domain}-db:5432/{dbname}",
                f"{prefix}_DB_USERNAME": db_user(domain), f"{prefix}_DB_PASSWORD": c[f"{prefix}_DB_PASSWORD"],
                f"{prefix}_KAFKA_ENABLED": "true", f"{prefix}_KAFKA_BROKERS": "kafka:9092"})
    if domain == "auth":
        env.update({k: c[k] for k in CLIENT_SECRETS})
        env.update({k: c[k] for k in ("AUTH_BOOTSTRAP_ADMIN_USERNAME", "AUTH_BOOTSTRAP_ADMIN_PASSWORD",
                                    "AUTH_SIGNING_KEY_STORE_PASSWORD")})
        env.update(AUTH_SIGNING_KEY_STORE="/run/rwms/auth.p12", AUTH_SIGNING_KEY_ALIAS="rwms-auth",
                   AUTH_DEV_DEFAULT_CREDENTIALS="false", AUTH_SESSION_COOKIE_SECURE="true",
                   AUTH_WAREHOUSE_VALIDATION_ENABLED="true", AUTH_WAREHOUSE_CLIENT_ENABLED="true",
                   AUTH_WAREHOUSE_TOKEN_URI="http://auth-service:9002/oauth2/token",
                   WAREHOUSE_SERVICE_INTERNAL_BASE_URL=urls["WAREHOUSE_SERVICE_URL"])
        for client in ("ASSET_WAREHOUSE", "MAINTENANCE", "INVENTORY", "LOGISTICS", "LOGISTICS_PLANNER"):
            env[f"{client}_CLIENT_ENABLED"] = "true"
        for client, path in (("PANEL", "/auth/callback"), ("WORKER", "/auth/worker/callback"),
                             ("DRIVER", "/auth/driver/callback"), ("MANAGER", "/auth/manager/callback"),
                             ("CUSTOMER", "/auth/customer/callback")):
            env.update({f"{client}_ORIGIN": origin, f"{client}_REDIRECT_URI": origin + path,
                        f"{client}_POST_LOGOUT_REDIRECT_URI": origin + "/"})
    if domain == "task-board":
        env.update(TASK_BOARD_CLIENT_SECRET=c["TASK_BOARD_CLIENT_SECRET"],
                   TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET=c["TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET"],
                   AUTH_TOKEN_URI=origin + ":9443/oauth2/token",
                   AUTH_WORKER_CREDENTIALS_URL=origin + ":9443/api/internal/worker-credentials",
                   WAREHOUSE_SERVICE_INTERNAL_BASE_URL=origin + ":9443/warehouse",
                   MEDIA_SERVICE_INTERNAL_BASE_URL=urls["MEDIA_SERVICE_URL"],
                   MAINTENANCE_SERVICE_URL=urls["MAINTENANCE_SERVICE_URL"],
                   WORKER_ORIGIN=origin, DRIVER_ORIGIN=origin, TASK_BOARD_FCM_ENABLED="false")
    if domain == "asset":
        env.update(ASSET_WAREHOUSE_REGISTRY_ENABLED="true", ASSET_MEDIA_IMPORT_ENABLED="true",
                   ASSET_WAREHOUSE_TOKEN_URI="http://auth-service:9002/oauth2/token",
                   ASSET_WAREHOUSE_CLIENT_SECRET=c["ASSET_WAREHOUSE_CLIENT_SECRET"],
                   WAREHOUSE_SERVICE_INTERNAL_BASE_URL=urls["WAREHOUSE_SERVICE_URL"],
                   MEDIA_SERVICE_INTERNAL_BASE_URL=urls["MEDIA_SERVICE_URL"])
    if domain in ("maintenance", "inventory", "logistics"):
        env.update({f"{prefix}_DEPENDENCIES_ENABLED": "true", f"{prefix}_CLIENT_SECRET": c[f"{prefix}_CLIENT_SECRET"],
                    "AUTH_TOKEN_URI": "http://auth-service:9002/oauth2/token"})
        if domain == "logistics":
            env.update(urls)
            env.update(LOGISTICS_KAFKA_FUNCTION_DEFINITION="logisticsInbound",
                       CLIENT_PRESENTATION_TOKEN_SECRET=c["CLIENT_PRESENTATION_TOKEN_SECRET"],
                       LOGISTICS_CUSTOMER_TEST_CHANGE_PAYMENT_ENABLED="false")
        else:
            env.update({key.replace("_SERVICE_URL", "_INTERNAL_BASE_URL"): value for key, value in urls.items()})
    if domain == "dossier":
        env["DOSSIER_CURSOR_SECRET"] = c["DOSSIER_CURSOR_SECRET"]
    if domain == "assistant":
        env.update({key: c[key] for key in ("LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL")})
        env["LOGISTICS_BASE_URL"] = urls["LOGISTICS_SERVICE_URL"]
    return env


def media_env(c: dict[str, str]) -> dict[str, str]:
    origin = f"https://{c['DOMAIN']}"
    return dict(MEDIA_RUNTIME_PROFILE="production", MEDIA_HTTP_ADDRESS=":8085",
                MEDIA_MANAGEMENT_ADDRESS="127.0.0.1:9095", MEDIA_INSTANCE_ID="media-managed-1",
                MEDIA_DATABASE_URL=f"postgres://rwms_media:{quote(c['MEDIA_DB_PASSWORD'], safe='')}@media-db:5432/rwms_media?sslmode=disable",
                MEDIA_AUTH_ISSUER=origin + "/auth", MEDIA_AUTH_JWKS_URL=origin + "/auth/oauth2/jwks",
                MEDIA_AUTH_AUDIENCE="rwms-services", MEDIA_MINIO_ENDPOINT=c["DOMAIN"] + ":9444",
                MEDIA_MINIO_USE_SSL="true", MEDIA_MINIO_ACCESS_KEY="rwms-media",
                MEDIA_MINIO_SECRET_KEY=c["MEDIA_MINIO_SECRET_KEY"], MEDIA_MINIO_BUCKET="rwms-media",
                MEDIA_KAFKA_BROKERS="kafka:9092", MEDIA_MAX_UPLOAD_BYTES="104857600",
                MEDIA_ALLOWED_MIME_TYPES="image/jpeg,image/png,image/webp,video/mp4,video/webm",
                MEDIA_UPLOAD_EXPIRY="15m", MEDIA_PROCESSING_TIMEOUT="5m",
                MEDIA_MAX_VIDEO_DURATION="5m", MEDIA_MAX_VIDEO_OUTPUT_BYTES="104857600",
                MEDIA_ALLOWED_VIDEO_CODECS="h264,hevc,vp8,vp9,av1")


def core_compose(c: dict[str, str], release: Path, config: Path, tag: str) -> dict:
    services, volumes = {}, {}
    for name, (_, port) in DATABASES.items():
        db = "rwms_" + name.replace("-", "_")
        volumes[name + "-db"] = {"name": f"{PROJECT}-{name}-db"}
        services[name + "-db"] = {
            "image": "postgres:17-alpine", "restart": "unless-stopped", "networks": ["private"],
            "environment": {"POSTGRES_DB": db, "POSTGRES_USER": db_user(name),
                            "POSTGRES_PASSWORD": c[name.upper().replace('-', '_') + "_DB_PASSWORD"]},
            "ports": [f"127.0.0.1:{port}:5432"],
            "volumes": [f"{name}-db:/var/lib/postgresql/data"],
            "healthcheck": {"test": ["CMD-SHELL", f"pg_isready -U {db_user(name)} -d {db}"],
                            "interval": "5s", "timeout": "5s", "retries": 30}}
    volumes["kafka"] = {"name": PROJECT + "-kafka"}
    services["kafka"] = {
        "image": "apache/kafka:4.3.1", "restart": "unless-stopped", "networks": ["private"],
        "volumes": ["kafka:/var/lib/kafka/data"],
        "environment": {"KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
            "KAFKA_LISTENERS": "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",
            "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:9092",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
            "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT", "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
            "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
            "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false", "KAFKA_NUM_PARTITIONS": "3"},
        "healthcheck": {"test": ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list >/dev/null"],
                        "interval": "5s", "timeout": "10s", "retries": 30}}
    volumes["objects"] = {"name": PROJECT + "-objects"}
    services["minio"] = {"image": "minio/minio:RELEASE.2025-04-22T22-12-26Z",
        "restart": "unless-stopped", "networks": ["private"],
        "command": ["server", "/data", "--console-address", ":9001"],
        "ports": ["127.0.0.1:9000:9000"], "volumes": ["objects:/data"],
        "environment": {"MINIO_ROOT_USER": "rwms-storage-admin", "MINIO_ROOT_PASSWORD": c["MINIO_ROOT_PASSWORD"]},
        "healthcheck": {"test": ["CMD", "mc", "ready", "local"], "interval": "5s", "timeout": "5s", "retries": 30}}
    # Bucket-scoped account, rather than giving media the MinIO root credential.
    services["bucket-init"] = {"image": "minio/mc:RELEASE.2025-04-16T18-13-26Z", "networks": ["private"],
        "environment": {"ROOT_PASSWORD": c["MINIO_ROOT_PASSWORD"], "MEDIA_PASSWORD": c["MEDIA_MINIO_SECRET_KEY"]},
        "volumes": [f"{release}/bucket-policy.json:/policy.json:ro"],
        "entrypoint": ["/bin/sh", "-ec", 'mc alias set local http://minio:9000 rwms-storage-admin "$ROOT_PASSWORD" >/dev/null; '
            'mc mb --ignore-existing local/rwms-media; mc version enable local/rwms-media; '
            'mc admin user add local rwms-media "$MEDIA_PASSWORD"; '
            'mc admin policy create local rwms-media /policy.json; mc admin policy attach local rwms-media --user rwms-media']}
    for name in JAVA:
        port = 8088 if name == "api-gateway-service" else DATABASES[name.removesuffix("-service")][0]
        services[name] = {"image": f"rwms-managed-java:{tag}", "restart": "unless-stopped",
            "networks": ["private"], "extra_hosts": [f"{c['DOMAIN']}:{BRIDGE_IP}"],
            "environment": service_env(name, c), "command": ["java", "-jar", f"/app/{name}.jar"],
            "ports": [f"127.0.0.1:{port}:{port}"],
            "security_opt": ["no-new-privileges:true"], "cap_drop": ["ALL"]}
    services["auth-service"]["volumes"] = [f"{config}/auth.p12:/run/rwms/auth.p12:ro"]
    services["keygen"] = {"image": f"rwms-managed-java:{tag}", "user": "0:0", "network_mode": "none",
        "volumes": [f"{config}:/keys"], "environment": {"STORE_PASSWORD": c["AUTH_SIGNING_KEY_STORE_PASSWORD"]},
        "entrypoint": ["keytool"], "command": ["-genkeypair", "-alias", "rwms-auth", "-keyalg", "RSA",
            "-keysize", "3072", "-storetype", "PKCS12", "-keystore", "/keys/auth.p12",
            "-storepass:env", "STORE_PASSWORD", "-keypass:env", "STORE_PASSWORD",
            "-validity", "3650", "-dname", "CN=RWMS Auth", "-noprompt"]}
    services["media-service"] = {"image": f"rwms-managed-media:{tag}", "restart": "unless-stopped",
        "networks": ["private"], "extra_hosts": [f"{c['DOMAIN']}:{BRIDGE_IP}"],
        "environment": media_env(c), "ports": ["127.0.0.1:8085:8085"],
        "security_opt": ["no-new-privileges:true"], "cap_drop": ["ALL"]}
    services["media-migrate"] = {"image": "redgate/flyway:11.8.2", "networks": ["private"],
        "volumes": [f"{release}/media-migrations:/flyway/sql:ro"],
        "environment": {"FLYWAY_URL": "jdbc:postgresql://media-db:5432/rwms_media",
                        "FLYWAY_USER": "rwms_media", "FLYWAY_PASSWORD": c["MEDIA_DB_PASSWORD"],
                        "FLYWAY_CLEAN_DISABLED": "true", "FLYWAY_BASELINE_ON_MIGRATE": "false"},
        "command": ["migrate"]}
    services["web"] = {"image": f"rwms-managed-web:{tag}", "restart": "unless-stopped",
                       "ports": ["127.0.0.1:18080:80"], "networks": ["private"]}
    return {"name": PROJECT, "services": services, "volumes": volumes,
            "networks": {"private": {"name": NETWORK, "external": True}}}


def planner_override(c: dict[str, str], tag: str) -> dict:
    """Use the canonical planner compose, overriding every sensitive default explicitly."""
    origin = f"https://{c['DOMAIN']}"
    db = "postgresql+psycopg://logistics:" + quote(c["PLANNER_DB_PASSWORD"], safe="") + "@db:5432/logistics"
    return {"services": {
        "db": {"environment": {"POSTGRES_DB": "logistics", "POSTGRES_USER": "logistics",
                                "POSTGRES_PASSWORD": c["PLANNER_DB_PASSWORD"]}},
        "backend": {"image": f"rwms-managed-planner:{tag}",
            "networks": {"default": {}, "private": {"aliases": ["logistics-planner"]}},
            "extra_hosts": [f"{c['DOMAIN']}:{BRIDGE_IP}"],
            "environment": {"DATABASE_URL": db, "AUTH_ISSUER": origin + "/auth",
                "AUTH_JWKS_URL": "http://auth-service:9002/oauth2/jwks", "AUTH_AUDIENCE": "rwms-services",
                "AUTH_PANEL_CLIENT_ID": "rwms-panel", "RWMS_SYNC_ENABLED": c["PLANNER_SYNC_ENABLED"],
                "RWMS_CAPACITY_PUBLISH_ENABLED": c["PLANNER_SYNC_ENABLED"],
                "RWMS_LOGISTICS_BASE_URL": "http://logistics-service:8090",
                "RWMS_TOKEN_URL": "http://auth-service:9002/oauth2/token", "RWMS_CLIENT_ID": "logistics-planner",
                "RWMS_CLIENT_SECRET": c["LOGISTICS_PLANNER_CLIENT_SECRET"],
                "ROUTING_PROVIDER": "valhalla", "VALHALLA_ENABLED": "true", "VALHALLA_URL": "http://valhalla:8002",
                "OSM_DATA_VERSION": "managed-pending-manifest",
                **{key: c[key] for key in ("YANDEX_GEOSUGGEST_API_KEY", "YANDEX_GEOCODER_API_KEY")}}},
        "truck-restrictions-indexer": {"image": f"rwms-managed-indexer:{tag}",
            "environment": {"DATABASE_URL": db, "OSM_DATA_VERSION": "managed-pending-manifest"}},
        "frontend": {"image": f"rwms-managed-planner-web:{tag}", "build": {"args": {
            "VITE_APP_BASE_PATH": "/logistics-panel/", "VITE_API_BASE_URL": "/logistics-panel/api",
            "VITE_MAP_STYLE_URL": "https://tiles.openfreemap.org/styles/liberty"}}}},
        "networks": {"private": {"name": NETWORK, "external": True}}}


def nginx_config(c: dict[str, str], tls: bool, maintenance: bool = False) -> str:
    d = c["DOMAIN"]
    header = "# Managed by RWMS installer v1. Do not edit while deploying.\n"
    challenge = "location ^~ /.well-known/acme-challenge/ { root /var/lib/rwms-managed/acme; }"
    if not tls:
        return header + f"server {{ listen 80; server_name {d}; {challenge} location / {{ return 503; }} }}\n"
    cert = f"ssl_certificate /etc/letsencrypt/live/{d}/fullchain.pem;\nssl_certificate_key /etc/letsencrypt/live/{d}/privkey.pem;\nssl_protocols TLSv1.2 TLSv1.3;"
    proxy = "proxy_http_version 1.1; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Host $http_host; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-Port 443; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header Connection '';"
    routes = f"""
    location = /auth/callback {{ proxy_pass http://127.0.0.1:18080; }}
    location ^~ /api/internal/ {{ return 404; }}
    location /auth/ {{ proxy_pass http://127.0.0.1:8088; {proxy} }}
    location /api/ {{ proxy_pass http://127.0.0.1:8088; {proxy} proxy_buffering off; proxy_cache off; proxy_read_timeout 1h; }}
    location = /.well-known/assetlinks.json {{ proxy_pass http://127.0.0.1:8088; {proxy} }}
    location = /logistics-panel {{ return 308 /logistics-panel/; }}
    location = /logistics-panel/api/admin {{ return 404; }}
    location ^~ /logistics-panel/api/admin/ {{ return 404; }}
    location ^~ /logistics-panel/api/ {{ proxy_pass http://127.0.0.1:8000/api/; {proxy} proxy_buffering off; proxy_cache off; proxy_read_timeout 1h; }}
    location ^~ /logistics-panel/ {{ proxy_pass http://127.0.0.1:5173/; {proxy} }}
    location / {{ proxy_pass http://127.0.0.1:18080; {proxy} }}
"""
    # During maintenance public writes stop, but private issuer/JWKS remains reachable
    # to containers via their hosts entry and the bridge-only TLS listener on 443.
    public = "location / { return 503; }" if maintenance else routes
    return header + f"""
server {{ listen 80; server_name {d}; {challenge} location / {{ return 308 https://{d}$request_uri; }} }}
server {{ listen {c['PUBLIC_IPV4']}:443 ssl; server_name {d}; {cert}
    access_log off; client_max_body_size 100m; {public}
}}
server {{ listen {BRIDGE_IP}:443 ssl; server_name {d}; {cert}
    access_log off; allow {SUBNET}; deny all; client_max_body_size 100m; {routes}
}}
server {{ listen {BRIDGE_IP}:9443 ssl; server_name {d}; {cert}
    access_log off; allow {SUBNET}; deny all;
    location = /oauth2/token {{ proxy_pass http://127.0.0.1:9002; }}
    location ^~ /api/internal/worker-credentials {{ proxy_pass http://127.0.0.1:9002; }}
    location /warehouse/ {{ proxy_pass http://127.0.0.1:8083/; }}
    location / {{ return 404; }}
}}
server {{ listen {BRIDGE_IP}:9444 ssl; server_name {d}; {cert}
    access_log off; allow {SUBNET}; deny all; client_max_body_size 100m;
    location / {{ proxy_pass http://127.0.0.1:9000; proxy_set_header Host $http_host;
        proxy_http_version 1.1; proxy_request_buffering off; proxy_buffering off; proxy_read_timeout 5m; }}
}}
"""


def kafka_topics(repo: Path) -> list[str]:
    # Canonical channel addresses plus explicitly declared service destinations/DLT.
    files = list((repo / "contracts/events").glob("*.yaml"))
    files += list((repo / "services").glob("*/src/main/resources/application.yaml"))
    topics = set()
    for path in files:
        for match in re.findall(r"\brwms\.[a-z0-9.-]+\.v[0-9]+(?:\.[a-z0-9.-]+\.dlt)?\b", path.read_text()):
            topics.add(match)
    return sorted(topics)
