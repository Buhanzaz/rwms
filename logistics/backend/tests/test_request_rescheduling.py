"""Focused API integration coverage for existing-request owner slot rescheduling."""

# ruff: noqa: RUF001 -- Russian fixtures are intentional.

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable
from datetime import UTC, date, datetime, time, timedelta
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import selectinload

from app.api.dependencies import get_capacity_rwms_client
from app.db import get_session
from app.errors import ApiError
from app.main import create_app
from app.models import (
    LogisticsEvent,
    LogisticsRequest,
    RecoveryProposal,
    RequestRescheduleHold,
    RoutePlan,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import (
    PlanStatus,
    RequestRescheduleHoldState,
    RequestStatus,
    TaskStatus,
)
from app.models.operations import RecoveryProposalStatus
from app.schemas.domain import (
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
)
from app.schemas.operations import (
    RwmsPlanningCustomerContact,
    RwmsRescheduleCommand,
    RwmsRescheduleOption,
    RwmsRescheduleOptions,
    RwmsRescheduleResult,
)
from app.services import catalog
from app.services import plans as plan_service
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.request_reschedule_worker import process_pending_request_reschedules
from app.services.request_rescheduling import ExistingRequestReschedulingService
from tests.auth import TEST_USER_ID, admin_access_token_verifier
from tests.factories import make_request, make_warehouse

pytestmark = pytest.mark.integration
CURRENT_DATE = date(2026, 9, 2)
TARGET_DATE = date(2026, 9, 3)


class _OwnerClient:
    """Deterministic owner seam that records transaction boundaries and command fences."""

    def __init__(
        self,
        session: AsyncSession,
        options: RwmsRescheduleOptions,
        result: RwmsRescheduleResult,
    ) -> None:
        self._session = session
        self.options = options
        self.result = result
        self.option_calls: list[tuple[UUID, int]] = []
        self.reschedule_calls: list[tuple[UUID, RwmsRescheduleCommand, str]] = []
        self.before_options: Callable[[], Awaitable[None]] | None = None
        self.after_options: Callable[[], None] | None = None
        self.before_reschedule: Callable[[], Awaitable[None]] | None = None
        self.reschedule_error: ApiError | None = None

    async def get_reschedule_options(
        self,
        order_id: UUID,
        *,
        expected_order_version: int,
    ) -> RwmsRescheduleOptions:
        """Return owner-calculated offers only after the local transaction was closed."""

        assert not self._session.in_transaction()
        if self.before_options is not None:
            await self.before_options()
        self.option_calls.append((order_id, expected_order_version))
        if self.after_options is not None:
            self.after_options()
        return self.options

    async def reschedule_order(
        self,
        order_id: UUID,
        command: RwmsRescheduleCommand,
        *,
        idempotency_key: str,
    ) -> RwmsRescheduleResult:
        """Replay the same authoritative receipt for repeated owner idempotency keys."""

        assert not self._session.in_transaction()
        if self.before_reschedule is not None:
            await self.before_reschedule()
        self.reschedule_calls.append((order_id, command, idempotency_key))
        if self.reschedule_error is not None:
            raise self.reschedule_error
        return self.result


def _application(session: AsyncSession, owner: _OwnerClient) -> FastAPI:
    """Bind one rollback-isolated database session and strict owner double."""

    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[AsyncSession]:
        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_capacity_rwms_client] = lambda: owner
    return application


async def _unassigned_rwms_request(
    session: AsyncSession,
) -> tuple[LogisticsRequest, RoutePlan, UUID]:
    """Persist one active mutable head containing every part as unassigned work."""

    warehouse = await make_warehouse(session, default_planning_date=CURRENT_DATE)
    request = await make_request(
        session,
        warehouse,
        planning_date=CURRENT_DATE,
        quantity=1,
    )
    order_id = uuid4()
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        orderId=order_id,
        orderVersion=3,
        sourceRevision="a" * 64,
        customerDeliveryPurpose="RENTAL_DELIVERY",
        orderNumber="ORD-000041",
        clientName="ООО Тест",
        clientType="LEGAL_ENTITY",
        contactName="Иван Петров",
        contactPhone="+79990000000",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unitIds=[unit_id],
        unitReservations=[
            RwmsPlanningUnitReservation(
                unitId=unit_id,
                inventorySourceWarehouseId=warehouse.external_warehouse_id,
            )
        ],
        dateOptions=[
            RwmsPlanningDateOption(
                date=CURRENT_DATE,
                priority=0,
                isHard=True,
                windowStart=time(9),
                windowEnd=time(12),
            )
        ],
        trailerAccessAllowed=True,
        deliveryPriceRubles=20_000,
        priceIsochroneMinutes=180,
        createdAt=datetime(2026, 9, 1, 8, tzinfo=UTC),
    )
    request.source_system = catalog.RWMS_SOURCE_SYSTEM
    request.external_id = order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    request.scheduled_date = CURRENT_DATE
    request.status = RequestStatus.UNASSIGNED
    for task in request.tasks:
        task.status = TaskStatus.UNASSIGNED
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=CURRENT_DATE,
        name="Нераспределённые доставки",
        status=PlanStatus.GENERATED,
        version=1,
    )
    session.add(plan)
    await session.flush()
    for task in request.tasks:
        session.add(
            UnassignedTask(
                route_plan_id=plan.id,
                task_id=task.id,
                reason_codes=["NO_RESOURCE"],
                descriptions_ru=["Нет доступной машины"],
            )
        )
    await session.flush()
    return request, plan, warehouse.external_warehouse_id


