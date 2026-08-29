"""Safe truck-routing adapter for a private Valhalla 3.8.3 instance."""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from datetime import datetime
from math import asin, cos, isfinite, radians, sin, sqrt
from time import perf_counter
from typing import cast
from urllib.parse import urlparse

import httpx

from .capabilities import VALHALLA_VERSION
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
from .truck_profile import EffectiveTruckProfile

LOGGER = logging.getLogger(__name__)

_NO_SAFE_ROUTE_CODES = frozenset({170, 171, 441, 442, 443})
_NO_SAFE_ROUTE_MESSAGES = (
    "no path",
    "no suitable edges",
    "unreachable",
    "unconnected regions",
)
_TRAVEL_TIME_CONTOURS_MINUTES = (60, 120, 180, 240)


class ValhallaRoutingError(RuntimeError):
    """Base error carrying a stable code suitable for an API problem response."""

    code = "VALHALLA_ROUTING_ERROR"


class NoSafeRouteError(ValhallaRoutingError):
    """Report that truck restrictions leave no route for the effective profile."""

    code = "NO_SAFE_ROUTE"


class RoutingProviderUnavailableError(ValhallaRoutingError):
    """Report a timeout, service failure, or malformed provider response."""

    code = "ROUTING_PROVIDER_UNAVAILABLE"


class RoutingProfileIncompleteError(ValhallaRoutingError):
    """Report missing or invalid physical inputs without substituting guessed values."""

    code = "ROUTING_PROFILE_INCOMPLETE"

    def __init__(self, missing_fields: Sequence[str]) -> None:
        unique_fields = tuple(dict.fromkeys(missing_fields))
        self.missing_fields = unique_fields
        suffix = ", ".join(unique_fields)
        super().__init__(f"Недостаточно данных грузового профиля: {suffix}")


class ValhallaRoadSnapNotFoundError(ValhallaRoutingError, RoadSnapNotFoundError):
    """Signal that `/locate` found no nearby graph edge for a generated point."""

    code = "ROAD_SNAP_NOT_FOUND"


