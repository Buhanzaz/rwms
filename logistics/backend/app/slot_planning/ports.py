"""Replaceable road-routing ports used by the pure slot planner."""

from __future__ import annotations

from datetime import datetime
from typing import Protocol

from app.routing import GeoJsonLineString, GeoPoint

from .models import RoadMetric, VehicleLegState


class TravelTimeProvider(Protocol):
    """Return exact truck-road metrics for a directed, time-dependent leg."""

    async def travel_time(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> RoadMetric:
        """Calculate one directed leg or raise a typed route-unavailable error."""

        ...

class RouteGeometryProvider(Protocol):
    """Enrich only selected candidates with exact truck route geometry."""

    async def route_geometry(
        self,
        points: tuple[GeoPoint, ...],
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> GeoJsonLineString:
        """Return one ordered GeoJSON LineString for the selected candidate."""

        ...