async def _append_unassigned_rwms_request(
    session: AsyncSession,
    warehouse: Warehouse,
    plan: RoutePlan,
) -> LogisticsRequest:
    """Add another independent owner order to the same mutable plan lineage."""

    request = await make_request(
        session,
        warehouse,
        planning_date=CURRENT_DATE,
        quantity=1,
    )
    # Catalog demand creation invalidates mutable heads; restore this synthetic shared
    # lineage so the test can exercise the active-plan uniqueness fence itself.
    plan.status = PlanStatus.GENERATED
    order_id = uuid4()
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        orderId=order_id,
        orderVersion=3,
        sourceRevision="b" * 64,
        customerDeliveryPurpose="RENTAL_DELIVERY",
        orderNumber="ORD-000042",
        clientName="ООО Второй клиент",
        clientType="LEGAL_ENTITY",
        contactName="Пётр Иванов",
        contactPhone="+79990000001",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unitIds=[unit_id],
        unitReservations=[
            RwmsPlanningUnitReservation(
                unitId=unit_id,
                inventorySourceWarehouseId=warehouse.external_warehouse_id,
            )
        ],
        dateOptions=[
            RwmsPlanningDateOption(
                date=CURRENT_DATE,
                priority=0,
                isHard=True,
                windowStart=time(12),
                windowEnd=time(15),
            )
        ],
        trailerAccessAllowed=True,
        deliveryPriceRubles=21_000,
        priceIsochroneMinutes=180,
        createdAt=datetime(2026, 9, 1, 9, tzinfo=UTC),
    )
    request.source_system = catalog.RWMS_SOURCE_SYSTEM
    request.external_id = order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    request.scheduled_date = CURRENT_DATE
    request.status = RequestStatus.UNASSIGNED
    for task in request.tasks:
        task.status = TaskStatus.UNASSIGNED
        session.add(
            UnassignedTask(
                route_plan_id=plan.id,
                task_id=task.id,
                reason_codes=["NO_RESOURCE"],
                descriptions_ru=["Нет доступной машины"],
            )
        )
    await session.flush()
    return request


def _slot(
    slot_id: UUID,
    *,
    planning_date: date,
    slot_version: int,
    window_start: time | None,
    window_end: time | None,
) -> RwmsRescheduleOption:
    """Build one unexpired owner offer for a fixed or during-day commitment."""

    return RwmsRescheduleOption(
        slotId=slot_id,
        slotVersion=slot_version,
        date=planning_date,
        kind=(
            "FIXED_WINDOW" if window_start is not None and window_end is not None else "DURING_DAY"
        ),
        windowStart=window_start,
        windowEnd=window_end,
        deliveryPriceRubles=24_000,
        expiresAt=datetime.now(UTC) + timedelta(hours=1),
    )


def _owner_models(
    request: LogisticsRequest,
    external_warehouse_id: UUID,
) -> tuple[RwmsRescheduleOptions, RwmsRescheduleResult, tuple[RwmsRescheduleOption, ...]]:
    """Create multiple same-day choices and the later durable owner receipt."""

    current = _slot(
        uuid4(),
        planning_date=CURRENT_DATE,
        slot_version=1,
        window_start=time(9),
        window_end=time(12),
    )
    morning = _slot(
        uuid4(),
        planning_date=TARGET_DATE,
        slot_version=2,
        window_start=time(9),
        window_end=time(12),
    )
    afternoon = _slot(
        uuid4(),
        planning_date=TARGET_DATE,
        slot_version=4,
        window_start=time(14),
        window_end=time(18),
    )
    other_date = _slot(
        uuid4(),
        planning_date=TARGET_DATE + timedelta(days=1),
        slot_version=1,
        window_start=None,
        window_end=None,
    )
    session_id = uuid4()
    booking_id = uuid4()
    options = RwmsRescheduleOptions(
        orderId=request.external_id,
        orderVersion=request.external_version,
        sessionId=session_id,
        sessionVersion=7,
        bookingId=booking_id,
        warehouseId=external_warehouse_id,
        customer=RwmsPlanningCustomerContact(
            clientType="LEGAL_ENTITY",
            clientName="ООО Тест",
            contactName="Иван Петров",
            contactPhone="+79990000000",
        ),
        currentSlot=current,
        options=[other_date, afternoon, morning],
    )
    confirmed = morning.model_copy(update={"slot_version": morning.slot_version + 1})
    result = RwmsRescheduleResult(
        orderId=request.external_id,
        orderVersion=(request.external_version or 0) + 1,
        sessionId=session_id,
        sessionVersion=8,
        bookingId=booking_id,
        warehouseId=external_warehouse_id,
        confirmedSlot=confirmed,
        publishedPlanWithdrawal=None,
    )
    return options, result, (morning, afternoon, other_date)


