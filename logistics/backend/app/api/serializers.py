"""Async response serialization that avoids hidden ORM lazy database access."""

from sqlalchemy.ext.asyncio import AsyncSession

from app.geo import geometry_to_geojson
from app.models import LogisticsRequest, Zone
from app.schemas.domain import (
    GeoJsonGeometry,
    LogisticsRequestRead,
    PlanningTaskRead,
    RequestDateOptionRead,
    ZoneRead,
)
from app.services.catalog import count_stale_requests


async def zone_read(session: AsyncSession, zone: Zone) -> ZoneRead:
    """Serialize a zone with GeoJSON geometry and its stale-request count."""

    return ZoneRead(
        id=zone.id,
        scenario_id=zone.scenario_id,
        name=zone.name,
        code=zone.code,
        route_group=zone.route_group,
        geometry=GeoJsonGeometry.model_validate(geometry_to_geojson(zone.geometry)),
        version=zone.version,
        priority=zone.priority,
        delivery_price=zone.delivery_price,
        pickup_price=zone.pickup_price,
        locked=zone.locked,
        stale_request_count=await count_stale_requests(session, zone),
        created_at=zone.created_at,
        updated_at=zone.updated_at,
    )


async def request_read(session: AsyncSession, request: LogisticsRequest) -> LogisticsRequestRead:
    """Serialize a request and derive whether its stored zone snapshot is stale."""

    zone_is_stale = False
    if request.zone_id is not None:
        zone = await session.get(Zone, request.zone_id)
        zone_is_stale = zone is None or zone.version != request.zone_version
    return LogisticsRequestRead(
        id=request.id,
        scenario_id=request.scenario_id,
        type=request.type,
        name=request.name,
        address_label=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=request.quantity,
        service_minutes=request.service_minutes,
        priority=request.priority,
        status=request.status,
        scheduled_date=request.scheduled_date,
        zone_id=request.zone_id,
        zone_version=request.zone_version,
        zone_classification_status=request.zone_classification_status,
        zone_is_stale=zone_is_stale,
        split_allowed=request.split_allowed,
        notes=request.notes,
        created_at=request.created_at,
        updated_at=request.updated_at,
        date_options=[
            RequestDateOptionRead.model_validate(option) for option in request.date_options
        ],
        tasks=[PlanningTaskRead.model_validate(task) for task in request.tasks],
    )
