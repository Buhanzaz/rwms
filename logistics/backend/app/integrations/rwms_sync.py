"""RWMS synchronization and plan-application orchestration for warehouse planning."""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from math import isfinite
from uuid import NAMESPACE_URL, UUID, uuid5

from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    CustomerDeliveryPurpose,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Vehicle,
    Warehouse,
)
from app.models.domain import PlanStatus, RequestStatus, StopType, TaskStatus
from app.schemas.domain import (
    RwmsApplyResult,
    RwmsAssignmentsCommand,
    RwmsDriverShiftPlan,
    RwmsDriverShiftPlanTrailer,
    RwmsDriverShiftPlanVehicle,
    RwmsDriverShiftRouteOperation,
    RwmsPlanApplyRequest,
    RwmsPlanningAssignment,
    RwmsPlanningAssignmentReplacement,
    RwmsPlanningAssignmentStatus,
    RwmsPlanningRequest,
    RwmsPlanStatusResult,
    RwmsPlanTaskStatus,
    RwmsReplacePlanningAssignmentsCommand,
    RwmsSyncFailure,
    RwmsSyncRequest,
    RwmsSyncResult,
    RwmsWarehouseIdentity,
    RwmsWarehouseRefreshResult,
    RwmsWarehouseSyncResult,
)
from app.schemas.geocoding import ResolvedAddress
from app.services import catalog, plans
from app.services.auto_planning import generate_missing_draft_plans
from app.services.plans import PlannerFacade
from app.slot_planning.configuration import effective_vehicle_cabin_capacity

INT64_MAX = 9_223_372_036_854_775_807


@dataclass(frozen=True, slots=True)
class _DriverShiftRouteEvidence:
    """Validated immutable origin, authorization, distance, and positioning-time evidence."""

    route_origin_warehouse_id: UUID | None
    service_warehouse_id: UUID
    support_warehouse_link_id: UUID | None
    positioning_distance_meters: int
    inbound_departure_at: datetime | None = None
    inbound_raw_arrival_at: datetime | None = None
    inbound_arrival_at: datetime | None = None
    inbound_travel_seconds: int = 0
    return_travel_seconds: int = 0

    @property
    def cross_warehouse(self) -> bool:
        """Return whether the snapshot contains an authorized positioning round trip."""

        return self.support_warehouse_link_id is not None


def warehouse_geocoding_query(identity: RwmsWarehouseIdentity) -> str:
    """Qualify a canonical warehouse address with its city for forward geocoding."""

    address = (identity.address or "").strip()
    if not address:
        raise ApiError(
            422,
            "WAREHOUSE_COORDINATES_REQUIRED",
            "Не заданы координаты для использования склада в логистике",  # noqa: RUF001
        )
    city = identity.city.strip()
    if city and city.casefold() not in address.casefold():
        return f"{city}, {address}"
    return address


async def refresh_warehouse_directory(
    session: AsyncSession,
    client: RwmsPlanningClient,
    resolve_address: Callable[[RwmsWarehouseIdentity], Awaitable[ResolvedAddress]] | None = None,
) -> list[Warehouse]:
    """Fetch and reconcile the owner directory, resolving address-only warehouses when possible."""

    return await catalog.reconcile_warehouse_directory(
        session,
        await client.list_warehouses(),
        resolve_address,
    )


async def sync_warehouse_requests(
    session: AsyncSession,
    warehouse_id: UUID,
    command: RwmsSyncRequest,
    client: RwmsPlanningClient,
    planner: PlannerFacade | None = None,
    *,
    resolve_address: Callable[[RwmsPlanningRequest], Awaitable[ResolvedAddress]] | None = None,
) -> RwmsSyncResult:
    """Reconcile one complete feed, resolving address-only orders before local import."""

    client.ensure_enabled()
    linked_warehouse = await catalog.require_warehouse(session, warehouse_id)
    if not linked_warehouse.routing_ready:
        raise ApiError(
            422,
            "WAREHOUSE_COORDINATES_REQUIRED",
            "Не заданы координаты для использования склада в логистике",  # noqa: RUF001
        )
    if linked_warehouse.external_warehouse_id != command.warehouse_id:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The selected workspace is linked to a different RWMS warehouse",
        )
    await session.commit()

    feed = await client.get_planning_requests(
        warehouse_id=command.warehouse_id,
        date_from=command.date_from,
        date_to=command.date_to,
    )
    if feed.warehouse_id != command.warehouse_id:
        raise ApiError(
            502,
            "RWMS_WAREHOUSE_MISMATCH",
            "RWMS planning response belongs to a different warehouse",
        )

    counts = {"imported": 0, "updated": 0, "skipped": 0}
    failures: list[RwmsSyncFailure] = []
    present_order_ids = {source.order_id for source in feed.requests}
    for source in feed.requests:
        resolved = None
        if source.latitude is None or source.longitude is None:
            if resolve_address is None:
                failures.append(
                    RwmsSyncFailure(
                        order_id=source.order_id,
                        code="COORDINATES_REQUIRED",
                        message=(
                            "Order has no coordinates and its address could not be resolved; "
                            "the order was not imported"
                        ),
                    )
                )
                continue
            try:
                resolved = await resolve_address(source)
            except ApiError as exc:
                failures.append(
                    RwmsSyncFailure(
                        order_id=source.order_id,
                        code=exc.code,
                        message=exc.detail,
                    )
                )
                continue
        try:
            async with session.begin_nested():
                outcome = await catalog.upsert_rwms_request(
                    session,
                    warehouse_id,
                    source,
                    resolved,
                )
        except ApiError as exc:
            failures.append(
                RwmsSyncFailure(
                    order_id=source.order_id,
                    code=exc.code,
                    message=exc.detail,
                )
            )
            continue
        except IntegrityError:
            failures.append(
                RwmsSyncFailure(
                    order_id=source.order_id,
                    code="RWMS_SYNC_CONFLICT",
                    message="The order conflicts with existing synchronized data",
                )
            )
            continue
        counts[outcome] += 1
    counts["updated"] += await catalog.retire_absent_rwms_requests(
        session,
        warehouse_id,
        present_order_ids=present_order_ids,
        date_from=command.date_from,
        date_to=command.date_to,
    )
    await session.flush()
    runs = (
        await generate_missing_draft_plans(
            session,
            planner,
            warehouse_id,
            (
                command.date_from + timedelta(days=offset)
                for offset in range((command.date_to - command.date_from).days + 1)
            ),
        )
        if planner is not None
        else ()
    )
    return RwmsSyncResult(
        **counts,
        failures=failures,
        auto_plan_run_ids=[run.id for run in runs],
        auto_plan_ids=[run.plan_id for run in runs if run.plan_id is not None],
    )


