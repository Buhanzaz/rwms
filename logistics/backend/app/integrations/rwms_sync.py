"""RWMS synchronization and plan-application orchestration for the simulator."""

from __future__ import annotations

from datetime import date
from uuid import NAMESPACE_URL, UUID, uuid5

from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import StopType
from app.schemas.domain import (
    RwmsApplyResult,
    RwmsAssignmentsCommand,
    RwmsPlanApplyRequest,
    RwmsPlanningAssignment,
    RwmsPlanningRequest,
    RwmsPlanStatusResult,
    RwmsPlanTaskStatus,
    RwmsScenarioRefreshResult,
    RwmsSyncFailure,
    RwmsSyncRequest,
    RwmsSyncResult,
    RwmsWarehouseSyncResult,
)
from app.services import catalog


async def sync_scenario_requests(
    session: AsyncSession,
    scenario_id: UUID,
    command: RwmsSyncRequest,
    client: RwmsPlanningClient,
) -> RwmsSyncResult:
    """Synchronize one warehouse feed with per-order savepoints and explicit failures."""

    client.ensure_enabled()
    await catalog.require_scenario(session, scenario_id)
    linked_warehouse = await session.scalar(
        select(Warehouse).where(
            Warehouse.scenario_id == scenario_id,
            Warehouse.external_warehouse_id == command.warehouse_id,
        )
    )
    if linked_warehouse is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "Link a scenario warehouse to the requested RWMS warehouse before synchronization",
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
    for source in feed.requests:
        if source.latitude is None or source.longitude is None:
            failures.append(
                RwmsSyncFailure(
                    order_id=source.order_id,
                    code="COORDINATES_REQUIRED",
                    message=(
                        "Order has no coordinates; address geocoding is disabled and the order "
                        "was not imported"
                    ),
                )
            )
            continue
        try:
            async with session.begin_nested():
                outcome = await catalog.upsert_rwms_request(session, scenario_id, source)
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
    await session.flush()
    return RwmsSyncResult(**counts, failures=failures)


async def refresh_scenario_requests(
    session: AsyncSession,
    scenario_id: UUID,
    *,
    date_from: date,
    date_to: date,
    client: RwmsPlanningClient,
) -> RwmsScenarioRefreshResult:
    """Refresh every linked warehouse without delegating the cross-warehouse saga to a browser.

    Each warehouse uses the ordinary strict synchronization workflow. That workflow commits
    before remote I/O, so a slow or failed upstream call never retains a local database lock.
    Successfully imported earlier warehouses remain retry-safe if a later warehouse fails.
    """

    await catalog.require_scenario(session, scenario_id)
    warehouse_ids = list(
        await session.scalars(
            select(Warehouse.external_warehouse_id)
            .where(
                Warehouse.scenario_id == scenario_id,
                Warehouse.external_warehouse_id.is_not(None),
            )
            .order_by(Warehouse.external_warehouse_id)
        )
    )
    results: list[RwmsWarehouseSyncResult] = []
    for warehouse_id in warehouse_ids:
        assert warehouse_id is not None
        result = await sync_scenario_requests(
            session,
            scenario_id,
            RwmsSyncRequest(
                warehouse_id=warehouse_id,
                date_from=date_from,
                date_to=date_to,
            ),
            client,
        )
        results.append(
            RwmsWarehouseSyncResult(
                warehouse_id=warehouse_id,
                **result.model_dump(),
            )
        )
    return RwmsScenarioRefreshResult(
        date_from=date_from,
        date_to=date_to,
        warehouses=results,
    )


def build_assignments_command(
    plan: RoutePlan,
    publish_unassigned_task_ids: set[UUID] | frozenset[UUID] = frozenset(),
) -> RwmsAssignmentsCommand:
    """Map assigned and explicitly published delivery parts to stable RWMS unit slices."""

    warehouse_id = plan.warehouse.external_warehouse_id
    if warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "The plan warehouse has no RWMS warehouse identity",
        )

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

    unassigned_by_task_id = {item.task.id: item.task for item in plan.unassigned_tasks}
    unknown_publish_ids = publish_unassigned_task_ids - unassigned_by_task_id.keys()
    if unknown_publish_ids:
        raise ApiError(
            422,
            "RWMS_UNASSIGNED_SELECTION_INVALID",
            "Every published task must still be unassigned in the exact plan version",
        )
    shared_tasks = [
        unassigned_by_task_id[task_id] for task_id in sorted(publish_unassigned_task_ids, key=str)
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
        ):
            raise ApiError(
                422,
                "RWMS_REQUEST_MAPPING_INVALID",
                "A synchronized request identity does not match its source metadata",
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
        if driver.external_worker_id is None:
            raise ApiError(
                422,
                "RWMS_DRIVER_NOT_LINKED",
                f"Driver {driver.name} has no RWMS worker identity",
            )
        source = source_by_request[request.id]
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                expected_order_version=source.order_version,
                scheduled_date=plan.date,
                driver_audience_mode="ASSIGNED_DRIVER",
                driver_worker_id=driver.external_worker_id,
                driver_name=driver.name,
                unit_ids=unit_slices[task.id],
            )
        )

    for task in sorted(
        shared_tasks,
        key=lambda item: (str(item.request.external_id), item.part_number, str(item.id)),
    ):
        source = source_by_request[task.request.id]
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                expected_order_version=source.order_version,
                scheduled_date=plan.date,
                driver_audience_mode="WAREHOUSE_DRIVERS",
                driver_worker_id=None,
                driver_name="Свободная доставка",
                unit_ids=unit_slices[task.id],
            )
        )

    return RwmsAssignmentsCommand(
        warehouse_id=warehouse_id,
        plan_id=plan.id,
        plan_version=plan.version,
        assignments=assignments,
    )


async def _load_plan_for_rwms_apply(session: AsyncSession, plan_id: UUID) -> RoutePlan:
    """Lock and load the complete graph used to freeze one outbound command snapshot."""

    cycles = selectinload(RoutePlan.cycles)
    statement = (
        select(RoutePlan)
        .where(RoutePlan.id == plan_id)
        .options(
            selectinload(RoutePlan.warehouse),
            cycles.selectinload(RouteCycle.driver_shift).selectinload(DriverShift.driver),
            cycles.selectinload(RouteCycle.stops)
            .selectinload(RouteStop.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
            selectinload(RoutePlan.unassigned_tasks)
            .selectinload(UnassignedTask.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.tasks),
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
) -> RwmsApplyResult:
    """Freeze an exact plan command, release local locks, then call RWMS idempotently."""

    client.ensure_enabled()
    plan = await _load_plan_for_rwms_apply(session, plan_id)
    if plan.version != command.expected_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The route plan changed after it was loaded",
            extra={"current_version": plan.version},
        )
    assignments = build_assignments_command(plan, set(command.publish_unassigned_task_ids))
    idempotency_key = str(rwms_plan_idempotency_key(plan.id, plan.version))
    await session.commit()
    return await client.apply_assignments(
        assignments,
        idempotency_key=idempotency_key,
    )


def rwms_plan_idempotency_key(plan_id: UUID, plan_version: int) -> UUID:
    """Derive the UUID header required by RWMS from one immutable plan version."""

    return uuid5(NAMESPACE_URL, f"rwms-plan:{plan_id}:v{plan_version}")


type RwmsTaskSliceKey = tuple[UUID, tuple[UUID, ...]]


def _build_plan_status_task_index(
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
    task_index = _build_plan_status_task_index(plan)
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
