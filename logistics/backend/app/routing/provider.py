"""Routing provider boundary used by planning and simulation services."""

from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Protocol

from .models import GeoPoint, RouteGeometry, SnappedPoint, TravelMatrix

if TYPE_CHECKING:
    from .truck_profile import EffectiveTruckProfile


class RoadSnapNotFoundError(RuntimeError):
    """Signal that a candidate has no routable road segment nearby."""


class RoadSnapper(Protocol):
    """Resolve arbitrary coordinates to the active provider's road network."""

    async def snap_point(self, point: GeoPoint) -> SnappedPoint:
        """Return the nearest routable road point or report that none exists."""

        ...


class RoutingProvider(Protocol):
    """Supply travel matrices and route geometry without exposing a vendor API."""

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> TravelMatrix:
        """Return metrics for the supplied effective road configuration.

        Legacy deterministic and OSRM adapters may ignore ``profile``. A safe
        truck provider must require it and must never substitute a car route.
        """

        ...

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Return a route calculated for one exact effective configuration."""

        ...
