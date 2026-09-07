"""Administrative writes and the authenticated browser map configuration."""

from typing import Literal

from pydantic import Field, SecretStr, field_validator

from app.schemas.domain import ApiModel

MapProvider = Literal["STANDARD", "YANDEX"]


class AdminMapSettingsRead(ApiModel):
    """Expose key presence to administrators without echoing the saved key."""

    version: int = Field(ge=1)
    provider: MapProvider
    yandex_api_key_configured: bool


class MapSettingsUpdate(ApiModel):
    """A null/blank key retains the existing key when changing the provider."""

    expected_version: int = Field(ge=1)
    provider: MapProvider
    yandex_api_key: SecretStr | None = Field(default=None, max_length=256, repr=False)

    @field_validator("yandex_api_key", mode="before")
    @classmethod
    def normalize_key(cls, value: object) -> object:
        """Trim pasted whitespace; omit empty input without clearing a stored key."""

        return value.strip() or None if isinstance(value, str) else value


class MapSettingsRead(ApiModel):
    """The JavaScript key is delivered only to authenticated map consumers when selected."""

    version: int = Field(ge=1)
    provider: MapProvider
    yandex_api_key: str | None = Field(repr=False)