async def refresh_warehouse_requests(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    date_from: date,
    date_to: date,
    client: RwmsPlanningClient,
    planner: PlannerFacade | None = None,
    resolve_warehouse_address: (
        Callable[[RwmsWarehouseIdentity], Awaitable[ResolvedAddress]] | None
    ) = None,
    resolve_request_address: (
        Callable[[RwmsPlanningRequest], Awaitable[ResolvedAddress]] | None
    ) = None,
) -> RwmsWarehouseRefreshResult:
    """Refresh one selected warehouse and address-only routing facts outside local locks."""

    await refresh_warehouse_directory(session, client, resolve_warehouse_address)
    warehouse = await catalog.require_warehouse(session, warehouse_id)
    result = await sync_warehouse_requests(
        session,
        warehouse_id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=date_from,
            date_to=date_to,
        ),
        client,
        planner,
        resolve_address=resolve_request_address,
    )
    return RwmsWarehouseRefreshResult(
        date_from=date_from,
        date_to=date_to,
        warehouses=[
            RwmsWarehouseSyncResult(
                warehouse_id=warehouse.external_warehouse_id,
                **result.model_dump(),
            )
        ],
    )


def build_assignments_command(
    plan: RoutePlan,
    publish_unassigned_task_ids: set[UUID] | frozenset[UUID] = frozenset(),
) -> RwmsAssignmentsCommand:
    """Map assigned and explicitly published delivery parts to stable RWMS unit slices."""

    delivery_stops: list[tuple[RouteCycle, RouteStop, PlanningTask, LogisticsRequest]] = []
    assigned_task_ids: set[UUID] = set()
    for cycle in plan.cycles:
        for stop in cycle.stops:
            if stop.stop_type != StopType.DELIVERY:
                continue
            if stop.task is None:
                raise ApiError(
                    422,
                    "RWMS_DELIVERY_TASK_MISSING",
                    "A delivery stop has no planning task",
                )
            if stop.task.request.source_system != catalog.RWMS_SOURCE_SYSTEM:
                continue
            if stop.task.id in assigned_task_ids:
                raise ApiError(
                    422,
                    "DUPLICATE_ASSIGNMENT_CONFLICT",
                    "A delivery task appears more than once in the plan",
                )
            assigned_task_ids.add(stop.task.id)
            delivery_stops.append((cycle, stop, stop.task, stop.task.request))

    unassigned_by_task_id = {item.task.id: item for item in plan.unassigned_tasks}
    unknown_publish_ids = publish_unassigned_task_ids - unassigned_by_task_id.keys()
    if unknown_publish_ids:
        raise ApiError(
            422,
            "RWMS_UNASSIGNED_SELECTION_INVALID",
            "Every published task must still be unassigned in the exact plan version",
        )
    shared_tasks = [
        unassigned_by_task_id[task_id].task
        for task_id in sorted(publish_unassigned_task_ids, key=str)
    ]
    if any(task.request.source_system != catalog.RWMS_SOURCE_SYSTEM for task in shared_tasks):
        raise ApiError(
            422,
            "RWMS_UNASSIGNED_SOURCE_INVALID",
            "Only synchronized RWMS deliveries can be explicitly published",
        )
    if any(task.type != "DELIVERY" for task in shared_tasks):
        raise ApiError(
            422,
            "RWMS_SHARED_TASK_NOT_DELIVERY",
            "Only future unassigned deliveries can be published to RWMS drivers",
        )

    if not delivery_stops and not shared_tasks:
        raise ApiError(422, "RWMS_NO_DELIVERIES", "The plan contains no delivery assignments")

    warehouse_id = plan.warehouse.external_warehouse_id
    if warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The plan warehouse has no RWMS warehouse identity",
        )

    unit_slices: dict[UUID, list[UUID]] = {}
    source_by_request: dict[UUID, RwmsPlanningRequest] = {}
    selected_requests = [item[3] for item in delivery_stops] + [
        task.request for task in shared_tasks
    ]
    for request in selected_requests:
        if request.id in source_by_request:
            continue
        if (
            request.external_id is None
            or request.external_version is None
            or request.external_payload is None
        ):
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_MISSING",
                "Every planned delivery must originate from a synchronized RWMS order",
            )
        try:
            source = RwmsPlanningRequest.model_validate(request.external_payload)
        except ValidationError as exc:
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_INVALID",
                "A synchronized request contains invalid source metadata",
            ) from exc
        if (
            source.order_id != request.external_id
            or source.order_version != request.external_version
            or source.customer_delivery_purpose != request.customer_delivery_purpose
        ):
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_INVALID",
                "A synchronized request identity does not match its source metadata",
            )
        if source.customer_delivery_purpose != CustomerDeliveryPurpose.RENTAL_DELIVERY:
            raise ApiError(
                422,
                "RWMS_DELIVERY_PURPOSE_UNSUPPORTED",
                "This customer delivery purpose has no supported owner apply command",
            )
        if plan.date not in {option.date for option in source.date_options}:
            raise ApiError(
                422,
                "RWMS_DATE_NOT_ALLOWED",
                "The plan date is not allowed by a synchronized order",
            )
        offset = 0
        tasks = sorted(request.tasks, key=lambda item: (item.part_number, str(item.id)))
        for task in tasks:
            next_offset = offset + task.quantity
            units = source.unit_ids[offset:next_offset]
            if len(units) != task.quantity:
                raise ApiError(
                    422,
                    "RWMS_UNIT_MAPPING_INVALID",
                    "Planning task quantities do not match synchronized cabin unit IDs",
                )
            unit_slices[task.id] = units
            offset = next_offset
        if offset != len(source.unit_ids):
            raise ApiError(
                422,
                "RWMS_UNIT_MAPPING_INVALID",
                "Planning task quantities do not consume every synchronized cabin unit ID",
            )
        source_by_request[request.id] = source

    assignments: list[RwmsPlanningAssignment] = []

    def inventory_source_warehouse_id(
        source: RwmsPlanningRequest,
        units: list[UUID],
    ) -> UUID:
        """Resolve one physical source for an indivisible vehicle-sized unit slice."""

        sources = source.inventory_sources_for(units)
        if len(sources) != 1:
            raise ApiError(
                422,
                "RWMS_MIXED_INVENTORY_SOURCE",
                "One vehicle-sized shipment cannot contain cabins from different warehouses",
            )
        return next(iter(sources))

    def service_warehouse_id(request: LogisticsRequest) -> UUID:
        """Resolve the request owner while retaining the root warehouse as legacy fallback."""

        if request.warehouse_id == plan.warehouse_id:
            return warehouse_id
        if request.warehouse is None or request.warehouse.external_warehouse_id is None:
            raise ApiError(
                422,
                "RWMS_SERVICE_WAREHOUSE_NOT_LINKED",
                "Every cross-warehouse request must retain its canonical service warehouse",
            )
        return request.warehouse.external_warehouse_id

    ordered_stops = sorted(
        delivery_stops,
        key=lambda item: (
            str(item[3].external_id),
            item[2].part_number,
            item[0].planned_start,
            str(item[1].id),
        ),
    )
    for cycle, _, task, request in ordered_stops:
        driver = cycle.driver_shift.driver
        if (
            driver.rwms_assignment_mode == "ASSIGNED_DRIVER"
            and driver.external_worker_id is None
        ):
            raise ApiError(
                422,
                "RWMS_DRIVER_NOT_LINKED",
                f"Driver {driver.name} has no RWMS worker identity",
            )
        source = source_by_request[request.id]
        units = unit_slices[task.id]
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                service_warehouse_id=service_warehouse_id(request),
                inventory_source_warehouse_id=inventory_source_warehouse_id(
                    source, units
                ),
                expected_order_version=source.order_version,
                scheduled_date=plan.date,
                driver_audience_mode=driver.rwms_assignment_mode,
                driver_worker_id=driver.external_worker_id,
                driver_name=driver.name,
                unit_ids=units,
            )
        )

    for task in sorted(
        shared_tasks,
        key=lambda item: (str(item.request.external_id), item.part_number, str(item.id)),
    ):
        source = source_by_request[task.request.id]
        units = unit_slices[task.id]
        nearest_option = unassigned_by_task_id[task.id].nearest_option
        approximate_eta: datetime | None = None
        if isinstance(nearest_option, dict):
            possible_at = nearest_option.get("possible_at")
            if isinstance(possible_at, str):
                parsed = datetime.fromisoformat(possible_at)
                if parsed.utcoffset() is not None:
                    approximate_eta = parsed
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                service_warehouse_id=service_warehouse_id(task.request),
                inventory_source_warehouse_id=inventory_source_warehouse_id(
                    source, units
                ),
                expected_order_version=source.order_version,
                scheduled_date=plan.date,
                driver_audience_mode="WAREHOUSE_DRIVERS",
                driver_worker_id=None,
                driver_name="Свободная доставка",
                unit_ids=units,
                provisional_eta=approximate_eta,
            )
        )

    return RwmsAssignmentsCommand(
        warehouse_id=warehouse_id,
        plan_id=plan.id,
        plan_version=plan.version,
        assignments=assignments,
        driver_shift_plans=_build_driver_shift_plans(plan, warehouse_id),
    )


