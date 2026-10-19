"""HTTP endpoints exposing read-only routing diagnostics and transfer estimates."""

from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Query
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.api.authorization import require_external_warehouse_access
from app.api.dependencies import CurrentUserDep, SessionDep, SettingsDep
from app.errors import ApiError
from app.models import Vehicle, Warehouse
from app.routing.models import GeoPoint
from app.routing.valhalla import ValhallaRoutingError, ValhallaRoutingProvider
from app.schemas.routing import (
    TransferArrivalEstimateRead,
    TransferArrivalEstimateRequest,
    TravelTimeContourFeatureCollection,
    TravelTimeContourMetadata,
    TravelTimeContourOrigin,
    TravelTimeContourQuery,
    TruckRestrictionFeatureCollection,
    TruckRestrictionQuery,
)
from app.security import WarehouseAccessLevel
from app.services.truck_restrictions import find_truck_restrictions
from app.slot_planning.configuration import (
    truck_travel_time_provider,
    vehicle_equipment_snapshot,
    vehicle_has_available_trailer,
)
from app.slot_planning.models import (
    REASON_MESSAGES_RU,
    PlanningReason,
    TravelTimeUnavailable,
    VehicleLegState,
)

router = APIRouter(prefix="/routing", tags=["routing"])


@router.post(
    "/transfer-arrival-estimate",
    response_model=TransferArrivalEstimateRead,
)
async def estimate_transfer_arrival(
    payload: TransferArrivalEstimateRequest,
    session: SessionDep,
    settings: SettingsDep,
    principal: CurrentUserDep,
) -> TransferArrivalEstimateRead:
    """Route one planned warehouse-to-warehouse leg without reserving any resource."""

    require_external_warehouse_access(
        principal, payload.source_warehouse_id, WarehouseAccessLevel.VIEW
    )
    require_external_warehouse_access(
        principal, payload.destination_warehouse_id, WarehouseAccessLevel.VIEW
    )
    if payload.source_warehouse_id == payload.destination_warehouse_id:
        raise ApiError(
            422,
            "TRANSFER_WAREHOUSES_MUST_DIFFER",
            "Source and destination warehouses must differ.",
        )

    source = await session.scalar(
        select(Warehouse).where(
            Warehouse.external_warehouse_id == payload.source_warehouse_id
        )
    )
    if source is None:
        raise ApiError(
            404,
            "SOURCE_WAREHOUSE_NOT_FOUND",
            f"Source warehouse {payload.source_warehouse_id} not found.",
        )
    destination = await session.scalar(
        select(Warehouse).where(
            Warehouse.external_warehouse_id == payload.destination_warehouse_id
        )
    )
    if destination is None:
        raise ApiError(
            404,
            "DESTINATION_WAREHOUSE_NOT_FOUND",
            f"Destination warehouse {payload.destination_warehouse_id} not found.",
        )
    for role, warehouse in (("source", source), ("destination", destination)):
        if (
            not warehouse.routing_ready
            or warehouse.latitude is None
            or warehouse.longitude is None
        ):
            raise ApiError(
                422,
                "WAREHOUSE_ROUTING_COORDINATES_MISSING",
                f"The {role} warehouse has no coordinates available for truck routing.",
                extra={"warehouse_id": str(warehouse.external_warehouse_id)},
            )

    vehicle = await session.scalar(
        select(Vehicle)
        .where(Vehicle.id == payload.vehicle_id)
        .options(
            selectinload(Vehicle.default_trailer),
            selectinload(Vehicle.load_profiles),
        )
    )
    if vehicle is None:
        raise ApiError(
            404,
            "VEHICLE_NOT_FOUND",
            f"Vehicle {payload.vehicle_id} not found.",
        )
    if vehicle.warehouse_id != source.id:
        raise ApiError(
            422,
            "VEHICLE_SOURCE_WAREHOUSE_MISMATCH",
            "The selected vehicle does not belong to the source warehouse.",
        )
    if not vehicle.active:
        raise ApiError(422, "VEHICLE_INACTIVE", "The selected vehicle is inactive.")
    if payload.cabin_count > vehicle.capacity:
        raise ApiError(
            422,
            PlanningReason.VEHICLE_CAPACITY_EXCEEDED.value,
            REASON_MESSAGES_RU[PlanningReason.VEHICLE_CAPACITY_EXCEEDED],
        )

    trailer_attached = payload.cabin_count == 2
    if trailer_attached and (
        not vehicle_has_available_trailer(vehicle)
        or vehicle.default_trailer is None
        or not vehicle.default_trailer.active
        or vehicle.default_trailer.warehouse_id != source.id
    ):
        raise ApiError(
            422,
            PlanningReason.TRAILER_REQUIRED.value,
            REASON_MESSAGES_RU[PlanningReason.TRAILER_REQUIRED],
        )

    try:
        source_point = GeoPoint(source.longitude, source.latitude, is_city=True)
        destination_point = GeoPoint(
            destination.longitude,
            destination.latitude,
            is_city=True,
        )
    except ValueError as exc:
        raise ApiError(
            422,
            "WAREHOUSE_ROUTING_COORDINATES_INVALID",
            "Warehouse coordinates are invalid for truck routing.",
        ) from exc

    equipment = vehicle_equipment_snapshot(vehicle, source.settings)
    provider = truck_travel_time_provider(
        settings,
        source,
        {str(vehicle.id): equipment},
    )
    try:
        metric = await provider.travel_time(
            source_point,
            destination_point,
            payload.planned_departure_at,
            VehicleLegState(
                vehicle_id=str(vehicle.id),
                trailer_attached=trailer_attached,
                current_load=payload.cabin_count,
                trip_peak_load=payload.cabin_count,
            ),
        )
    except TravelTimeUnavailable as exc:
        raise ApiError(
            422,
            exc.reason.value,
            REASON_MESSAGES_RU.get(
                exc.reason,
                "Грузовой маршрут для выбранной конфигурации не найден.",
            ),
        ) from exc
    finally:
        await provider.aclose()

    return TransferArrivalEstimateRead(
        departure_at=payload.planned_departure_at,
        estimated_arrival_at=(
            payload.planned_departure_at + timedelta(seconds=metric.travel_seconds)
        ),
        travel_seconds=metric.travel_seconds,
        distance_meters=metric.distance_meters,
        vehicle_id=vehicle.id,
        cabin_count=payload.cabin_count,
        trailer_attached=trailer_attached,
        routing_provider=settings.routing_provider,
        osm_data_version=(
            settings.osm_data_version
            if settings.routing_provider == "valhalla"
            and settings.osm_data_version != "unknown"
            else None
        ),
    )


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
