"""Public contracts for spatial truck-routing diagnostics."""

from __future__ import annotations

from typing import Any, Literal
from uuid import UUID

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


class TravelTimeContourQuery(ApiModel):
    """WGS84 origin and configured contiguous hourly contour minutes."""

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    contours_minutes: list[int] = Field(
        default_factory=lambda: [60, 120, 180, 240],
        min_length=1,
        max_length=12,
    )

    @model_validator(mode="after")
    def validate_contours(self) -> TravelTimeContourQuery:
        """Require contiguous hourly tiers starting at sixty and ending by twelve hours."""

        expected = list(range(60, 60 * (len(self.contours_minutes) + 1), 60))
        if self.contours_minutes != expected:
            raise ValueError("contours_minutes must be contiguous hourly tiers starting at 60")
        return self


class TravelTimeContourGeometry(ApiModel):
    """Validated GeoJSON area returned by Valhalla for one truck-time contour."""

    type: Literal["Polygon", "MultiPolygon"]
    coordinates: list[Any]


class TravelTimeContourProperties(ApiModel):
    """Stable styling key for one configured travel-time contour."""

    contour_minutes: int = Field(ge=60, le=720, multiple_of=60)


class TravelTimeContourFeature(ApiModel):
    """One truck travel-time area centered on the requested origin."""

    type: Literal["Feature"] = "Feature"
    geometry: TravelTimeContourGeometry
    properties: TravelTimeContourProperties


class TravelTimeContourOrigin(ApiModel):
    """WGS84 depot or route-front origin echoed in contour response metadata."""

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class TravelTimeContourMetadata(ApiModel):
    """Provider provenance distinguishing visual estimates from planner routes."""

    source: Literal["valhalla"] = "valhalla"
    costing: Literal["truck"] = "truck"
    origin: TravelTimeContourOrigin
    contours_minutes: list[int] = Field(min_length=1, max_length=12)
    osm_data_version: str


class TravelTimeContourFeatureCollection(ApiModel):
    """Configured Valhalla truck isochrones rendered as visual map estimates."""

    type: Literal["FeatureCollection"] = "FeatureCollection"
    features: list[TravelTimeContourFeature]
    metadata: TravelTimeContourMetadata


class TransferArrivalEstimateRequest(ApiModel):
    """Side-effect-free facts needed to route one planned warehouse transfer leg."""

    source_warehouse_id: UUID
    destination_warehouse_id: UUID
    planned_departure_at: AwareDatetime
    vehicle_id: UUID
    cabin_count: int = Field(ge=0, le=2)


class TransferArrivalEstimateRead(ApiModel):
    """Exact truck-road estimate and physical configuration used for the calculation."""

    departure_at: AwareDatetime
    estimated_arrival_at: AwareDatetime
    travel_seconds: int = Field(ge=0)
    distance_meters: int = Field(ge=0)
    vehicle_id: UUID
    cabin_count: int = Field(ge=0, le=2)
    trailer_attached: bool
    routing_provider: str
    osm_data_version: str | None = None