def _build_driver_shift_plans(
    plan: RoutePlan, warehouse_id: UUID
) -> list[RwmsDriverShiftPlan]:
    """Aggregate assigned-driver cycles into deterministic workday snapshots."""

    snapshots: dict[
        UUID,
        tuple[
            RwmsDriverShiftPlan,
            _DriverShiftRouteEvidence,
            list[RouteCycle],
            int,
        ],
    ] = {}
    for cycle in sorted(
        plan.cycles,
        key=lambda item: (
            str(item.driver_shift_id),
            item.sequence,
            str(item.id),
        ),
    ):
        shift = cycle.driver_shift
        driver = shift.driver
        if driver.rwms_assignment_mode != "ASSIGNED_DRIVER":
            continue
        if driver.external_worker_id is None:
            raise ApiError(
                422,
                "RWMS_DRIVER_NOT_LINKED",
                f"Driver {driver.name} has no RWMS worker identity",
            )
        evidence = _driver_shift_route_evidence(plan, warehouse_id, shift, cycle)
        base = _driver_shift_plan_snapshot(
            plan,
            warehouse_id,
            shift,
            route_origin_warehouse_id=evidence.route_origin_warehouse_id,
            support_warehouse_link_id=evidence.support_warehouse_link_id,
        )
        cycle_distance_meters = _exact_route_distance_meters(cycle)
        existing = snapshots.get(shift.id)
        if existing is None:
            route_distance_meters = (
                cycle_distance_meters + evidence.positioning_distance_meters
            )
            if route_distance_meters > INT64_MAX:
                raise ApiError(
                    422,
                    "RWMS_ROUTE_DISTANCE_INVALID",
                    "A driver shift route distance exceeds the int64 transport contract",
                )
            snapshots[shift.id] = (
                base,
                evidence,
                [cycle],
                route_distance_meters,
            )
            continue
        if existing[0] != base or existing[1] != evidence:
            raise ApiError(
                422,
                "DUPLICATE_DRIVER_SHIFT_CONFLICT",
                "One source shift resolves to conflicting driver, vehicle, or origin evidence",
            )
        route_distance_meters = existing[3] + cycle_distance_meters
        if route_distance_meters > INT64_MAX:
            raise ApiError(
                422,
                "RWMS_ROUTE_DISTANCE_INVALID",
                "A driver shift route distance exceeds the int64 transport contract",
            )
        snapshots[shift.id] = (
            base,
            evidence,
            [*existing[2], cycle],
            route_distance_meters,
        )
    return [
        snapshot.model_copy(
            update={
                "trip_count": len(cycles),
                "route_distance_meters": distance_meters,
                "operations": _build_driver_shift_route_operations(
                    plan,
                    warehouse_id,
                    cycles,
                    evidence,
                ),
            }
        )
        for snapshot, evidence, cycles, distance_meters in (
            snapshots[shift_id] for shift_id in sorted(snapshots, key=str)
        )
    ]


