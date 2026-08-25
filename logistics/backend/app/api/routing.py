"""HTTP endpoints exposing read-only routing diagnostics."""

from typing import Annotated

from fastapi import APIRouter, Query

from app.api.dependencies import SessionDep, SettingsDep
from app.schemas.routing import TruckRestrictionFeatureCollection, TruckRestrictionQuery
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
