"""Focused direct-contractor handoff tests without fabricated route capacity."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from typing import cast
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.serializers import request_read
from app.db import utc_now
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    ContractorHandoffCommand,
    LogisticsRequest,
    RoutePlan,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import ContractorHandoffStatus, RequestStatus, TaskStatus
from app.schemas.domain import (
    ContractorAssignmentCreate,
    ContractorDispatchCreate,
    RwmsAppliedAssignment,
    RwmsApplyResult,
    RwmsDriverIdentity,
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
    RwmsRejectedAssignment,
)
from app.services.contractor_assignment import (
    CONTRACTOR_HANDOFF,
    _request_handoff_identities,
    apply_claimed_contractor_handoff,
    assign_request_to_contractor,
    claim_due_contractor_handoffs,
    dispatch_requests_to_contractor,
)
from app.services.workload_generator import GENERATOR_SOURCE_SYSTEM
from tests.factories import make_request, make_warehouse

pytestmark = pytest.mark.integration
PLANNING_DATE = date(2026, 9, 2)
TEST_ACTOR = "test-logistics-user"


class ContractorDirectoryClient:
    """Record canonical directory and assignment calls for one contractor."""

    def __init__(self, warehouse_id: UUID, contractor_id: UUID) -> None:
        self.identity = RwmsDriverIdentity.model_validate(
            {
                "workerId": str(contractor_id),
                "displayName": "Иван Петров",
                "employmentType": "CONTRACTOR",
                "phone": "+79990000000",
                "operationalWarehouseId": str(warehouse_id),
                "availableFrom": None,
                "availableUntil": None,
                "availabilityKind": "HOME",
            }
        )
        self.directory_calls: list[tuple[UUID, datetime, bool]] = []
        self.apply_assignments = AsyncMock()

    async def list_drivers(
        self,
        warehouse_id: UUID,
        *,
        at: datetime | None = None,
        include_incoming: bool = False,
    ) -> list[RwmsDriverIdentity]:
        """Return the active warehouse contractor without a profile date interval."""

        assert at is not None
        self.directory_calls.append((warehouse_id, at, include_incoming))
        return [self.identity]


async def _ready_request(
    session: AsyncSession,
    *,
    source_system: str,
    quantity: int = 1,
) -> tuple[LogisticsRequest, ContractorDirectoryClient, UUID]:
    """Create one selected-date delivery and a matching canonical contractor directory."""

    warehouse = await make_warehouse(
        session,
        timezone="Europe/Moscow",
        default_planning_date=PLANNING_DATE,
    )
    request = await make_request(
        session,
        warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=quantity,
    )
    request.source_system = source_system
    request.scheduled_date = PLANNING_DATE
    contractor_id = uuid4()
    client = ContractorDirectoryClient(warehouse.external_warehouse_id, contractor_id)
    if source_system != "RWMS":
        await session.flush()
    return request, client, contractor_id


async def _rwms_delivery(
    session: AsyncSession,
    *,
    quantity: int = 1,
    source_version: int = 12,
) -> tuple[
    LogisticsRequest,
    ContractorDirectoryClient,
    UUID,
    RwmsPlanningRequest,
]:
    """Create one concrete RWMS delivery suitable for durable handoff recovery tests."""

    request, client, contractor_id = await _ready_request(
        session,
        source_system="RWMS",
        quantity=quantity,
    )
    units = [uuid4() for _ in range(quantity)]
    source = RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=source_version,
        source_revision="f" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number=f"R-{source_version}",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=quantity,
        unit_ids=units,
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=client.identity.operational_warehouse_id,
            )
            for unit_id in units
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=28_500,
        price_isochrone_minutes=180,
        created_at=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    await session.flush()
    return request, client, contractor_id, source


@pytest.mark.asyncio
async def test_generated_delivery_handoff_is_local_and_invalidates_mutable_plan(
    db_session: AsyncSession,
) -> None:
    """Synthetic workload never writes RWMS and disappears from normal route planning."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )
    plan = RoutePlan(
        warehouse_id=request.warehouse_id,
        date=PLANNING_DATE,
        name="Mutable plan",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет свободного штатного водителя."],
        )
    )
    await db_session.flush()

    assigned = await assign_request_to_contractor(
        db_session,
        request.id,
        ContractorAssignmentCreate(contractor_worker_id=contractor_id),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert assigned.assignment_type == CONTRACTOR_HANDOFF
    assert assigned.assigned_contractor_name == "Иван Петров"
    assert assigned.status == RequestStatus.PLANNED
    assert assigned.contractor_handoff_command_id is None
    assert assigned.contractor_handoff_sequence is None
    assert assigned.contractor_external_task_ids == []
    assert {task.status for task in assigned.tasks} == {TaskStatus.PLANNED}
    assert client.apply_assignments.await_count == 0
    assert await db_session.get(RoutePlan, plan.id) is None


@pytest.mark.asyncio
async def test_auto_dispatch_uses_header_date_and_assigns_unplanned_delivery_and_pickup(
    db_session: AsyncSession,
) -> None:
    """AUTO hands every latest-plan unassigned request to one vehicle-free contractor."""

    delivery, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )
    warehouse = await db_session.get(Warehouse, delivery.warehouse_id)
    assert warehouse is not None
    pickup = await make_request(
        db_session,
        warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=1,
    )
    pickup.source_system = GENERATOR_SOURCE_SYSTEM
    pickup.scheduled_date = PLANNING_DATE
    pickup.type = "PICKUP"
    pickup.name = "Test pickup"
    for task in pickup.tasks:
        task.type = "PICKUP"
    plan = RoutePlan(
        warehouse_id=delivery.warehouse_id,
        date=PLANNING_DATE,
        name="Unassigned day",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    for request in (delivery, pickup):
        for task in request.tasks:
            db_session.add(
                UnassignedTask(
                    route_plan_id=plan.id,
                    task_id=task.id,
                    reason_codes=["NO_SHIFT_CAPACITY"],
                    descriptions_ru=["Нет свободного штатного водителя."],
                )
            )
    await db_session.flush()

    result = await dispatch_requests_to_contractor(
        db_session,
        delivery.warehouse_id,
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=PLANNING_DATE,
            mode="AUTO",
        ),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert result.planning_date == PLANNING_DATE
    assert result.mode == "AUTO"
    assert result.assigned_count == 2
    assert set(result.assigned_request_ids) == {delivery.id, pickup.id}
    assert result.contractor_handoff_command_id is None
    assert result.external_task_ids == []
    assert {delivery.status, pickup.status} == {RequestStatus.PLANNED}
    assert delivery.contractor_external_task_ids == []
    assert pickup.contractor_external_task_ids == []
    assert delivery.contractor_handoff_sequence is None
    assert pickup.contractor_handoff_sequence is None
    assert client.apply_assignments.await_count == 0
    assert await db_session.get(RoutePlan, plan.id) is None


@pytest.mark.asyncio
async def test_auto_dispatch_skips_rwms_pickup_and_assigns_supported_delivery(
    db_session: AsyncSession,
) -> None:
    """One unsupported RWMS pickup must not abort the supported delivery batch."""

    delivery, client, contractor_id = await _ready_request(
        db_session,
        source_system="RWMS",
    )
    warehouse = await db_session.get(Warehouse, delivery.warehouse_id)
    assert warehouse is not None
    assert warehouse.external_warehouse_id is not None
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=11,
        source_revision="e" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="R-111",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address=delivery.address_label,
        latitude=delivery.latitude,
        longitude=delivery.longitude,
        quantity=1,
        unit_ids=[unit_id],
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=warehouse.external_warehouse_id,
            )
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=None,
        price_isochrone_minutes=None,
        created_at=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    delivery.external_id = source.order_id
    delivery.external_version = source.order_version
    delivery.external_payload = source.model_dump(mode="json", by_alias=True)
    delivery.customer_delivery_purpose = source.customer_delivery_purpose
    pickup = await make_request(
        db_session,
        warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=1,
    )
    pickup.source_system = "RWMS"
    pickup.scheduled_date = PLANNING_DATE
    pickup.type = "PICKUP"
    pickup.name = "Real RWMS pickup"
    for task in pickup.tasks:
        task.type = "PICKUP"
    plan = RoutePlan(
        warehouse_id=delivery.warehouse_id,
        date=PLANNING_DATE,
        name="Mixed real workload",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    for request in (delivery, pickup):
        for task in request.tasks:
            db_session.add(
                UnassignedTask(
                    route_plan_id=plan.id,
                    task_id=task.id,
                    reason_codes=["NO_SHIFT_CAPACITY"],
                    descriptions_ru=["Нет свободного штатного водителя."],
                )
            )
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            )
        ],
        rejected=[],
    )
    await db_session.flush()

    result = await dispatch_requests_to_contractor(
        db_session,
        delivery.warehouse_id,
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=PLANNING_DATE,
            mode="AUTO",
        ),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert result.assigned_request_ids == [delivery.id]
    assert result.assigned_count == 1
    assert delivery.assignment_type == CONTRACTOR_HANDOFF
    assert pickup.assignment_type is None
    assert pickup.status == RequestStatus.READY
    assert client.apply_assignments.await_count == 1
    await_args = client.apply_assignments.await_args
    assert await_args is not None
    command = await_args.args[0]
    assert [assignment.order_id for assignment in command.assignments] == [source.order_id]


