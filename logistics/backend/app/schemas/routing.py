"""Public contracts for spatial truck-routing diagnostics."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import AwareDatetime, Field, model_validator

from app.routing.restrictions import RestrictionSupportStatus, TruckRestrictionCategory
from app.schemas.domain import ApiModel


class TruckRestrictionQuery(ApiModel):
    """Validated WGS84 viewport and bounded feature count."""

    west: float = Field(ge=-180, le=180)
    south: float = Field(ge=-90, le=90)
    east: float = Field(ge=-180, le=180)
    north: float = Field(ge=-90, le=90)
    limit: int = Field(default=2000, ge=1, le=5000)

    @model_validator(mode="after")
    def validate_bbox_order(self) -> TruckRestrictionQuery:
        """Reject antimeridian and inverted boxes unsupported by this endpoint."""

        if self.west >= self.east:
            raise ValueError("west must be smaller than east")
        if self.south >= self.north:
            raise ValueError("south must be smaller than north")
        return self


class TruckRestrictionGeometry(ApiModel):
    """GeoJSON geometry shapes emitted by the node/way OSM extractor."""

    type: Literal["Point", "LineString", "MultiLineString", "Polygon"]
    coordinates: list[Any]


class TruckRestrictionProperties(ApiModel):
    """Map styling and diagnostic properties for one OSM restriction."""

    osm_type: Literal["node", "way"]
    osm_id: int
    category: TruckRestrictionCategory
    primary_tag: str
    value: str
    tags: dict[str, str]
    support_status: RestrictionSupportStatus


class TruckRestrictionFeature(ApiModel):
    """One GeoJSON feature from the active OSM routing-data version."""

    type: Literal["Feature"] = "Feature"
    geometry: TruckRestrictionGeometry
    properties: TruckRestrictionProperties


class TruckRestrictionMetadata(ApiModel):
    """Completeness and provenance metadata for one viewport query."""

    osm_data_version: str
    count: int
    truncated: bool
    generated_at: AwareDatetime | None


class TruckRestrictionFeatureCollection(ApiModel):
    """Bounded GeoJSON collection used by the optional map overlay."""

    type: Literal["FeatureCollection"] = "FeatureCollection"
    features: list[TruckRestrictionFeature]
    metadata: TruckRestrictionMetadata