def _apply_payload(
    request: LogisticsRequest,
    plan: RoutePlan,
    slot: RwmsRescheduleOption,
) -> dict[str, object]:
    """Build the complete browser command from one options-response fence set."""

    return {
        "expected_request_version": request.version,
        "source_plan_id": str(plan.id),
        "source_plan_version": plan.version,
        "expected_order_version": request.external_version,
        "expected_session_version": 7,
        "slot_id": str(slot.slot_id),
        "slot_version": slot.slot_version,
    }


@pytest.mark.asyncio
async def test_options_return_all_owner_slots_for_selected_date_without_manual_window(
    db_session: AsyncSession,
) -> None:
    """The facade filters only by date and preserves every route-feasible owner option."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, expected = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
        no_offers = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": (TARGET_DATE + timedelta(days=5)).isoformat(),
            },
        )
        manual_window = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
                "window_start": "10:00:00",
            },
        )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["request_id"] == str(request.id)
    assert body["order_id"] == str(request.external_id)
    assert body["order_version"] == 3
    assert body["session_version"] == 7
    assert body["source_plan_id"] == str(plan.id)
    assert body["source_plan_version"] == plan.version
    assert {item["slot_id"] for item in body["options"]} == {
        str(expected[0].slot_id),
        str(expected[1].slot_id),
    }
    assert all(item["date"] == TARGET_DATE.isoformat() for item in body["options"])
    assert no_offers.status_code == 200, no_offers.text
    assert no_offers.json()["options"] == []
    assert manual_window.status_code == 422
    assert owner.option_calls == [
        (request.external_id, 3),
        (request.external_id, 3),
    ]


@pytest.mark.asyncio
async def test_apply_converges_atomically_and_replays_owner_without_version_regression(
    db_session: AsyncSession,
) -> None:
    """A retry reaches the owner receipt and never duplicates or re-versions local demand."""

    request, old_plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    target_plan = RoutePlan(
        warehouse_id=old_plan.warehouse_id,
        date=TARGET_DATE,
        name="Старый расчёт целевого дня",
        status=PlanStatus.GENERATED,
        version=1,
    )
    db_session.add(target_plan)
    await db_session.flush()
    initial_version = request.version
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    key = uuid4()
    payload = _apply_payload(request, old_plan, choices[0])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        first = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )
        replay = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )

    assert first.status_code == 200, first.text
    assert replay.status_code == 200, replay.text
    assert replay.json() == first.json()
    assert first.json()["scheduled_date"] == TARGET_DATE.isoformat()
    assert first.json()["confirmed_slot"]["slot_id"] == str(choices[0].slot_id)
    assert first.json()["confirmed_slot"]["slot_version"] == choices[0].slot_version + 1
    stored = await db_session.scalar(
        select(LogisticsRequest)
        .where(LogisticsRequest.id == request.id)
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .execution_options(populate_existing=True)
    )
    assert stored is not None
    assert stored.version == initial_version + 1
    assert stored.external_version == 4
    assert stored.scheduled_date == TARGET_DATE
    assert stored.status == RequestStatus.READY
    assert all(task.status == TaskStatus.READY for task in stored.tasks)
    assert len(stored.date_options) == 1
    assert stored.date_options[0].date == TARGET_DATE
    assert stored.date_options[0].window_start == time(9)
    assert stored.date_options[0].window_end == time(12)
    assert stored.date_options[0].is_hard is True
    assert stored.external_payload is not None
    assert stored.external_payload["orderVersion"] == 4
    assert stored.external_payload["dateOptions"] == [
        {
            "date": TARGET_DATE.isoformat(),
            "priority": 0,
            "isHard": True,
            "windowStart": "09:00:00",
            "windowEnd": "12:00:00",
            "travelZoneHours": None,
        }
    ]
    archived = dict(
        (
            await db_session.execute(
                select(RoutePlan.id, RoutePlan.status).where(
                    RoutePlan.id.in_((old_plan.id, target_plan.id))
                )
            )
        ).all()
    )
    assert archived == {
        old_plan.id: PlanStatus.ARCHIVED,
        target_plan.id: PlanStatus.ARCHIVED,
    }
    assert len(owner.option_calls) == 1
    assert len(owner.reschedule_calls) == 1
    first_command = owner.reschedule_calls[0][1]
    assert first_command.decision_code == "CUSTOMER_AGREED_ALTERNATIVE"
    assert first_command.decision_actor_subject_id == TEST_USER_ID
    assert first_command.published_plan_withdrawal is None
    assert owner.reschedule_calls[0][2] == str(key)


@pytest.mark.asyncio
async def test_stale_owner_slot_does_not_mutate_or_archive_local_request(
    db_session: AsyncSession,
) -> None:
    """A stale slot fence stops before owner apply and preserves the active unassigned head."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    initial_version = request.version

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json={
                **_apply_payload(request, plan, choices[0]),
                "slot_version": choices[0].slot_version + 99,
            },
        )

    assert response.status_code == 409, response.text
    assert response.json()["code"] == "RWMS_RESCHEDULE_SLOT_VERSION_CONFLICT"
    assert owner.reschedule_calls == []
    stored = await db_session.get(LogisticsRequest, request.id, populate_existing=True)
    active_plan = await db_session.get(RoutePlan, plan.id, populate_existing=True)
    assert stored is not None and stored.version == initial_version
    assert stored.scheduled_date == CURRENT_DATE
    assert active_plan is not None and active_plan.status == PlanStatus.GENERATED