@pytest.mark.asyncio
async def test_manual_dispatch_rejects_rwms_pickup_without_side_effects(
    db_session: AsyncSession,
) -> None:
    """An unsupported real pickup is rejected before catalog, plan, or RWMS writes."""

    pickup, client, contractor_id = await _ready_request(
        db_session,
        source_system="RWMS",
    )
    pickup.type = "PICKUP"
    pickup.name = "Real RWMS pickup"
    for task in pickup.tasks:
        task.type = "PICKUP"
    plan = RoutePlan(
        warehouse_id=pickup.warehouse_id,
        date=PLANNING_DATE,
        name="Protected manual plan",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=pickup.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет свободного штатного водителя."],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as unsupported:
        await dispatch_requests_to_contractor(
            db_session,
            pickup.warehouse_id,
            ContractorDispatchCreate(
                contractor_worker_id=contractor_id,
                planning_date=PLANNING_DATE,
                mode="MANUAL",
                request_ids=[pickup.id],
            ),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert unsupported.value.code == "RWMS_CONTRACTOR_PICKUP_UNSUPPORTED"
    assert unsupported.value.detail == (
        "Вывоз RWMS пока нельзя передать наёмному водителю: назначьте внутренний маршрут."
    )
    assert pickup.assignment_type is None
    assert pickup.status == RequestStatus.READY
    assert {task.status for task in pickup.tasks} == {TaskStatus.READY}
    assert await db_session.get(RoutePlan, plan.id) is plan
    assert client.directory_calls == []
    assert client.apply_assignments.await_count == 0


@pytest.mark.asyncio
async def test_manual_dispatch_assigns_only_explicit_requests_from_header_date(
    db_session: AsyncSession,
) -> None:
    """MANUAL validates the server-side selection and leaves other requests untouched."""

    selected, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )
    warehouse = await db_session.get(Warehouse, selected.warehouse_id)
    assert warehouse is not None
    untouched = await make_request(
        db_session,
        warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=1,
    )
    untouched.source_system = GENERATOR_SOURCE_SYSTEM
    untouched.scheduled_date = PLANNING_DATE
    await db_session.flush()

    result = await dispatch_requests_to_contractor(
        db_session,
        selected.warehouse_id,
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=PLANNING_DATE,
            mode="MANUAL",
            request_ids=[selected.id],
        ),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert result.assigned_request_ids == [selected.id]
    assert selected.status == RequestStatus.PLANNED
    assert untouched.status == RequestStatus.READY


@pytest.mark.asyncio
async def test_manual_dispatch_rejects_request_from_another_warehouse(
    db_session: AsyncSession,
) -> None:
    """The server never trusts a manually submitted cross-warehouse request id."""

    selected, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )
    other_warehouse = await make_warehouse(
        db_session,
        name="Other warehouse",
        default_planning_date=PLANNING_DATE,
    )
    foreign_request = await make_request(
        db_session,
        other_warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=1,
    )
    foreign_request.source_system = GENERATOR_SOURCE_SYSTEM
    foreign_request.scheduled_date = PLANNING_DATE
    await db_session.flush()

    with pytest.raises(ApiError) as invalid:
        await dispatch_requests_to_contractor(
            db_session,
            selected.warehouse_id,
            ContractorDispatchCreate(
                contractor_worker_id=contractor_id,
                planning_date=PLANNING_DATE,
                mode="MANUAL",
                request_ids=[foreign_request.id],
            ),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert invalid.value.code == "CONTRACTOR_DISPATCH_WAREHOUSE_MISMATCH"
    assert foreign_request.assignment_type is None


@pytest.mark.asyncio
async def test_manual_dispatch_rejects_request_from_another_header_date(
    db_session: AsyncSession,
) -> None:
    """The date selected in the header is the strict boundary for manual handoff."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )

    with pytest.raises(ApiError) as invalid:
        await dispatch_requests_to_contractor(
            db_session,
            request.warehouse_id,
            ContractorDispatchCreate(
                contractor_worker_id=contractor_id,
                planning_date=date(2026, 9, 3),
                mode="MANUAL",
                request_ids=[request.id],
            ),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert invalid.value.code == "CONTRACTOR_DISPATCH_DATE_MISMATCH"
    assert request.assignment_type is None


@pytest.mark.asyncio
async def test_rwms_handoff_persists_reordered_exact_tasks_and_replays(
    db_session: AsyncSession,
) -> None:
    """A real order retains deterministic exact task IDs despite upstream response order."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system="RWMS",
        quantity=3,
    )
    units = [uuid4(), uuid4(), uuid4()]
    source = RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=7,
        source_revision="a" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="R-77",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=3,
        unit_ids=units,
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=client.identity.operational_warehouse_id,
            )
            for unit_id in units
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=28_500,
        price_isochrone_minutes=180,
        created_at=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    first_external_task_id = UUID(int=2)
    second_external_task_id = UUID(int=1)
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=first_external_task_id,
                task_version=1,
                replayed=False,
            ),
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=second_external_task_id,
                task_version=1,
                replayed=False,
            ),
        ],
        rejected=[],
    )
    payload = ContractorAssignmentCreate(contractor_worker_id=contractor_id)

    assigned = await assign_request_to_contractor(
        db_session,
        request.id,
        payload,
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )
    replay = await assign_request_to_contractor(
        db_session,
        request.id,
        payload,
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert replay is assigned
    assert client.apply_assignments.await_count == 1
    await_args = client.apply_assignments.await_args
    assert await_args is not None
    command = await_args.args[0]
    assert command.driver_shift_plans == []
    assert [item.unit_ids for item in command.assignments] == [units[:2], units[2:]]
    assert {item.assignment_type for item in command.assignments} == {
        CONTRACTOR_HANDOFF
    }
    assert {item.driver_worker_id for item in command.assignments} == {contractor_id}
    assert {item.inventory_source_warehouse_id for item in command.assignments} == {
        client.identity.operational_warehouse_id
    }
    assert assigned.assigned_by == TEST_ACTOR
    assert assigned.status == RequestStatus.PLANNED
    assert assigned.contractor_handoff_command_id is not None
    assert assigned.contractor_handoff_sequence == 0
    assert assigned.contractor_external_task_ids == [
        str(second_external_task_id),
        str(first_external_task_id),
    ]
    serialized = await request_read(db_session, assigned)
    assert serialized.contractor_handoff_command_id == assigned.contractor_handoff_command_id
    assert serialized.contractor_handoff_sequence == 0
    assert serialized.external_task_ids == [
        second_external_task_id,
        first_external_task_id,
    ]


@pytest.mark.asyncio
async def test_rwms_batch_reloads_exact_task_ids_in_immutable_command_order(
    db_session: AsyncSession,
) -> None:
    """Response and database row order cannot change the contractor route identity order."""

    first, client, contractor_id, first_source = await _rwms_delivery(db_session)
    warehouse = await db_session.get(Warehouse, first.warehouse_id)
    assert warehouse is not None
    second = await make_request(
        db_session,
        warehouse,
        planning_date=PLANNING_DATE,
        mandatory=True,
        quantity=1,
    )
    second.source_system = "RWMS"
    second.scheduled_date = PLANNING_DATE
    second_unit_id = uuid4()
    second_source = first_source.model_copy(
        update={
            "order_id": uuid4(),
            "order_version": first_source.order_version + 1,
            "source_revision": "e" * 64,
            "order_number": "R-batch-second",
            "unit_ids": [second_unit_id],
            "unit_reservations": [
                RwmsPlanningUnitReservation(
                    unit_id=second_unit_id,
                    inventory_source_warehouse_id=client.identity.operational_warehouse_id,
                )
            ],
        }
    )
    second.external_id = second_source.order_id
    second.external_version = second_source.order_version
    second.external_payload = second_source.model_dump(mode="json", by_alias=True)
    second.customer_delivery_purpose = second_source.customer_delivery_purpose
    await db_session.flush()

    command_requests = sorted((first, second), key=lambda request: str(request.id))
    task_ids_by_order = {
        first_source.order_id: UUID(int=11),
        second_source.order_id: UUID(int=22),
    }
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=request.external_id,
                order_version=request.external_version or 0,
                document_id=uuid4(),
                external_task_id=task_ids_by_order[request.external_id],
                task_version=1,
                replayed=False,
            )
            for request in reversed(command_requests)
            if request.external_id is not None
        ],
        rejected=[],
    )
    initial_payload = ContractorDispatchCreate(
        contractor_worker_id=contractor_id,
        planning_date=PLANNING_DATE,
        mode="MANUAL",
        request_ids=[first.id, second.id],
    )

    initial = await dispatch_requests_to_contractor(
        db_session,
        first.warehouse_id,
        initial_payload,
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )
    expected_task_ids = [
        task_ids_by_order[request.external_id]
        for request in command_requests
        if request.external_id is not None
    ]
    assert initial.assigned_request_ids == [request.id for request in command_requests]
    assert initial.external_task_ids == expected_task_ids
    assert [request.contractor_handoff_sequence for request in command_requests] == [0, 1]

    reloaded_command_id, reloaded_task_ids = _request_handoff_identities(
        list(reversed(command_requests))
    )
    assert reloaded_command_id == initial.contractor_handoff_command_id
    assert reloaded_task_ids == expected_task_ids

    replay = await dispatch_requests_to_contractor(
        db_session,
        first.warehouse_id,
        initial_payload.model_copy(update={"request_ids": [second.id, first.id]}),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )
    assert replay.external_task_ids == expected_task_ids
    assert client.apply_assignments.await_count == 1

    command_requests[1].contractor_handoff_sequence = None
    with pytest.raises(RuntimeError, match="sequence is missing"):
        _request_handoff_identities(command_requests)
    command_requests[1].contractor_handoff_sequence = 0
    with pytest.raises(RuntimeError, match="sequence is duplicated"):
        _request_handoff_identities(command_requests)
    command_requests[1].contractor_handoff_sequence = 2
    with pytest.raises(RuntimeError, match="sequence is not contiguous"):
        _request_handoff_identities(command_requests)
    command_requests[1].contractor_handoff_sequence = 1
    command_requests[1].contractor_handoff_command_id = uuid4()
    with pytest.raises(RuntimeError, match="mixed commands"):
        _request_handoff_identities(command_requests)


