"""Async response serialization that avoids hidden ORM lazy database access."""

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import LogisticsRequest
from app.schemas.domain import (
    LogisticsRequestRead,
    PlanningTaskRead,
    RequestDateOptionRead,
)


async def request_read(session: AsyncSession, request: LogisticsRequest) -> LogisticsRequestRead:
    """Serialize a request without triggering hidden ORM relationship access."""

    del session
    return LogisticsRequestRead(
        id=request.id,
        warehouse_id=request.warehouse_id,
        source_system=request.source_system,
        external_id=request.external_id,
        type=request.type,
        name=request.name,
        address_label=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=request.quantity,
        cargo_length_mm=request.cargo_length_mm,
        cargo_width_mm=request.cargo_width_mm,
        cargo_height_mm=request.cargo_height_mm,
        cargo_weight_kg=request.cargo_weight_kg,
        service_minutes=request.service_minutes,
        priority=request.priority,
        status=request.status,
        scheduled_date=request.scheduled_date,
        split_allowed=request.split_allowed,
        mandatory=request.mandatory,
        trailer_access_allowed=request.trailer_access_allowed,
        include_driver_passport_in_notification=(request.include_driver_passport_in_notification),
        contact_name=request.contact_name,
        contact_phone=request.contact_phone,
        notes=request.notes,
        created_at=request.created_at,
        updated_at=request.updated_at,
        date_options=[
            RequestDateOptionRead.model_validate(option) for option in request.date_options
        ],
        tasks=[PlanningTaskRead.model_validate(task) for task in request.tasks],
    )