def _build_driver_shift_route_operations(
    plan: RoutePlan,
    service_warehouse_id: UUID,
    cycles: list[RouteCycle],
    evidence: _DriverShiftRouteEvidence,
) -> list[RwmsDriverShiftRouteOperation]:
    """Materialize one contiguous exact stop order without mutating planner inventory."""

    ordered_cycles = sorted(cycles, key=lambda item: (item.sequence, str(item.id)))
    planner_operations: list[RwmsDriverShiftRouteOperation] = []
    for cycle in ordered_cycles:
        for stop in sorted(cycle.stops, key=lambda item: (item.sequence, str(item.id))):
            try:
                kind = StopType(stop.stop_type).value
            except ValueError as exc:
                raise ApiError(
                    422,
                    "RWMS_ROUTE_OPERATION_INVALID",
                    f"Planner route contains an unsupported stop type: {stop.stop_type}",
                ) from exc
            customer = stop.task_id is not None
            request = stop.task.request if stop.task is not None else None
            location_label = (
                request.address_label.strip()
                if request is not None and request.address_label.strip()
                else plan.warehouse.name.strip()
            )
            planner_operations.append(
                RwmsDriverShiftRouteOperation(
                    sequence=1,
                    kind=kind,
                    warehouse_id=None if customer else service_warehouse_id,
                    source_task_id=stop.task_id,
                    location_label=location_label,
                    planned_arrival=stop.planned_arrival,
                    planned_departure=stop.planned_departure,
                    load_before=stop.load_before,
                    load_after=stop.load_after,
                )
            )
    if not evidence.cross_warehouse:
        return [
            operation.model_copy(update={"sequence": sequence})
            for sequence, operation in enumerate(planner_operations, start=1)
        ]
    if not planner_operations:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_ROUTE_OPERATIONS_MISSING",
            "A confirmed cross-warehouse shift requires persisted planner stops",
        )
    inbound_departure = evidence.inbound_departure_at
    inbound_arrival = evidence.inbound_arrival_at
    route_origin_warehouse_id = evidence.route_origin_warehouse_id
    if (
        inbound_departure is None
        or inbound_arrival is None
        or route_origin_warehouse_id is None
    ):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_MISSING",
            "A confirmed cross-warehouse shift requires exact inbound positioning times",
        )
    shift = ordered_cycles[0].driver_shift
    origin = shift.warehouse
    if origin is None:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
            "The physical route origin warehouse snapshot is missing",
        )
    origin_label = origin.name.strip()
    service_label = plan.warehouse.name.strip()
    last_cycle = ordered_cycles[-1]
    return_departure = last_cycle.planned_finish
    return_arrival = return_departure + timedelta(
        seconds=evidence.return_travel_seconds
    )
    final_load = planner_operations[-1].load_after
    operations = [
        RwmsDriverShiftRouteOperation(
            sequence=1,
            kind="ORIGIN_START",
            warehouse_id=route_origin_warehouse_id,
            source_task_id=None,
            location_label=origin_label,
            planned_arrival=inbound_departure,
            planned_departure=inbound_departure,
            load_before=0,
            load_after=0,
        ),
        RwmsDriverShiftRouteOperation(
            sequence=2,
            kind="INBOUND_POSITIONING",
            warehouse_id=service_warehouse_id,
            source_task_id=None,
            location_label=service_label,
            planned_arrival=inbound_arrival,
            planned_departure=inbound_departure,
            load_before=0,
            load_after=0,
        ),
        *planner_operations,
        RwmsDriverShiftRouteOperation(
            sequence=1,
            kind="RETURN_POSITIONING",
            warehouse_id=route_origin_warehouse_id,
            source_task_id=None,
            location_label=origin_label,
            planned_arrival=return_arrival,
            planned_departure=return_departure,
            load_before=final_load,
            load_after=final_load,
        ),
    ]
    return [
        operation.model_copy(update={"sequence": sequence})
        for sequence, operation in enumerate(operations, start=1)
    ]


def _exact_route_distance_meters(cycle: RouteCycle) -> int:
    """Keep the planner's integer meters exact and reject lossy transport coercion."""

    distance_meters = cycle.total_distance_meters
    if (
        not isfinite(distance_meters)
        or distance_meters < 0
        or distance_meters % 1 != 0
        or distance_meters > INT64_MAX
    ):
        raise ApiError(
            422,
            "RWMS_ROUTE_DISTANCE_INVALID",
            "A route cycle distance must be a non-negative int64 meter value",
        )
    return int(distance_meters)


def _driver_shift_plan_snapshot(
    plan: RoutePlan,
    warehouse_id: UUID,
    shift: DriverShift,
    *,
    route_origin_warehouse_id: UUID | None,
    support_warehouse_link_id: UUID | None,
) -> RwmsDriverShiftPlan:
    """Freeze source identities and fleet presentation without creating a second catalog."""

    driver_id = shift.driver.external_worker_id
    if driver_id is None:
        raise ApiError(
            422,
            "RWMS_DRIVER_NOT_LINKED",
            f"Driver {shift.driver.name} has no RWMS worker identity",
        )
    vehicle = shift.vehicle
    trailer = vehicle.default_trailer
    return RwmsDriverShiftPlan(
        source_shift_id=shift.id,
        source_plan_id=plan.id,
        source_plan_version=plan.version,
        warehouse_id=warehouse_id,
        route_origin_warehouse_id=route_origin_warehouse_id,
        support_warehouse_link_id=support_warehouse_link_id,
        driver_id=driver_id,
        driver_name=shift.driver.name,
        work_date=plan.date,
        vehicle=RwmsDriverShiftPlanVehicle(
            id=vehicle.id,
            name=vehicle.name,
            registration_number=vehicle.registration_number,
            vehicle_type=vehicle.vehicle_type,
            manufacturer=vehicle.manufacturer,
            model=vehicle.model,
            configuration_type=_vehicle_configuration_type(vehicle),
            cabin_capacity=effective_vehicle_cabin_capacity(vehicle),
            start_odometer=None,
        ),
        trailer=(
            RwmsDriverShiftPlanTrailer(
                id=trailer.id,
                name=trailer.name,
                registration_number=trailer.registration_number,
            )
            if trailer is not None
            else None
        ),
        trip_count=0,
        route_distance_meters=0,
    )