@pytest.mark.asyncio
async def test_rwms_handoff_rejects_mixed_source_task_before_http(
    db_session: AsyncSession,
) -> None:
    """Keep one contractor shipment slice bound to one physical source warehouse."""

    request, client, contractor_id, source = await _rwms_delivery(
        db_session,
        quantity=2,
    )
    mixed_source = source.model_copy(
        update={
            "unit_reservations": [
                source.unit_reservations[0],
                RwmsPlanningUnitReservation(
                    unit_id=source.unit_ids[1],
                    inventory_source_warehouse_id=uuid4(),
                ),
            ]
        }
    )
    request.external_payload = mixed_source.model_dump(mode="json", by_alias=True)
    await db_session.flush()

    with pytest.raises(ApiError) as error:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert error.value.code == "RWMS_MIXED_INVENTORY_SOURCE"
    client.apply_assignments.assert_not_awaited()
    assert request.assignment_type is None


@pytest.mark.asyncio
async def test_manual_dispatch_applies_real_rwms_delivery_once_and_replays(
    db_session: AsyncSession,
) -> None:
    """The warehouse-day command owns one stable upstream side effect and local replay."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system="RWMS",
    )
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=9,
        source_revision="d" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="R-99",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unit_ids=[unit_id],
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=client.identity.operational_warehouse_id,
            )
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=None,
        price_isochrone_minutes=None,
        created_at=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    external_task_id = uuid4()
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=external_task_id,
                task_version=1,
                replayed=False,
            )
        ],
        rejected=[],
    )
    payload = ContractorDispatchCreate(
        contractor_worker_id=contractor_id,
        planning_date=PLANNING_DATE,
        mode="MANUAL",
        request_ids=[request.id],
    )

    applied = await dispatch_requests_to_contractor(
        db_session,
        request.warehouse_id,
        payload,
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )
    replayed = await dispatch_requests_to_contractor(
        db_session,
        request.warehouse_id,
        payload,
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert applied.assigned_request_ids == [request.id]
    assert replayed.assigned_request_ids == [request.id]
    assert applied.contractor_handoff_command_id is not None
    assert replayed.contractor_handoff_command_id == applied.contractor_handoff_command_id
    assert applied.external_task_ids == [external_task_id]
    assert replayed.external_task_ids == [external_task_id]
    assert request.contractor_handoff_command_id == applied.contractor_handoff_command_id
    assert request.contractor_handoff_sequence == 0
    assert request.contractor_external_task_ids == [str(external_task_id)]
    assert client.apply_assignments.await_count == 1
    await_args = client.apply_assignments.await_args
    assert await_args is not None
    command = await_args.args[0]
    assert command.driver_shift_plans == []
    assert [assignment.unit_ids for assignment in command.assignments] == [[unit_id]]
    assert command.assignments[0].driver_worker_id == contractor_id
    assert (
        command.assignments[0].inventory_source_warehouse_id
        == client.identity.operational_warehouse_id
    )


@pytest.mark.asyncio
async def test_rejected_rwms_handoff_does_not_persist_assignment(
    db_session: AsyncSession,
) -> None:
    """A domain rejection leaves the request ready and eligible for another decision."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system="RWMS",
    )
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=4,
        source_revision="c" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="R-88",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unit_ids=[unit_id],
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=client.identity.operational_warehouse_id,
            )
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                window_start=time(10),
                window_end=time(14),
                is_hard=True,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=None,
        price_isochrone_minutes=None,
        created_at=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[],
        rejected=[
            RwmsRejectedAssignment(
                order_id=source.order_id,
                code="PLANNING_DATE_LOCKED",
                message="internal source message",
            )
        ],
    )

    with pytest.raises(ApiError) as rejected:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert rejected.value.code == "CONTRACTOR_ASSIGNMENT_REJECTED"
    assert request.assignment_type is None
    assert request.status == RequestStatus.READY
    assert {task.status for task in request.tasks} == {TaskStatus.READY}


