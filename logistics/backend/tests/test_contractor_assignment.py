"""Focused direct-contractor handoff tests without fabricated route capacity."""

from __future__ import annotations

from datetime import UTC, date, datetime, time
from typing import cast
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models import LogisticsRequest, RoutePlan, UnassignedTask, Warehouse
from app.models.domain import RequestStatus, TaskStatus
from app.schemas.domain import (
    ContractorAssignmentCreate,
    ContractorDispatchCreate,
    RwmsAppliedAssignment,
    RwmsApplyResult,
    RwmsDriverIdentity,
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsRejectedAssignment,
)
from app.services.contractor_assignment import (
    CONTRACTOR_HANDOFF,
    assign_request_to_contractor,
    dispatch_requests_to_contractor,
)
from app.services.workload_generator import GENERATOR_SOURCE_SYSTEM
from tests.factories import make_request, make_warehouse

pytestmark = pytest.mark.integration
PLANNING_DATE = date(2026, 9, 2)


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
    await session.flush()
    return request, client, contractor_id


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
    )

    assert assigned.assignment_type == CONTRACTOR_HANDOFF
    assert assigned.assigned_contractor_name == "Иван Петров"
    assert assigned.status == RequestStatus.PLANNED
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
    )

    assert result.planning_date == PLANNING_DATE
    assert result.mode == "AUTO"
    assert result.assigned_count == 2
    assert set(result.assigned_request_ids) == {delivery.id, pickup.id}
    assert {delivery.status, pickup.status} == {RequestStatus.PLANNED}
    assert client.apply_assignments.await_count == 0
    assert await db_session.get(RoutePlan, plan.id) is None


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
        )

    assert invalid.value.code == "CONTRACTOR_DISPATCH_DATE_MISMATCH"
    assert request.assignment_type is None


@pytest.mark.asyncio
async def test_rwms_handoff_sends_every_unit_slice_without_shift_plan_and_replays(
    db_session: AsyncSession,
) -> None:
    """A real order uses stable vehicle-free assignments and persists only one handoff."""

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
        order_number="R-77",
        client_name="Тестовый клиент",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=3,
        unit_ids=units,
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
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(order_id=source.order_id, document_id=uuid4(), replayed=False),
            RwmsAppliedAssignment(order_id=source.order_id, document_id=uuid4(), replayed=False),
        ],
        rejected=[],
    )
    payload = ContractorAssignmentCreate(contractor_worker_id=contractor_id)

    assigned = await assign_request_to_contractor(
        db_session,
        request.id,
        payload,
        cast(RwmsPlanningClient, client),
    )
    replay = await assign_request_to_contractor(
        db_session,
        request.id,
        payload,
        cast(RwmsPlanningClient, client),
    )

    assert replay is assigned
    assert client.apply_assignments.await_count == 1
    command = client.apply_assignments.await_args.args[0]
    assert command.driver_shift_plans == []
    assert [item.unit_ids for item in command.assignments] == [units[:2], units[2:]]
    assert {item.assignment_type for item in command.assignments} == {
        CONTRACTOR_HANDOFF
    }
    assert {item.driver_worker_id for item in command.assignments} == {contractor_id}
    assert assigned.assigned_by == "logistics-simulator"
    assert assigned.status == RequestStatus.PLANNED


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
        order_number="R-99",
        client_name="Тестовый клиент",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unit_ids=[unit_id],
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
    client.apply_assignments.return_value = RwmsApplyResult(
        applied=[
            RwmsAppliedAssignment(
                order_id=source.order_id,
                document_id=uuid4(),
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
    )
    replayed = await dispatch_requests_to_contractor(
        db_session,
        request.warehouse_id,
        payload,
        cast(RwmsPlanningClient, client),
    )

    assert applied.assigned_request_ids == [request.id]
    assert replayed.assigned_request_ids == [request.id]
    assert client.apply_assignments.await_count == 1
    command = client.apply_assignments.await_args.args[0]
    assert command.driver_shift_plans == []
    assert [assignment.unit_ids for assignment in command.assignments] == [[unit_id]]
    assert command.assignments[0].driver_worker_id == contractor_id


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
        order_number="R-88",
        client_name="Тестовый клиент",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unit_ids=[unit_id],
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
        )

    assert unavailable.value.code == "CONTRACTOR_UNAVAILABLE"
    assert request.assignment_type is None
    assert await db_session.scalar(
        select(LogisticsRequest.assignment_type).where(LogisticsRequest.id == request.id)
    ) is None
