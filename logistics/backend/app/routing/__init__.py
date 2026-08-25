"""Public routing-provider contracts and the offline MVP implementation."""

from .mock import MockRoutingProvider, haversine_distance_meters
from .models import (
    GeoJsonLineString,
    GeoPoint,
    RouteGeometry,
    RouteLeg,
    RoutingSettings,
    SnappedPoint,
    TravelMatrix,
    TravelMetric,
)
from .osrm import OsrmRoutingProvider, OsrmRoutingProviderError
from .provider import RoadSnapNotFoundError, RoadSnapper, RoutingProvider
from .valhalla import (
    NoSafeRouteError,
    RoutingProfileIncompleteError,
    RoutingProviderUnavailableError,
    ValhallaRoutingProvider,
)

__all__ = [
    "GeoJsonLineString",
    "GeoPoint",
    "MockRoutingProvider",
    "NoSafeRouteError",
    "OsrmRoutingProvider",
    "OsrmRoutingProviderError",
    "RoadSnapNotFoundError",
    "RoadSnapper",
    "RouteGeometry",
    "RouteLeg",
    "RoutingProfileIncompleteError",
    "RoutingProvider",
    "RoutingProviderUnavailableError",
    "RoutingSettings",
    "SnappedPoint",
    "TravelMatrix",
    "TravelMetric",
    "ValhallaRoutingProvider",
    "haversine_distance_meters",
]