class ValhallaRoutingProvider:
    """Route each leg with truck costing and one explicit effective load profile."""

    provider_name = "valhalla"

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 30.0,
        osm_data_version: str = "unknown",
        provider_version: str = VALHALLA_VERSION,
        transport: httpx.AsyncBaseTransport | None = None,
        log_context: Mapping[str, str | int] | None = None,
    ) -> None:
        normalized_url = base_url.strip().rstrip("/")
        parsed = urlparse(normalized_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("Valhalla base_url must be an absolute http(s) URL")
        if parsed.query or parsed.fragment:
            raise ValueError("Valhalla base_url must not contain a query or fragment")
        if not isfinite(timeout_seconds) or timeout_seconds < 0:
            raise ValueError("Valhalla timeout_seconds must be non-negative")
        normalized_osm_version = osm_data_version.strip()
        if (
            not normalized_osm_version
            or any(char.isspace() for char in normalized_osm_version)
            or len(normalized_osm_version) > 128
        ):
            raise ValueError("osm_data_version must be non-blank and contain no spaces")
        normalized_provider_version = provider_version.strip()
        if (
            not normalized_provider_version
            or len(normalized_provider_version) > 64
            or any(char.isspace() for char in normalized_provider_version)
        ):
            raise ValueError("provider_version must be non-blank")

        self.base_url = normalized_url
        self.timeout_seconds = timeout_seconds
        self.osm_data_version = normalized_osm_version
        self.provider_version = normalized_provider_version
        self._transport = transport
        self._log_context = dict(log_context or {})
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=self.timeout_seconds,
            transport=self._transport,
            limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
        )

    async def aclose(self) -> None:
        """Release the provider's pooled HTTP connections after one operation."""

        await self._client.aclose()

    @property
    def cache_key(self) -> tuple[str, str, str, float, str]:
        """Partition route caches by engine, endpoint, deadline, and exact OSM tileset."""

        return (
            self.provider_name,
            self.provider_version,
            self.base_url,
            self.timeout_seconds,
            self.osm_data_version,
        )

    def route_cache_key(
        self,
        points: Sequence[GeoPoint],
        departure_at: datetime | None,
        profile: EffectiveTruckProfile,
    ) -> tuple[object, ...]:
        """Build the complete immutable identity expected by an external route cache."""

        require_aware(departure_at, "departure_at")
        return (
            *self.cache_key,
            tuple((point.lon, point.lat) for point in points),
            departure_at.isoformat() if departure_at is not None else None,
            profile.cargo_count,
            *profile.cache_key_data(),
        )

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> TravelMatrix:
        """Return a directed matrix calculated only with Valhalla truck costing."""

        effective_profile = self._require_profile(profile)
        require_aware(departure_at, "departure_at")
        immutable_points = tuple(points)
        if not immutable_points:
            return TravelMatrix(points=(), rows=())
        if len(immutable_points) == 1:
            return TravelMatrix(
                points=immutable_points,
                rows=((TravelMetric(distance_meters=0, travel_seconds=0),),),
            )

        started_at = perf_counter()
        try:
            raw_payload = await self._request_json(
                "/sources_to_targets",
                self._matrix_request(immutable_points, departure_at, effective_profile),
            )
            payload = self._mapping(raw_payload, "matrix response")
            matrix = self._parse_matrix(payload, immutable_points)
        except ValhallaRoutingError as exc:
            self._log_result("matrix", effective_profile, started_at, error=exc)
            raise
        total_distance = sum(metric.distance_meters for row in matrix.rows for metric in row)
        total_duration = sum(metric.travel_seconds for row in matrix.rows for metric in row)
        self._log_result(
            "matrix",
            effective_profile,
            started_at,
            distance_meters=total_distance,
            travel_seconds=total_duration,
        )
        return matrix

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Return a truck-safe geometry or a typed error; never retry with car costing."""

        effective_profile = self._require_profile(profile)
        require_aware(departure_at, "departure_at")
        immutable_points = tuple(points)
        if not immutable_points:
            raise ValueError("a route requires at least one point")
        if len(immutable_points) == 1:
            coordinate = list(immutable_points[0].coordinates)
            geometry: GeoJsonLineString = {
                "type": "LineString",
                "coordinates": [coordinate, coordinate],
            }
            return RouteGeometry(geometry, (), 0, 0)

        started_at = perf_counter()
        try:
            raw_payload = await self._request_json(
                "/route",
                self._route_request(immutable_points, departure_at, effective_profile),
            )
            payload = self._mapping(raw_payload, "route response")
            route = self._parse_route(payload, len(immutable_points) - 1)
        except ValhallaRoutingError as exc:
            self._log_result("route", effective_profile, started_at, error=exc)
            raise
        self._log_result(
            "route",
            effective_profile,
            started_at,
            distance_meters=route.total_distance_meters,
            travel_seconds=route.total_travel_seconds,
        )
        return route

    async def snap_point(self, point: GeoPoint) -> SnappedPoint:
        """Locate a candidate road edge; this is not proof of a safe truck route."""

        try:
            payload = await self._request_json(
                "/locate",
                {"locations": [self._location(point)], "verbose": True},
                snap_request=True,
            )
        except NoSafeRouteError as exc:
            raise ValhallaRoadSnapNotFoundError(
                "Valhalla did not find a routable road segment"
            ) from exc
        locations = self._sequence(payload, "locate response")
        if not locations:
            raise ValhallaRoadSnapNotFoundError("Valhalla did not find a routable road segment")
        location = self._mapping(locations[0], "locate response location")
        edges = self._sequence(location.get("edges"), "locate response edges")
        if not edges:
            raise ValhallaRoadSnapNotFoundError("Valhalla did not find a routable road segment")
        edge = self._mapping(edges[0], "locate response edge")
        try:
            snapped = GeoPoint(
                lon=self._finite_number(edge.get("correlated_lon"), "correlated_lon"),
                lat=self._finite_number(edge.get("correlated_lat"), "correlated_lat"),
                is_city=point.is_city,
            )
        except ValueError as exc:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула некорректные координаты дорожного сегмента."
            ) from exc
        return SnappedPoint(
            point=snapped,
            distance_meters=self._haversine_meters(point, snapped),
            name=self._road_name(edge),
        )

    async def get_truck_travel_time_contours(
        self,
        origin: GeoPoint,
        *,
        departure_at: datetime | None = None,
        profile: EffectiveTruckProfile | None = None,
        contour_minutes: tuple[int, ...] = _TRAVEL_TIME_CONTOURS_MINUTES,
    ) -> list[dict[str, object]]:
        """Return time/profile-aware truck isochrones with validated GeoJSON areas."""

        require_aware(departure_at, "departure_at")
        if not contour_minutes or any(value < 1 or value > 240 for value in contour_minutes):
            raise ValueError("isochrone contours must contain minutes in [1, 240]")
        if len(set(contour_minutes)) != len(contour_minutes):
            raise ValueError("isochrone contour minutes must be unique")
        payload: dict[str, object] = {
            "locations": [self._location(origin)],
            "costing": "truck",
            "contours": [{"time": minutes} for minutes in contour_minutes],
            "polygons": True,
        }
        if profile is not None:
            payload["costing_options"] = {"truck": self._truck_costing(profile)}
        if departure_at is not None:
            payload["date_time"] = self._date_time(departure_at)

        raw_payload = await self._request_json(
            "/isochrone",
            payload,
        )
        response = self._mapping(raw_payload, "isochrone response")
        return self._parse_travel_time_contours(response, contour_minutes)

    def _route_request(
        self,
        points: tuple[GeoPoint, ...],
        departure_at: datetime | None,
        profile: EffectiveTruckProfile,
    ) -> dict[str, object]:
        """Build the native route request while explicitly retaining a GeoJSON preference."""

        payload: dict[str, object] = {
            "locations": [self._location(point) for point in points],
            "costing": "truck",
            "costing_options": {"truck": self._truck_costing(profile)},
            "units": "kilometers",
            "shape_format": "geojson",
            "directions_type": "none",
        }
        if departure_at is not None:
            payload["date_time"] = self._date_time(departure_at)
        return payload

    def _matrix_request(
        self,
        points: tuple[GeoPoint, ...],
        departure_at: datetime | None,
        profile: EffectiveTruckProfile,
    ) -> dict[str, object]:
        """Build a square truck matrix without returning unused per-cell path shapes."""

        locations = [self._location(point) for point in points]
        payload: dict[str, object] = {
            "sources": locations,
            "targets": locations,
            "costing": "truck",
            "costing_options": {"truck": self._truck_costing(profile)},
            "units": "kilometers",
            "verbose": True,
            "shape_format": "no_shape",
        }
        if departure_at is not None:
            payload["date_time"] = self._date_time(departure_at)
        return payload

    @staticmethod
    def _location(point: GeoPoint) -> dict[str, float]:
        """Map one domain point to Valhalla's latitude/longitude object."""

        return {"lat": point.lat, "lon": point.lon}

    @staticmethod
    def _date_time(value: datetime) -> dict[str, str | int]:
        """Send the supplied wall-clock minute in the origin's local timezone."""

        require_aware(value, "departure_at")
        return {"type": 1, "value": value.strftime("%Y-%m-%dT%H:%M")}

    @staticmethod
    def _truck_costing(profile: EffectiveTruckProfile) -> dict[str, float | int | bool]:
        """Translate only already-calculated SI values into Valhalla truck options."""

        return {
            "height": profile.height_meters,
            "width": profile.width_meters,
            "length": profile.length_meters,
            "weight": profile.actual_weight_tons,
            "axle_load": profile.max_axle_load_tons,
            "axle_count": profile.axle_count,
            "hgv_no_access_penalty": 43_200,
            "ignore_restrictions": False,
            "ignore_access": False,
            "ignore_closures": False,
        }

    @staticmethod
    def _require_profile(
        profile: EffectiveTruckProfile | None,
    ) -> EffectiveTruckProfile:
        """Reject absent or physically invalid profiles before any provider call."""

        if profile is None:
            raise RoutingProfileIncompleteError(("effective_truck_profile",))
        missing: list[str] = []
        positive_values = {
            "height_meters": profile.height_meters,
            "width_meters": profile.width_meters,
            "length_meters": profile.length_meters,
            "actual_weight_tons": profile.actual_weight_tons,
            "max_axle_load_tons": profile.max_axle_load_tons,
        }
        for field_name, value in positive_values.items():
            if not isfinite(value) or value <= 0:
                missing.append(field_name)
        if profile.axle_count < 2 or profile.axle_count > 20:
            missing.append("axle_count")
        if missing:
            raise RoutingProfileIncompleteError(missing)
        return profile

    async def _request_json(
        self,
        path: str,
        payload: Mapping[str, object],
        *,
        snap_request: bool = False,
    ) -> object:
        """Issue one bounded POST and map provider failures to stable domain errors."""

        try:
            response = await self._client.post(path, json=payload)
        except httpx.TimeoutException as exc:
            raise RoutingProviderUnavailableError(
                "Valhalla не ответила за отведённое время."
            ) from exc
        except httpx.RequestError as exc:
            raise RoutingProviderUnavailableError("Valhalla недоступна.") from exc

        try:
            response_payload = cast(object, response.json())
        except ValueError as exc:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула некорректный JSON-ответ."
            ) from exc
        if response.is_success:
            return response_payload

        error_code, detail = self._provider_error(response_payload)
        normalized_detail = detail.lower()
        is_no_route = response.status_code < 500 and (
            error_code in _NO_SAFE_ROUTE_CODES
            or any(marker in normalized_detail for marker in _NO_SAFE_ROUTE_MESSAGES)
        )
        if is_no_route:
            if snap_request:
                raise ValhallaRoadSnapNotFoundError("Valhalla did not find a routable road segment")
            raise NoSafeRouteError(
                "Для текущей конфигурации автомобиля безопасный грузовой маршрут не найден."
            )
        suffix = f" Код Valhalla: {error_code}." if error_code is not None else ""
        detail_suffix = f" {detail}" if detail else ""
        raise RoutingProviderUnavailableError(
            f"Valhalla отклонила запрос (HTTP {response.status_code}).{suffix}{detail_suffix}"
        )

    def _parse_matrix(
        self,
        payload: Mapping[str, object],
        points: tuple[GeoPoint, ...],
    ) -> TravelMatrix:
        """Convert Valhalla's verbose row-major response into exact metric objects."""

        units = payload.get("units")
        if units not in {None, "kilometers", "km"}:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула матрицу в неожиданных единицах измерения."
            )
        raw_rows = self._sequence(payload.get("sources_to_targets"), "sources_to_targets")
        if len(raw_rows) != len(points):
            raise RoutingProviderUnavailableError(
                "Valhalla returned a matrix with an unexpected row count."
            )
        rows: list[tuple[TravelMetric, ...]] = []
        for from_index, raw_row in enumerate(raw_rows):
            cells = self._sequence(raw_row, f"sources_to_targets[{from_index}]")
            if len(cells) != len(points):
                raise RoutingProviderUnavailableError(
                    "Valhalla returned a matrix with an unexpected column count."
                )
            parsed_row: list[TravelMetric] = []
            for to_index, raw_cell in enumerate(cells):
                cell = self._mapping(
                    raw_cell,
                    f"sources_to_targets[{from_index}][{to_index}]",
                )
                if cell.get("distance") is None or cell.get("time") is None:
                    raise NoSafeRouteError(
                        "Для текущей конфигурации автомобиля безопасный грузовой маршрут "
                        "найден не для всех точек."
                    )
                self._validate_matrix_index(cell, "from_index", from_index)
                self._validate_matrix_index(cell, "to_index", to_index)
                parsed_row.append(
                    TravelMetric(
                        distance_meters=self._kilometers_to_meters(
                            cell.get("distance"), "matrix distance"
                        ),
                        travel_seconds=self._seconds(cell.get("time"), "matrix time"),
                    )
                )
            rows.append(tuple(parsed_row))
        return TravelMatrix(points=points, rows=tuple(rows))

    def _parse_route(
        self,
        payload: Mapping[str, object],
        expected_leg_count: int,
    ) -> RouteGeometry:
        """Parse native Valhalla trip summaries and GeoJSON/polyline6 leg shapes."""

        trip = self._mapping(payload.get("trip"), "trip")
        raw_legs = self._sequence(trip.get("legs"), "trip.legs")
        if len(raw_legs) != expected_leg_count:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула неожиданное число участков маршрута."
            )
        legs: list[RouteLeg] = []
        for index, raw_leg in enumerate(raw_legs):
            leg = self._mapping(raw_leg, f"trip.legs[{index}]")
            summary = self._mapping(leg.get("summary"), f"trip.legs[{index}].summary")
            legs.append(
                RouteLeg(
                    from_index=index,
                    to_index=index + 1,
                    distance_meters=self._kilometers_to_meters(
                        summary.get("length"), "route leg length"
                    ),
                    travel_seconds=self._seconds(summary.get("time"), "route leg time"),
                    geometry=self._shape(leg.get("shape"), f"trip.legs[{index}].shape"),
                )
            )
        summary = self._mapping(trip.get("summary"), "trip.summary")
        return RouteGeometry(
            geometry=self._join_leg_shapes(legs),
            legs=tuple(legs),
            total_distance_meters=self._kilometers_to_meters(summary.get("length"), "route length"),
            total_travel_seconds=self._seconds(summary.get("time"), "route time"),
        )

    def _parse_travel_time_contours(
        self,
        payload: Mapping[str, object],
        requested_contours: tuple[int, ...],
    ) -> list[dict[str, object]]:
        """Canonicalize exactly the requested Valhalla Polygon/MultiPolygon contours."""

        if payload.get("type") != "FeatureCollection":
            raise RoutingProviderUnavailableError(
                "Valhalla isochrone response must be a GeoJSON FeatureCollection."
            )
        raw_features = self._sequence(payload.get("features"), "isochrone features")
        contours: dict[int, dict[str, object]] = {}
        for index, raw_feature in enumerate(raw_features):
            feature = self._mapping(raw_feature, f"isochrone features[{index}]")
            if feature.get("type") != "Feature":
                raise RoutingProviderUnavailableError(
                    f"Valhalla isochrone features[{index}] must be a GeoJSON Feature."
                )
            properties = self._mapping(
                feature.get("properties"), f"isochrone features[{index}].properties"
            )
            contour_minutes = self._isochrone_contour_minutes(
                properties.get("contour"),
                f"isochrone features[{index}].properties.contour",
                requested_contours,
            )
            if contour_minutes in contours:
                raise RoutingProviderUnavailableError(
                    f"Valhalla returned duplicate {contour_minutes}-minute isochrones."
                )
            geometry = self._isochrone_geometry(
                feature.get("geometry"), f"isochrone features[{index}].geometry"
            )
            contours[contour_minutes] = {
                "type": "Feature",
                "geometry": geometry,
                "properties": {"contour_minutes": contour_minutes},
            }
        if set(contours) != set(requested_contours):
            raise RoutingProviderUnavailableError(
                "Valhalla did not return all requested travel-time contours."
            )
        return [contours[minutes] for minutes in sorted(requested_contours, reverse=True)]

    def _isochrone_geometry(self, value: object, field: str) -> dict[str, object]:
        """Validate and normalize one GeoJSON Polygon or MultiPolygon geometry."""

        geometry = self._mapping(value, field)
        geometry_type = geometry.get("type")
        if geometry_type == "Polygon":
            coordinates: object = self._isochrone_polygon_coordinates(
                geometry.get("coordinates"), f"{field}.coordinates"
            )
        elif geometry_type == "MultiPolygon":
            raw_polygons = self._sequence(geometry.get("coordinates"), f"{field}.coordinates")
            if not raw_polygons:
                raise RoutingProviderUnavailableError(
                    f"Valhalla {field}.coordinates must contain at least one polygon."
                )
            coordinates = [
                self._isochrone_polygon_coordinates(polygon, f"{field}.coordinates[{index}]")
                for index, polygon in enumerate(raw_polygons)
            ]
        else:
            raise RoutingProviderUnavailableError(
                f"Valhalla {field} must be a GeoJSON Polygon or MultiPolygon."
            )
        return {"type": geometry_type, "coordinates": coordinates}

    def _isochrone_polygon_coordinates(
        self,
        value: object,
        field: str,
    ) -> list[list[list[float]]]:
        """Validate all closed linear rings of one GeoJSON polygon."""

        raw_rings = self._sequence(value, field)
        if not raw_rings:
            raise RoutingProviderUnavailableError(
                f"Valhalla {field} must contain at least one linear ring."
            )
        rings: list[list[list[float]]] = []
        for ring_index, raw_ring in enumerate(raw_rings):
            ring_field = f"{field}[{ring_index}]"
            raw_coordinates = self._sequence(raw_ring, ring_field)
            coordinates: list[list[float]] = []
            for coordinate_index, raw_coordinate in enumerate(raw_coordinates):
                coordinate = self._sequence(raw_coordinate, f"{ring_field}[{coordinate_index}]")
                if len(coordinate) < 2:
                    raise RoutingProviderUnavailableError(
                        f"Valhalla {ring_field} contains an invalid coordinate."
                    )
                longitude = self._finite_number(coordinate[0], "isochrone longitude")
                latitude = self._finite_number(coordinate[1], "isochrone latitude")
                if not -180 <= longitude <= 180 or not -90 <= latitude <= 90:
                    raise RoutingProviderUnavailableError(
                        f"Valhalla {ring_field} contains an out-of-range WGS84 coordinate."
                    )
                coordinates.append([longitude, latitude])
            if len(coordinates) < 4 or coordinates[0] != coordinates[-1]:
                raise RoutingProviderUnavailableError(
                    f"Valhalla {ring_field} must be a closed GeoJSON linear ring."
                )
            rings.append(coordinates)
        return rings

    @staticmethod
    def _isochrone_contour_minutes(
        value: object,
        field: str,
        requested_contours: tuple[int, ...],
    ) -> int:
        """Require one unique member of the contours requested for this calculation."""

        if (
            not isinstance(value, int | float)
            or isinstance(value, bool)
            or not isfinite(value)
            or int(value) != value
            or int(value) not in requested_contours
        ):
            raise RoutingProviderUnavailableError(
                f"Valhalla {field} must be one of {requested_contours}."
            )
        return int(value)

    def _shape(self, value: object, field: str) -> GeoJsonLineString:
        """Accept Valhalla's requested GeoJSON or its native polyline6 representation."""

        if isinstance(value, str):
            decoded_coordinates = self._decode_polyline6(value)
            if len(decoded_coordinates) < 2:
                raise RoutingProviderUnavailableError(
                    f"Valhalla {field} contains too few coordinates."
                )
            return {"type": "LineString", "coordinates": decoded_coordinates}
        shape = self._mapping(value, field)
        if shape.get("type") != "LineString":
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be a GeoJSON LineString.")
        raw_coordinates = self._sequence(shape.get("coordinates"), f"{field}.coordinates")
        coordinates: list[list[float]] = []
        for index, raw_coordinate in enumerate(raw_coordinates):
            coordinate = self._sequence(raw_coordinate, f"{field}.coordinates[{index}]")
            if len(coordinate) < 2:
                raise RoutingProviderUnavailableError(
                    f"Valhalla {field} contains an invalid coordinate."
                )
            coordinates.append(
                [
                    self._finite_number(coordinate[0], f"{field} longitude"),
                    self._finite_number(coordinate[1], f"{field} latitude"),
                ]
            )
        if len(coordinates) < 2:
            raise RoutingProviderUnavailableError(f"Valhalla {field} contains too few coordinates.")
        return {"type": "LineString", "coordinates": coordinates}

    @staticmethod
    def _decode_polyline6(encoded: str) -> list[list[float]]:
        """Decode Valhalla's native latitude/longitude polyline with precision six."""

        coordinates: list[list[float]] = []
        index = 0
        latitude = 0
        longitude = 0
        try:
            while index < len(encoded):
                latitude_delta, index = ValhallaRoutingProvider._decode_polyline_value(
                    encoded, index
                )
                longitude_delta, index = ValhallaRoutingProvider._decode_polyline_value(
                    encoded, index
                )
                latitude += latitude_delta
                longitude += longitude_delta
                coordinates.append([longitude / 1_000_000, latitude / 1_000_000])
        except ValueError as exc:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула повреждённую polyline6-геометрию."
            ) from exc
        return coordinates

    @staticmethod
    def _decode_polyline_value(encoded: str, index: int) -> tuple[int, int]:
        """Decode one signed delta from an encoded polyline."""

        result = 0
        shift = 0
        while True:
            if index >= len(encoded) or shift > 30:
                raise ValueError("invalid encoded polyline")
            byte = ord(encoded[index]) - 63
            index += 1
            if byte < 0:
                raise ValueError("invalid encoded polyline")
            result |= (byte & 0x1F) << shift
            shift += 5
            if byte < 0x20:
                break
        delta = ~(result >> 1) if result & 1 else result >> 1
        return delta, index

    @staticmethod
    def _join_leg_shapes(legs: Sequence[RouteLeg]) -> GeoJsonLineString:
        """Join exact leg LineStrings while removing only duplicate boundary points."""

        coordinates: list[object] = []
        for leg in legs:
            raw_coordinates = cast(list[object], leg.geometry["coordinates"])
            if coordinates and raw_coordinates and coordinates[-1] == raw_coordinates[0]:
                coordinates.extend(raw_coordinates[1:])
            else:
                coordinates.extend(raw_coordinates)
        if len(coordinates) < 2:
            raise RoutingProviderUnavailableError(
                "Valhalla вернула маршрут без достаточной геометрии."
            )
        return {"type": "LineString", "coordinates": coordinates}

    @staticmethod
    def _provider_error(payload: object) -> tuple[int | None, str]:
        """Extract a bounded provider code/message without exposing the request URL."""

        if not isinstance(payload, Mapping):
            return (None, "")
        raw_code = payload.get("error_code")
        error_code = (
            raw_code if isinstance(raw_code, int) and not isinstance(raw_code, bool) else None
        )
        raw_detail = payload.get("error") or payload.get("status") or payload.get("message")
        if not isinstance(raw_detail, str):
            return (error_code, "")
        return (error_code, " ".join(raw_detail.split())[:240])

    @staticmethod
    def _mapping(value: object, field: str) -> Mapping[str, object]:
        """Require a JSON object at one response boundary."""

        if not isinstance(value, Mapping):
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be an object.")
        return cast(Mapping[str, object], value)

    @staticmethod
    def _sequence(value: object, field: str) -> Sequence[object]:
        """Require a JSON array while rejecting text and binary sequences."""

        if not isinstance(value, Sequence) or isinstance(value, str | bytes | bytearray):
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be an array.")
        return cast(Sequence[object], value)

    @staticmethod
    def _finite_number(value: object, field: str) -> float:
        """Parse one finite JSON number while rejecting booleans."""

        if not isinstance(value, int | float) or isinstance(value, bool):
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be numeric.")
        parsed = float(value)
        if not isfinite(parsed):
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be finite.")
        return parsed

    def _kilometers_to_meters(self, value: object, field: str) -> int:
        """Convert a finite non-negative Valhalla kilometer value to integer meters."""

        parsed = self._finite_number(value, field)
        if parsed < 0:
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be non-negative.")
        return round(parsed * 1000)

    def _seconds(self, value: object, field: str) -> int:
        """Round one finite non-negative Valhalla duration to domain seconds."""

        parsed = self._finite_number(value, field)
        if parsed < 0:
            raise RoutingProviderUnavailableError(f"Valhalla {field} must be non-negative.")
        return round(parsed)

    @staticmethod
    def _validate_matrix_index(cell: Mapping[str, object], field: str, expected: int) -> None:
        """Validate verbose matrix indexes when Valhalla includes them."""

        value = cell.get(field)
        if value is None:
            return
        if not isinstance(value, int) or isinstance(value, bool) or value != expected:
            raise RoutingProviderUnavailableError(
                f"Valhalla matrix {field} does not match row-major order."
            )

    @staticmethod
    def _road_name(edge: Mapping[str, object]) -> str | None:
        """Extract a bounded OSM road name, falling back to the non-secret way ID."""

        edge_info = edge.get("edge_info")
        if isinstance(edge_info, Mapping):
            names = edge_info.get("names")
            if isinstance(names, Sequence) and not isinstance(names, str | bytes | bytearray):
                for raw_name in names:
                    if isinstance(raw_name, str):
                        normalized = " ".join(raw_name.split())[:300]
                        if normalized:
                            return normalized
                    if isinstance(raw_name, Mapping):
                        value = raw_name.get("value") or raw_name.get("name")
                        if isinstance(value, str):
                            normalized = " ".join(value.split())[:300]
                            if normalized:
                                return normalized
            way_id = edge_info.get("way_id")
        else:
            way_id = edge.get("way_id")
        if isinstance(way_id, int) and not isinstance(way_id, bool) and way_id >= 0:
            return f"OSM way {way_id}"
        return None

    @staticmethod
    def _haversine_meters(first: GeoPoint, second: GeoPoint) -> float:
        """Measure the display-only snap distance between input and correlated points."""

        latitude_delta = radians(second.lat - first.lat)
        longitude_delta = radians(second.lon - first.lon)
        first_latitude = radians(first.lat)
        second_latitude = radians(second.lat)
        haversine = sin(latitude_delta / 2) ** 2 + (
            cos(first_latitude) * cos(second_latitude) * sin(longitude_delta / 2) ** 2
        )
        return 6_371_008.8 * 2 * asin(sqrt(min(1.0, haversine)))

    def _log_result(
        self,
        action: str,
        profile: EffectiveTruckProfile,
        started_at: float,
        *,
        distance_meters: int | None = None,
        travel_seconds: int | None = None,
        error: ValhallaRoutingError | None = None,
    ) -> None:
        """Emit bounded structured routing facts without serializing OSM/provider payloads."""

        extra: dict[str, object] = {
            **self._log_context,
            "routing_action": action,
            "routing_provider": self.provider_name,
            "routing_provider_version": self.provider_version,
            "osm_data_version": self.osm_data_version,
            "vehicle_id": str(profile.vehicle_id),
            "trailer_id": str(profile.trailer_id) if profile.trailer_id is not None else None,
            "trailer_attached": profile.trailer_attached,
            "cargo_count": profile.cargo_count,
            "routing_configuration_type": profile.configuration_type.value,
            "effective_height_meters": profile.height_meters,
            "effective_width_meters": profile.width_meters,
            "effective_length_meters": profile.length_meters,
            "actual_weight_tons": profile.actual_weight_tons,
            "max_axle_load_tons": profile.max_axle_load_tons,
            "axle_count": profile.axle_count,
            "request_duration_ms": round((perf_counter() - started_at) * 1000, 1),
            "distance_meters": distance_meters,
            "travel_seconds": travel_seconds,
            "routing_success": error is None,
            "routing_error_code": error.code if error is not None else None,
        }
        if error is None:
            LOGGER.info("Valhalla truck routing completed", extra=extra)
        else:
            LOGGER.warning("Valhalla truck routing failed", extra=extra)
