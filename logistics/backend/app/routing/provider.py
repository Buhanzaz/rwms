"""Routing provider boundary used by planning and simulation services."""

from __future__ import annotations

from datetime import datetime
from typing import Protocol

from .models import GeoPoint, RouteGeometry, TravelMatrix


class RoutingProvider(Protocol):
    """Supply travel matrices and route geometry without exposing a vendor API."""

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> TravelMatrix:
        """Return distance and duration for every ordered point pair."""

        ...

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> RouteGeometry:
        """Return an ordered route, its legs, and a GeoJSON LineString."""

        ...