@pytest.mark.asyncio
async def test_staff_identity_cannot_be_used_for_contractor_handoff(
    db_session: AsyncSession,
) -> None:
    """The normal driver type is validated at the backend boundary, not in React."""

    request, client, contractor_id = await _ready_request(
        db_session,
        source_system=GENERATOR_SOURCE_SYSTEM,
    )
    client.identity = client.identity.model_copy(update={"employment_type": "STAFF"})

    with pytest.raises(ApiError) as unavailable:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert unavailable.value.code == "CONTRACTOR_UNAVAILABLE"
    assert request.assignment_type is None
    assert await db_session.scalar(
        select(LogisticsRequest.assignment_type).where(LogisticsRequest.id == request.id)
    ) is None


@pytest.mark.asyncio
async def test_rwms_handoff_releases_transaction_before_directory_and_apply(
    db_session: AsyncSession,
) -> None:
    """Neither canonical directory lookup nor assignment apply runs under a DB transaction."""

    request, client, contractor_id, source = await _rwms_delivery(db_session)
    directory_states: list[bool] = []
    apply_states: list[bool] = []

    async def list_drivers(
        warehouse_id: UUID,
        *,
        at: datetime | None = None,
        include_incoming: bool = False,
    ) -> list[RwmsDriverIdentity]:
        directory_states.append(db_session.in_transaction())
        assert warehouse_id == request.warehouse.external_warehouse_id
        assert at is not None
        assert include_incoming is False
        return [client.identity]

    async def apply_assignments(
        command: object,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        del command, idempotency_key
        apply_states.append(db_session.in_transaction())
        reserved = await db_session.get(LogisticsRequest, request.id)
        assert reserved is not None
        assert reserved.status == RequestStatus.DRAFT
        assert reserved.assignment_type is None
        await db_session.commit()
        return RwmsApplyResult(
            applied=[
                RwmsAppliedAssignment(
                    order_id=source.order_id,
                    order_version=source.order_version,
                    document_id=uuid4(),
                    external_task_id=uuid4(),
                    task_version=1,
                    replayed=False,
                )
            ],
            rejected=[],
        )

    client.list_drivers = list_drivers  # type: ignore[method-assign]
    client.apply_assignments.side_effect = apply_assignments

    assigned = await assign_request_to_contractor(
        db_session,
        request.id,
        ContractorAssignmentCreate(contractor_worker_id=contractor_id),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert directory_states == [False]
    assert apply_states == [False]
    assert assigned.status == RequestStatus.PLANNED
    assert assigned.assignment_type == CONTRACTOR_HANDOFF


@pytest.mark.asyncio
async def test_lost_response_keeps_recoverable_pending_command_and_replays_same_key(
    db_session: AsyncSession,
) -> None:
    """A lost response never reports completion and worker replay cannot duplicate the effect."""

    request, client, contractor_id, source = await _rwms_delivery(db_session)
    idempotency_keys: list[str] = []

    async def lost_response(
        command: object,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        del command
        idempotency_keys.append(idempotency_key)
        raise ApiError(502, "RWMS_REQUEST_FAILED", "upstream response was lost")

    client.apply_assignments.side_effect = lost_response
    with pytest.raises(ApiError) as pending:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert pending.value.code == "CONTRACTOR_HANDOFF_PENDING"
    command = await db_session.scalar(select(ContractorHandoffCommand))
    assert command is not None
    immutable_payload = command.command_payload
    assert command.status == ContractorHandoffStatus.PENDING
    assert command.error_code == "RWMS_REQUEST_FAILED"
    assert command.next_attempt_at is not None
    assert request.status == RequestStatus.DRAFT
    assert request.assignment_type is None
    assert request.contractor_handoff_command_id == command.id

    command.next_attempt_at = utc_now() - timedelta(seconds=1)
    await db_session.commit()
    claimed = await claim_due_contractor_handoffs(db_session, limit=10)
    await db_session.commit()
    assert len(claimed) == 1

    async def replay_success(
        command_payload: object,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        del command_payload
        idempotency_keys.append(idempotency_key)
        return RwmsApplyResult(
            applied=[
                RwmsAppliedAssignment(
                    order_id=source.order_id,
                    order_version=source.order_version,
                    document_id=uuid4(),
                    external_task_id=uuid4(),
                    task_version=1,
                    replayed=True,
                )
            ],
            rejected=[],
        )

    client.apply_assignments.side_effect = replay_success
    await apply_claimed_contractor_handoff(
        db_session,
        claimed[0],
        cast(RwmsPlanningClient, client),
    )

    recovered = await db_session.get(ContractorHandoffCommand, command.id)
    assert recovered is not None
    assert recovered.status == ContractorHandoffStatus.SUCCEEDED
    assert recovered.command_payload == immutable_payload
    assert idempotency_keys == [str(command.id), str(command.id)]
    assert request.status == RequestStatus.PLANNED
    assert request.assignment_type == CONTRACTOR_HANDOFF
    assert request.assigned_by == TEST_ACTOR


@pytest.mark.asyncio
async def test_plan_invalidation_waits_for_successful_rwms_finalization(
    db_session: AsyncSession,
) -> None:
    """The staged intent reserves work but leaves mutable plans intact until RWMS applies it."""

    request, client, contractor_id, source = await _rwms_delivery(db_session)
    plan = RoutePlan(
        warehouse_id=request.warehouse_id,
        date=PLANNING_DATE,
        name="Plan awaiting contractor apply",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет свободного штатного водителя."],
        )
    )

    async def inspect_before_success(
        command: object,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        del command, idempotency_key
        assert await db_session.get(RoutePlan, plan.id) is not None
        await db_session.commit()
        return RwmsApplyResult(
            applied=[
                RwmsAppliedAssignment(
                    order_id=source.order_id,
                    order_version=source.order_version,
                    document_id=uuid4(),
                    external_task_id=uuid4(),
                    task_version=1,
                    replayed=False,
                )
            ],
            rejected=[],
        )

    client.apply_assignments.side_effect = inspect_before_success
    await assign_request_to_contractor(
        db_session,
        request.id,
        ContractorAssignmentCreate(contractor_worker_id=contractor_id),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    assert await db_session.get(RoutePlan, plan.id) is None


@pytest.mark.asyncio
async def test_typed_rwms_rejection_is_terminal_and_releases_reservation(
    db_session: AsyncSession,
) -> None:
    """A typed domain rejection is retained for action and is never retried as transient."""

    request, client, contractor_id, source = await _rwms_delivery(db_session)
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[],
        rejected=[
            RwmsRejectedAssignment(
                order_id=source.order_id,
                code="PLANNING_DATE_LOCKED",
                message="internal source message",
            )
        ],
    )

    with pytest.raises(ApiError) as rejected:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert rejected.value.code == "CONTRACTOR_ASSIGNMENT_REJECTED"
    assert rejected.value.extra == {"rejection_codes": ["PLANNING_DATE_LOCKED"]}
    command = await db_session.scalar(select(ContractorHandoffCommand))
    assert command is not None
    assert command.status == ContractorHandoffStatus.REJECTED
    assert command.rejection_codes == ["PLANNING_DATE_LOCKED"]
    assert command.next_attempt_at is None
    assert command.lease_until is None
    assert request.status == RequestStatus.READY
    assert request.assignment_type is None
    assert request.contractor_handoff_command_id is None
    assert client.apply_assignments.await_count == 1


@pytest.mark.asyncio
async def test_rejected_handoff_can_retry_after_source_revision_changes(
    db_session: AsyncSession,
) -> None:
    """A new reservation snapshot must not replay a rejected command identity."""

    request, client, contractor_id, source = await _rwms_delivery(db_session)
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[],
        rejected=[
            RwmsRejectedAssignment(
                order_id=source.order_id,
                code="PLANNING_DATE_LOCKED",
                message="internal source message",
            )
        ],
    )

    with pytest.raises(ApiError):
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    first_command = await db_session.scalar(select(ContractorHandoffCommand))
    assert first_command is not None
    revised_source_warehouse_id = uuid4()
    revised_source = source.model_copy(
        update={
            "source_revision": "e" * 64,
            "unit_reservations": [
                reservation.model_copy(
                    update={
                        "inventory_source_warehouse_id": revised_source_warehouse_id
                    }
                )
                for reservation in source.unit_reservations
            ],
        }
    )
    request.external_payload = revised_source.model_dump(mode="json", by_alias=True)
    await db_session.commit()
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            )
        ],
        rejected=[],
    )

    assigned = await assign_request_to_contractor(
        db_session,
        request.id,
        ContractorAssignmentCreate(contractor_worker_id=contractor_id),
        cast(RwmsPlanningClient, client),
        assigned_by=TEST_ACTOR,
    )

    commands = list(
        await db_session.scalars(
            select(ContractorHandoffCommand).order_by(ContractorHandoffCommand.created_at)
        )
    )
    assert len(commands) == 2
    assert commands[0].id == first_command.id
    assert commands[0].status == ContractorHandoffStatus.REJECTED
    assert commands[1].id != commands[0].id
    assert commands[1].status == ContractorHandoffStatus.SUCCEEDED
    assert assigned.assignment_type == CONTRACTOR_HANDOFF
    second_call = client.apply_assignments.await_args_list[1]
    second_command = second_call.args[0]
    assert second_command.assignments[0].inventory_source_warehouse_id == (
        revised_source_warehouse_id
    )


