"""Public routing-provider contracts and the offline MVP implementation."""

from .mock import MockRoutingProvider, haversine_distance_meters
from .models import (
    GeoJsonLineString,
    GeoPoint,
    RouteGeometry,
    RouteLeg,
    RoutingSettings,
    TravelMatrix,
    TravelMetric,
)
from .osrm import OsrmRoutingProvider, OsrmRoutingProviderError
from .provider import RoutingProvider

__all__ = [
    "GeoJsonLineString",
    "GeoPoint",
    "MockRoutingProvider",
    "OsrmRoutingProvider",
    "OsrmRoutingProviderError",
    "RouteGeometry",
    "RouteLeg",
    "RoutingProvider",
    "RoutingSettings",
    "TravelMatrix",
    "TravelMetric",
    "haversine_distance_meters",
]
