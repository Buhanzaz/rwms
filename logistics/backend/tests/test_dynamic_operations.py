"""Integration coverage for day constraints, incidents, and human recovery state."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.authorization import require_dynamic_day_access
from app.errors import ApiError
from app.models import (
    DriverShift,
    LogisticsActionStatus,
    LogisticsEvent,
    LogisticsEventType,
    LogisticsHumanAction,
    LogisticsHumanDecision,
    LogisticsNotice,
    LogisticsRequest,
    PlanningDayMode,
    PlanningDayPolicy,
    RecoveryProposal,
    RecoveryProposalStatus,
    RequestDateOption,
    RoutePlan,
    Trailer,
    Vehicle,
    Warehouse,
)
from app.models.domain import PlanStatus, RequestStatus, TaskStatus
from app.schemas.domain import (
    DriverCreate,
    GeneratePlanRequest,
    LogisticsRequestCreate,
    ManualChangeCommand,
    RequestDateOptionInput,
    RwmsDriverIdentity,
    RwmsPlanningAssignmentStatus,
    RwmsPlanningAssignmentStatusFeed,
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
    RwmsReplacedPlanningAssignment,
    RwmsReplacedPlanningDriverShift,
    RwmsReplacePlanningAssignmentsCommand,
    RwmsReplacePlanningAssignmentsResult,
    RwmsWarehouseIdentity,
    RwmsWarehouseSupportLink,
    VehicleUpdate,
)
from app.schemas.operations import (
    LogisticsEventCreate,
    LogisticsHumanDecisionCreate,
    RwmsPlanningBaseTask,
    RwmsPublishedAssignmentWithdrawal,
    RwmsPublishedAssignmentWithdrawalResult,
)
from app.security import CurrentUserPrincipal, WarehouseAccessLevel
from app.services import catalog, plans
from app.services.capacity_projection import build_capacity_projection
from app.services.dynamic_operations import DynamicLogisticsService
from app.services.dynamic_projection import DynamicOperationsProjection
from app.services.planner_runtime import RuntimePlannerFacade
from tests.auth import user_claims
from tests.factories import (
    make_driver,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration
PLANNING_DATE = date(2026, 9, 3)
ZONE = ZoneInfo("Europe/Moscow")


@pytest.fixture(autouse=True)
def recovery_clock(monkeypatch: pytest.MonkeyPatch) -> None:
    """Keep incident recovery reproducible without allowing plans to start in the past."""

    monkeypatch.setattr(
        "app.services.planner_runtime.utc_now",
        lambda: datetime.combine(PLANNING_DATE, time(7), tzinfo=ZONE),
    )


def _warehouse_principal(*warehouses: Warehouse) -> CurrentUserPrincipal:
    """Build a non-admin dispatcher with exact signed grants for selected warehouses."""

    return CurrentUserPrincipal.from_claims(
        user_claims(
            global_role="WAREHOUSE_MANAGER",
            warehouse_access=[
                {
                    "warehouseId": str(warehouse.external_warehouse_id),
                    "level": "EDIT",
                }
                for warehouse in warehouses
            ],
        ),
        panel_client_id="rwms-panel",
    )


class _FeasiblePlanner:
    """Small deterministic planner seam used only to isolate operational-state tests."""

    def __init__(self) -> None:
        self.preview_calls: list[tuple[UUID, tuple[date, ...]]] = []
        self.recovery_calls = 0

    async def preview_feasible_request_dates(
        self,
        session: AsyncSession,
        request_id: UUID,
        candidate_dates: tuple[date, ...],
    ) -> tuple[date, ...]:
        """Return supplied dates in stable order as already route-feasible options."""

        del session
        ordered = tuple(sorted(dict.fromkeys(candidate_dates)))
        self.preview_calls.append((request_id, ordered))
        return ordered

    async def reoptimize_recovery(self, *args: object, **kwargs: object) -> object:
        """Fail loudly when a state-only test unexpectedly attempts plan application."""

        del args, kwargs
        self.recovery_calls += 1
        raise AssertionError("recovery application was not expected")


def _warehouse_identity(warehouse: Warehouse) -> RwmsWarehouseIdentity:
    """Build the canonical identity needed by a direct planning-group edge."""

    return RwmsWarehouseIdentity(
        warehouseId=warehouse.external_warehouse_id,
        warehouseVersion=warehouse.external_warehouse_version,
        name=warehouse.name,
        city=warehouse.city or "Test city",
        address=warehouse.address,
        latitude=warehouse.latitude,
        longitude=warehouse.longitude,
        timeZone=warehouse.timezone,
        representative=warehouse.representative,
        routingReady=warehouse.routing_ready,
    )


def _support_link(root: Warehouse, representative: Warehouse) -> RwmsWarehouseSupportLink:
    """Build one active direct support edge for a root-group operations test."""

    return RwmsWarehouseSupportLink(
        supportLinkId=uuid4(),
        supportLinkVersion=1,
        supportWarehouse=_warehouse_identity(root),
        servedWarehouse=_warehouse_identity(representative),
        priority=1,
        allowDrivers=True,
        allowVehicles=True,
        allowInventory=True,
        allowDirectFulfillment=True,
        allowInterwarehouseTransfer=True,
        allowContractorFallback=True,
        allowedWeekdays=[],
        allowedDates=[],
        excludedDates=[],
        serviceStart=time(8),
        serviceEnd=time(20),
    )


class _PlanningGroupClient:
    """Minimal owner-network adapter for one main and one representative warehouse."""

    def __init__(self, link: RwmsWarehouseSupportLink) -> None:
        self.link = link

    async def list_support_network(
        self,
        warehouse_id: UUID,
    ) -> list[RwmsWarehouseSupportLink]:
        """Return the edge when the requested warehouse is one of its endpoints."""

        return (
            [self.link]
            if warehouse_id
            in {
                self.link.support_warehouse.warehouse_id,
                self.link.served_warehouse.warehouse_id,
            }
            else []
        )

    async def list_vehicle_assignments(
        self,
        warehouse_id: UUID,
        *,
        window_start: datetime,
        window_end: datetime,
    ) -> list[object]:
        """Return no vehicle moves for the isolated planning-group test."""

        del warehouse_id, window_start, window_end
        return []

    async def list_drivers(
        self,
        warehouse_id: UUID,
        *,
        at: datetime | None = None,
        include_incoming: bool = False,
    ) -> list[RwmsDriverIdentity]:
        """Return no remote workers; local root resources remain authoritative."""

        del warehouse_id, at, include_incoming
        return []

    async def list_support_links(
        self,
        served_warehouse_id: UUID,
        *,
        at: datetime,
    ) -> list[RwmsWarehouseSupportLink]:
        """Return the direct incoming edge for support-resource evaluation."""

        del at
        return [self.link] if served_warehouse_id == self.link.served_warehouse.warehouse_id else []


class _GroupStatusClient(_PlanningGroupClient):
    """Return warehouse-scoped execution snapshots for every root-plan member."""

    def __init__(
        self,
        link: RwmsWarehouseSupportLink,
        assignments: dict[UUID, list[RwmsPlanningAssignmentStatus]],
    ) -> None:
        super().__init__(link)
        self.assignments = assignments
        self.status_calls: list[UUID] = []
        self.replacement_commands: list[
            tuple[UUID, UUID, RwmsReplacePlanningAssignmentsCommand]
        ] = []
        self.replacement_receipts: dict[UUID, RwmsReplacePlanningAssignmentsResult] = {}
        self.fail_after_replacement_once = False
        self.cancellation_withdrawal_commands: list[
            tuple[UUID, UUID, RwmsPublishedAssignmentWithdrawal]
        ] = []
        self.cancellation_withdrawal_receipts: dict[
            UUID, RwmsPublishedAssignmentWithdrawalResult
        ] = {}

    async def get_assignment_statuses(
        self,
        *,
        warehouse_id: UUID,
        date: date,
    ) -> RwmsPlanningAssignmentStatusFeed:
        """Return only this exact member warehouse's authoritative task slice."""

        self.status_calls.append(warehouse_id)
        return RwmsPlanningAssignmentStatusFeed(
            warehouseId=warehouse_id,
            date=date,
            assignments=self.assignments.get(warehouse_id, []),
        )

    async def list_base_tasks(
        self,
        warehouse_id: UUID,
        *,
        available_at: datetime,
        limit: int,
    ) -> list[RwmsPlanningBaseTask]:
        """Return no base work unless a test explicitly supplies another adapter."""

        del warehouse_id, available_at, limit
        return []

    async def replace_assignments(
        self,
        source_plan_id: UUID,
        command: RwmsReplacePlanningAssignmentsCommand,
        *,
        idempotency_key: UUID,
    ) -> RwmsReplacePlanningAssignmentsResult:
        """Converge one exact owner revision and replay its durable receipt by UUID key."""

        self.replacement_commands.append((source_plan_id, idempotency_key, command))
        replay = self.replacement_receipts.get(idempotency_key)
        if replay is not None:
            return replay.model_copy(update={"outcome": "REPLAYED"})
        replaced_assignments = [
            RwmsReplacedPlanningAssignment(
                orderId=item.order_id,
                orderVersion=item.expected_order_version,
                documentId=item.document_id,
                externalTaskId=item.external_task_id,
                taskVersion=item.expected_task_version + 1,
                taskBoardTaskVersion=item.expected_task_version + 1,
                taskBoardEntryId=uuid4(),
                taskBoardEntryVersion=item.expected_task_version + 1,
                queuePosition=item.target_queue_position,
            )
            for item in command.assignments
        ]
        result = RwmsReplacePlanningAssignmentsResult(
            outcome="APPLIED",
            sourcePlanId=source_plan_id,
            sourcePlanVersion=command.replacement_plan_version,
            warehouseId=command.warehouse_id,
            date=command.date,
            assignments=replaced_assignments,
            driverShiftPlans=[
                RwmsReplacedPlanningDriverShift(
                    sourceShiftId=shift.source_shift_id,
                    taskBoardShiftPlanVersion=command.replacement_plan_version,
                    sourcePlanVersion=command.replacement_plan_version,
                )
                for shift in command.driver_shift_plans
            ],
        )
        self.replacement_receipts[idempotency_key] = result
        desired_by_id = {item.external_task_id: item for item in command.assignments}
        current = self.assignments.get(command.warehouse_id, [])
        self.assignments[command.warehouse_id] = [
            status.model_copy(
                update={
                    "task_version": status.task_version + 1,
                    "source_plan_version": command.replacement_plan_version,
                    "scheduled_date": desired_by_id[status.external_task_id].scheduled_date,
                    "driver_audience_mode": desired_by_id[
                        status.external_task_id
                    ].driver_audience_mode,
                    "driver_worker_id": desired_by_id[status.external_task_id].driver_worker_id,
                    "driver_name": desired_by_id[status.external_task_id].driver_name,
                }
            )
            for status in current
            if status.external_task_id in desired_by_id
        ]
        if self.fail_after_replacement_once:
            self.fail_after_replacement_once = False
            raise ApiError(
                503,
                "RWMS_REPLACEMENT_RESPONSE_LOST",
                "Owner committed the replacement before the response was lost",
            )
        return result

    async def withdraw_cancelled_assignment(
        self,
        source_plan_id: UUID,
        command: RwmsPublishedAssignmentWithdrawal,
        *,
        idempotency_key: UUID,
    ) -> RwmsPublishedAssignmentWithdrawalResult:
        """Converge and replay one owner-cancelled published member withdrawal."""

        self.cancellation_withdrawal_commands.append((source_plan_id, idempotency_key, command))
        replay = self.cancellation_withdrawal_receipts.get(idempotency_key)
        if replay is not None:
            return replay
        result = RwmsPublishedAssignmentWithdrawalResult(
            sourcePlanId=source_plan_id,
            sourcePlanVersion=command.replacement_plan_version,
            removedExternalTaskId=command.removed_assignment.external_task_id,
            removedTaskVersion=command.removed_assignment.expected_task_version + 1,
            state="COMPLETE",
        )
        self.cancellation_withdrawal_receipts[idempotency_key] = result
        remaining_ids = {item.external_task_id for item in command.remaining_assignments}
        for warehouse_id, current in tuple(self.assignments.items()):
            self.assignments[warehouse_id] = [
                status.model_copy(update={"source_plan_version": command.replacement_plan_version})
                for status in current
                if status.external_task_id in remaining_ids
            ]
        return result


