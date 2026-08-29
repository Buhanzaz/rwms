"""Truck-only routing adapter and bounded cache for dynamic slot planning."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.routing import GeoJsonLineString, GeoPoint, RoutingProvider
from app.routing.truck_profile import (
    CargoDimensions,
    CargoPlacement,
    CargoPosition,
    EffectiveTruckProfile,
    EffectiveTruckProfileCalculator,
    LoadConfiguration,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckProfileError,
    VehicleRoutingSpec,
)

from .models import PlanningReason, RoadMetric, TravelTimeUnavailable, VehicleLegState


@dataclass(frozen=True, slots=True)
class VehicleEquipmentSnapshot:
    """Physical vehicle, trailer, axle, and standard-cabin routing inputs."""

    vehicle_id: str
    vehicle: VehicleRoutingSpec
    trailer: TrailerSpec | None
    axle_profiles: tuple[OperationalAxleLoadProfile, ...]
    cabin: CargoDimensions


class CachedTruckTravelTimeProvider:
    """Cache exact directed road facts without sharing truck configurations."""

    def __init__(
        self,
        delegate: RoutingProvider,
        equipment: dict[str, VehicleEquipmentSnapshot],
        *,
        routing_version: tuple[object, ...],
        max_entries: int = 4_096,
        calculator: EffectiveTruckProfileCalculator | None = None,
    ) -> None:
        if max_entries < 1:
            raise ValueError("max_entries must be positive")
        self._delegate = delegate
        self._equipment = equipment
        self._routing_version = routing_version
        self._max_entries = max_entries
        self._calculator = calculator or EffectiveTruckProfileCalculator()
        self._metric_cache: OrderedDict[tuple[object, ...], RoadMetric] = OrderedDict()
        self._geometry_cache: OrderedDict[tuple[object, ...], GeoJsonLineString] = OrderedDict()

    async def travel_time(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> RoadMetric:
        """Return one exact directed metric or a stable no-truck-route failure."""

        profile = self._profile(vehicle_state)
        key = self._key(origin, destination, departure_at, vehicle_state, profile)
        cached = self._metric_cache.get(key)
        if cached is not None:
            self._metric_cache.move_to_end(key)
            return cached
        try:
            matrix = await self._delegate.get_matrix(
                [origin, destination],
                departure_at,
                profile=profile,
            )
            metric = matrix.at(0, 1)
        except Exception as exc:
            raise TravelTimeUnavailable(PlanningReason.TRUCK_ROUTE_NOT_FOUND) from exc
        result = RoadMetric(
            travel_seconds=metric.travel_seconds,
            distance_meters=metric.distance_meters,
        )
        self._remember(self._metric_cache, key, result, self._max_entries)
        return result

    async def route_geometry(
        self,
        points: tuple[GeoPoint, ...],
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> GeoJsonLineString:
        """Return cached exact geometry only for a selected feasible candidate."""

        if len(points) < 2:
            raise ValueError("route geometry requires at least two points")
        profile = self._profile(vehicle_state)
        point_key = tuple((point.lon, point.lat, point.is_city) for point in points)
        key = (
            "geometry",
            self._routing_version,
            point_key,
            departure_at.replace(minute=0, second=0, microsecond=0).isoformat(),
            vehicle_state,
            profile.cache_key_data(),
        )
        cached = self._geometry_cache.get(key)
        if cached is not None:
            self._geometry_cache.move_to_end(key)
            return cached
        try:
            route = await self._delegate.get_route(
                list(points),
                departure_at,
                profile=profile,
            )
        except Exception as exc:
            raise TravelTimeUnavailable(PlanningReason.TRUCK_ROUTE_NOT_FOUND) from exc
        self._remember(self._geometry_cache, key, route.geometry, self._max_entries // 4 or 1)
        return route.geometry

    async def aclose(self) -> None:
        """Release connections owned by the configured routing provider."""

        close = getattr(self._delegate, "aclose", None)
        if close is not None:
            await close()

    def _profile(self, state: VehicleLegState) -> EffectiveTruckProfile:
        """Build the exact operational truck profile for one road leg load state."""

        equipment = self._equipment.get(state.vehicle_id)
        if equipment is None:
            raise TravelTimeUnavailable(PlanningReason.NO_COMPATIBLE_VEHICLE)
        if state.current_load < 0 or state.current_load > 2:
            raise TravelTimeUnavailable(PlanningReason.VEHICLE_CAPACITY_EXCEEDED)
        placements: list[CargoPlacement] = []
        if state.current_load >= 1:
            placements.append(
                CargoPlacement(
                    cargo_id=f"slot:{state.vehicle_id}:truck",
                    position=CargoPosition.TRUCK_PLATFORM,
                    dimensions=equipment.cabin,
                )
            )
        if state.current_load == 2:
            if not state.trailer_attached:
                raise TravelTimeUnavailable(PlanningReason.NO_COMPATIBLE_VEHICLE)
            placements.append(
                CargoPlacement(
                    cargo_id=f"slot:{state.vehicle_id}:trailer",
                    position=CargoPosition.TRAILER_PLATFORM,
                    dimensions=equipment.cabin,
                )
            )
        trailer = equipment.trailer if state.trailer_attached else None
        if state.trailer_attached and trailer is None:
            raise TravelTimeUnavailable(PlanningReason.NO_COMPATIBLE_VEHICLE)
        try:
            return self._calculator.calculate(
                vehicle=equipment.vehicle,
                load=LoadConfiguration(
                    vehicle_id=state.vehicle_id,
                    trailer_attached=state.trailer_attached,
                    trailer_id=trailer.trailer_id if trailer is not None else None,
                    cargo_placements=tuple(placements),
                ),
                axle_profiles=equipment.axle_profiles,
                trailer=trailer,
            )
        except TruckProfileError as exc:
            raise TravelTimeUnavailable(PlanningReason.NO_COMPATIBLE_VEHICLE) from exc

    def _key(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        state: VehicleLegState,
        profile: EffectiveTruckProfile,
    ) -> tuple[object, ...]:
        """Build a directional time-bucketed cache key for one exact configuration."""

        return (
            "metric",
            self._routing_version,
            (origin.lon, origin.lat, origin.is_city),
            (destination.lon, destination.lat, destination.is_city),
            departure_at.replace(minute=0, second=0, microsecond=0).isoformat(),
            state,
            profile.cache_key_data(),
        )

    @staticmethod
    def _remember(
        cache: OrderedDict[tuple[object, ...], Any],
        key: tuple[object, ...],
        value: Any,
        max_entries: int,
    ) -> None:
        """Insert one LRU value and enforce a deterministic process-local bound."""

        cache[key] = value
        cache.move_to_end(key)
        while len(cache) > max_entries:
            cache.popitem(last=False)
