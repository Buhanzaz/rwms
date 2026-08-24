"""REST endpoints for depots, zones, resources, shifts, and logistics requests."""

from uuid import UUID

from fastapi import APIRouter, Response, status

from app.api.dependencies import SessionDep
from app.api.serializers import request_read, zone_read
from app.models import (
    Driver,
    DriverShift,
    RequestDateOption,
    Vehicle,
    Warehouse,
    Zone,
    ZoneRelation,
)
from app.repositories import get_required
from app.schemas.domain import (
    DriverCreate,
    DriverRead,
    DriverUpdate,
    LogisticsRequestCreate,
    LogisticsRequestRead,
    LogisticsRequestUpdate,
    ReclassificationResult,
    RequestDateOptionInput,
    RequestDateOptionRead,
    RequestDateOptionUpdate,
    ShiftCreate,
    ShiftRead,
    ShiftUpdate,
    VehicleCreate,
    VehicleRead,
    VehicleUpdate,
    WarehouseCreate,
    WarehouseRead,
    WarehouseUpdate,
    ZoneCreate,
    ZoneLockRequest,
    ZoneRead,
    ZoneRelationCreate,
    ZoneRelationRead,
    ZoneRelationUpdate,
    ZoneUpdate,
)
from app.services import catalog as service

router = APIRouter(tags=["catalog"])


@router.get("/scenarios/{scenario_id}/warehouses", response_model=list[WarehouseRead])
async def list_warehouses(scenario_id: UUID, session: SessionDep) -> object:
    """List scenario depots."""

    return await service.list_catalog(session, Warehouse, scenario_id)