async def _make_request_type(
    session: AsyncSession,
    warehouse: Warehouse,
    request_type: str,
    planning_date: date,
    *,
    name: str,
    window_start: time = time(10),
    window_end: time = time(14),
    quantity: int = 1,
) -> LogisticsRequest:
    """Persist one fully specified request direction for policy and recovery tests."""

    return await catalog.create_request(
        session,
        warehouse.id,
        LogisticsRequestCreate(
            type=request_type,
            name=name,
            address_label=f"Адрес: {name}",
            latitude=59.94,
            longitude=30.33,
            quantity=quantity,
            service_minutes=30,
            cargo_length_mm=6_000,
            cargo_width_mm=2_400,
            cargo_height_mm=2_400,
            cargo_weight_kg=1_200,
            mandatory=False,
            trailer_access_allowed=True,
            date_options=[
                RequestDateOptionInput(
                    date=planning_date,
                    window_start=window_start,
                    window_end=window_end,
                    is_hard=True,
                )
            ],
        ),
    )


def _mark_as_rwms_request(
    request: LogisticsRequest,
    service_warehouse: Warehouse,
) -> RwmsPlanningRequest:
    """Attach one strict owner snapshot to an existing local planning request."""

    unit_ids = [uuid4() for _ in range(request.quantity)]
    source = RwmsPlanningRequest(
        orderId=uuid4(),
        orderVersion=3,
        sourceRevision="a" * 64,
        customerDeliveryPurpose="RENTAL_DELIVERY",
        orderNumber=f"RWMS-{str(request.id)[:8]}",
        clientName=request.name,
        clientType="LEGAL_ENTITY",
        contactName="Иван Петров",
        contactPhone="+79990000000",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=request.quantity,
        unitIds=unit_ids,
        unitReservations=[
            RwmsPlanningUnitReservation(
                unitId=unit_id,
                inventorySourceWarehouseId=service_warehouse.external_warehouse_id,
            )
            for unit_id in unit_ids
        ],
        dateOptions=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                isHard=True,
                windowStart=time(10),
                windowEnd=time(14),
            )
        ],
        trailerAccessAllowed=True,
        deliveryPriceRubles=25_000,
        priceIsochroneMinutes=240,
        createdAt=datetime(2026, 9, 1, tzinfo=UTC),
    )
    request.source_system = catalog.RWMS_SOURCE_SYSTEM
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    request.client_type = source.client_type
    request.contact_name = source.contact_name or ""
    request.contact_phone = source.contact_phone or ""
    return source


def _owner_assignment(
    source: RwmsPlanningRequest,
    *,
    state: str,
    task_version: int = 7,
    external_task_id: UUID | None = None,
) -> RwmsPlanningAssignmentStatus:
    """Build one exact authoritative assignment slice for a synchronized request."""

    return RwmsPlanningAssignmentStatus(
        orderId=source.order_id,
        orderVersion=source.order_version,
        documentId=uuid4(),
        externalTaskId=external_task_id or uuid4(),
        taskVersion=task_version,
        sourcePlanId=None,
        sourcePlanVersion=None,
        scheduledDate=PLANNING_DATE,
        unitIds=source.unit_ids,
        driverAudienceMode="WAREHOUSE_DRIVERS",
        driverWorkerId=None,
        driverName=None,
        taskState=state,
    )


