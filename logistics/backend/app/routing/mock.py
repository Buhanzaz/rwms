"""Deterministic, offline routing based on great-circle distances."""

from __future__ import annotations

from datetime import datetime, timedelta
from hashlib import sha256
from itertools import pairwise
from math import asin, cos, radians, sin, sqrt

from .models import (
    GeoJsonLineString,
    GeoPoint,
    RouteGeometry,
    RouteLeg,
    RoutingSettings,
    TravelMatrix,
    TravelMetric,
    require_aware,
)

EARTH_RADIUS_METERS = 6_371_008.8


def haversine_distance_meters(first: GeoPoint, second: GeoPoint) -> float:
    """Calculate WGS84 great-circle distance between two points."""

    lat1 = radians(first.lat)
    lat2 = radians(second.lat)
    delta_lat = lat2 - lat1
    delta_lon = radians(second.lon - first.lon)
    haversine = sin(delta_lat / 2.0) ** 2 + cos(lat1) * cos(lat2) * sin(delta_lon / 2.0) ** 2
    return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(haversine)))


class MockRoutingProvider:
    """Provide reproducible straight-line routes with configurable road realism."""

    def __init__(self, settings: RoutingSettings | None = None) -> None:
        self.settings = settings or RoutingSettings()

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> TravelMatrix:
        """Build a complete matrix without network access."""

        require_aware(departure_at, "departure_at")
        immutable_points = tuple(points)
        rows: list[tuple[TravelMetric, ...]] = []
        for from_point in immutable_points:
            rows.append(
                tuple(
                    self._metric(from_point, to_point, departure_at)
                    for to_point in immutable_points
                )
            )
        return TravelMatrix(points=immutable_points, rows=tuple(rows))

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> RouteGeometry:
        """Route through points in input order and expose each straight leg."""

        require_aware(departure_at, "departure_at")
        if not points:
            raise ValueError("a route requires at least one point")
        if len(points) == 1:
            geometry = self._line_string(points)
            return RouteGeometry(geometry, (), 0, 0)

        cursor = departure_at
        legs: list[RouteLeg] = []
        total_distance = 0
        total_seconds = 0
        for index, (start, finish) in enumerate(pairwise(points)):
            metric = self._metric(start, finish, cursor)
            leg_geometry = self._line_string([start, finish])
            legs.append(
                RouteLeg(
                    from_index=index,
                    to_index=index + 1,
                    distance_meters=metric.distance_meters,
                    travel_seconds=metric.travel_seconds,
                    geometry=leg_geometry,
                )
            )
            total_distance += metric.distance_meters
            total_seconds += metric.travel_seconds
            if cursor is not None:
                cursor += timedelta(seconds=metric.travel_seconds)
        return RouteGeometry(
            geometry=self._line_string(points),
            legs=tuple(legs),
            total_distance_meters=total_distance,
            total_travel_seconds=total_seconds,
        )

    def _metric(
        self,
        first: GeoPoint,
        second: GeoPoint,
        departure_at: datetime | None,
    ) -> TravelMetric:
        if first == second:
            return TravelMetric(distance_meters=0, travel_seconds=0)
        air_distance = haversine_distance_meters(first, second)
        road_distance = (
            air_distance * self.settings.road_factor * self._stable_factor(first, second)
        )
        speed_kmh = (
            self.settings.city_speed_kmh
            if first.is_city or second.is_city
            else self.settings.region_speed_kmh
        )
        traffic = self._traffic_multiplier(departure_at)
        seconds = road_distance / (speed_kmh * 1000.0 / 3600.0) * traffic
        return TravelMetric(
            distance_meters=max(1, round(road_distance)),
            travel_seconds=max(1, round(seconds)),
        )

    def _stable_factor(self, first: GeoPoint, second: GeoPoint) -> float:
        ratio = self.settings.deterministic_noise_ratio
        if ratio == 0.0:
            return 1.0
        ordered = sorted((first.coordinates, second.coordinates))
        material = f"{self.settings.seed}|{ordered[0]}|{ordered[1]}".encode()
        unit = int.from_bytes(sha256(material).digest()[:8], "big") / (2**64 - 1)
        return 1.0 + ((unit * 2.0) - 1.0) * ratio

    def _traffic_multiplier(self, departure_at: datetime | None) -> float:
        if departure_at is None:
            return 1.0
        hour = departure_at.hour + departure_at.minute / 60.0
        if self.settings.morning_start_hour <= hour < self.settings.morning_end_hour:
            return self.settings.morning_traffic_multiplier
        if self.settings.evening_start_hour <= hour < self.settings.evening_end_hour:
            return self.settings.evening_traffic_multiplier
        return 1.0

    @staticmethod
    def _line_string(points: list[GeoPoint]) -> GeoJsonLineString:
        coordinates = [[point.lon, point.lat] for point in points]
        if len(coordinates) == 1:
            coordinates.append(coordinates[0].copy())
        return {"type": "LineString", "coordinates": coordinates}