def _driver_shift_route_evidence(
    plan: RoutePlan,
    warehouse_id: UUID,
    shift: DriverShift,
    cycle: RouteCycle,
) -> _DriverShiftRouteEvidence:
    """Validate persisted cross-warehouse evidence and return its exact route facts."""

    metrics = cycle.metrics or {}
    local_to_plan = shift.warehouse_id == plan.warehouse_id
    if local_to_plan:
        actual_origin_warehouse_id = warehouse_id
    else:
        origin_warehouse = shift.warehouse
        if origin_warehouse is None or origin_warehouse.external_warehouse_id is None:
            raise ApiError(
                422,
                "RWMS_ORIGIN_WAREHOUSE_NOT_LINKED",
                "The driver shift origin has no RWMS warehouse identity",
            )
        actual_origin_warehouse_id = origin_warehouse.external_warehouse_id

    execution_mode = metrics.get("execution_mode")
    if execution_mode in (None, "LOCAL"):
        forbidden_fields = (
            "support_warehouse_link_id",
            "inbound_distance_meters",
            "return_distance_meters",
            "positioning_distance_meters",
            "inbound_departure_at",
            "inbound_raw_arrival_at",
            "inbound_arrival_at",
            "inbound_travel_seconds",
            "return_travel_seconds",
        )
        if any(metrics.get(field) is not None for field in forbidden_fields):
            raise ApiError(
                422,
                "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
                "A local route cannot carry support positioning evidence",
            )
        raw_origin = metrics.get("resource_origin_warehouse_id")
        if raw_origin is not None and _required_uuid_metric(
            metrics,
            "resource_origin_warehouse_id",
            code="CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
        ) != actual_origin_warehouse_id:
            raise ApiError(
                422,
                "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
                "Local route evidence does not match the physical shift warehouse",
            )
        if not local_to_plan and raw_origin is None:
            raise ApiError(
                422,
                "CROSS_WAREHOUSE_PLAN_EVIDENCE_MISSING",
                "A foreign local route requires its persisted physical origin",
            )
        return _DriverShiftRouteEvidence(
            route_origin_warehouse_id=(
                None if local_to_plan else actual_origin_warehouse_id
            ),
            service_warehouse_id=warehouse_id,
            support_warehouse_link_id=None,
            positioning_distance_meters=0,
        )

    if execution_mode != "CROSS_WAREHOUSE_SERVICE":
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_PLAN_EVIDENCE_MISSING",
            "A foreign driver shift requires persisted cross-warehouse plan evidence",
        )
    if (
        metrics.get("returns_to_origin") is not True
        or metrics.get("changes_operational_warehouse") is not False
    ):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
            "Cross-warehouse service must retain the origin as the post-route base",
        )

    evidence_origin = _required_uuid_metric(
        metrics,
        "resource_origin_warehouse_id",
        code="CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
    )
    evidence_service = _required_uuid_metric(
        metrics,
        "service_warehouse_id",
        code="CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
    )
    support_warehouse_link_id = _required_uuid_metric(
        metrics,
        "support_warehouse_link_id",
        code="CROSS_WAREHOUSE_SUPPORT_LINK_MISSING",
    )
    if evidence_origin != actual_origin_warehouse_id or evidence_service != warehouse_id:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID",
            "Cross-warehouse plan evidence does not match the shift origin and plan owner",
        )

    inbound_distance = _required_int64_metric(metrics, "inbound_distance_meters")
    return_distance = _required_int64_metric(metrics, "return_distance_meters")
    positioning_distance = _required_int64_metric(
        metrics, "positioning_distance_meters"
    )
    if positioning_distance != inbound_distance + return_distance:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_DISTANCE_INVALID",
            "Stored positioning distance must equal its exact inbound and return route legs",
        )
    inbound_departure_at = _required_datetime_metric(
        metrics, "inbound_departure_at"
    )
    inbound_raw_arrival_at = _required_datetime_metric(
        metrics, "inbound_raw_arrival_at"
    )
    inbound_arrival_at = _required_datetime_metric(metrics, "inbound_arrival_at")
    inbound_travel_seconds = _required_duration_metric(
        metrics, "inbound_travel_seconds"
    )
    return_travel_seconds = _required_duration_metric(
        metrics, "return_travel_seconds"
    )
    if not (
        inbound_departure_at <= inbound_raw_arrival_at <= inbound_arrival_at
    ) or int((inbound_arrival_at - inbound_departure_at).total_seconds()) != (
        inbound_travel_seconds
    ):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_INVALID",
            "Stored inbound departure, raw arrival, buffered arrival, and duration conflict",
        )
    return _DriverShiftRouteEvidence(
        route_origin_warehouse_id=actual_origin_warehouse_id,
        service_warehouse_id=warehouse_id,
        support_warehouse_link_id=support_warehouse_link_id,
        positioning_distance_meters=positioning_distance,
        inbound_departure_at=inbound_departure_at,
        inbound_raw_arrival_at=inbound_raw_arrival_at,
        inbound_arrival_at=inbound_arrival_at,
        inbound_travel_seconds=inbound_travel_seconds,
        return_travel_seconds=return_travel_seconds,
    )


def _required_uuid_metric(
    metrics: dict[str, object],
    field: str,
    *,
    code: str,
) -> UUID:
    """Parse one required UUID from immutable route-cycle evidence."""

    value = metrics.get(field)
    if value is None:
        raise ApiError(
            422,
            code,
            f"Cross-warehouse plan evidence is missing or invalid: {field}",
        )
    try:
        return UUID(str(value))
    except (TypeError, ValueError) as exc:
        raise ApiError(
            422,
            code,
            f"Cross-warehouse plan evidence is missing or invalid: {field}",
        ) from exc


def _required_int64_metric(metrics: dict[str, object], field: str) -> int:
    """Read one exact non-negative int64 meter value from persisted route evidence."""

    value = metrics.get(field)
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not isfinite(value)
        or value < 0
        or value % 1 != 0
        or value > INT64_MAX
    ):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_DISTANCE_MISSING",
            f"Exact cross-warehouse route distance is missing or invalid: {field}",
        )
    return int(value)


def _required_duration_metric(metrics: dict[str, object], field: str) -> int:
    """Read one exact non-negative operational duration in whole seconds."""

    value = metrics.get(field)
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not isfinite(value)
        or value < 0
        or value % 1 != 0
        or value > INT64_MAX
    ):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_MISSING",
            f"Exact cross-warehouse positioning duration is missing: {field}",
        )
    return int(value)


