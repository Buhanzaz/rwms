"""Public request and response contracts for operator-facing address geocoding."""

from __future__ import annotations

from pydantic import Field, field_validator, model_validator

from app.schemas.domain import ApiModel


class GeocodingSuggestionsQuery(ApiModel):
    """Validated autocomplete text with an optional WGS84 search bias."""

    text: str = Field(min_length=1, max_length=256)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)

    @field_validator("text")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        """Reject whitespace-only searches and send normalized text to the provider."""

        normalized = value.strip()
        if not normalized:
            raise ValueError("text must not be blank")
        return normalized

    @model_validator(mode="after")
    def validate_bias_pair(self) -> GeocodingSuggestionsQuery:
        """Require both coordinates when the caller supplies a geographic bias."""

        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("latitude and longitude must be supplied together")
        return self


class GeocodingResolveQuery(ApiModel):
    """Validated opaque Yandex URI selected from an autocomplete result."""

    uri: str = Field(min_length=1, max_length=4096)

    @field_validator("uri")
    @classmethod
    def normalize_uri(cls, value: str) -> str:
        """Reject a blank provider identifier while preserving its opaque contents."""

        normalized = value.strip()
        if not normalized:
            raise ValueError("uri must not be blank")
        return normalized


class ReverseGeocodingQuery(ApiModel):
    """Validated WGS84 point selected directly on the logistics map."""

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class AddressSuggestion(ApiModel):
    """One navigator-style address option whose URI can be resolved to a point."""

    id: str
    title: str
    subtitle: str | None = None
    address: str | None = None
    uri: str


class ResolvedAddress(ApiModel):
    """A provider-confirmed address paired with its WGS84 coordinates."""

    address: str
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