@pytest.mark.asyncio
async def test_apply_rechecks_local_plan_after_owner_options_before_owner_effect(
    db_session: AsyncSession,
) -> None:
    """A changed plan lineage stops before the owner reschedule command is sent."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    owner.after_options = lambda: setattr(plan, "version", plan.version + 1)
    app = _application(db_session, owner)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )

    assert response.status_code == 409, response.text
    assert response.json()["code"] == "REQUEST_RESCHEDULE_STATE_CHANGED"
    assert len(owner.option_calls) == 1
    assert owner.reschedule_calls == []


@pytest.mark.asyncio
async def test_apply_rejects_plan_fence_returned_by_an_outdated_options_response(
    db_session: AsyncSession,
) -> None:
    """A new mutable plan revision cannot be silently substituted at apply time."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    stale_payload = _apply_payload(request, plan, choices[0])
    plan.version += 1
    await db_session.commit()

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=stale_payload,
        )

    assert response.status_code == 409, response.text
    assert response.json()["code"] == "PLAN_VERSION_CONFLICT"
    assert owner.option_calls == []
    assert owner.reschedule_calls == []


@pytest.mark.asyncio
async def test_foreground_claim_lease_prevents_worker_from_replaying_live_options_call(
    db_session: AsyncSession,
) -> None:
    """A worker cannot take a CLAIMED hold while the initiating request is still active."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    session_factory = async_sessionmaker(bind=db_session.bind, expire_on_commit=False)
    worker_batch = None

    async def try_worker_during_options() -> None:
        nonlocal worker_batch
        worker_batch = await process_pending_request_reschedules(
            owner,
            batch_size=1,
            max_attempts=3,
            session_factory=session_factory,
        )

    owner.before_options = try_worker_during_options
    app = _application(db_session, owner)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )

    assert response.status_code == 200, response.text
    assert worker_batch is not None
    assert worker_batch.claimed == 0
    assert len(owner.option_calls) == 1
    assert len(owner.reschedule_calls) == 1


@pytest.mark.asyncio
async def test_owner_success_then_local_failure_replays_forward_with_same_key(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The worker recovers an owner success after process-local state and browser key are lost."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    original = ExistingRequestReschedulingService._converge_local_request
    convergence_attempts = 0

    async def fail_local_once(
        service: ExistingRequestReschedulingService,
        session: AsyncSession,
        request_id: UUID,
        owner_result: RwmsRescheduleResult,
        *,
        active_hold_id: UUID,
    ) -> object:
        nonlocal convergence_attempts
        convergence_attempts += 1
        if convergence_attempts == 1:
            raise ApiError(
                503,
                "TEST_LOCAL_CONVERGENCE_FAILED",
                "Owner committed before a simulated local projection failure",
            )
        return await original(
            service,
            session,
            request_id,
            owner_result,
            active_hold_id=active_hold_id,
        )

    monkeypatch.setattr(
        ExistingRequestReschedulingService,
        "_converge_local_request",
        fail_local_once,
    )
    key = uuid4()
    payload = _apply_payload(request, plan, choices[0])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        failed = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )

    assert failed.status_code == 503, failed.text
    pending_hold = await db_session.scalar(
        select(RequestRescheduleHold).where(RequestRescheduleHold.request_id == request.id)
    )
    assert pending_hold is not None
    pending_hold.lease_until = datetime.now(UTC) - timedelta(seconds=1)
    await db_session.commit()
    session_factory = async_sessionmaker(
        bind=db_session.bind,
        expire_on_commit=False,
    )
    batch = await process_pending_request_reschedules(
        owner,
        batch_size=10,
        max_attempts=3,
        session_factory=session_factory,
    )
    assert batch.claimed == 1
    assert batch.succeeded == 1
    assert batch.failed == 0
    assert convergence_attempts == 2
    assert len(owner.option_calls) == 1
    assert len(owner.reschedule_calls) == 2
    assert {call[2] for call in owner.reschedule_calls} == {str(key)}
    stored = await db_session.get(LogisticsRequest, request.id, populate_existing=True)
    assert stored is not None
    assert stored.external_version == result.order_version
    assert stored.scheduled_date == TARGET_DATE


@pytest.mark.asyncio
async def test_owner_replay_with_later_reschedule_terminalizes_hold_without_applying_it(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A same-key replay returning later commitment B releases A without projecting B as A."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, first_result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, first_result)
    app = _application(db_session, owner)
    original = ExistingRequestReschedulingService._converge_local_request
    convergence_attempts = 0

    async def fail_first_local_convergence(
        service: ExistingRequestReschedulingService,
        session: AsyncSession,
        request_id: UUID,
        owner_result: RwmsRescheduleResult,
        *,
        active_hold_id: UUID,
    ) -> object:
        nonlocal convergence_attempts
        convergence_attempts += 1
        if convergence_attempts == 1:
            raise ApiError(
                503,
                "TEST_LOCAL_CONVERGENCE_FAILED",
                "Owner committed before a simulated local projection failure",
            )
        return await original(
            service,
            session,
            request_id,
            owner_result,
            active_hold_id=active_hold_id,
        )

    monkeypatch.setattr(
        ExistingRequestReschedulingService,
        "_converge_local_request",
        fail_first_local_convergence,
    )
    key = uuid4()
    payload = _apply_payload(request, plan, choices[0])
    initial_request_version = request.version
    initial_order_version = request.external_version

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        failed = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )

    assert failed.status_code == 503, failed.text
    pending_hold = await db_session.scalar(
        select(RequestRescheduleHold).where(RequestRescheduleHold.request_id == request.id)
    )
    assert pending_hold is not None
    later_slot = choices[1].model_copy(update={"slot_version": choices[1].slot_version + 1})
    owner.result = first_result.model_copy(
        update={
            "order_version": payload["expected_order_version"] + 2,
            "session_version": payload["expected_session_version"] + 2,
            "confirmed_slot": later_slot,
        }
    )
    pending_hold.lease_until = datetime.now(UTC) - timedelta(seconds=1)
    await db_session.commit()
    session_factory = async_sessionmaker(bind=db_session.bind, expire_on_commit=False)

    batch = await process_pending_request_reschedules(
        owner,
        batch_size=1,
        max_attempts=3,
        session_factory=session_factory,
    )

    assert batch.claimed == 1
    assert batch.failed == 1
    assert batch.quarantined == 0
    assert convergence_attempts == 1
    await db_session.refresh(pending_hold)
    assert pending_hold.state == RequestRescheduleHoldState.SUPERSEDED
    assert pending_hold.quarantine_count == 0
    assert pending_hold.owner_result == owner.result.model_dump(mode="json", by_alias=True)
    stored = await db_session.get(LogisticsRequest, request.id, populate_existing=True)
    stored_plan = await db_session.get(RoutePlan, plan.id, populate_existing=True)
    assert stored is not None
    assert stored.external_version == initial_order_version
    assert stored.scheduled_date == CURRENT_DATE
    assert stored.version == initial_request_version
    assert stored_plan is not None and stored_plan.status == PlanStatus.ARCHIVED


@pytest.mark.asyncio
async def test_owner_replay_invalid_identity_remains_an_upstream_error(
    db_session: AsyncSession,
) -> None:
    """A mismatched owner aggregate is never mistaken for a later valid commitment."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(
        db_session,
        options,
        result.model_copy(
            update={
                "order_id": uuid4(),
                "order_version": (request.external_version or 0) + 2,
                "session_version": options.session_version + 2,
                "confirmed_slot": choices[1].model_copy(
                    update={"slot_version": choices[1].slot_version + 1}
                ),
            }
        ),
    )
    app = _application(db_session, owner)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )

    assert response.status_code == 502, response.text
    assert response.json()["code"] == "RWMS_RESCHEDULE_RESPONSE_INVALID"
    stored_plan = await db_session.get(RoutePlan, plan.id, populate_existing=True)
    assert stored_plan is not None and stored_plan.status == PlanStatus.GENERATED