def _required_datetime_metric(
    metrics: dict[str, object], field: str
) -> datetime:
    """Parse one exact timezone-aware instant from immutable route evidence."""

    value = metrics.get(field)
    if not isinstance(value, str):
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_MISSING",
            f"Exact cross-warehouse positioning instant is missing: {field}",
        )
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_INVALID",
            f"Cross-warehouse positioning instant is invalid: {field}",
        ) from exc
    if parsed.utcoffset() is None:
        raise ApiError(
            422,
            "CROSS_WAREHOUSE_POSITIONING_TIME_INVALID",
            f"Cross-warehouse positioning instant has no timezone: {field}",
        )
    return parsed


def _vehicle_configuration_type(vehicle: Vehicle) -> str:
    """Classify only explicit trailer and crane facts available in the planner catalog."""

    if vehicle.default_trailer is not None:
        return "TRUCK_WITH_TRAILER"
    if vehicle.vehicle_type is not None and vehicle.vehicle_type.upper() == "FLATBED_CRANE":
        return "TRUCK_WITH_CRANE"
    return "TRUCK"


async def _load_plan_for_rwms_apply(session: AsyncSession, plan_id: UUID) -> RoutePlan:
    """Lock and load the complete graph used to freeze one outbound command snapshot."""

    cycles = selectinload(RoutePlan.cycles)
    statement = (
        select(RoutePlan)
        .where(RoutePlan.id == plan_id)
        .options(
            selectinload(RoutePlan.warehouse),
            cycles.selectinload(RouteCycle.driver_shift).selectinload(DriverShift.driver),
            cycles.selectinload(RouteCycle.driver_shift).selectinload(DriverShift.warehouse),
            cycles.selectinload(RouteCycle.driver_shift)
            .selectinload(DriverShift.vehicle)
            .selectinload(Vehicle.default_trailer),
            cycles.selectinload(RouteCycle.stops)
            .selectinload(RouteStop.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
            cycles.selectinload(RouteCycle.stops)
            .selectinload(RouteStop.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.warehouse),
            selectinload(RoutePlan.unassigned_tasks)
            .selectinload(UnassignedTask.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
            selectinload(RoutePlan.unassigned_tasks)
            .selectinload(UnassignedTask.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.warehouse),
        )
        .with_for_update()
    )
    plan = await session.scalar(statement)
    if plan is None:
        raise not_found("plan", plan_id)
    return plan


async def apply_plan_to_rwms(
    session: AsyncSession,
    plan_id: UUID,
    command: RwmsPlanApplyRequest,
    client: RwmsPlanningClient,
    *,
    planner: PlannerFacade,
) -> RwmsApplyResult:
    """Revalidate each attempt, release local locks, then call RWMS idempotently.

    A stale retry is not sent again, but an earlier timed-out attempt may already
    have taken effect in RWMS. Its outcome must be checked through owner status
    and reconciliation, never inferred from the local rejection.
    """

    client.ensure_enabled()
    assignments, idempotency_key = await prepare_plan_for_rwms_apply(
        session,
        plan_id,
        command,
        planner=planner,
    )
    await session.commit()
    return await client.apply_assignments(
        assignments,
        idempotency_key=idempotency_key,
    )


async def prepare_plan_for_rwms_apply(
    session: AsyncSession,
    plan_id: UUID,
    command: RwmsPlanApplyRequest,
    *,
    planner: PlannerFacade,
) -> tuple[RwmsAssignmentsCommand, str]:
    """Check current resources and build this attempt's fenced outbound command."""

    plan = await plans.lock_plan_execution_resources(session, plan_id, command.expected_version)
    if plan.status != PlanStatus.CONFIRMED:
        raise ApiError(
            409,
            "PLAN_NOT_CONFIRMED",
            "Only a confirmed route plan can be published to RWMS",
        )
    if plans.PENDING_REQUEST_REFRESH_METRIC in plan.metrics:
        raise ApiError(409, "PLAN_REFRESH_REQUIRED", "Request routing facts changed")
    _require_publishable_task_states(plan, set(command.publish_unassigned_task_ids))
    await planner.validate_confirmation(session, plan, accept_warnings=True)
    # The current-facts refresh can expire inverse ORM relations. Reload the
    # publication graph before accessing exact source-unit slices and depots.
    plan = await _load_plan_for_rwms_apply(session, plan_id)
    assignments = build_assignments_command(plan, set(command.publish_unassigned_task_ids))
    idempotency_key = str(rwms_plan_idempotency_key(plan.id, plan.version))
    return assignments, idempotency_key


def _require_publishable_task_states(plan: RoutePlan, publish_unassigned_ids: set[UUID]) -> None:
    """Do not mistake a recovery-only READY projection for execution permission."""

    assigned_request_ids: set[UUID] = set()
    for cycle in plan.cycles:
        for stop in cycle.stops:
            if stop.task_id is None:
                continue
            task = stop.task
            if (
                task is None
                or task.status != TaskStatus.PLANNED
                or task.request.status != RequestStatus.PLANNED
                or task.request.scheduled_date != plan.date
            ):
                raise ApiError(
                    409, "PLAN_REFRESH_REQUIRED",
                    "Task execution state changed; check RWMS status before retrying",
                )
            assigned_request_ids.add(task.request_id)
    for item in plan.unassigned_tasks:
        if item.task_id not in publish_unassigned_ids:
            continue
        task = item.task
        request = task.request
        request_available = request.status in (RequestStatus.READY, RequestStatus.UNASSIGNED) or (
            request.status == RequestStatus.PLANNED
            and request.id in assigned_request_ids
            and request.scheduled_date == plan.date
        )
        if task.status not in (TaskStatus.READY, TaskStatus.UNASSIGNED) or not request_available:
            raise ApiError(
                409, "PLAN_REFRESH_REQUIRED",
                "Unassigned task state changed; check RWMS status before retrying",
            )


def rwms_plan_idempotency_key(plan_id: UUID, plan_version: int) -> UUID:
    """Derive the UUID header required by RWMS from one immutable plan version."""

    return uuid5(NAMESPACE_URL, f"rwms-plan:{plan_id}:v{plan_version}")


type RwmsTaskSliceKey = tuple[UUID, tuple[UUID, ...]]


def build_plan_status_task_index(
    plan: RoutePlan,
) -> dict[RwmsTaskSliceKey, tuple[PlanningTask, LogisticsRequest]]:
    """Index exact-plan RWMS deliveries by their deterministic order/unit slice."""

    plan_tasks: dict[UUID, PlanningTask] = {}

    def add_plan_task(task: PlanningTask) -> None:
        if task.type != "DELIVERY":
            raise ApiError(
                422,
                "RWMS_DELIVERY_TASK_INVALID",
                "A delivery plan position references a non-delivery task",
            )
        if task.id in plan_tasks:
            raise ApiError(
                422,
                "DUPLICATE_ASSIGNMENT_CONFLICT",
                "A delivery task appears more than once in the exact plan",
            )
        plan_tasks[task.id] = task

    for cycle in plan.cycles:
        for stop in cycle.stops:
            if stop.stop_type != StopType.DELIVERY:
                continue
            if stop.task is None:
                raise ApiError(
                    422,
                    "RWMS_DELIVERY_TASK_MISSING",
                    "A delivery stop has no planning task",
                )
            add_plan_task(stop.task)
    for unassigned in plan.unassigned_tasks:
        if unassigned.task.type == "DELIVERY":
            add_plan_task(unassigned.task)

    requests: dict[UUID, LogisticsRequest] = {}
    selected_task_ids_by_request: dict[UUID, set[UUID]] = {}
    for task in plan_tasks.values():
        request = task.request
        if request.source_system != catalog.RWMS_SOURCE_SYSTEM:
            continue
        requests[request.id] = request
        selected_task_ids_by_request.setdefault(request.id, set()).add(task.id)

    index: dict[RwmsTaskSliceKey, tuple[PlanningTask, LogisticsRequest]] = {}
    for request_id in sorted(requests, key=str):
        request = requests[request_id]
        if (
            request.external_id is None
            or request.external_version is None
            or request.external_payload is None
        ):
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_MISSING",
                "Every synchronized delivery must retain its RWMS source identity",
            )
        try:
            source = RwmsPlanningRequest.model_validate(request.external_payload)
        except ValidationError as exc:
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_INVALID",
                "A synchronized request contains invalid source metadata",
            ) from exc
        if (
            source.order_id != request.external_id
            or source.order_version != request.external_version
            or source.customer_delivery_purpose != request.customer_delivery_purpose
        ):
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_INVALID",
                "A synchronized request identity does not match its source metadata",
            )

        offset = 0
        selected_task_ids = selected_task_ids_by_request[request_id]
        for task in sorted(request.tasks, key=lambda item: (item.part_number, str(item.id))):
            next_offset = offset + task.quantity
            unit_slice = source.unit_ids[offset:next_offset]
            if len(unit_slice) != task.quantity:
                raise ApiError(
                    422,
                    "RWMS_UNIT_MAPPING_INVALID",
                    "Planning task quantities do not match synchronized cabin unit IDs",
                )
            if task.id in selected_task_ids:
                key = (source.order_id, tuple(unit_slice))
                existing = index.get(key)
                if existing is not None and existing[0].id != task.id:
                    raise ApiError(
                        422,
                        "RWMS_STATUS_TASK_MAPPING_CONFLICT",
                        "Two exact-plan tasks map to the same RWMS order and unit slice",
                    )
                index[key] = (task, request)
            offset = next_offset
        if offset != len(source.unit_ids):
            raise ApiError(
                422,
                "RWMS_UNIT_MAPPING_INVALID",
                "Planning task quantities do not consume every synchronized cabin unit ID",
            )
    return index