async def _generated_day(
    session: AsyncSession,
    *,
    alternatives: int = 2,
    request_type: str = "DELIVERY",
    quantity: int = 1,
    resource_count: int = 1,
) -> tuple[RuntimePlannerFacade, RoutePlan, LogisticsRequest, Vehicle, DriverShift]:
    """Create one real generated day with a dated multi-day resource interval."""

    warehouse = await make_warehouse(session, default_planning_date=PLANNING_DATE)
    request = await _make_request_type(
        session,
        warehouse,
        request_type,
        PLANNING_DATE,
        name="Test delivery" if request_type == "DELIVERY" else "Test pickup",
        quantity=quantity,
    )
    for offset in range(1, alternatives + 1):
        request.date_options.append(
            RequestDateOption(
                date=PLANNING_DATE + timedelta(days=offset),
                priority=offset,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        )
    driver = await make_driver(session, warehouse)
    vehicle = await make_vehicle(session, warehouse)
    shift = await make_shift(
        session,
        warehouse,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE + timedelta(days=2),
    )
    for index in range(1, resource_count):
        worker_id = uuid4()
        identity = RwmsDriverIdentity(
            workerId=worker_id,
            displayName=f"Тестовый водитель {index + 1}",
            employmentType="STAFF",
            phone=None,
            operationalWarehouseId=warehouse.external_warehouse_id,
            availableFrom=None,
            availableUntil=None,
            availabilityKind="HOME",
        )
        extra_driver = await catalog.create_driver(
            session,
            warehouse.id,
            DriverCreate(
                rwms_assignment_mode="ASSIGNED_DRIVER",
                external_worker_id=worker_id,
            ),
            identity,
        )
        extra_vehicle = await make_vehicle(session, warehouse)
        await make_shift(
            session,
            warehouse,
            extra_driver,
            extra_vehicle,
            date_from=PLANNING_DATE,
            date_to=PLANNING_DATE + timedelta(days=2),
        )
    await session.flush()
    planner = RuntimePlannerFacade()
    run = await planner.generate_plan(
        session,
        warehouse.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=warehouse.seed),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(session, run.plan_id)
    assert any(stop.task_id is not None for cycle in plan.cycles for stop in cycle.stops)
    return planner, plan, request, vehicle, shift


async def _confirmed_rwms_day(
    session: AsyncSession,
    *,
    owner_state: str,
) -> tuple[
    DynamicLogisticsService,
    _GroupStatusClient,
    RoutePlan,
    LogisticsRequest,
    RwmsPlanningAssignmentStatus,
]:
    """Create one published owner-backed task with a controllable execution state."""

    root = await make_warehouse(session, default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(
        session,
        default_planning_date=PLANNING_DATE,
        name="Representative status edge",
    )
    representative.representative = True
    request = await _make_request_type(
        session,
        root,
        "DELIVERY",
        PLANNING_DATE,
        name="RWMS cancellation",
    )
    source = _mark_as_rwms_request(request, root)
    driver = await make_driver(session, root)
    vehicle = await make_vehicle(session, root)
    await make_shift(
        session,
        root,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    assignment = _owner_assignment(source, state=owner_state)
    client = _GroupStatusClient(
        _support_link(root, representative),
        {root.external_warehouse_id: [assignment]},
    )
    runtime = RuntimePlannerFacade(rwms_client=client)  # type: ignore[arg-type]
    run = await runtime.generate_plan(
        session,
        root.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=root.seed),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(session, run.plan_id)
    plan = await plans.confirm_plan(
        session,
        plan.id,
        plan.version,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by="dispatcher",
        planner=runtime,
    )
    assignment = assignment.model_copy(
        update={"source_plan_id": plan.id, "source_plan_version": plan.version}
    )
    client.assignments[root.external_warehouse_id] = [assignment]
    return (
        DynamicLogisticsService(runtime, client),  # type: ignore[arg-type]
        client,
        plan,
        request,
        assignment,
    )


async def _late_delay_case(
    session: AsyncSession,
) -> tuple[DynamicLogisticsService, LogisticsHumanAction, LogisticsNotice]:
    """Create one deterministic late customer contact action and recovery candidate."""

    runtime, plan, request, vehicle, _ = await _generated_day(session, alternatives=0)
    customer_stop = next(
        stop for cycle in plan.cycles for stop in cycle.stops if stop.task_id is not None
    )
    local_arrival = customer_stop.planned_arrival.astimezone(ZONE)
    option = next(item for item in request.date_options if item.date == PLANNING_DATE)
    option.window_start = (local_arrival - timedelta(minutes=30)).timetz().replace(tzinfo=None)
    option.window_end = (local_arrival + timedelta(minutes=20)).timetz().replace(tzinfo=None)
    await session.flush()
    service = DynamicLogisticsService(runtime, None)
    result = await service.register_event(
        session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.VEHICLE_DELAY,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            vehicle_id=vehicle.id,
            occurred_at=customer_stop.planned_arrival - timedelta(minutes=5),
            delay_minutes=90,
            reason="Пробка",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"late-delay-{uuid4()}",
    )
    assert len(result.actions) == 1
    action = await session.get(LogisticsHumanAction, result.actions[0].id)
    notice = await session.get(LogisticsNotice, result.notices[0].id)
    assert action is not None and notice is not None
    return service, action, notice


async def _local_reschedule_case(
    session: AsyncSession,
    *,
    alternatives: int = 2,
) -> tuple[
    DynamicLogisticsService,
    LogisticsRequest,
    LogisticsHumanAction,
    RecoveryProposal,
]:
    """Create one local dated commitment that needs customer-approved rescheduling."""

    warehouse = await make_warehouse(session, default_planning_date=PLANNING_DATE)
    request = await _make_request_type(
        session,
        warehouse,
        "DELIVERY",
        PLANNING_DATE,
        name="Local customer delivery",
    )
    request.scheduled_date = PLANNING_DATE
    for offset in range(1, alternatives + 1):
        request.date_options.append(
            RequestDateOption(
                date=PLANNING_DATE + timedelta(days=offset),
                priority=offset,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        )
    planner = _FeasiblePlanner()
    service = DynamicLogisticsService(planner, None)  # type: ignore[arg-type]
    await service.set_day_mode(
        session,
        warehouse.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=None,
        expected_plan_version=None,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"local-reschedule-{alternatives}",
    )
    action = await session.scalar(
        select(LogisticsHumanAction).where(
            LogisticsHumanAction.action_type == "AGREE_RESCHEDULE",
            LogisticsHumanAction.request_id == request.id,
        )
    )
    proposal = (
        await session.scalar(
            select(RecoveryProposal).where(RecoveryProposal.action_id == action.id)
        )
        if action is not None
        else None
    )
    assert action is not None and proposal is not None
    return service, request, action, proposal


@pytest.mark.asyncio
async def test_empty_day_mode_is_silent_but_audited(db_session: AsyncSession) -> None:
    """An empty future day stores its optimizer constraint without fake operator work."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    planner = _FeasiblePlanner()
    result = await DynamicLogisticsService(planner, None).set_day_mode(  # type: ignore[arg-type]
        db_session,
        warehouse.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=None,
        expected_plan_version=None,
        mode=PlanningDayMode.DELIVERIES_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="empty-mode",
    )

    assert result.conflict_count == 0
    assert result.pending_action_count == 0
    assert await db_session.scalar(select(func.count(LogisticsEvent.id))) == 1
    assert await db_session.scalar(select(func.count(LogisticsNotice.id))) == 0
    assert await db_session.scalar(select(func.count(LogisticsHumanAction.id))) == 0
    policy = await db_session.scalar(select(PlanningDayPolicy))
    assert policy is not None and policy.mode == PlanningDayMode.DELIVERIES_ONLY
    assert planner.preview_calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("mode", "allowed_type", "blocked_type"),
    [
        (PlanningDayMode.DELIVERIES_ONLY, "DELIVERY", "PICKUP"),
        (PlanningDayMode.PICKUPS_ONLY, "PICKUP", "DELIVERY"),
    ],
)
async def test_day_mode_is_a_real_optimizer_constraint_in_both_directions(
    db_session: AsyncSession,
    mode: PlanningDayMode,
    allowed_type: str,
    blocked_type: str,
) -> None:
    """The runtime optimizer receives only the request direction permitted by policy."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    allowed = await _make_request_type(
        db_session,
        warehouse,
        allowed_type,
        PLANNING_DATE,
        name=f"Allowed {allowed_type}",
    )
    blocked = await _make_request_type(
        db_session,
        warehouse,
        blocked_type,
        PLANNING_DATE,
        name=f"Blocked {blocked_type}",
    )
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    await DynamicLogisticsService(_FeasiblePlanner(), None).set_day_mode(  # type: ignore[arg-type]
        db_session,
        warehouse.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=None,
        expected_plan_version=None,
        mode=mode,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"optimizer-mode-{mode}",
    )

    run = await RuntimePlannerFacade().generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=warehouse.seed),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(db_session, run.plan_id)
    assigned_request_ids = {
        stop.task.request_id
        for cycle in plan.cycles
        for stop in cycle.stops
        if stop.task is not None
    }
    assert allowed.id in assigned_request_ids
    assert blocked.id not in assigned_request_ids
    assert blocked.status == RequestStatus.READY


@pytest.mark.asyncio
async def test_mode_change_fences_the_active_plan_head(db_session: AsyncSession) -> None:
    """A filled day cannot change policy without the exact current plan id and version."""

    _, plan, _, _, _ = await _generated_day(db_session)
    service = DynamicLogisticsService(_FeasiblePlanner(), None)  # type: ignore[arg-type]

    with pytest.raises(ApiError) as missing:
        await service.set_day_mode(
            db_session,
            plan.warehouse_id,
            PLANNING_DATE,
            expected_version=0,
            plan_id=None,
            expected_plan_version=None,
            mode=PlanningDayMode.PICKUPS_ONLY,
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="missing-plan-fence",
        )
    assert missing.value.code == "PLANNING_DAY_PLAN_VERSION_CONFLICT"

    with pytest.raises(ApiError) as stale:
        await service.set_day_mode(
            db_session,
            plan.warehouse_id,
            PLANNING_DATE,
            expected_version=0,
            plan_id=plan.id,
            expected_plan_version=plan.version + 1,
            mode=PlanningDayMode.PICKUPS_ONLY,
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="stale-plan-fence",
        )
    assert stale.value.code == "PLANNING_DAY_PLAN_VERSION_CONFLICT"
    assert await db_session.scalar(select(func.count(PlanningDayPolicy.id))) == 0


@pytest.mark.asyncio
async def test_unassigned_and_committed_without_plan_are_real_mode_conflicts(
    db_session: AsyncSession,
) -> None:
    """A dated commitment is not considered an empty day merely because routing failed."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    pickup = await _make_request_type(
        db_session,
        warehouse,
        "PICKUP",
        PLANNING_DATE,
        name="Committed pickup",
    )
    runtime = RuntimePlannerFacade()
    run = await runtime.generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=warehouse.seed),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(db_session, run.plan_id)
    assert any(item.task.request_id == pickup.id for item in plan.unassigned_tasks)
    result = await DynamicLogisticsService(_FeasiblePlanner(), None).set_day_mode(  # type: ignore[arg-type]
        db_session,
        warehouse.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.DELIVERIES_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="unassigned-mode-conflict",
    )
    assert result.conflict_count == 1
    assert any(item.task.request_id == pickup.id for item in plan.unassigned_tasks)

    other_day = PLANNING_DATE + timedelta(days=5)
    committed = await _make_request_type(
        db_session,
        warehouse,
        "PICKUP",
        other_day,
        name="No-plan committed pickup",
    )
    committed.scheduled_date = other_day
    await db_session.flush()
    second = await DynamicLogisticsService(_FeasiblePlanner(), None).set_day_mode(  # type: ignore[arg-type]
        db_session,
        warehouse.id,
        other_day,
        expected_version=0,
        plan_id=None,
        expected_plan_version=None,
        mode=PlanningDayMode.DELIVERIES_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="no-plan-committed-conflict",
    )
    assert second.conflict_count == 1
    assert committed.status == RequestStatus.READY


@pytest.mark.asyncio
async def test_representative_demand_uses_one_root_group_day_policy_and_plan_fence(
    db_session: AsyncSession,
) -> None:
    """Header selection may be regional, but operations fence and constrain the root plan."""

    root = await make_warehouse(db_session, name="Main", default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(
        db_session,
        name="Representative",
        default_planning_date=PLANNING_DATE,
    )
    representative.representative = True
    regional_delivery = await _make_request_type(
        db_session,
        representative,
        "DELIVERY",
        PLANNING_DATE,
        name="Regional delivery",
    )
    driver = await make_driver(db_session, root)
    vehicle = await make_vehicle(db_session, root)
    await make_shift(
        db_session,
        root,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    client = _PlanningGroupClient(_support_link(root, representative))
    runtime = RuntimePlannerFacade(rwms_client=client)  # type: ignore[arg-type]
    run = await runtime.generate_plan(
        db_session,
        root.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=root.seed),
    )
    assert run.plan_id is not None
    root_plan = await plans.get_plan(db_session, run.plan_id)
    assert root_plan.warehouse_id == root.id
    assert any(
        stop.task is not None and stop.task.request_id == regional_delivery.id
        for cycle in root_plan.cycles
        for stop in cycle.stops
    )
    service = DynamicLogisticsService(_FeasiblePlanner(), client)  # type: ignore[arg-type]
    result = await service.set_day_mode(
        db_session,
        root.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=root_plan.id,
        expected_plan_version=root_plan.version,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="root-group-mode",
    )
    assert result.conflict_count == 1
    policy = await db_session.scalar(select(PlanningDayPolicy))
    assert policy is not None and policy.warehouse_id == root.id
    snapshot = await runtime._load_snapshot(
        db_session,
        root.id,
        PLANNING_DATE,
        None,
        None,
    )
    assert str(regional_delivery.id) not in snapshot.request_task_uuids

    with pytest.raises(ApiError) as representative_scope:
        await service.set_day_mode(
            db_session,
            representative.id,
            PLANNING_DATE,
            expected_version=0,
            plan_id=root_plan.id,
            expected_plan_version=root_plan.version,
            mode=PlanningDayMode.PICKUPS_ONLY,
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="representative-direct-mode",
        )
    assert representative_scope.value.code == "PLANNING_ROOT_REQUIRED"


@pytest.mark.asyncio
async def test_filled_day_conflict_remains_in_source_plan_and_creates_work(
    db_session: AsyncSession,
) -> None:
    """Changing a filled day never silently removes an incompatible client commitment."""

    _, plan, request, _, _ = await _generated_day(db_session)
    request.client_type = "LEGAL_ENTITY"
    request.contact_name = "ООО «СтройМонтаж»"  # noqa: RUF001
    request.contact_phone = "+7 921 000-00-00"
    await db_session.flush()
    planner = _FeasiblePlanner()
    result = await DynamicLogisticsService(planner, None).set_day_mode(  # type: ignore[arg-type]
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="filled-mode",
    )

    assert result.conflict_count == 1
    assert result.pending_action_count == 1
    source = await plans.get_plan(db_session, plan.id)
    assert any(
        stop.task is not None and stop.task.request_id == request.id
        for cycle in source.cycles
        for stop in cycle.stops
    )
    action = await db_session.scalar(select(LogisticsHumanAction))
    proposal = await db_session.scalar(select(RecoveryProposal))
    assert action is not None and action.status == LogisticsActionStatus.PENDING
    assert action.customer_type == "LEGAL_ENTITY"
    assert action.customer_name == "ООО «СтройМонтаж»"  # noqa: RUF001
    assert action.customer_phone == "+7 921 000-00-00"
    assert proposal is not None and proposal.status == RecoveryProposalStatus.PROPOSED
    assert action.recommended_date == PLANNING_DATE + timedelta(days=1)


@pytest.mark.asyncio
async def test_rejected_date_is_immutable_and_reruns_next_suggestion(
    db_session: AsyncSession,
) -> None:
    """A rejection preserves history, fences the proposal, and recommends the next date."""

    _, plan, _, _, _ = await _generated_day(db_session)
    planner = _FeasiblePlanner()
    service = DynamicLogisticsService(planner, None)  # type: ignore[arg-type]
    await service.set_day_mode(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="mode-before-reject",
    )
    action = await db_session.scalar(select(LogisticsHumanAction))
    proposal = await db_session.scalar(select(RecoveryProposal))
    assert action is not None and proposal is not None
    command = LogisticsHumanDecisionCreate(
        expected_version=action.version,
        expected_proposal_version=proposal.version,
        decision_type="REJECT",
        comment="Клиенту дата не подходит",
    )

    first = await service.decide_action(
        db_session,
        action.id,
        command,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="reject-date",
    )
    replay = await service.decide_action(
        db_session,
        action.id,
        command,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="reject-date",
    )

    assert replay.id == first.id
    assert action.recommended_date == PLANNING_DATE + timedelta(days=2)
    proposals = tuple(await db_session.scalars(select(RecoveryProposal)))
    assert {item.status for item in proposals} == {
        RecoveryProposalStatus.REJECTED,
        RecoveryProposalStatus.PROPOSED,
    }
    assert await db_session.scalar(select(func.count(LogisticsHumanDecision.id))) == 1
    with pytest.raises(ApiError, match="Ключ повтора"):
        await service.decide_action(
            db_session,
            action.id,
            command.model_copy(update={"comment": "Другой ответ"}),
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="reject-date",
        )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("decision_type", "selected_offset"),
    [
        ("ACCEPT_RECOMMENDATION", None),
        ("ACCEPT_OTHER_DATE", 2),
    ],
)
async def test_customer_agreement_and_plan_application_are_distinct_idempotent_states(
    db_session: AsyncSession,
    decision_type: str,
    selected_offset: int | None,
) -> None:
    """Agreement only stores a constraint; applying later updates the local owner atomically."""

    service, request, action, proposal = await _local_reschedule_case(db_session)
    original_version = request.version
    command = LogisticsHumanDecisionCreate(
        expected_version=action.version,
        expected_proposal_version=proposal.version,
        decision_type=decision_type,
        selected_date=(
            PLANNING_DATE + timedelta(days=selected_offset) if selected_offset is not None else None
        ),
        comment="Дата согласована",
    )
    await service.decide_action(
        db_session,
        action.id,
        command,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"agree-{decision_type}",
    )

    assert proposal.status == RecoveryProposalStatus.CUSTOMER_AGREED
    assert request.scheduled_date == PLANNING_DATE
    expected_date = PLANNING_DATE + timedelta(days=selected_offset or 1)
    apply_expected_version = proposal.version
    applied = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=apply_expected_version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key=f"apply-{decision_type}",
    )
    replay = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=apply_expected_version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key=f"apply-{decision_type}",
    )

    assert applied.status == RecoveryProposalStatus.APPLIED
    assert replay.id == applied.id
    assert request.scheduled_date == expected_date
    assert request.version == original_version + 1
    with pytest.raises(ApiError) as reused:
        await service.apply_proposal(
            db_session,
            proposal.id,
            expected_version=applied.version,
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
            idempotency_key="different-apply-key",
        )
    assert reused.value.code == "RECOVERY_ALREADY_APPLIED"


@pytest.mark.asyncio
async def test_unreachable_and_invalid_customer_decisions_never_apply_changes(
    db_session: AsyncSession,
) -> None:
    """Unreachable and unoffered dates preserve the original commitment and action queue."""

    service, request, action, proposal = await _local_reschedule_case(db_session)
    with pytest.raises(ApiError) as invalid:
        await service.decide_action(
            db_session,
            action.id,
            LogisticsHumanDecisionCreate(
                expected_version=action.version,
                expected_proposal_version=proposal.version,
                decision_type="ACCEPT_OTHER_DATE",
                selected_date=PLANNING_DATE + timedelta(days=9),
            ),
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="invalid-other-date",
        )
    assert invalid.value.code == "RESCHEDULE_DATE_NOT_OFFERED"
    assert await db_session.scalar(select(func.count(LogisticsHumanDecision.id))) == 0

    await service.decide_action(
        db_session,
        action.id,
        LogisticsHumanDecisionCreate(
            expected_version=action.version,
            expected_proposal_version=proposal.version,
            decision_type="UNREACHABLE",
            comment="Абонент не отвечает",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="customer-unreachable",
    )
    assert action.status == LogisticsActionStatus.PENDING
    assert proposal.status == RecoveryProposalStatus.PROPOSED
    assert request.scheduled_date == PLANNING_DATE


@pytest.mark.asyncio
async def test_reject_without_a_recommendation_is_fail_closed(db_session: AsyncSession) -> None:
    """A dispatcher cannot create an empty rejection that adds no durable constraint."""

    service, _, action, proposal = await _local_reschedule_case(
        db_session,
        alternatives=0,
    )
    assert action.recommended_date is None
    with pytest.raises(ApiError) as rejected:
        await service.decide_action(
            db_session,
            action.id,
            LogisticsHumanDecisionCreate(
                expected_version=action.version,
                expected_proposal_version=proposal.version,
                decision_type="REJECT",
            ),
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="reject-nothing",
        )
    assert rejected.value.code == "NO_RECOMMENDATION_TO_REJECT"
    assert await db_session.scalar(select(func.count(LogisticsHumanDecision.id))) == 0


@pytest.mark.asyncio
async def test_mode_supersession_obsoletes_only_old_mode_work_and_keeps_rejected_date(
    db_session: AsyncSession,
) -> None:
    """Mode round-trips do not leave stale actions or recommend a date rejected earlier."""

    _, plan, _, _, _ = await _generated_day(db_session)
    service = DynamicLogisticsService(_FeasiblePlanner(), None)  # type: ignore[arg-type]
    await service.set_day_mode(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="mode-a",
    )
    original_action = await db_session.scalar(select(LogisticsHumanAction))
    original_proposal = await db_session.scalar(select(RecoveryProposal))
    assert original_action is not None and original_proposal is not None
    rejected_date = original_action.recommended_date
    await service.decide_action(
        db_session,
        original_action.id,
        LogisticsHumanDecisionCreate(
            expected_version=original_action.version,
            expected_proposal_version=original_proposal.version,
            decision_type="REJECT",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="mode-a-reject",
    )
    await service.set_day_mode(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        expected_version=1,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.DELIVERIES_AND_PICKUPS,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="mode-mixed",
    )
    assert original_action.status == LogisticsActionStatus.OBSOLETE
    await service.set_day_mode(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        expected_version=2,
        plan_id=plan.id,
        expected_plan_version=plan.version,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="mode-b",
    )
    pending = await db_session.scalar(
        select(LogisticsHumanAction)
        .where(LogisticsHumanAction.status == LogisticsActionStatus.PENDING)
        .order_by(LogisticsHumanAction.created_at.desc())
    )
    assert pending is not None
    assert pending.recommended_date != rejected_date
    decisions = tuple(await db_session.scalars(select(LogisticsHumanDecision)))
    assert decisions[0].selected_date == rejected_date


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("delay_minutes", "end_offset_minutes", "reason", "action_count"),
    [
        (10, 90, "ETA_IN_SLOT", 0),
        (40, 60, "ETA_RISK", 0),
        (90, 60, "ETA_LATE", 1),
    ],
)
async def test_delay_classification_uses_persisted_stop_window_facts(
    db_session: AsyncSession,
    delay_minutes: int,
    end_offset_minutes: int,
    reason: str,
    action_count: int,
) -> None:
    """Delay analysis distinguishes safe, risk, and late without IN_SLOT action noise."""

    runtime, plan, request, vehicle, _ = await _generated_day(db_session, alternatives=0)
    customer_stop = next(
        stop for cycle in plan.cycles for stop in cycle.stops if stop.task_id is not None
    )
    local_arrival = customer_stop.planned_arrival.astimezone(ZONE)
    option = next(item for item in request.date_options if item.date == PLANNING_DATE)
    option.window_start = (local_arrival - timedelta(minutes=60)).timetz().replace(tzinfo=None)
    option.window_end = (
        (local_arrival + timedelta(minutes=end_offset_minutes)).timetz().replace(tzinfo=None)
    )
    await db_session.flush()

    result = await DynamicLogisticsService(runtime, None).register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.VEHICLE_DELAY,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            vehicle_id=vehicle.id,
            occurred_at=customer_stop.planned_arrival - timedelta(minutes=5),
            delay_minutes=delay_minutes,
            reason="Пробка",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"delay-{reason}",
    )

    assert reason in result.notices[0].reason_codes
    assert len(result.actions) == action_count


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "decision_type",
    ["ACCEPT_DELAY", "REJECT_DELAY", "UNREACHABLE"],
)
async def test_delay_contact_decisions_have_a_complete_queue_lifecycle(
    db_session: AsyncSession,
    decision_type: str,
) -> None:
    """Delay contact outcomes resolve, replan, or remain retryable without changing a slot."""

    service, action, notice = await _late_delay_case(db_session)
    proposal = await db_session.scalar(
        select(RecoveryProposal)
        .where(
            RecoveryProposal.event_id == action.event_id,
            RecoveryProposal.proposal_type == "PARTIAL_REPLAN_DELAY",
        )
        .with_for_update()
    )
    assert proposal is not None
    decision = await service.decide_action(
        db_session,
        action.id,
        LogisticsHumanDecisionCreate(
            expected_version=action.version,
            expected_proposal_version=proposal.version,
            decision_type=decision_type,
            comment="Результат разговора",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key=f"delay-decision-{decision_type}",
    )

    assert decision.constraint_data["window_end"] == action.context["window_end"]
    assert decision.constraint_data["new_eta"] == action.context["new_eta"]
    if decision_type == "ACCEPT_DELAY":
        assert action.status == LogisticsActionStatus.RESOLVED
        assert notice.status == "ACCEPTED"
    elif decision_type == "UNREACHABLE":
        assert action.status == LogisticsActionStatus.PENDING
        assert (await db_session.get(LogisticsNotice, action.notice_id)).notice_type == (
            "DELAY_CUSTOMER_UNREACHABLE"
        )
    else:
        assert action.status == LogisticsActionStatus.RESOLVED
        followup = await db_session.scalar(
            select(LogisticsHumanAction).where(
                LogisticsHumanAction.event_id == action.event_id,
                LogisticsHumanAction.id != action.id,
            )
        )
        assert followup is not None
        assert followup.action_type == "APPLY_PARTIAL_REPLAN"
        proposal = await db_session.scalar(
            select(RecoveryProposal).where(RecoveryProposal.action_id == followup.id)
        )
        assert proposal is not None
        assert proposal.proposal_type == "PARTIAL_REPLAN_DELAY"
        assert proposal.changes["preserve_original_window"] is True


@pytest.mark.asyncio
async def test_decision_kinds_are_scoped_to_their_action_family(
    db_session: AsyncSession,
) -> None:
    """Date decisions cannot close delay contacts and delay decisions cannot move dates."""

    delay_service, delay_action, _ = await _late_delay_case(db_session)
    with pytest.raises(ApiError) as invalid_delay:
        await delay_service.decide_action(
            db_session,
            delay_action.id,
            LogisticsHumanDecisionCreate(
                expected_version=delay_action.version,
                decision_type="ACCEPT_RECOMMENDATION",
            ),
            actor="dispatcher",
            idempotency_key="wrong-delay-decision",
        )
    assert invalid_delay.value.code == "DECISION_NOT_ALLOWED_FOR_ACTION"

    reschedule_service, _, reschedule_action, proposal = await _local_reschedule_case(db_session)
    with pytest.raises(ApiError) as invalid_reschedule:
        await reschedule_service.decide_action(
            db_session,
            reschedule_action.id,
            LogisticsHumanDecisionCreate(
                expected_version=reschedule_action.version,
                expected_proposal_version=proposal.version,
                decision_type="ACCEPT_DELAY",
            ),
            actor="dispatcher",
            idempotency_key="wrong-reschedule-decision",
        )
    assert invalid_reschedule.value.code == "DECISION_NOT_ALLOWED_FOR_ACTION"


@pytest.mark.asyncio
async def test_driver_unavailable_is_day_scoped_and_breakdown_has_recovery_path(
    db_session: AsyncSession,
) -> None:
    """Driver incidents keep recurring shifts intact while broken vehicles require reactivation."""

    runtime, plan, _, vehicle, shift = await _generated_day(db_session)
    service = DynamicLogisticsService(_FeasiblePlanner(), None)  # type: ignore[arg-type]
    await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.DRIVER_UNAVAILABLE,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            driver_shift_id=shift.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Водитель заболел",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="driver-unavailable",
    )
    assert shift.active is True
    incident_snapshot = await runtime._load_snapshot(
        db_session, plan.warehouse_id, PLANNING_DATE, None, None
    )
    next_snapshot = await runtime._load_snapshot(
        db_session, plan.warehouse_id, PLANNING_DATE + timedelta(days=1), None, None
    )
    assert not next(
        item for item in incident_snapshot.input_data.shifts if item.id == str(shift.id)
    ).active
    assert next(item for item in next_snapshot.input_data.shifts if item.id == str(shift.id)).active

    await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.VEHICLE_BREAKDOWN,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            vehicle_id=vehicle.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(10), tzinfo=ZONE),
            reason="Поломка двигателя",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="vehicle-breakdown",
    )
    assert vehicle.active is False
    await catalog.update_vehicle(
        db_session,
        vehicle.id,
        VehicleUpdate(expected_version=vehicle.version, active=True),
    )
    assert vehicle.active is True


@pytest.mark.asyncio
async def test_cancellation_never_pulls_future_delivery_and_base_work_stays_low_priority(
    db_session: AsyncSession,
) -> None:
    """With no same-day pickup, cancellation only suggests existing base work after return."""

    _, plan, request, _, _ = await _generated_day(db_session, alternatives=0)
    base_task = RwmsPlanningBaseTask(
        taskId=uuid4(),
        externalTaskId=uuid4(),
        kind="DELIVER_TO_REPAIR",
        unitNumber="450",
        summary="Переместить бытовку №450 в ремонт",
        scheduledDate=PLANNING_DATE,
        priority=4,
        state="SCHEDULED",
    )
    rwms = SimpleNamespace(list_base_tasks=lambda *args, **kwargs: None)

    async def list_base_tasks(*args: object, **kwargs: object) -> list[RwmsPlanningBaseTask]:
        """Return one already-existing RWA task without creating planner state."""

        del args, kwargs
        return [base_task]

    async def list_support_network(*args: object, **kwargs: object) -> list[object]:
        """Keep the standalone warehouse as its own planning root in this test."""

        del args, kwargs
        return []

    rwms.list_base_tasks = list_base_tasks
    rwms.list_support_network = list_support_network
    result = await DynamicLogisticsService(  # type: ignore[arg-type]
        _FeasiblePlanner(),
        rwms,  # type: ignore[arg-type]
    ).register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.DELIVERY_CANCELLED,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            request_id=request.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(13), tzinfo=ZONE),
            reason="Клиент отменил доставку",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="cancel-delivery",
    )

    proposal = result.proposals[0]
    assert proposal.metrics["future_deliveries_pulled"] == 0
    assert proposal.metrics["same_day_pickup_candidate_count"] == 0
    assert proposal.metrics["feasible_same_day_pickup_count"] == 0
    assert proposal.metrics["base_task_count"] == 1
    base_group = result.notices[0].facts["base_task_groups"][0]
    assert base_group["warehouse_id"] == str(plan.warehouse_id)
    assert base_group["tasks"][0]["externalTaskId"] == str(base_task.external_task_id)


@pytest.mark.asyncio
async def test_owner_cancelled_published_task_creates_fenced_recovery_impact(
    db_session: AsyncSession,
) -> None:
    """Owner CANCELLED authorizes removal while retaining the exact task revision fence."""

    service, _, plan, request, assignment = await _confirmed_rwms_day(
        db_session,
        owner_state="CANCELLED",
    )
    result = await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.DELIVERY_CANCELLED,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            request_id=request.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Клиент отменил опубликованную доставку",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="owner-cancelled-impact",
    )

    assert len(result.proposals) == 1
    proposal = result.proposals[0]
    assert proposal.proposal_type == "PARTIAL_REPLAN_CANCELLATION"
    snapshot = proposal.changes["owner_cancellation_snapshot"]
    task = request.tasks[0]
    assert snapshot[str(task.id)] == {
        "external_task_id": str(assignment.external_task_id),
        "task_version": assignment.task_version,
        "task_state": "CANCELLED",
    }
    assert request.status == RequestStatus.PLANNED


@pytest.mark.asyncio
async def test_owner_cancelled_published_task_applies_through_withdrawal_saga(
    db_session: AsyncSession,
) -> None:
    """A published cancellation is tombstoned before the hidden local revision becomes active."""

    service, client, plan, request, assignment = await _confirmed_rwms_day(
        db_session,
        owner_state="CANCELLED",
    )
    impact = await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.DELIVERY_CANCELLED,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            request_id=request.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Клиент отменил опубликованную доставку",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="owner-cancelled-apply-impact",
    )
    proposal = impact.proposals[0]
    source_plan_version = plan.version

    applied = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=proposal.version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key="owner-cancelled-apply",
    )

    assert applied.status == RecoveryProposalStatus.APPLIED
    assert applied.result_plan_id is not None
    assert request.status == RequestStatus.CANCELLED
    assert all(task.status == TaskStatus.CANCELLED for task in request.tasks)
    assert len(client.cancellation_withdrawal_commands) == 1
    source_plan_id, _, withdrawal = client.cancellation_withdrawal_commands[0]
    assert source_plan_id == plan.id
    assert withdrawal.removed_assignment.external_task_id == assignment.external_task_id
    assert withdrawal.expected_source_plan_version == source_plan_version
    assert withdrawal.replacement_plan_version == source_plan_version + 1
    assert withdrawal.remaining_assignments == []
    assert client.replacement_commands == []
    prepared = applied.changes["prepared_recovery"]
    assert prepared["owner_operation"] == "WITHDRAW_CANCELLATION"
    receipt = applied.changes["owner_cancellation_withdrawal_receipt"]
    assert receipt["removedExternalTaskId"] == str(assignment.external_task_id)
    assert receipt["state"] == "COMPLETE"


@pytest.mark.asyncio
async def test_owner_completed_task_cannot_be_cancelled(
    db_session: AsyncSession,
) -> None:
    """COMPLETED remains immutable and is never conflated with an owner cancellation."""

    service, _, plan, request, _ = await _confirmed_rwms_day(
        db_session,
        owner_state="COMPLETED",
    )
    with pytest.raises(ApiError) as completed:
        async with db_session.begin_nested():
            await service.register_event(
                db_session,
                plan.warehouse_id,
                PLANNING_DATE,
                LogisticsEventCreate(
                    event_type=LogisticsEventType.DELIVERY_CANCELLED,
                    plan_id=plan.id,
                    expected_plan_version=plan.version,
                    request_id=request.id,
                    occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
                    reason="Ошибочная отмена выполненного задания",
                ),
                actor="dispatcher<00000000-0000-0000-0000-000000000001>",
                idempotency_key="owner-completed-reject",
            )
    assert completed.value.code == "REQUEST_ALREADY_EXECUTED"
    assert await db_session.scalar(select(func.count(LogisticsEvent.id))) == 0


@pytest.mark.asyncio
async def test_owner_cancellation_revision_change_fails_closed_before_local_mutation(
    db_session: AsyncSession,
) -> None:
    """A changed task revision between analysis and apply cannot remove local work."""

    service, client, plan, request, assignment = await _confirmed_rwms_day(
        db_session,
        owner_state="CANCELLED",
    )
    result = await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.DELIVERY_CANCELLED,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            request_id=request.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Клиент отменил опубликованную доставку",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="owner-cancel-race-impact",
    )
    proposal = result.proposals[0]
    service_warehouse = await db_session.get(Warehouse, request.warehouse_id)
    assert service_warehouse is not None
    source = RwmsPlanningRequest.model_validate(request.external_payload)
    client.assignments[service_warehouse.external_warehouse_id] = [
        _owner_assignment(
            source,
            state="CURRENT",
            task_version=assignment.task_version + 1,
            external_task_id=assignment.external_task_id,
        )
    ]

    failed = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=proposal.version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key="owner-cancel-race-apply",
    )

    assert failed.status == RecoveryProposalStatus.FAILED
    assert failed.failure_code == "RWMS_CANCELLATION_STATE_CHANGED"
    assert request.status == RequestStatus.PLANNED
    assert all(task.status == TaskStatus.PLANNED for task in request.tasks)
    assert plan.status == PlanStatus.CONFIRMED


@pytest.mark.asyncio
async def test_root_recovery_reads_authoritative_states_from_every_service_warehouse(
    db_session: AsyncSession,
) -> None:
    """A root plan never infers representative execution state from the root feed."""

    root = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(
        db_session,
        default_planning_date=PLANNING_DATE,
        name="Великий Новгород",
    )
    representative.representative = True
    root_request = await _make_request_type(
        db_session,
        root,
        "DELIVERY",
        PLANNING_DATE,
        name="Root RWMS delivery",
    )
    representative_request = await _make_request_type(
        db_session,
        representative,
        "DELIVERY",
        PLANNING_DATE,
        name="Representative RWMS delivery",
    )
    sources = {
        root_request.id: _mark_as_rwms_request(root_request, root),
        representative_request.id: _mark_as_rwms_request(representative_request, representative),
    }
    driver = await make_driver(db_session, root)
    vehicle = await make_vehicle(db_session, root)
    await make_shift(
        db_session,
        root,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    link = _support_link(root, representative)
    client = _GroupStatusClient(link, {})
    runtime = RuntimePlannerFacade(rwms_client=client)  # type: ignore[arg-type]
    run = await runtime.generate_plan(
        db_session,
        root.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=root.seed),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(db_session, run.plan_id)
    tasks = {
        stop.task.request_id: stop.task
        for cycle in plan.cycles
        for stop in cycle.stops
        if stop.task is not None
    }
    tasks.update({item.task.request_id: item.task for item in plan.unassigned_tasks})
    service_warehouse_by_request = {
        root_request.id: root,
        representative_request.id: representative,
    }
    for request in (root_request, representative_request):
        source = sources[request.id]
        service_warehouse = service_warehouse_by_request[request.id]
        client.assignments.setdefault(service_warehouse.external_warehouse_id, []).append(
            RwmsPlanningAssignmentStatus(
                orderId=source.order_id,
                orderVersion=source.order_version,
                documentId=uuid4(),
                externalTaskId=uuid4(),
                taskVersion=1,
                sourcePlanId=plan.id,
                sourcePlanVersion=plan.version,
                scheduledDate=PLANNING_DATE,
                unitIds=source.unit_ids,
                driverAudienceMode="WAREHOUSE_DRIVERS",
                driverWorkerId=None,
                driverName=None,
                taskState="SCHEDULED",
            )
        )
    target_task = tasks[representative_request.id]
    result = await DynamicLogisticsService(runtime, client).register_event(  # type: ignore[arg-type]
        db_session,
        root.id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.TASK_BLOCKED,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            task_id=target_task.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Подъезд перекрыт",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="group-owner-state",
    )

    assert set(client.status_calls) == {
        root.external_warehouse_id,
        representative.external_warehouse_id,
    }
    assert result.proposals[0].proposal_type == "PARTIAL_REPLAN_TASK_BLOCKED"


@pytest.mark.asyncio
async def test_group_day_operations_require_grants_for_every_member(
    db_session: AsyncSession,
) -> None:
    """Root access alone cannot reveal representative contacts or authorize commands."""

    root = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(
        db_session,
        default_planning_date=PLANNING_DATE,
        name="Великий Новгород",
    )
    representative.representative = True
    client = _PlanningGroupClient(_support_link(root, representative))

    with pytest.raises(ApiError) as denied:
        await require_dynamic_day_access(
            db_session,
            _warehouse_principal(root),
            client,  # type: ignore[arg-type]
            root.id,
            PLANNING_DATE,
            WarehouseAccessLevel.VIEW,
        )
    assert denied.value.code == "ACCESS_DENIED"

    allowed = await require_dynamic_day_access(
        db_session,
        _warehouse_principal(root, representative),
        client,  # type: ignore[arg-type]
        root.id,
        PLANNING_DATE,
        WarehouseAccessLevel.EDIT,
    )
    assert allowed.id == root.id
    admin = CurrentUserPrincipal.from_claims(
        user_claims(global_role="SYSTEM_ADMIN"),
        panel_client_id="rwms-panel",
    )
    assert (
        await require_dynamic_day_access(
            db_session,
            admin,
            client,  # type: ignore[arg-type]
            root.id,
            PLANNING_DATE,
            WarehouseAccessLevel.EDIT,
        )
    ).id == root.id


@pytest.mark.asyncio
async def test_root_day_mode_queues_and_filters_capacity_for_every_group_member(
    db_session: AsyncSession,
) -> None:
    """PICKUPS_ONLY removes delivery shifts from root and representative publications."""

    root = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(
        db_session,
        default_planning_date=PLANNING_DATE,
        name="Великий Новгород",
    )
    representative.representative = True
    for warehouse in (root, representative):
        driver = await make_driver(db_session, warehouse)
        vehicle = await make_vehicle(db_session, warehouse)
        await make_shift(
            db_session,
            warehouse,
            driver,
            vehicle,
            date_from=PLANNING_DATE,
            date_to=PLANNING_DATE,
        )
    client = _PlanningGroupClient(_support_link(root, representative))
    service = DynamicLogisticsService(_FeasiblePlanner(), client)  # type: ignore[arg-type]
    before = {warehouse.id: warehouse.capacity_generation for warehouse in (root, representative)}
    await service.set_day_mode(
        db_session,
        root.id,
        PLANNING_DATE,
        expected_version=0,
        plan_id=None,
        expected_plan_version=None,
        mode=PlanningDayMode.PICKUPS_ONLY,
        actor="dispatcher",
        idempotency_key="group-pickups-only-capacity",
    )

    for warehouse in (root, representative):
        assert warehouse.capacity_generation > before[warehouse.id]
        assert str(warehouse.capacity_publish_status) == "PENDING"
        projection = await build_capacity_projection(
            db_session,
            warehouse.id,
            client,  # type: ignore[arg-type]
        )
        assert not any(shift.delivery_date == PLANNING_DATE for shift in projection.command.shifts)

    await service.set_day_mode(
        db_session,
        root.id,
        PLANNING_DATE,
        expected_version=1,
        plan_id=None,
        expected_plan_version=None,
        mode=PlanningDayMode.DELIVERIES_ONLY,
        actor="dispatcher",
        idempotency_key="group-deliveries-only-capacity",
    )
    for warehouse in (root, representative):
        projection = await build_capacity_projection(
            db_session,
            warehouse.id,
            client,  # type: ignore[arg-type]
        )
        assert any(shift.delivery_date == PLANNING_DATE for shift in projection.command.shifts)


@pytest.mark.asyncio
async def test_confirmed_recovery_replays_owner_commit_and_activates_prepared_revision(
    db_session: AsyncSession,
) -> None:
    """A lost owner response keeps the source active and resumes the exact prepared command."""

    service, client, plan, _, assignment = await _confirmed_rwms_day(
        db_session,
        owner_state="SCHEDULED",
    )
    spare = await make_vehicle(db_session, plan.warehouse)
    worker_id = uuid4()
    spare_driver = await catalog.create_driver(
        db_session, plan.warehouse_id,
        DriverCreate(rwms_assignment_mode="ASSIGNED_DRIVER", external_worker_id=worker_id),
        RwmsDriverIdentity(
            workerId=worker_id, displayName="Spare driver", employmentType="STAFF", phone=None,
            operationalWarehouseId=plan.warehouse.external_warehouse_id,
            availableFrom=None, availableUntil=None, availabilityKind="HOME",
        ),
    )
    await make_shift(
        db_session, plan.warehouse, spare_driver, spare,
        date_from=PLANNING_DATE, date_to=PLANNING_DATE,
    )
    vehicle_id = plan.cycles[0].driver_shift.vehicle_id
    event_result = await service.register_event(
        db_session,
        plan.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.VEHICLE_BREAKDOWN,
            plan_id=plan.id,
            expected_plan_version=plan.version,
            vehicle_id=vehicle_id,
            occurred_at=datetime.combine(PLANNING_DATE, time(8), tzinfo=ZONE),
            reason="Поломка до выезда",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="confirmed-breakdown",
    )
    proposal = event_result.proposals[0]
    initial_version = proposal.version
    client.fail_after_replacement_once = True
    failed = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=initial_version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key="confirmed-apply-1",
    )
    prepared = failed.changes["prepared_recovery"]
    prepared_plan_id = UUID(prepared["result_plan_id"])
    source_after_failure = await plans.get_plan(db_session, plan.id)
    staged_after_failure = await plans.get_plan(db_session, prepared_plan_id)

    assert failed.status == RecoveryProposalStatus.FAILED
    assert failed.failure_code == "RWMS_REPLACEMENT_RESPONSE_LOST"
    assert source_after_failure.status == PlanStatus.CONFIRMED
    assert staged_after_failure.status == PlanStatus.ARCHIVED
    assert (
        await db_session.scalar(
            select(func.count(RoutePlan.id)).where(
                RoutePlan.warehouse_id == plan.warehouse_id,
                RoutePlan.date == PLANNING_DATE,
            )
        )
        == 2
    )
    assert client.replacement_commands[0][2].assignments[0].external_task_id == (
        assignment.external_task_id
    )

    replay = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=initial_version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key="confirmed-apply-1",
    )
    assert replay.version == failed.version
    with pytest.raises(ApiError) as reused:
        await service.apply_proposal(
            db_session,
            proposal.id,
            expected_version=failed.version,
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
            idempotency_key="confirmed-apply-1",
        )
    assert reused.value.code == "IDEMPOTENCY_KEY_REUSED"
    retried = await service.apply_proposal(
        db_session,
        proposal.id,
        expected_version=failed.version,
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        actor_subject_id=UUID("00000000-0000-0000-0000-000000000001"),
        idempotency_key="confirmed-apply-2",
    )
    source = await plans.get_plan(db_session, plan.id)
    activated = await plans.get_plan(db_session, prepared_plan_id)
    first_source_id, first_owner_key, first_command = client.replacement_commands[0]
    retry_source_id, retry_owner_key, retry_command = client.replacement_commands[1]

    assert retried.status == RecoveryProposalStatus.APPLIED
    assert retried.result_plan_id == prepared_plan_id
    assert source.status == PlanStatus.ARCHIVED
    assert activated.status == PlanStatus.GENERATED
    assert first_source_id == retry_source_id == plan.id
    assert first_owner_key == retry_owner_key
    assert first_command == retry_command


@pytest.mark.asyncio
async def test_recovery_persists_unaffected_locked_task_identity_without_duplicate_solver_work(
    db_session: AsyncSession,
) -> None:
    """Locked source stops keep exact DB task IDs while only affected tasks enter solve."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    first = await _make_request_type(
        db_session,
        warehouse,
        "DELIVERY",
        PLANNING_DATE,
        name="First full load",
        quantity=2,
    )
    second = await _make_request_type(
        db_session,
        warehouse,
        "DELIVERY",
        PLANNING_DATE,
        name="Second full load",
        quantity=2,
    )
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    vehicle.can_use_trailer = True
    vehicle.default_trailer = Trailer(
        warehouse_id=warehouse.id, name="Full-load trailer", registration_number=str(uuid4()),
    )
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    runtime = RuntimePlannerFacade()
    run = await runtime.generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=PLANNING_DATE, seed=warehouse.seed),
    )
    assert run.plan_id is not None
    source = await plans.get_plan(db_session, run.plan_id)
    assert len(source.cycles) >= 2
    source = await plans.confirm_plan(
        db_session,
        source.id,
        source.version,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by="dispatcher",
        planner=runtime,
    )
    source = await plans.get_plan(db_session, source.id)
    affected_cycle, locked_cycle = source.cycles[:2]
    affected_task_ids = [stop.task_id for stop in affected_cycle.stops if stop.task_id is not None]
    expected_locked = [
        (
            stop.sequence,
            stop.task_id,
            stop.stop_type,
            stop.planned_arrival,
            stop.planned_departure,
            stop.quantity_delta,
            stop.load_before,
            stop.load_after,
        )
        for stop in locked_cycle.stops
    ]
    recovery = await runtime.reoptimize_recovery(
        db_session,
        source.id,
        ManualChangeCommand(
            expected_version=source.version,
            change_type="DYNAMIC_RECOVERY",
            payload={
                "locked_cycle_ids": [str(locked_cycle.id)],
                "recovery_task_ids": [str(item) for item in affected_task_ids],
                "effective_at": (locked_cycle.planned_start + timedelta(minutes=5)).isoformat(),
            },
            reason="Проверка частичного восстановления",
            changed_by="dispatcher",
        ),
    )
    assert recovery.plan_id is not None, recovery.error_message
    successor = await plans.get_plan(db_session, recovery.plan_id)
    persisted_locked = next(
        cycle
        for cycle in successor.cycles
        if cycle.driver_shift_id == locked_cycle.driver_shift_id
        and cycle.sequence == locked_cycle.sequence
    )
    assert persisted_locked.locked == locked_cycle.locked
    assert [
        (
            stop.sequence,
            stop.task_id,
            stop.stop_type,
            stop.planned_arrival,
            stop.planned_departure,
            stop.quantity_delta,
            stop.load_before,
            stop.load_after,
        )
        for stop in persisted_locked.stops
    ] == expected_locked
    all_successor_task_ids = [
        stop.task_id
        for cycle in successor.cycles
        for stop in cycle.stops
        if stop.task_id is not None
    ] + [item.task_id for item in successor.unassigned_tasks]
    assert all_successor_task_ids.count(first.tasks[0].id) == 1
    assert all_successor_task_ids.count(second.tasks[0].id) == 1
    assert all(
        cycle.planned_start >= locked_cycle.planned_start + timedelta(minutes=5)
        for cycle in successor.cycles if cycle.id != persisted_locked.id
    )