@pytest.mark.asyncio
async def test_retryable_failed_recovery_blocks_new_slot_reschedule(
    db_session: AsyncSession,
) -> None:
    """A FAILED forward-only recovery remains active until its retry reaches a terminal state."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    event = LogisticsEvent(
        warehouse_id=request.warehouse_id,
        day=CURRENT_DATE,
        plan_id=plan.id,
        request_id=request.id,
        event_type="DELIVERY_CANCELLED",
        idempotency_key="failed-recovery-fence",
        occurred_at=datetime(2026, 9, 2, 10, tzinfo=UTC),
        actor="dispatcher",
        facts={},
    )
    db_session.add(event)
    await db_session.flush()
    proposal = RecoveryProposal(
        event_id=event.id,
        warehouse_id=request.warehouse_id,
        day=CURRENT_DATE,
        source_plan_id=plan.id,
        proposal_type="RESCHEDULE_REQUEST",
        status=RecoveryProposalStatus.FAILED,
        version=3,
        summary_ru="Повторить незавершённое применение.",
        affected_request_ids=[str(request.id)],
        affected_task_ids=[str(task.id) for task in request.tasks],
        changes={
            "request_id": str(request.id),
            "active_apply": {"token": str(uuid4())},
            "owner_reschedule_receipt": {"orderId": str(request.external_id)},
        },
        metrics={},
        failure_code="TEST_LOCAL_CONVERGENCE_FAILED",
    )
    db_session.add(proposal)
    await db_session.commit()
    app = _application(db_session, owner)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        failed_options = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
        failed_apply = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )
        proposal.status = RecoveryProposalStatus.APPLIED
        proposal.applied_at = datetime.now(UTC)
        await db_session.commit()
        applied_options = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
        proposal.status = RecoveryProposalStatus.REJECTED
        proposal.applied_at = None
        await db_session.commit()
        rejected_options = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )

    assert failed_options.status_code == 409, failed_options.text
    assert failed_options.json()["code"] == "REQUEST_RECOVERY_IN_PROGRESS"
    assert failed_apply.status_code == 409, failed_apply.text
    assert failed_apply.json()["code"] == "REQUEST_RECOVERY_IN_PROGRESS"
    assert applied_options.status_code == 200, applied_options.text
    assert rejected_options.status_code == 200, rejected_options.text
    assert owner.reschedule_calls == []


@pytest.mark.asyncio
async def test_every_active_recovery_lineage_blocks_new_slot_reschedule(
    db_session: AsyncSession,
) -> None:
    """Cancellation, affected-request, and whole-plan proposals share one admission fence."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    event = LogisticsEvent(
        warehouse_id=request.warehouse_id,
        day=CURRENT_DATE,
        plan_id=plan.id,
        request_id=request.id,
        event_type="VEHICLE_BREAKDOWN",
        idempotency_key="all-recovery-lineages-fence",
        occurred_at=datetime(2026, 9, 2, 11, tzinfo=UTC),
        actor="dispatcher",
        facts={},
    )
    db_session.add(event)
    await db_session.flush()
    app = _application(db_session, owner)

    async def assert_blocked(client: AsyncClient) -> None:
        options_response = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
        apply_response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )
        assert options_response.status_code == 409, options_response.text
        assert options_response.json()["code"] == "REQUEST_RECOVERY_IN_PROGRESS"
        assert apply_response.status_code == 409, apply_response.text
        assert apply_response.json()["code"] == "REQUEST_RECOVERY_IN_PROGRESS"

    cancellation = RecoveryProposal(
        event_id=event.id,
        warehouse_id=request.warehouse_id,
        day=CURRENT_DATE,
        source_plan_id=None,
        proposal_type="CANCEL_REQUEST",
        status=RecoveryProposalStatus.READY_TO_APPLY,
        version=1,
        summary_ru="Завершить отмену заявки.",
        affected_request_ids=[],
        affected_task_ids=[],
        changes={"cancel_request_id": str(request.id)},
        metrics={},
    )
    db_session.add(cancellation)
    await db_session.commit()

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        await assert_blocked(client)
        cancellation.status = RecoveryProposalStatus.REJECTED
        await db_session.commit()

        resource_loss = RecoveryProposal(
            event_id=event.id,
            warehouse_id=request.warehouse_id,
            day=CURRENT_DATE,
            source_plan_id=None,
            proposal_type="PARTIAL_REPLAN_RESOURCE",
            status=RecoveryProposalStatus.FAILED,
            version=2,
            summary_ru="Повторить восстановление ресурса.",
            affected_request_ids=[str(request.id)],
            affected_task_ids=[],
            changes={"active_apply": {"token": str(uuid4())}},
            metrics={},
            failure_code="TEST_LOCAL_CONVERGENCE_FAILED",
        )
        db_session.add(resource_loss)
        await db_session.commit()
        await assert_blocked(client)
        resource_loss.status = RecoveryProposalStatus.APPLIED
        resource_loss.applied_at = datetime.now(UTC)
        await db_session.commit()

        whole_plan = RecoveryProposal(
            event_id=event.id,
            warehouse_id=request.warehouse_id,
            day=CURRENT_DATE,
            source_plan_id=plan.id,
            proposal_type="PARTIAL_REPLAN_RESOURCE",
            status=RecoveryProposalStatus.PROPOSED,
            version=1,
            summary_ru="Пересчитать затронутую часть плана.",
            affected_request_ids=[],
            affected_task_ids=[],
            changes={},
            metrics={},
        )
        db_session.add(whole_plan)
        await db_session.commit()
        await assert_blocked(client)
        whole_plan.status = RecoveryProposalStatus.REJECTED
        await db_session.commit()

        terminal_options = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )

    assert terminal_options.status_code == 200, terminal_options.text
    assert len(owner.option_calls) == 1
    assert owner.reschedule_calls == []