@router.post(
    "/scenarios/{scenario_id}/warehouses",
    response_model=WarehouseRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_warehouse(
    scenario_id: UUID, payload: WarehouseCreate, session: SessionDep
) -> Warehouse:
    """Create a scenario depot."""

    return await service.create_warehouse(session, scenario_id, payload)


@router.get("/warehouses/{warehouse_id}", response_model=WarehouseRead)
async def get_warehouse(warehouse_id: UUID, session: SessionDep) -> Warehouse:
    """Read a depot by UUID."""

    return await get_required(session, Warehouse, warehouse_id, "warehouse")


@router.patch("/warehouses/{warehouse_id}", response_model=WarehouseRead)
async def update_warehouse(
    warehouse_id: UUID, payload: WarehouseUpdate, session: SessionDep
) -> Warehouse:
    """Update a depot."""

    return await service.update_warehouse(session, warehouse_id, payload)


@router.delete("/warehouses/{warehouse_id}", status_code=204)
async def delete_warehouse(warehouse_id: UUID, session: SessionDep) -> Response:
    """Delete an unused depot."""

    await service.delete_catalog_entity(session, Warehouse, warehouse_id, "warehouse")
    return Response(status_code=204)


@router.get("/scenarios/{scenario_id}/zones", response_model=list[ZoneRead])
async def list_zones(scenario_id: UUID, session: SessionDep) -> list[ZoneRead]:
    """List zones with geometry and stale-classification counts."""

    zones = await service.list_catalog(session, Zone, scenario_id)
    return [await zone_read(session, zone) for zone in zones]


@router.post("/scenarios/{scenario_id}/zones", response_model=ZoneRead, status_code=201)
async def create_zone(scenario_id: UUID, payload: ZoneCreate, session: SessionDep) -> ZoneRead:
    """Create a validated version-one zone."""

    return await zone_read(session, await service.create_zone(session, scenario_id, payload))


@router.get("/zones/{zone_id}", response_model=ZoneRead)
async def get_zone(zone_id: UUID, session: SessionDep) -> ZoneRead:
    """Read a zone and its current stale-request count."""

    return await zone_read(session, await get_required(session, Zone, zone_id, "zone"))


@router.patch("/zones/{zone_id}", response_model=ZoneRead)
async def update_zone(zone_id: UUID, payload: ZoneUpdate, session: SessionDep) -> ZoneRead:
    """Update an unlocked zone and version geometry changes."""

    return await zone_read(session, await service.update_zone(session, zone_id, payload))


@router.delete("/zones/{zone_id}", status_code=204)
async def delete_zone(zone_id: UUID, session: SessionDep) -> Response:
    """Delete one zone and explicitly mark its request snapshots outside."""

    await service.delete_zone(session, zone_id)
    return Response(status_code=204)


@router.post("/zones/{zone_id}/lock", response_model=ZoneRead)
async def lock_zone(zone_id: UUID, payload: ZoneLockRequest, session: SessionDep) -> ZoneRead:
    """Set or clear a zone editor lock."""

    return await zone_read(session, await service.set_zone_lock(session, zone_id, payload.locked))


@router.post(
    "/scenarios/{scenario_id}/reclassify-requests",
    response_model=ReclassificationResult,
)
async def reclassify_requests(scenario_id: UUID, session: SessionDep) -> ReclassificationResult:
    """Run the deliberate scenario-wide request classification action."""

    updated, outside_zones, unchanged = await service.reclassify_requests(session, scenario_id)
    return ReclassificationResult(updated=updated, outside_zones=outside_zones, unchanged=unchanged)


@router.get("/scenarios/{scenario_id}/zone-relations", response_model=list[ZoneRelationRead])
async def list_zone_relations(scenario_id: UUID, session: SessionDep) -> object:
    """List directed zone transition policies."""

    return await service.list_catalog(session, ZoneRelation, scenario_id)


@router.post(
    "/scenarios/{scenario_id}/zone-relations",
    response_model=ZoneRelationRead,
    status_code=201,
)
async def create_zone_relation(
    scenario_id: UUID, payload: ZoneRelationCreate, session: SessionDep
) -> ZoneRelation:
    """Create a relation after verifying both zones belong to the scenario."""

    return await service.create_zone_relation(session, scenario_id, payload)


@router.patch("/zone-relations/{relation_id}", response_model=ZoneRelationRead)
async def update_zone_relation(
    relation_id: UUID, payload: ZoneRelationUpdate, session: SessionDep
) -> ZoneRelation:
    """Update a zone relation's routing policy."""

    return await service.update_zone_relation(session, relation_id, payload)


@router.delete("/zone-relations/{relation_id}", status_code=204)
async def delete_zone_relation(relation_id: UUID, session: SessionDep) -> Response:
    """Delete one directed zone relation."""

    await service.delete_catalog_entity(session, ZoneRelation, relation_id, "zone_relation")
    return Response(status_code=204)


@router.get("/scenarios/{scenario_id}/drivers", response_model=list[DriverRead])
async def list_drivers(scenario_id: UUID, session: SessionDep) -> object:
    """List scenario drivers."""

    return await service.list_catalog(session, Driver, scenario_id)


@router.post("/scenarios/{scenario_id}/drivers", response_model=DriverRead, status_code=201)
async def create_driver(scenario_id: UUID, payload: DriverCreate, session: SessionDep) -> Driver:
    """Create a scenario driver."""

    return await service.create_driver(session, scenario_id, payload)


@router.patch("/drivers/{driver_id}", response_model=DriverRead)
async def update_driver(driver_id: UUID, payload: DriverUpdate, session: SessionDep) -> Driver:
    """Update a driver."""

    return await service.update_driver(session, driver_id, payload)


@router.delete("/drivers/{driver_id}", status_code=204)
async def delete_driver(driver_id: UUID, session: SessionDep) -> Response:
    """Delete a driver not retained by plan history."""

    await service.delete_catalog_entity(session, Driver, driver_id, "driver")
    return Response(status_code=204)


@router.get("/scenarios/{scenario_id}/vehicles", response_model=list[VehicleRead])
async def list_vehicles(scenario_id: UUID, session: SessionDep) -> object:
    """List scenario vehicles."""

    return await service.list_catalog(session, Vehicle, scenario_id)


@router.post("/scenarios/{scenario_id}/vehicles", response_model=VehicleRead, status_code=201)
async def create_vehicle(scenario_id: UUID, payload: VehicleCreate, session: SessionDep) -> Vehicle:
    """Create a scenario vehicle."""

    return await service.create_vehicle(session, scenario_id, payload)


@router.patch("/vehicles/{vehicle_id}", response_model=VehicleRead)
async def update_vehicle(vehicle_id: UUID, payload: VehicleUpdate, session: SessionDep) -> Vehicle:
    """Update a vehicle."""

    return await service.update_vehicle(session, vehicle_id, payload)


@router.delete("/vehicles/{vehicle_id}", status_code=204)
async def delete_vehicle(vehicle_id: UUID, session: SessionDep) -> Response:
    """Delete a vehicle not retained by plan history."""

    await service.delete_catalog_entity(session, Vehicle, vehicle_id, "vehicle")
    return Response(status_code=204)


@router.get("/scenarios/{scenario_id}/shifts", response_model=list[ShiftRead])
async def list_shifts(scenario_id: UUID, session: SessionDep) -> object:
    """List scenario driver and vehicle assignments."""

    return await service.list_catalog(session, DriverShift, scenario_id)


@router.post("/scenarios/{scenario_id}/shifts", response_model=ShiftRead, status_code=201)
async def create_shift(scenario_id: UUID, payload: ShiftCreate, session: SessionDep) -> DriverShift:
    """Create a non-overlapping scenario shift."""

    return await service.create_shift(session, scenario_id, payload)


@router.patch("/shifts/{shift_id}", response_model=ShiftRead)
async def update_shift(shift_id: UUID, payload: ShiftUpdate, session: SessionDep) -> DriverShift:
    """Update a shift with repeated overlap validation."""

    return await service.update_shift(session, shift_id, payload)


@router.delete("/shifts/{shift_id}", status_code=204)
async def delete_shift(shift_id: UUID, session: SessionDep) -> Response:
    """Delete a shift not retained by plan history."""

    await service.delete_catalog_entity(session, DriverShift, shift_id, "shift")
    return Response(status_code=204)


@router.get("/scenarios/{scenario_id}/requests", response_model=list[LogisticsRequestRead])
async def list_requests(scenario_id: UUID, session: SessionDep) -> list[LogisticsRequestRead]:
    """List source requests with date options, tasks, and stale-zone signals."""

    requests = await service.list_requests(session, scenario_id)
    return [await request_read(session, request) for request in requests]


@router.post(
    "/scenarios/{scenario_id}/requests",
    response_model=LogisticsRequestRead,
    status_code=201,
)
async def create_request(
    scenario_id: UUID, payload: LogisticsRequestCreate, session: SessionDep
) -> LogisticsRequestRead:
    """Create, server-classify, and split a delivery or pickup request."""

    entity = await service.create_request(session, scenario_id, payload)
    return await request_read(session, entity)


@router.get("/requests/{request_id}", response_model=LogisticsRequestRead)
async def get_request(request_id: UUID, session: SessionDep) -> LogisticsRequestRead:
    """Read one logistics request."""

    return await request_read(session, await service.get_request(session, request_id))


@router.patch("/requests/{request_id}", response_model=LogisticsRequestRead)
async def update_request(
    request_id: UUID, payload: LogisticsRequestUpdate, session: SessionDep
) -> LogisticsRequestRead:
    """Update a request and reclassify coordinate changes on the backend."""

    return await request_read(session, await service.update_request(session, request_id, payload))


@router.delete("/requests/{request_id}", status_code=204)
async def delete_request(request_id: UUID, session: SessionDep) -> Response:
    """Delete one request when no saved plan references its tasks."""

    await service.delete_request(session, request_id)
    return Response(status_code=204)


@router.post("/requests/{request_id}/split", response_model=LogisticsRequestRead)
async def split_request(request_id: UUID, session: SessionDep) -> LogisticsRequestRead:
    """Regenerate deterministic capacity-two transport parts."""

    return await request_read(session, await service.split_request(session, request_id))


@router.post(
    "/requests/{request_id}/date-options",
    response_model=RequestDateOptionRead,
    status_code=201,
)
async def create_date_option(
    request_id: UUID, payload: RequestDateOptionInput, session: SessionDep
) -> RequestDateOption:
    """Add one acceptable date option."""

    return await service.create_date_option(session, request_id, payload)


@router.patch("/request-date-options/{option_id}", response_model=RequestDateOptionRead)
async def update_date_option(
    option_id: UUID, payload: RequestDateOptionUpdate, session: SessionDep
) -> RequestDateOption:
    """Update one acceptable request date and window."""

    return await service.update_date_option(session, option_id, payload)


@router.delete("/request-date-options/{option_id}", status_code=204)
async def delete_date_option(option_id: UUID, session: SessionDep) -> Response:
    """Delete one acceptable request date."""

    await service.delete_catalog_entity(
        session, RequestDateOption, option_id, "request_date_option"
    )
    return Response(status_code=204)
