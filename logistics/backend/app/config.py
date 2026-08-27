"""Environment-backed runtime configuration for the logistics backend."""

from __future__ import annotations

from functools import lru_cache
from math import isfinite
from urllib.parse import urlparse

from pydantic import AliasChoices, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Validated process configuration with development-safe defaults."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="LOGISTICS_",
        case_sensitive=False,
        extra="ignore",
    )

    app_name: str = "RWMS Logistics Simulator"
    environment: str = "development"
    database_url: str = Field(
        default="postgresql+psycopg://logistics:logistics@db:5432/logistics",
        validation_alias=AliasChoices("LOGISTICS_DATABASE_URL", "DATABASE_URL"),
    )
    cors_origins: tuple[str, ...] = ("http://localhost:5173",)
    sql_echo: bool = False
    routing_provider: str = Field(
        default="mock",
        validation_alias=AliasChoices("LOGISTICS_ROUTING_PROVIDER", "ROUTING_PROVIDER"),
    )
    osrm_base_url: str = Field(
        default="http://osrm:5000",
        validation_alias=AliasChoices("LOGISTICS_OSRM_BASE_URL", "OSRM_BASE_URL"),
    )
    osrm_profile: str = Field(
        default="driving",
        validation_alias=AliasChoices("LOGISTICS_OSRM_PROFILE", "OSRM_PROFILE"),
    )
    osrm_timeout_seconds: float = Field(
        default=15.0,
        validation_alias=AliasChoices("LOGISTICS_OSRM_TIMEOUT_SECONDS", "OSRM_TIMEOUT_SECONDS"),
    )
    valhalla_url: str = Field(
        default="http://valhalla:8002",
        validation_alias=AliasChoices("LOGISTICS_VALHALLA_URL", "VALHALLA_URL"),
    )
    valhalla_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices("LOGISTICS_VALHALLA_ENABLED", "VALHALLA_ENABLED"),
    )
    valhalla_timeout_seconds: float = Field(
        default=30.0,
        validation_alias=AliasChoices(
            "LOGISTICS_VALHALLA_TIMEOUT_SECONDS", "VALHALLA_TIMEOUT_SECONDS"
        ),
    )
    osm_data_version: str = Field(
        default="unknown",
        validation_alias=AliasChoices("LOGISTICS_OSM_DATA_VERSION", "OSM_DATA_VERSION"),
    )
    default_scenario_timezone: str = Field(
        default="Europe/Moscow",
        validation_alias=AliasChoices(
            "LOGISTICS_DEFAULT_SCENARIO_TIMEZONE", "DEFAULT_SCENARIO_TIMEZONE"
        ),
    )
    planner_default_seed: int = Field(
        default=1,
        validation_alias=AliasChoices("LOGISTICS_PLANNER_DEFAULT_SEED", "PLANNER_DEFAULT_SEED"),
    )
    sse_poll_interval_seconds: float = 0.5
    sse_heartbeat_seconds: float = 15.0
    rwms_sync_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices(
            "RWMS_SYNC_ENABLED", "LOGISTICS_RWMS_SYNC_ENABLED", "rwms_sync_enabled"
        ),
    )
    rwms_capacity_publish_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices(
            "RWMS_CAPACITY_PUBLISH_ENABLED",
            "LOGISTICS_RWMS_CAPACITY_PUBLISH_ENABLED",
            "rwms_capacity_publish_enabled",
        ),
    )
    rwms_logistics_base_url: str | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "RWMS_LOGISTICS_BASE_URL",
            "LOGISTICS_RWMS_LOGISTICS_BASE_URL",
            "rwms_logistics_base_url",
        ),
    )
    rwms_token_url: str | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "RWMS_TOKEN_URL", "LOGISTICS_RWMS_TOKEN_URL", "rwms_token_url"
        ),
    )
    rwms_client_id: str = Field(
        default="logistics-planner",
        validation_alias=AliasChoices(
            "RWMS_CLIENT_ID", "LOGISTICS_RWMS_CLIENT_ID", "rwms_client_id"
        ),
    )
    rwms_client_secret: str | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "RWMS_CLIENT_SECRET", "LOGISTICS_RWMS_CLIENT_SECRET", "rwms_client_secret"
        ),
    )
    rwms_timeout_seconds: float = Field(
        default=15.0,
        validation_alias=AliasChoices(
            "RWMS_TIMEOUT_SECONDS", "LOGISTICS_RWMS_TIMEOUT_SECONDS", "rwms_timeout_seconds"
        ),
    )

    @field_validator("cors_origins", mode="before")
    @classmethod
    def parse_cors_origins(cls, value: object) -> object:
        """Accept a comma-separated env value as well as a native sequence."""

        if isinstance(value, str):
            return tuple(item.strip() for item in value.split(",") if item.strip())
        return value

    @field_validator("sse_poll_interval_seconds", "sse_heartbeat_seconds")
    @classmethod
    def validate_positive_interval(cls, value: float) -> float:
        """Reject non-positive polling values that would create busy loops."""

        if value <= 0:
            raise ValueError("SSE intervals must be positive")
        return value

    @field_validator("routing_provider")
    @classmethod
    def validate_routing_provider(cls, value: str) -> str:
        """Accept only installed providers rather than silently falling back."""

        normalized = value.strip().lower()
        if normalized not in {"mock", "osrm", "valhalla"}:
            raise ValueError("ROUTING_PROVIDER must be 'mock', 'osrm', or 'valhalla'")
        return normalized

    @field_validator("osrm_base_url")
    @classmethod
    def validate_osrm_base_url(cls, value: str) -> str:
        """Require an absolute internal OSRM HTTP endpoint."""

        normalized = value.rstrip("/")
        parsed = urlparse(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("OSRM_BASE_URL must be an absolute http(s) URL")
        return normalized

    @field_validator("osrm_profile")
    @classmethod
    def validate_osrm_profile(cls, value: str) -> str:
        """Keep the profile safe for use as a path component."""

        normalized = value.strip().lower()
        if not normalized or not normalized.replace("_", "").replace("-", "").isalnum():
            raise ValueError("OSRM_PROFILE must contain only letters, digits, '_' or '-'")
        return normalized

    @field_validator("osrm_timeout_seconds")
    @classmethod
    def validate_osrm_timeout(cls, value: float) -> float:
        """Prevent disabled or unbounded routing requests."""

        if value <= 0:
            raise ValueError("OSRM_TIMEOUT_SECONDS must be positive")
        return value

    @field_validator("valhalla_url")
    @classmethod
    def validate_valhalla_url(cls, value: str) -> str:
        """Require an absolute internal Valhalla HTTP endpoint without URL parameters."""

        normalized = value.strip().rstrip("/")
        parsed = urlparse(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("VALHALLA_URL must be an absolute http(s) URL")
        if parsed.query or parsed.fragment:
            raise ValueError("VALHALLA_URL must not contain a query or fragment")
        return normalized

    @field_validator("valhalla_timeout_seconds")
    @classmethod
    def validate_valhalla_timeout(cls, value: float) -> float:
        """Reject negative request deadlines while permitting an explicit zero deadline."""

        if not isfinite(value) or value < 0:
            raise ValueError("VALHALLA_TIMEOUT_SECONDS must be non-negative")
        return value

    @field_validator("osm_data_version")
    @classmethod
    def validate_osm_data_version(cls, value: str) -> str:
        """Keep the operator-supplied tileset identity bounded and log-safe."""

        normalized = value.strip()
        if not normalized or len(normalized) > 128 or any(char.isspace() for char in normalized):
            raise ValueError(
                "OSM_DATA_VERSION must be non-blank, at most 128 characters, and contain no spaces"
            )
        return normalized

    @field_validator("rwms_logistics_base_url", "rwms_token_url")
    @classmethod
    def validate_rwms_url(cls, value: str | None) -> str | None:
        """Require configured RWMS endpoints to be absolute HTTP URLs."""

        if value is None:
            return None
        normalized = value.strip().rstrip("/")
        parsed = urlparse(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("RWMS endpoint must be an absolute http(s) URL")
        if parsed.query or parsed.fragment:
            raise ValueError("RWMS endpoint must not contain a query or fragment")
        return normalized

    @field_validator("rwms_client_id")
    @classmethod
    def validate_rwms_client_id(cls, value: str) -> str:
        """Reject a blank OAuth client identifier."""

        normalized = value.strip()
        if not normalized:
            raise ValueError("RWMS_CLIENT_ID must not be blank")
        return normalized

    @field_validator("rwms_timeout_seconds")
    @classmethod
    def validate_rwms_timeout(cls, value: float) -> float:
        """Reject disabled or unbounded RWMS HTTP calls."""

        if value <= 0:
            raise ValueError("RWMS_TIMEOUT_SECONDS must be positive")
        return value

    @model_validator(mode="after")
    def validate_enabled_rwms_integration(self) -> Settings:
        """Require all service-authentication inputs when synchronization is enabled."""

        if self.rwms_capacity_publish_enabled and not self.rwms_sync_enabled:
            raise ValueError("RWMS capacity publication requires RWMS_SYNC_ENABLED=true")
        if not self.rwms_sync_enabled:
            return self
        missing: list[str] = []
        if self.rwms_logistics_base_url is None:
            missing.append("RWMS_LOGISTICS_BASE_URL")
        if self.rwms_token_url is None:
            missing.append("RWMS_TOKEN_URL")
        if self.rwms_client_secret is None or not self.rwms_client_secret.strip():
            missing.append("RWMS_CLIENT_SECRET")
        if missing:
            raise ValueError("RWMS synchronization requires " + ", ".join(missing))
        return self

    @model_validator(mode="after")
    def validate_enabled_valhalla_provider(self) -> Settings:
        """Do not accept a selected truck provider that was explicitly disabled."""

        if self.routing_provider == "valhalla" and not self.valhalla_enabled:
            raise ValueError("ROUTING_PROVIDER=valhalla requires VALHALLA_ENABLED=true")
        return self

    @field_validator("default_scenario_timezone")
    @classmethod
    def validate_default_timezone(cls, value: str) -> str:
        """Require a valid IANA default scenario timezone."""

        from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("DEFAULT_SCENARIO_TIMEZONE must be a valid IANA name") from exc
        return value


@lru_cache
def get_settings() -> Settings:
    """Return the immutable process-wide settings instance."""

    return Settings()