@pytest.mark.asyncio
async def test_local_or_confirmed_requests_never_call_owner_reschedule_facade(
    db_session: AsyncSession,
) -> None:
    """The public facade rejects generated demand and immutable plan history explicitly."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, _choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    original_external_id = request.external_id
    original_external_version = request.external_version
    original_external_payload = request.external_payload
    request.source_system = "GENERATOR"
    request.external_id = None
    request.external_version = None
    request.external_payload = None
    await db_session.flush()

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        local_response = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
    assert local_response.status_code == 422, local_response.text
    assert local_response.json()["code"] == "REQUEST_RESCHEDULE_SOURCE_UNSUPPORTED"
    assert owner.option_calls == []

    request.source_system = catalog.RWMS_SOURCE_SYSTEM
    request.external_id = original_external_id
    request.external_version = original_external_version
    request.external_payload = original_external_payload
    plan.status = PlanStatus.CONFIRMED
    await db_session.flush()
    owner.option_calls.clear()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        confirmed_response = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
    assert confirmed_response.status_code == 409, confirmed_response.text
    assert confirmed_response.json()["code"] == "REQUEST_IN_CONFIRMED_PLAN"
    assert owner.option_calls == []


@pytest.mark.asyncio
async def test_owner_call_hold_blocks_unassigned_only_plan_confirmation(
    db_session: AsyncSession,
) -> None:
    """The durable OWNER_CALLING phase is a real plan hold, not an advisory marker."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    blocked_code: str | None = None

    async def try_confirm_during_owner_call() -> None:
        nonlocal blocked_code
        try:
            await plan_service.confirm_plan(
                db_session,
                plan.id,
                plan.version,
                accept_warnings=True,
                empty_positioning_reason=None,
                confirmed_by="test",
                planner=RuntimePlannerFacade(),
            )
        except ApiError as exc:
            blocked_code = exc.code

    owner.before_reschedule = try_confirm_during_owner_call
    app = _application(db_session, owner)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(request, plan, choices[0]),
        )

    assert response.status_code == 200, response.text
    assert blocked_code == "REQUEST_RESCHEDULE_IN_PROGRESS"
    stored_plan = await db_session.get(RoutePlan, plan.id, populate_existing=True)
    assert stored_plan is not None and stored_plan.status == PlanStatus.ARCHIVED


