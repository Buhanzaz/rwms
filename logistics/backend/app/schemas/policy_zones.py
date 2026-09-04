"""HTTP schemas for warehouse exceptional policy polygons."""

from __future__ import annotations

import json
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    FiniteFloat,
    StringConstraints,
    model_validator,
)
from shapely.geometry import MultiPolygon, mapping, shape

from app.models.policy_zone import PolicyZoneKind

MAX_POLICY_ZONE_GEOMETRY_CHARS = 2_000_000

type Longitude = Annotated[FiniteFloat, Field(ge=-180, le=180)]
type Latitude = Annotated[FiniteFloat, Field(ge=-90, le=90)]
type Position = tuple[Longitude, Latitude]
type LinearRing = Annotated[list[Position], Field(min_length=4)]
type PolygonCoordinates = Annotated[list[LinearRing], Field(min_length=1)]
type MultiPolygonCoordinates = Annotated[list[PolygonCoordinates], Field(min_length=1)]


class PolicyZoneApiModel(BaseModel):
    """Strict base model shared by policy-zone requests and responses."""

    model_config = ConfigDict(from_attributes=True, extra="forbid")


class GeoJsonMultiPolygon(PolicyZoneApiModel):
    """Validated WGS84 GeoJSON MultiPolygon used at every policy boundary."""

    type: Literal["MultiPolygon"]
    coordinates: MultiPolygonCoordinates

    def to_shapely(self) -> MultiPolygon:
        """Build a non-empty valid MultiPolygon without repairing caller geometry."""

        geometry = shape(self.model_dump(mode="python"))
        if not isinstance(geometry, MultiPolygon):
            raise ValueError("policy zone geometry must be a MultiPolygon")
        if geometry.is_empty:
            raise ValueError("policy zone geometry must not be empty")
        if not geometry.is_valid:
            raise ValueError("policy zone geometry is invalid or self-intersecting")
        return geometry

    @model_validator(mode="after")
    def validate_geometry(self) -> GeoJsonMultiPolygon:
        """Bound the canonical JSON and require explicitly closed valid rings."""

        canonical = json.dumps(
            self.model_dump(mode="json"),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if len(canonical) > MAX_POLICY_ZONE_GEOMETRY_CHARS:
            raise ValueError(
                "policy zone geometry exceeds the 2000000 character contract limit"
            )
        for polygon in self.coordinates:
            for ring in polygon:
                if ring[0] != ring[-1]:
                    raise ValueError("policy zone linear rings must be closed")
        self.to_shapely()
        return self

    @classmethod
    def from_shapely(cls, geometry: MultiPolygon) -> GeoJsonMultiPolygon:
        """Convert one persisted MultiPolygon into its exact JSON representation."""

        return cls.model_validate(mapping(geometry))


class PolicyZoneCreate(PolicyZoneApiModel):
    """Create one exceptional policy without any ordinary delivery-region meaning."""

    name: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=200),
    ]
    kind: PolicyZoneKind
    color: str | None = Field(default=None, pattern=r"^#[0-9A-Fa-f]{6}$")
    geometry: GeoJsonMultiPolygon
    delivery_price_rubles: int | None = Field(default=None, ge=0)
    pickup_price_rubles: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def validate_kind_values(self) -> PolicyZoneCreate:
        """Keep money on SPECIAL_PRICE policies and off route restrictions."""

        priced = self.kind is PolicyZoneKind.SPECIAL_PRICE
        both_values_present = (
            self.delivery_price_rubles is not None
            and self.pickup_price_rubles is not None
        )
        both_values_absent = (
            self.delivery_price_rubles is None
            and self.pickup_price_rubles is None
        )
        if (priced and not both_values_present) or (
            not priced and not both_values_absent
        ):
            raise ValueError(
                "SPECIAL_PRICE requires both prices; restrictions must omit prices"
            )
        return self


class PolicyZoneUpdate(PolicyZoneApiModel):
    """Optimistically fenced partial update for one exceptional policy."""

    expected_version: int = Field(ge=1)
    name: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=200),
    ] | None = None
    kind: PolicyZoneKind | None = None
    color: str | None = Field(default=None, pattern=r"^#[0-9A-Fa-f]{6}$")
    geometry: GeoJsonMultiPolygon | None = None
    delivery_price_rubles: int | None = Field(default=None, ge=0)
    pickup_price_rubles: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def require_change(self) -> PolicyZoneUpdate:
        """Reject an empty version-only command instead of advancing a phantom revision."""

        if self.model_fields_set == {"expected_version"}:
            raise ValueError("at least one policy zone field must be updated")
        for field in ("name", "kind", "color", "geometry"):
            if field in self.model_fields_set and getattr(self, field) is None:
                raise ValueError(f"{field} cannot be null")
        return self


class PolicyZoneRead(PolicyZoneApiModel):
    """Exact server-authoritative exceptional policy returned to dispatchers."""

    id: UUID
    warehouse_id: UUID
    name: str
    kind: PolicyZoneKind
    color: str
    geometry: GeoJsonMultiPolygon
    version: int
    delivery_price_rubles: int | None
    pickup_price_rubles: int | None
    created_at: AwareDatetime
    updated_at: AwareDatetime
