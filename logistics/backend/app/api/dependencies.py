"""Typed FastAPI dependencies shared by route modules."""

from collections.abc import AsyncIterator
from typing import Annotated, cast

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db import get_session
from app.integrations.rwms import RwmsPlanningClient, get_rwms_planning_client
from app.routing import (
    MockRoutingProvider,
    OsrmRoutingProvider,
    RoadSnapper,
    ValhallaRoutingProvider,
)
from app.services.plans import PlannerFacade

# Commit write transactions before the response becomes observable. This keeps an
# immediate client-side refetch from seeing a partially old workspace snapshot.
SessionDep = Annotated[AsyncSession, Depends(get_session, scope="function")]
SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_capacity_rwms_client(settings: SettingsDep) -> RwmsPlanningClient:
    """Resolve the shared authenticated client for optional capacity publication."""

    return get_rwms_planning_client(settings)


CapacityRwmsClientDep = Annotated[RwmsPlanningClient, Depends(get_capacity_rwms_client)]


async def get_road_snapper(settings: SettingsDep) -> AsyncIterator[RoadSnapper]:
    """Yield the configured snapper and close any pooled provider connections."""

    if settings.routing_provider == "mock":
        provider: RoadSnapper = MockRoutingProvider()
    elif settings.routing_provider == "osrm":
        provider = OsrmRoutingProvider(
            settings.osrm_base_url,
            profile=settings.osrm_profile,
            timeout_seconds=settings.osrm_timeout_seconds,
        )
    elif settings.routing_provider == "valhalla":
        provider = ValhallaRoutingProvider(
            settings.valhalla_url,
            timeout_seconds=settings.valhalla_timeout_seconds,
            osm_data_version=settings.osm_data_version,
        )
    else:
        raise RuntimeError(f"Unsupported routing provider {settings.routing_provider!r}")
    try:
        yield provider
    finally:
        close = getattr(provider, "aclose", None)
        if close is not None:
            await close()


RoadSnapperDep = Annotated[RoadSnapper, Depends(get_road_snapper)]


def get_planner_facade(request: Request) -> PlannerFacade:
    """Resolve the configured planner integration from application state."""

    return cast(PlannerFacade, request.app.state.planner_facade)


PlannerDep = Annotated[PlannerFacade, Depends(get_planner_facade)]
