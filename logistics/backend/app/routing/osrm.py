"""HTTP adapter for an internally hosted Open Source Routing Machine instance."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import datetime
from math import isfinite
from typing import TYPE_CHECKING
from urllib.parse import urlparse

import httpx

from .models import (
    GeoJsonLineString,
    GeoPoint,
    RouteGeometry,
    RouteLeg,
    SnappedPoint,
    TravelMatrix,
    TravelMetric,
    require_aware,
)
from .provider import RoadSnapNotFoundError

if TYPE_CHECKING:
    from .truck_profile import EffectiveTruckProfile


class OsrmRoutingProviderError(RuntimeError):
    """Report an unavailable, malformed, or infeasible OSRM routing response."""


class OsrmRoadSnapNotFoundError(OsrmRoutingProviderError, RoadSnapNotFoundError):
    """Preserve OSRM failure typing when no nearby road segment can be snapped."""


class OsrmRoutingProvider:
    """Route planner traffic through a private OSRM HTTP service backed by OSM data."""

    def __init__(
        self,
        base_url: str,
        *,
        profile: str = "driving",
        timeout_seconds: float = 15.0,
        max_table_points: int = 100,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        normalized_url = base_url.rstrip("/")
        parsed = urlparse(normalized_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("OSRM base_url must be an absolute http(s) URL")
        normalized_profile = profile.strip().lower()
        if not normalized_profile.replace("_", "").replace("-", "").isalnum():
            raise ValueError("OSRM profile must contain letters, digits, '_' or '-'")
        if timeout_seconds <= 0:
            raise ValueError("OSRM timeout_seconds must be positive")
        if max_table_points < 2:
            raise ValueError("OSRM max_table_points must be at least two")
        self.base_url = normalized_url
        self.profile = normalized_profile
        self.timeout_seconds = timeout_seconds
        self.max_table_points = max_table_points
        self._transport = transport

    @property
    def cache_key(self) -> tuple[str, str, float, int]:
        """Expose only stable, non-secret connection facts for matrix cache partitioning."""

        return (self.base_url, self.profile, self.timeout_seconds, self.max_table_points)

    async def snap_point(self, point: GeoPoint) -> SnappedPoint:
        """Resolve one coordinate to the nearest segment in OSRM's driving graph."""

        payload = await self._request(
            f"/nearest/v1/{self.profile}/{self._coordinates((point,))}",
            {"number": "1"},
        )
        waypoints = self._sequence(payload.get("waypoints"), "waypoints")
        if not waypoints or not isinstance(waypoints[0], Mapping):
            raise OsrmRoadSnapNotFoundError("OSRM did not find a routable road segment")
        waypoint = waypoints[0]
        location = self._sequence(waypoint.get("location"), "waypoints[0].location")
        if len(location) != 2:
            raise OsrmRoutingProviderError(
                "OSRM nearest waypoint location must contain longitude and latitude"
            )
        try:
            snapped = GeoPoint(
                lon=self._finite_number(location[0], "nearest longitude"),
                lat=self._finite_number(location[1], "nearest latitude"),
                is_city=point.is_city,
            )
        except ValueError as exc:
            raise OsrmRoutingProviderError("OSRM returned an invalid nearest coordinate") from exc
        return SnappedPoint(
            point=snapped,
            distance_meters=self._finite_nonnegative_number(
                waypoint.get("distance"), "nearest distance"
            ),
            name=self._normalized_name(waypoint.get("name")),
        )

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> TravelMatrix:
        """Return OSRM's legacy driving matrix without claiming truck safety."""

        require_aware(departure_at, "departure_at")
        del profile
        immutable_points = tuple(points)
        if not immutable_points:
            return TravelMatrix(points=(), rows=())
        if len(immutable_points) == 1:
            return TravelMatrix(
                points=immutable_points,
                rows=((TravelMetric(distance_meters=0, travel_seconds=0),),),
            )
        if len(immutable_points) <= self.max_table_points:
            payload = await self._request(
                f"/table/v1/{self.profile}/{self._coordinates(immutable_points)}",
                {"annotations": "distance,duration"},
            )
            distances = self._matrix(
                payload,
                "distances",
                len(immutable_points),
                len(immutable_points),
            )
            durations = self._matrix(
                payload,
                "durations",
                len(immutable_points),
                len(immutable_points),
            )
        else:
            distances, durations = await self._partitioned_matrix(immutable_points)
        rows = tuple(
            tuple(
                TravelMetric(
                    distance_meters=self._metric_value(distances[row][column], "distance"),
                    travel_seconds=self._metric_value(durations[row][column], "duration"),
                )
                for column in range(len(immutable_points))
            )
            for row in range(len(immutable_points))
        )
        return TravelMatrix(points=immutable_points, rows=rows)

    async def _partitioned_matrix(
        self,
        points: tuple[GeoPoint, ...],
    ) -> tuple[tuple[tuple[object, ...], ...], tuple[tuple[object, ...], ...]]:
        """Assemble a full directed matrix from bounded rectangular Table requests."""

        block_size = self.max_table_points // 2
        size = len(points)
        distances: list[list[object]] = [[None] * size for _ in range(size)]
        durations: list[list[object]] = [[None] * size for _ in range(size)]

        for source_start in range(0, size, block_size):
            sources = points[source_start : source_start + block_size]
            for destination_start in range(0, size, block_size):
                destinations = points[destination_start : destination_start + block_size]
                request_points = sources + destinations
                source_indexes = ";".join(str(index) for index in range(len(sources)))
                destination_indexes = ";".join(
                    str(index)
                    for index in range(len(sources), len(request_points))
                )
                payload = await self._request(
                    f"/table/v1/{self.profile}/{self._coordinates(request_points)}",
                    {
                        "annotations": "distance,duration",
                        "sources": source_indexes,
                        "destinations": destination_indexes,
                    },
                )
                block_distances = self._matrix(
                    payload,
                    "distances",
                    len(sources),
                    len(destinations),
                )
                block_durations = self._matrix(
                    payload,
                    "durations",
                    len(sources),
                    len(destinations),
                )
                for source_offset in range(len(sources)):
                    source_index = source_start + source_offset
                    for destination_offset in range(len(destinations)):
                        destination_index = destination_start + destination_offset
                        distances[source_index][destination_index] = block_distances[
                            source_offset
                        ][destination_offset]
                        durations[source_index][destination_index] = block_durations[
                            source_offset
                        ][destination_offset]

        return (
            tuple(tuple(row) for row in distances),
            tuple(tuple(row) for row in durations),
        )

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Return one legacy OSRM route without claiming truck restriction support."""

        require_aware(departure_at, "departure_at")
        del profile
        immutable_points = tuple(points)
        if not immutable_points:
            raise ValueError("a route requires at least one point")
        if len(immutable_points) == 1:
            geometry = self._line_from_points(immutable_points)
            return RouteGeometry(geometry, (), 0, 0)
        payload = await self._request(
            f"/route/v1/{self.profile}/{self._coordinates(immutable_points)}",
            {"geometries": "geojson", "overview": "full", "steps": "true"},
        )
        routes = self._sequence(payload.get("routes"), "routes")
        if not routes or not isinstance(routes[0], Mapping):
            raise OsrmRoutingProviderError("OSRM did not return a route")
        route = routes[0]
        route_legs = self._sequence(route.get("legs"), "routes[0].legs")
        if len(route_legs) != len(immutable_points) - 1:
            raise OsrmRoutingProviderError("OSRM returned an unexpected number of route legs")
        legs: list[RouteLeg] = []
        for index, raw_leg in enumerate(route_legs):
            if not isinstance(raw_leg, Mapping):
                raise OsrmRoutingProviderError("OSRM route leg has an invalid shape")
            legs.append(
                RouteLeg(
                    from_index=index,
                    to_index=index + 1,
                    distance_meters=self._metric_value(
                        raw_leg.get("distance"), "route leg distance"
                    ),
                    travel_seconds=self._metric_value(
                        raw_leg.get("duration"), "route leg duration"
                    ),
                    geometry=self._leg_geometry(raw_leg, immutable_points[index : index + 2]),
                )
            )
        geometry = self._geometry(route.get("geometry"), "route geometry")
        return RouteGeometry(
            geometry=geometry,
            legs=tuple(legs),
            total_distance_meters=self._metric_value(route.get("distance"), "route distance"),
            total_travel_seconds=self._metric_value(route.get("duration"), "route duration"),
        )

    async def _request(self, path: str, params: Mapping[str, str]) -> Mapping[str, object]:
        """Issue one bounded request and normalize OSRM failures into a domain-neutral error."""

        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout_seconds,
                transport=self._transport,
            ) as client:
                response = await client.get(path, params=params)
        except httpx.TimeoutException as exc:
            raise OsrmRoutingProviderError(
                "OSRM не ответил за отведённое время. Повторите построение маршрутов."
            ) from exc
        except httpx.RequestError as exc:
            raise OsrmRoutingProviderError(
                "OSRM недоступен. Проверьте состояние сервиса маршрутизации."
            ) from exc
        try:
            raw_payload: object = response.json()
        except ValueError as exc:
            if response.is_error:
                raise OsrmRoutingProviderError(
                    f"OSRM отклонил запрос маршрутизации (HTTP {response.status_code})."
                ) from exc
            raise OsrmRoutingProviderError("OSRM вернул некорректный JSON-ответ.") from exc
        if response.is_error:
            code = raw_payload.get("code") if isinstance(raw_payload, Mapping) else None
            message = raw_payload.get("message") if isinstance(raw_payload, Mapping) else None
            if code == "NoSegment":
                raise OsrmRoadSnapNotFoundError(
                    "OSRM did not find a routable road segment"
                )
            code_suffix = f", код {code}" if isinstance(code, str) else ""
            message_suffix = f": {message[:300]}" if isinstance(message, str) else ""
            raise OsrmRoutingProviderError(
                f"OSRM отклонил запрос маршрутизации "
                f"(HTTP {response.status_code}{code_suffix}){message_suffix}"
            )
        if not isinstance(raw_payload, Mapping):
            raise OsrmRoutingProviderError("OSRM returned a non-object JSON response")
        code = raw_payload.get("code")
        if code != "Ok":
            if code == "NoSegment":
                raise OsrmRoadSnapNotFoundError(
                    "OSRM did not find a routable road segment"
                )
            message = raw_payload.get("message")
            suffix = f": {message}" if isinstance(message, str) else ""
            raise OsrmRoutingProviderError(f"OSRM routing failed with code {code!r}{suffix}")
        return raw_payload

    @staticmethod
    def _coordinates(points: Sequence[GeoPoint]) -> str:
        """Encode WGS84 points in the longitude-latitude order required by OSRM."""

        return ";".join(f"{point.lon:.7f},{point.lat:.7f}" for point in points)

    @staticmethod
    def _sequence(value: object, field: str) -> Sequence[object]:
        """Require a JSON array without exposing untyped vendor payloads to callers."""

        if not isinstance(value, list):
            raise OsrmRoutingProviderError(f"OSRM field {field!r} must be an array")
        return value

    @classmethod
    def _matrix(
        cls,
        payload: Mapping[str, object],
        field: str,
        row_count: int,
        column_count: int,
    ) -> tuple[tuple[object, ...], ...]:
        """Validate one rectangular OSRM matrix while preserving unreachable cells."""

        rows = cls._sequence(payload.get(field), field)
        if len(rows) != row_count:
            raise OsrmRoutingProviderError(f"OSRM {field} matrix has an unexpected row count")
        normalized: list[tuple[object, ...]] = []
        for row in rows:
            values = cls._sequence(row, field)
            if len(values) != column_count:
                raise OsrmRoutingProviderError(
                    f"OSRM {field} matrix has an unexpected column count"
                )
            normalized.append(tuple(values))
        return tuple(normalized)

    @staticmethod
    def _metric_value(value: object, field: str) -> int:
        """Convert a finite non-negative metric to the simulator integer contract."""

        if isinstance(value, bool) or not isinstance(value, (float, int)):
            raise OsrmRoutingProviderError(
                f"OSRM {field} is unavailable for at least one point pair"
            )
        numeric = float(value)
        if not isfinite(numeric) or numeric < 0:
            raise OsrmRoutingProviderError(f"OSRM {field} must be a finite non-negative number")
        return round(numeric)

    @staticmethod
    def _finite_number(value: object, field: str) -> float:
        """Parse one finite OSRM coordinate without accepting booleans."""

        if isinstance(value, bool) or not isinstance(value, (float, int)):
            raise OsrmRoutingProviderError(f"OSRM {field} must be numeric")
        numeric = float(value)
        if not isfinite(numeric):
            raise OsrmRoutingProviderError(f"OSRM {field} must be finite")
        return numeric

    @classmethod
    def _finite_nonnegative_number(cls, value: object, field: str) -> float:
        """Parse one finite non-negative OSRM scalar without rounding it."""

        numeric = cls._finite_number(value, field)
        if numeric < 0:
            raise OsrmRoutingProviderError(f"OSRM {field} must be non-negative")
        return numeric

    @staticmethod
    def _normalized_name(value: object) -> str | None:
        """Sanitize an optional OSRM road name for bounded operator-facing display."""

        if not isinstance(value, str):
            return None
        normalized = " ".join(value.split())[:300]
        return normalized or None

    @classmethod
    def _leg_geometry(
        cls,
        leg: Mapping[str, object],
        fallback_points: Sequence[GeoPoint],
    ) -> GeoJsonLineString:
        """Join OSRM step geometries into one exact road polyline for a route leg."""

        raw_steps = leg.get("steps")
        if not isinstance(raw_steps, list) or not raw_steps:
            return cls._line_from_points(fallback_points)
        coordinates: list[object] = []
        for raw_step in raw_steps:
            if not isinstance(raw_step, Mapping):
                raise OsrmRoutingProviderError("OSRM route step has an invalid shape")
            geometry = cls._geometry(raw_step.get("geometry"), "route step geometry")
            step_coordinates = geometry["coordinates"]
            if not isinstance(step_coordinates, list):
                raise OsrmRoutingProviderError("OSRM route step has invalid coordinates")
            if coordinates and step_coordinates and coordinates[-1] == step_coordinates[0]:
                coordinates.extend(step_coordinates[1:])
            else:
                coordinates.extend(step_coordinates)
        if len(coordinates) < 2:
            return cls._line_from_points(fallback_points)
        return {"type": "LineString", "coordinates": coordinates}

    @classmethod
    def _geometry(cls, value: object, field: str) -> GeoJsonLineString:
        """Validate and copy a vendor GeoJSON LineString before persistence."""

        if not isinstance(value, Mapping) or value.get("type") != "LineString":
            raise OsrmRoutingProviderError(f"OSRM {field} must be a GeoJSON LineString")
        coordinates = value.get("coordinates")
        if not isinstance(coordinates, list) or len(coordinates) < 2:
            raise OsrmRoutingProviderError(f"OSRM {field} has too few coordinates")
        for coordinate in coordinates:
            if (
                not isinstance(coordinate, list)
                or len(coordinate) < 2
                or isinstance(coordinate[0], bool)
                or isinstance(coordinate[1], bool)
                or not isinstance(coordinate[0], (float, int))
                or not isinstance(coordinate[1], (float, int))
            ):
                raise OsrmRoutingProviderError(f"OSRM {field} has invalid coordinates")
        return {"type": "LineString", "coordinates": coordinates}

    @staticmethod
    def _line_from_points(points: Sequence[GeoPoint]) -> GeoJsonLineString:
        """Return a degenerate but valid LineString for an identity route."""

        coordinates = [[point.lon, point.lat] for point in points]
        if len(coordinates) == 1:
            coordinates.append(coordinates[0].copy())
        return {"type": "LineString", "coordinates": coordinates}
