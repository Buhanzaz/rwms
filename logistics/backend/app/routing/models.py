"""Framework-neutral value objects used by routing providers."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from math import isfinite

type GeoJsonLineString = dict[str, object]


def require_aware(value: datetime | None, field_name: str) -> None:
    """Reject naive datetimes at routing boundaries."""

    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError(f"{field_name} must be timezone-aware")


@dataclass(frozen=True, slots=True)
class GeoPoint:
    """A WGS84 point whose coordinate order is always longitude, latitude."""

    lon: float
    lat: float
    is_city: bool = False

    def __post_init__(self) -> None:
        if not isfinite(self.lon) or not -180.0 <= self.lon <= 180.0:
            raise ValueError("lon must be a finite value in [-180, 180]")
        if not isfinite(self.lat) or not -90.0 <= self.lat <= 90.0:
            raise ValueError("lat must be a finite value in [-90, 90]")

    @property
    def coordinates(self) -> tuple[float, float]:
        """Return GeoJSON-compatible ``(longitude, latitude)`` coordinates."""

        return (self.lon, self.lat)


@dataclass(frozen=True, slots=True)
class TravelMetric:
    """Distance and travel duration for one ordered point pair."""

    distance_meters: int
    travel_seconds: int

    def __post_init__(self) -> None:
        if self.distance_meters < 0 or self.travel_seconds < 0:
            raise ValueError("travel metrics cannot be negative")


@dataclass(frozen=True, slots=True)
class TravelMatrix:
    """Square immutable matrix indexed in the same order as ``points``."""

    points: tuple[GeoPoint, ...]
    rows: tuple[tuple[TravelMetric, ...], ...]

    def __post_init__(self) -> None:
        size = len(self.points)
        if len(self.rows) != size or any(len(row) != size for row in self.rows):
            raise ValueError("travel matrix must be square and match points")

    def at(self, from_index: int, to_index: int) -> TravelMetric:
        """Return the metric for an ordered pair of point indexes."""

        return self.rows[from_index][to_index]


@dataclass(frozen=True, slots=True)
class RouteLeg:
    """One routed section between consecutive input points."""

    from_index: int
    to_index: int
    distance_meters: int
    travel_seconds: int
    geometry: GeoJsonLineString


@dataclass(frozen=True, slots=True)
class RouteGeometry:
    """A complete ordered route with aggregate metrics and per-section legs."""

    geometry: GeoJsonLineString
    legs: tuple[RouteLeg, ...]
    total_distance_meters: int
    total_travel_seconds: int


@dataclass(frozen=True, slots=True)
class RoutingSettings:
    """Deterministic offline-routing parameters owned by a scenario snapshot."""

    city_speed_kmh: float = 35.0
    region_speed_kmh: float = 60.0
    road_factor: float = 1.25
    morning_traffic_multiplier: float = 1.35
    evening_traffic_multiplier: float = 1.25
    morning_start_hour: int = 7
    morning_end_hour: int = 10
    evening_start_hour: int = 16
    evening_end_hour: int = 20
    deterministic_noise_ratio: float = 0.015
    seed: int = 0

    def __post_init__(self) -> None:
        positive = {
            "city_speed_kmh": self.city_speed_kmh,
            "region_speed_kmh": self.region_speed_kmh,
            "road_factor": self.road_factor,
            "morning_traffic_multiplier": self.morning_traffic_multiplier,
            "evening_traffic_multiplier": self.evening_traffic_multiplier,
        }
        if any(not isfinite(value) or value <= 0 for value in positive.values()):
            raise ValueError("routing speeds and multipliers must be positive")
        if not 0.0 <= self.deterministic_noise_ratio <= 0.25:
            raise ValueError("deterministic_noise_ratio must be in [0, 0.25]")
        hours = (
            self.morning_start_hour,
            self.morning_end_hour,
            self.evening_start_hour,
            self.evening_end_hour,
        )
        if any(hour < 0 or hour > 24 for hour in hours):
            raise ValueError("traffic period hours must be in [0, 24]")