def build_replacement_membership(
    plan: RoutePlan,
    owner_statuses: Iterable[RwmsPlanningAssignmentStatus],
    *,
    source_plan_id: UUID,
    expected_source_plan_version: int,
    replacement_plan_version: int,
    excluded_external_task_ids: frozenset[UUID] = frozenset(),
) -> tuple[list[RwmsPlanningAssignmentReplacement], list[RwmsDriverShiftPlan]]:
    """Map one solved plan to the owner's exact existing membership and queue fences."""

    if replacement_plan_version <= expected_source_plan_version:
        raise ApiError(
            422,
            "RWMS_REPLACEMENT_VERSION_INVALID",
            "The owner replacement revision must be strictly newer",
        )
    warehouse_id = plan.warehouse.external_warehouse_id
    if warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The plan warehouse has no RWMS warehouse identity",
        )
    status_by_key: dict[RwmsTaskSliceKey, RwmsPlanningAssignmentStatus] = {}
    for status in owner_statuses:
        if status.external_task_id in excluded_external_task_ids:
            continue
        if (
            status.source_plan_id != source_plan_id
            or status.source_plan_version != expected_source_plan_version
            or status.task_state != "SCHEDULED"
        ):
            raise ApiError(
                409,
                "RWMS_REPLACEMENT_SOURCE_CHANGED",
                "Published assignment membership changed or execution already started",
            )
        key = (status.order_id, tuple(status.unit_ids))
        if key in status_by_key:
            raise ApiError(
                502,
                "RWMS_ASSIGNMENT_STATUS_DUPLICATE",
                "RWMS returned duplicate published assignment membership",
            )
        status_by_key[key] = status

    plan_index = build_plan_status_task_index(plan)
    missing_from_plan = status_by_key.keys() - plan_index.keys()
    if missing_from_plan:
        raise ApiError(
            409,
            "RWMS_REPLACEMENT_MEMBERSHIP_CHANGED",
            "The recovery plan no longer contains every published assignment",
        )
    selected_task_ids = {plan_index[key][0].id for key in status_by_key}
    unassigned_task_ids = {
        item.task.id
        for item in plan.unassigned_tasks
        if item.task.id in selected_task_ids
    }
    desired_by_key: dict[RwmsTaskSliceKey, RwmsPlanningAssignment] = {}
    if status_by_key:
        desired_command = build_assignments_command(plan, unassigned_task_ids)
        for assignment in desired_command.assignments:
            key = (assignment.order_id, tuple(assignment.unit_ids))
            if key in desired_by_key:
                raise ApiError(
                    422,
                    "RWMS_REPLACEMENT_MEMBERSHIP_CONFLICT",
                    "The recovery plan contains duplicate published assignment slices",
                )
            desired_by_key[key] = assignment
        if desired_by_key.keys() != status_by_key.keys():
            raise ApiError(
                409,
                "RWMS_REPLACEMENT_MEMBERSHIP_CHANGED",
                "The recovery plan does not match the complete published membership",
            )

    route_rank: dict[UUID, tuple[int, str, int, int, str]] = {}
    for cycle in sorted(
        plan.cycles,
        key=lambda item: (str(item.driver_shift_id), item.sequence, str(item.id)),
    ):
        for stop in sorted(cycle.stops, key=lambda item: (item.sequence, str(item.id))):
            if stop.task_id is not None:
                route_rank[stop.task_id] = (
                    0,
                    str(cycle.driver_shift_id),
                    cycle.sequence,
                    stop.sequence,
                    str(stop.task_id),
                )
    for ordinal, item in enumerate(
        sorted(plan.unassigned_tasks, key=lambda value: str(value.task_id))
    ):
        route_rank[item.task_id] = (1, "", 0, ordinal, str(item.task_id))

    ordered_keys = sorted(
        status_by_key,
        key=lambda key: route_rank.get(
            plan_index[key][0].id,
            (2, "", 0, 0, str(plan_index[key][0].id)),
        ),
    )
    next_position: dict[tuple[UUID, str, UUID | None], int] = {}
    replacements: list[RwmsPlanningAssignmentReplacement] = []
    for key in ordered_keys:
        status = status_by_key[key]
        desired_assignment = desired_by_key[key]
        if (
            desired_assignment.service_warehouse_id is None
            or desired_assignment.expected_order_version != status.order_version
        ):
            raise ApiError(
                409,
                "RWMS_REPLACEMENT_ORDER_CHANGED",
                "An order or service warehouse changed after recovery analysis",
            )
        scope = (
            desired_assignment.service_warehouse_id,
            desired_assignment.driver_audience_mode,
            desired_assignment.driver_worker_id,
        )
        position = next_position.get(scope, 0)
        next_position[scope] = position + 1
        replacements.append(
            RwmsPlanningAssignmentReplacement(
                order_id=status.order_id,
                expected_order_version=status.order_version,
                document_id=status.document_id,
                external_task_id=status.external_task_id,
                expected_task_version=status.task_version,
                service_warehouse_id=desired_assignment.service_warehouse_id,
                scheduled_date=desired_assignment.scheduled_date,
                unit_ids=list(status.unit_ids),
                driver_audience_mode=desired_assignment.driver_audience_mode,
                driver_worker_id=desired_assignment.driver_worker_id,
                driver_name=desired_assignment.driver_name,
                target_queue_position=position,
                provisional_eta=desired_assignment.provisional_eta,
            )
        )

    shifts = [
        shift.model_copy(
            update={
                "source_plan_id": source_plan_id,
                "source_plan_version": replacement_plan_version,
            }
        )
        for shift in _build_driver_shift_plans(plan, warehouse_id)
    ]
    return replacements, shifts