@pytest.mark.asyncio
async def test_validated_manual_edit_appends_one_operational_event_and_rejected_edit_adds_none(
    db_session: AsyncSession,
) -> None:
    """Existing manual history is bridged once; failed validation never creates commentary."""

    runtime, plan, _, _, _ = await _generated_day(db_session, alternatives=0)
    task_id = next(
        stop.task_id for cycle in plan.cycles for stop in cycle.stops if stop.task_id is not None
    )
    await runtime.apply_manual_change(
        db_session,
        plan.id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="LOCK_TASK",
            payload={"task_id": str(task_id), "locked": True},
            reason="Зафиксировано диспетчером",
            changed_by="dispatcher",
        ),
    )
    events_before = int(
        await db_session.scalar(
            select(func.count(LogisticsEvent.id)).where(
                LogisticsEvent.event_type == LogisticsEventType.MANUAL_PLAN_CHANGE
            )
        )
        or 0
    )
    assert events_before == 1
    notice = await db_session.scalar(
        select(LogisticsNotice).where(LogisticsNotice.notice_type == "MANUAL_PLAN_CHANGE_APPLIED")
    )
    assert notice is not None and notice.status == "COMPLETED"
    current = await plans.get_plan(db_session, plan.id)
    with pytest.raises(ApiError):
        await runtime.apply_manual_change(
            db_session,
            plan.id,
            ManualChangeCommand(
                expected_version=current.version,
                change_type="LOCK_TASK",
                payload={"task_id": str(uuid4()), "locked": True},
                reason="Несуществующее задание",
                changed_by="dispatcher",
            ),
        )
    events_after = int(
        await db_session.scalar(
            select(func.count(LogisticsEvent.id)).where(
                LogisticsEvent.event_type == LogisticsEventType.MANUAL_PLAN_CHANGE
            )
        )
        or 0
    )
    assert events_after == events_before