@pytest.mark.asyncio
async def test_one_unfinished_reschedule_is_allowed_per_source_plan_lineage(
    db_session: AsyncSession,
) -> None:
    """A sibling request cannot start a second owner command on the same mutable head."""

    first, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    warehouse = await db_session.get(Warehouse, first.warehouse_id)
    assert warehouse is not None
    second = await _append_unassigned_rwms_request(db_session, warehouse, plan)
    first_options, first_result, first_choices = _owner_models(
        first,
        external_warehouse_id,
    )
    owner = _OwnerClient(db_session, first_options, first_result)
    owner.reschedule_error = ApiError(503, "TEST_OWNER_UNAVAILABLE", "owner unavailable")
    app = _application(db_session, owner)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        first_response = await client.post(
            f"/api/requests/{first.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(first, plan, first_choices[0]),
        )
        assert first_response.status_code == 503, first_response.text
        second_options, second_result, second_choices = _owner_models(
            second,
            external_warehouse_id,
        )
        owner.options = second_options
        owner.result = second_result
        owner.reschedule_error = None
        second_response = await client.post(
            f"/api/requests/{second.id}/reschedule",
            headers={"Idempotency-Key": str(uuid4())},
            json=_apply_payload(second, plan, second_choices[0]),
        )

    assert second_response.status_code == 409, second_response.text
    assert second_response.json()["code"] == "REQUEST_RESCHEDULE_IN_PROGRESS"
    assert len(owner.reschedule_calls) == 1


@pytest.mark.asyncio
async def test_complete_replay_returns_original_receipt_after_newer_projection(
    db_session: AsyncSession,
) -> None:
    """An exact completed key replays its frozen response without regressing newer owner data."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    key = uuid4()
    payload = _apply_payload(request, plan, choices[0])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        first = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )
        assert first.status_code == 200, first.text
        newer_date = TARGET_DATE + timedelta(days=2)
        stored = await db_session.get(LogisticsRequest, request.id, populate_existing=True)
        assert stored is not None
        stored.external_version = result.order_version + 1
        stored.scheduled_date = newer_date
        stored.version += 1
        await db_session.commit()
        newer_request_version = stored.version
        replay = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )

    assert replay.status_code == 200, replay.text
    assert replay.json() == first.json()
    assert len(owner.reschedule_calls) == 1
    unchanged = await db_session.get(LogisticsRequest, request.id, populate_existing=True)
    assert unchanged is not None
    assert unchanged.external_version == result.order_version + 1
    assert unchanged.scheduled_date == newer_date
    assert unchanged.version == newer_request_version


@pytest.mark.asyncio
async def test_attached_browser_key_is_durably_bound_to_the_original_payload(
    db_session: AsyncSession,
) -> None:
    """A replacement browser key cannot later be reused for a different slot command."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    app = _application(db_session, owner)
    original_key = uuid4()
    attached_key = uuid4()
    payload = _apply_payload(request, plan, choices[0])
    changed_payload = {**payload, "slot_id": str(choices[1].slot_id)}

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        first = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(original_key)},
            json=payload,
        )
        attached = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(attached_key)},
            json=payload,
        )
        conflict = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(attached_key)},
            json=changed_payload,
        )

    assert first.status_code == 200, first.text
    assert attached.status_code == 200, attached.text
    assert attached.json() == first.json()
    assert conflict.status_code == 409, conflict.text
    assert conflict.json()["code"] == "IDEMPOTENCY_KEY_CONFLICT"
    assert len(owner.reschedule_calls) == 1


