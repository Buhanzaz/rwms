"""HTTP endpoints exposing read-only routing diagnostics."""

from typing import Annotated

from fastapi import APIRouter, Query

from app.api.dependencies import SessionDep, SettingsDep
from app.errors import ApiError
from app.routing.models import GeoPoint
from app.routing.valhalla import ValhallaRoutingError, ValhallaRoutingProvider
from app.schemas.routing import (
    TravelTimeContourFeatureCollection,
    TravelTimeContourMetadata,
    TravelTimeContourOrigin,
    TravelTimeContourQuery,
    TruckRestrictionFeatureCollection,
    TruckRestrictionQuery,
)
from app.services.truck_restrictions import find_truck_restrictions

router = APIRouter(prefix="/routing", tags=["routing"])


@router.get("/truck-restrictions", response_model=TruckRestrictionFeatureCollection)
async def list_truck_restrictions(
    query: Annotated[TruckRestrictionQuery, Query()],
    session: SessionDep,
    settings: SettingsDep,
) -> TruckRestrictionFeatureCollection:
    """Return bounded real OSM truck restrictions intersecting the map viewport."""

    return await find_truck_restrictions(
        session,
        osm_data_version=settings.osm_data_version,
        query=query,
    )


@router.get("/travel-time-contours", response_model=TravelTimeContourFeatureCollection)
async def get_travel_time_contours(
    query: Annotated[TravelTimeContourQuery, Query()],
    settings: SettingsDep,
) -> TravelTimeContourFeatureCollection:
    """Return Valhalla truck isochrones as a visual estimate around one WGS84 origin."""

    if not settings.valhalla_enabled:
        raise ApiError(
            503,
            "ROUTING_PROVIDER_UNAVAILABLE",
            "Valhalla travel-time contours are disabled.",
        )
    provider = ValhallaRoutingProvider(
        settings.valhalla_url,
        timeout_seconds=settings.valhalla_timeout_seconds,
        osm_data_version=settings.osm_data_version,
    )
    try:
        features = await provider.get_truck_travel_time_contours(
            GeoPoint(lon=query.longitude, lat=query.latitude),
            contour_minutes=tuple(query.contours_minutes),
        )
    except ValhallaRoutingError as exc:
        raise ApiError(503, exc.code, str(exc)) from exc
    finally:
        await provider.aclose()
    return TravelTimeContourFeatureCollection(
        features=features,
        metadata=TravelTimeContourMetadata(
            origin=TravelTimeContourOrigin(
                latitude=query.latitude,
                longitude=query.longitude,
            ),
            contours_minutes=query.contours_minutes,
            osm_data_version=settings.osm_data_version,
        ),
    )