def test_event_facts_are_bounded_and_cannot_override_service_owned_values() -> None:
    """Untrusted incident metadata cannot become an unbounded or ambiguous JSON record."""

    common = {
        "event_type": LogisticsEventType.MANUAL_PLAN_CHANGE,
        "occurred_at": datetime(2026, 9, 3, 9, tzinfo=ZONE),
        "reason": "Проверка",
    }
    with pytest.raises(ValidationError):
        LogisticsEventCreate(**common, facts={f"key-{index}": index for index in range(65)})
    with pytest.raises(ValidationError):
        LogisticsEventCreate(**common, facts={"payload": "x" * (16 * 1024)})
    with pytest.raises(ValidationError):
        LogisticsEventCreate(**common, facts={"command_hash": "подмена"})


@pytest.mark.asyncio
async def test_event_effective_time_is_fenced_by_root_warehouse_local_day(
    db_session: AsyncSession,
) -> None:
    """UTC instants use the root IANA zone and cannot mutate a different operational day."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    service = DynamicLogisticsService(_FeasiblePlanner(), None)  # type: ignore[arg-type]
    accepted = await service.register_event(
        db_session,
        warehouse.id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.MANUAL_PLAN_CHANGE,
            occurred_at=datetime(2026, 9, 1, 12, tzinfo=UTC),
            effective_at=datetime(2026, 9, 2, 21, tzinfo=UTC),
            reason="Изменение на начало локального дня",
        ),
        actor="dispatcher<00000000-0000-0000-0000-000000000001>",
        idempotency_key="local-day-boundary-ok",
    )
    assert accepted.event.day == PLANNING_DATE
    with pytest.raises(ApiError) as mismatch:
        await service.register_event(
            db_session,
            warehouse.id,
            PLANNING_DATE,
            LogisticsEventCreate(
                event_type=LogisticsEventType.MANUAL_PLAN_CHANGE,
                occurred_at=datetime(2026, 9, 1, 12, tzinfo=UTC),
                effective_at=datetime(2026, 9, 2, 20, 59, tzinfo=UTC),
                reason="Другая локальная дата",
            ),
            actor="dispatcher<00000000-0000-0000-0000-000000000001>",
            idempotency_key="local-day-boundary-reject",
        )
    assert mismatch.value.code == "EVENT_EFFECTIVE_DAY_MISMATCH"
    assert await db_session.scalar(select(func.count(LogisticsEvent.id))) == 1


@pytest.mark.asyncio
async def test_operations_window_reports_truncation_but_pending_count_is_exact(
    db_session: AsyncSession,
) -> None:
    """A bounded read never understates dispatcher work outside its returned window."""

    warehouse = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    event = LogisticsEvent(
        warehouse_id=warehouse.id,
        day=PLANNING_DATE,
        event_type=LogisticsEventType.MANUAL_PLAN_CHANGE,
        idempotency_key="projection-window",
        occurred_at=datetime(2026, 9, 3, 9, tzinfo=ZONE),
        actor="dispatcher",
        facts={},
    )
    db_session.add(event)
    await db_session.flush()
    notice = LogisticsNotice(
        event_id=event.id,
        warehouse_id=warehouse.id,
        day=PLANNING_DATE,
        notice_type="TEST",
        severity="INFO",
        reason_codes=["TEST"],
        facts={},
        message_ru="Тест",
        requires_action=True,
        status="REQUIRES_ACTION",
    )
    db_session.add(notice)
    await db_session.flush()
    db_session.add_all(
        LogisticsHumanAction(
            notice_id=notice.id,
            event_id=event.id,
            warehouse_id=warehouse.id,
            day=PLANNING_DATE,
            action_type=f"TEST_{index}",
        )
        for index in range(3)
    )
    await db_session.flush()
    projection = DynamicOperationsProjection()
    projection._ACTION_LIMIT = 2
    result = await projection.day_operations(db_session, warehouse.id, PLANNING_DATE)

    assert result.pending_action_count == 3
    assert len(result.actions) == 2
    assert "ACTIONS" in result.truncated_collections
    assert "DECISIONS" in result.truncated_collections