def build_assignment_replacement_command(
    plan: RoutePlan,
    owner_statuses: Iterable[RwmsPlanningAssignmentStatus],
    *,
    source_plan_id: UUID,
    expected_source_plan_version: int,
    replacement_plan_version: int,
) -> RwmsReplacePlanningAssignmentsCommand:
    """Build the complete all-or-nothing owner command for a solved recovery revision."""

    warehouse_id = plan.warehouse.external_warehouse_id
    if warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The plan warehouse has no RWMS warehouse identity",
        )
    assignments, shifts = build_replacement_membership(
        plan,
        owner_statuses,
        source_plan_id=source_plan_id,
        expected_source_plan_version=expected_source_plan_version,
        replacement_plan_version=replacement_plan_version,
    )
    return RwmsReplacePlanningAssignmentsCommand(
        warehouse_id=warehouse_id,
        date=plan.date,
        expected_source_plan_version=expected_source_plan_version,
        replacement_plan_version=replacement_plan_version,
        assignments=assignments,
        driver_shift_plans=shifts,
    )


async def _load_plan_for_rwms_status(session: AsyncSession, plan_id: UUID) -> RoutePlan:
    """Load the exact plan graph needed for an immutable status mapping snapshot."""

    cycles = selectinload(RoutePlan.cycles)
    statement = (
        select(RoutePlan)
        .where(RoutePlan.id == plan_id)
        .options(
            selectinload(RoutePlan.warehouse),
            cycles.selectinload(RouteCycle.stops)
            .selectinload(RouteStop.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
            selectinload(RoutePlan.unassigned_tasks)
            .selectinload(UnassignedTask.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
        )
    )
    plan = await session.scalar(statement)
    if plan is None:
        raise not_found("plan", plan_id)
    return plan


async def get_plan_rwms_status(
    session: AsyncSession,
    plan_id: UUID,
    expected_version: int,
    client: RwmsPlanningClient,
) -> RwmsPlanStatusResult:
    """Map a released exact-plan snapshot to the current RWMS publication states."""

    client.ensure_enabled()
    plan = await _load_plan_for_rwms_status(session, plan_id)
    if plan.version != expected_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The route plan changed after it was loaded",
            extra={"current_version": plan.version},
        )
    warehouse_id = plan.warehouse.external_warehouse_id
    if warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The plan warehouse has no RWMS warehouse identity",
        )
    plan_date = plan.date
    plan_version = plan.version
    task_index = build_plan_status_task_index(plan)
    await session.commit()

    status_feed = await client.get_assignment_statuses(
        warehouse_id=warehouse_id,
        date=plan_date,
    )
    if status_feed.warehouse_id != warehouse_id:
        raise ApiError(
            502,
            "RWMS_WAREHOUSE_MISMATCH",
            "RWMS assignment status response belongs to a different warehouse",
        )
    if status_feed.date != plan_date:
        raise ApiError(
            502,
            "RWMS_ASSIGNMENT_DATE_MISMATCH",
            "RWMS assignment status response belongs to a different date",
        )

    mapped: dict[UUID, RwmsPlanTaskStatus] = {}
    for status in status_feed.assignments:
        local = task_index.get((status.order_id, tuple(status.unit_ids)))
        if local is None:
            continue
        task, request = local
        task_status = RwmsPlanTaskStatus(
            task_id=task.id,
            request_id=request.id,
            order_id=status.order_id,
            document_id=status.document_id,
            driver_audience_mode=status.driver_audience_mode,
            driver_worker_id=status.driver_worker_id,
            driver_name=status.driver_name,
            task_state=status.task_state,
        )
        existing = mapped.get(task.id)
        if existing is not None and existing != task_status:
            raise ApiError(
                502,
                "RWMS_ASSIGNMENT_STATUS_CONFLICT",
                "RWMS returned conflicting assignment statuses for one exact plan task",
            )
        mapped[task.id] = task_status

    return RwmsPlanStatusResult(
        plan_id=plan_id,
        plan_version=plan_version,
        tasks=sorted(mapped.values(), key=lambda item: str(item.task_id)),
    )