@pytest.mark.asyncio
async def test_partial_rwms_result_stays_pending_and_retains_every_reservation(
    db_session: AsyncSession,
) -> None:
    """A mixed applied/rejected response cannot be rolled back or reported as completed."""

    request, client, contractor_id, source = await _rwms_delivery(
        db_session,
        quantity=3,
        source_version=13,
    )
    plan = RoutePlan(
        warehouse_id=request.warehouse_id,
        date=PLANNING_DATE,
        name="Plan protected by partial handoff",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет свободного штатного водителя."],
        )
    )
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            )
        ],
        rejected=[
            RwmsRejectedAssignment(
                order_id=source.order_id,
                code="ASSIGNMENT_SLICE_CONFLICT",
                message="internal source message",
            )
        ],
    )

    with pytest.raises(ApiError) as pending:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert pending.value.code == "CONTRACTOR_HANDOFF_PENDING"
    command = await db_session.scalar(select(ContractorHandoffCommand))
    assert command is not None
    assert command.status == ContractorHandoffStatus.PENDING
    assert command.error_code == "RWMS_PARTIAL_ASSIGNMENT_RESULT"
    assert command.rejection_codes == ["ASSIGNMENT_SLICE_CONFLICT"]
    assert command.next_attempt_at is not None
    assert command.lease_until is None
    assert request.status == RequestStatus.DRAFT
    assert request.assignment_type is None
    assert request.assigned_contractor_worker_id is None
    assert request.contractor_handoff_command_id == command.id
    assert {task.status for task in request.tasks} == {TaskStatus.READY}
    assert await db_session.get(RoutePlan, plan.id) is not None

    first_key = client.apply_assignments.await_args_list[0].kwargs["idempotency_key"]
    command.next_attempt_at = utc_now() - timedelta(seconds=1)
    await db_session.commit()
    claimed = await claim_due_contractor_handoffs(db_session, limit=1)
    await db_session.commit()
    assert len(claimed) == 1
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[],
        rejected=[
            RwmsRejectedAssignment(
                order_id=source.order_id,
                code="ASSIGNMENT_SLICE_CONFLICT",
                message="same typed rejection after a prior partial apply",
            )
        ],
    )
    with pytest.raises(ApiError) as replay_pending:
        await apply_claimed_contractor_handoff(
            db_session,
            claimed[0],
            cast(RwmsPlanningClient, client),
        )

    assert replay_pending.value.code == "CONTRACTOR_HANDOFF_PENDING"
    assert client.apply_assignments.await_count == 2
    second_key = client.apply_assignments.await_args_list[1].kwargs["idempotency_key"]
    assert first_key == second_key == str(command.id)
    assert command.status == ContractorHandoffStatus.PENDING
    assert request.status == RequestStatus.DRAFT
    assert request.assignment_type is None
    assert await db_session.get(RoutePlan, plan.id) is not None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "response_case",
    ["missing", "foreign", "duplicate_order", "duplicate_task"],
)
async def test_mismatched_rwms_task_identities_never_finalize_local_assignment(
    db_session: AsyncSession,
    response_case: str,
) -> None:
    """Incomplete, foreign or duplicated applied identities remain recoverably pending."""

    quantity = 3 if response_case == "duplicate_task" else 1
    request, client, contractor_id, source = await _rwms_delivery(
        db_session,
        quantity=quantity,
    )
    if response_case == "missing":
        applied: list[RwmsAppliedAssignment] = []
    elif response_case == "foreign":
        applied = [
            RwmsAppliedAssignment(
                order_id=uuid4(),
                order_version=0,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            )
        ]
    elif response_case == "duplicate_order":
        applied = [
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            ),
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=uuid4(),
                task_version=1,
                replayed=False,
            ),
        ]
    else:
        repeated_task_id = uuid4()
        applied = [
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=repeated_task_id,
                task_version=1,
                replayed=False,
            ),
            RwmsAppliedAssignment(
                order_id=source.order_id,
                order_version=source.order_version,
                document_id=uuid4(),
                external_task_id=repeated_task_id,
                task_version=1,
                replayed=False,
            ),
        ]
    client.apply_assignments.return_value = RwmsApplyResult(applied=applied, rejected=[])

    with pytest.raises(ApiError) as pending:
        await assign_request_to_contractor(
            db_session,
            request.id,
            ContractorAssignmentCreate(contractor_worker_id=contractor_id),
            cast(RwmsPlanningClient, client),
            assigned_by=TEST_ACTOR,
        )

    assert pending.value.code == "CONTRACTOR_HANDOFF_PENDING"
    command = await db_session.scalar(select(ContractorHandoffCommand))
    assert command is not None
    assert command.status == ContractorHandoffStatus.PENDING
    assert request.status == RequestStatus.DRAFT
    assert request.assignment_type is None
    assert request.contractor_handoff_command_id == command.id
    assert request.contractor_external_task_ids == []
    assert {task.status for task in request.tasks} == {TaskStatus.READY}