@pytest.mark.asyncio
async def test_worker_backs_off_then_quarantines_and_operator_replays_original_key(
    db_session: AsyncSession,
) -> None:
    """Permanent owner uncertainty stops automatically and remains safely recoverable."""

    request, plan, external_warehouse_id = await _unassigned_rwms_request(db_session)
    options, result, choices = _owner_models(request, external_warehouse_id)
    owner = _OwnerClient(db_session, options, result)
    owner.reschedule_error = ApiError(503, "TEST_OWNER_UNAVAILABLE", "owner unavailable")
    app = _application(db_session, owner)
    key = uuid4()
    payload = _apply_payload(request, plan, choices[0])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        failed = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )
    assert failed.status_code == 503

    pending_hold = await db_session.scalar(
        select(RequestRescheduleHold).where(RequestRescheduleHold.request_id == request.id)
    )
    assert pending_hold is not None
    pending_hold.lease_until = datetime.now(UTC) - timedelta(seconds=1)
    await db_session.commit()

    session_factory = async_sessionmaker(bind=db_session.bind, expire_on_commit=False)
    backed_off = await process_pending_request_reschedules(
        owner,
        batch_size=10,
        max_attempts=2,
        session_factory=session_factory,
    )
    assert backed_off.claimed == 1
    assert backed_off.failed == 1
    deferred = await process_pending_request_reschedules(
        owner,
        batch_size=10,
        max_attempts=2,
        session_factory=session_factory,
    )
    assert deferred.claimed == 0
    hold = await db_session.scalar(
        select(RequestRescheduleHold).where(RequestRescheduleHold.request_id == request.id)
    )
    assert hold is not None
    await db_session.refresh(hold)
    assert hold.state == RequestRescheduleHoldState.OWNER_CALLING
    assert hold.next_attempt_at is not None
    hold.next_attempt_at = datetime.now(UTC) - timedelta(seconds=1)
    await db_session.commit()
    quarantined = await process_pending_request_reschedules(
        owner,
        batch_size=10,
        max_attempts=2,
        session_factory=session_factory,
    )
    assert quarantined.quarantined == 1
    await db_session.refresh(hold)
    assert hold.state == RequestRescheduleHoldState.QUARANTINED
    assert hold.next_attempt_at is None
    assert hold.lease_until is None
    assert hold.quarantine_count == 1
    assert hold.quarantined_at is not None
    assert hold.last_quarantine_error_code == "TEST_OWNER_UNAVAILABLE"
    retry_key = uuid4()
    retry_payload = {
        "hold_id": str(hold.id),
        "expected_quarantine_count": hold.quarantine_count,
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        blocked_options = await client.post(
            f"/api/requests/{request.id}/reschedule-options",
            json={
                "expected_request_version": request.version,
                "date": TARGET_DATE.isoformat(),
            },
        )
        calls_before_explicit_retry = len(owner.reschedule_calls)
        automatic_replay = await client.post(
            f"/api/requests/{request.id}/reschedule",
            headers={"Idempotency-Key": str(key)},
            json=payload,
        )
        owner.reschedule_error = None
        recovered = await client.post(
            f"/api/requests/{request.id}/reschedule-retry",
            headers={"Idempotency-Key": str(retry_key)},
            json=retry_payload,
        )
        replayed_retry = await client.post(
            f"/api/requests/{request.id}/reschedule-retry",
            headers={"Idempotency-Key": str(retry_key)},
            json=retry_payload,
        )
        changed_retry = await client.post(
            f"/api/requests/{request.id}/reschedule-retry",
            headers={"Idempotency-Key": str(retry_key)},
            json={**retry_payload, "expected_quarantine_count": 2},
        )

    assert blocked_options.status_code == 409
    assert blocked_options.json()["code"] == "REQUEST_RESCHEDULE_QUARANTINED"
    assert blocked_options.json()["hold_id"] == str(hold.id)
    assert blocked_options.json()["quarantine_count"] == 1
    assert automatic_replay.status_code == 409
    assert automatic_replay.json()["code"] == "REQUEST_RESCHEDULE_QUARANTINED"
    assert automatic_replay.json()["hold_id"] == str(hold.id)
    assert len(owner.reschedule_calls) == calls_before_explicit_retry + 1
    assert recovered.status_code == 200, recovered.text
    assert replayed_retry.status_code == 200, replayed_retry.text
    assert replayed_retry.json() == recovered.json()
    assert changed_retry.status_code == 409, changed_retry.text
    assert changed_retry.json()["code"] == "IDEMPOTENCY_KEY_CONFLICT"
    assert {call[2] for call in owner.reschedule_calls} == {str(key)}
    await db_session.refresh(hold)
    assert hold.state == RequestRescheduleHoldState.COMPLETE
    assert hold.last_retry_requested_by == TEST_USER_ID
    assert hold.last_retry_requested_at is not None
