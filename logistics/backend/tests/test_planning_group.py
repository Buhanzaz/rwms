"""Focused integration tests for direct representative planning groups."""

from __future__ import annotations

from datetime import UTC, date, datetime, time
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.catalog import get_warehouse_workspace
from app.config import Settings
from app.integrations.rwms_sync import _load_plan_for_rwms_apply, build_assignments_command
from app.models import RoutePlan, Warehouse
from app.models.domain import PlanStatus
from app.schemas.domain import (
    GeneratePlanRequest,
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsWarehouseIdentity,
    RwmsWarehouseSupportLink,
)
from app.services.auto_planning import invalidate_mutable_group_root_plans
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.planning_group import link_allows_group_planning
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


def _identity(warehouse: Warehouse) -> RwmsWarehouseIdentity:
    """Translate a local test projection into the strict canonical identity shape."""

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


def _link(
    root: Warehouse,
    representative: Warehouse,
    *,
    excluded_dates: list[date] | None = None,
) -> RwmsWarehouseSupportLink:
    """Build one active direct support edge returned by the frozen network endpoint."""

    return RwmsWarehouseSupportLink(
        supportLinkId=uuid4(),
        supportLinkVersion=1,
        supportWarehouse=_identity(root),
        servedWarehouse=_identity(representative),
        priority=1,
        allowDrivers=True,
        allowVehicles=True,
        allowInventory=True,
        allowDirectFulfillment=True,
        allowInterwarehouseTransfer=True,
        allowContractorFallback=True,
        allowedWeekdays=[],
        allowedDates=[],
        excludedDates=excluded_dates or [],
        serviceStart=time(8),
        serviceEnd=time(20),
    )


class _NetworkClient:
    """Minimal adjacent-network double used without exercising remote synchronization."""

    def __init__(self, links: list[RwmsWarehouseSupportLink]) -> None:
        self.links = links
        self.calls: list[UUID] = []

    async def list_support_network(
        self,
        warehouse_id: UUID,
    ) -> list[RwmsWarehouseSupportLink]:
        """Return only edges adjacent to the requested canonical warehouse."""

        self.calls.append(warehouse_id)
        return [
            link
            for link in self.links
            if warehouse_id
            in {
                link.support_warehouse.warehouse_id,
                link.served_warehouse.warehouse_id,
            }
        ]


def test_contractor_fallback_keeps_regional_demand_in_the_group() -> None:
    """A contractor-only edge still admits demand so the planner can explain the fallback."""

    root = SimpleNamespace(
        external_warehouse_id=uuid4(),
        external_warehouse_version=1,
        name="Main",
        city="Main city",
        address=None,
        latitude=59.9,
        longitude=30.3,
        timezone="Europe/Moscow",
        representative=False,
        routing_ready=True,
    )
    representative = SimpleNamespace(
        external_warehouse_id=uuid4(),
        external_warehouse_version=1,
        name="Representative",
        city="Regional city",
        address=None,
        latitude=58.5,
        longitude=31.3,
        timezone="Europe/Moscow",
        representative=True,
        routing_ready=True,
    )
    link = _link(root, representative)  # type: ignore[arg-type]
    contractor_only = link.model_copy(
        update={
            "allow_drivers": False,
            "allow_direct_fulfillment": False,
            "allow_contractor_fallback": True,
        }
    )

    assert link_allows_group_planning(contractor_only)


def _enabled_settings() -> Settings:
    """Enable only the frozen directory boundary required by workspace resolution."""

    return Settings(
        rwms_sync_enabled=True,
        rwms_logistics_base_url="https://rwms.internal",
        rwms_token_url="https://auth.internal/oauth2/token",
        rwms_client_id="planner",
        rwms_client_secret="secret",
    )


@pytest.mark.asyncio
async def test_workspace_exposes_root_and_representative_requests_once(
    db_session: AsyncSession,
) -> None:
    """A selected root sees direct regional demand while all resources remain root-owned."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    root_request = await make_request(db_session, root, planning_date=planning_date)
    representative_request = await make_request(
        db_session,
        representative,
        planning_date=planning_date,
    )
    client = _NetworkClient([_link(root, representative)])

    workspace = await get_warehouse_workspace(
        root.id,
        db_session,
        SimpleNamespace(),
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        refresh_rwms=False,
    )

    assert workspace.planning_root_warehouse_id == root.id
    assert workspace.planning_group_warehouse_ids == [root.id, representative.id]
    assert {item.id for item in workspace.requests} == {
        root_request.id,
        representative_request.id,
    }
    assert len(workspace.requests) == 2


@pytest.mark.asyncio
async def test_representative_selection_resolves_the_same_root_without_duplicates(
    db_session: AsyncSession,
) -> None:
    """Selecting a representative preserves selection but exposes its main planning root."""

    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    link = _link(root, representative)
    client = _NetworkClient([link, link])

    workspace = await get_warehouse_workspace(
        representative.id,
        db_session,
        SimpleNamespace(),
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        refresh_rwms=False,
    )

    assert workspace.warehouse.id == representative.id
    assert workspace.planning_root_warehouse_id == root.id
    assert workspace.planning_group_warehouse_ids == [root.id, representative.id]


@pytest.mark.asyncio
async def test_planner_excludes_representative_demand_on_link_calendar_exception(
    db_session: AsyncSession,
) -> None:
    """An exact exclusion keeps regional demand out of the root's dated planning snapshot."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    root_request = await make_request(db_session, root, planning_date=planning_date)
    representative_request = await make_request(
        db_session,
        representative,
        planning_date=planning_date,
    )
    client = _NetworkClient(
        [_link(root, representative, excluded_dates=[planning_date])]
    )

    snapshot = await RuntimePlannerFacade(
        rwms_client=client,  # type: ignore[arg-type]
    )._load_snapshot(db_session, root.id, planning_date, None, None)

    assert {item.id for item in snapshot.input_data.requests} == {str(root_request.id)}
    assert str(representative_request.id) not in snapshot.request_task_uuids


@pytest.mark.asyncio
async def test_planner_aggregates_calendar_eligible_representative_demand(
    db_session: AsyncSession,
) -> None:
    """An admitted direct representative request becomes a task in the root plan snapshot."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    root_request = await make_request(db_session, root, planning_date=planning_date)
    representative_request = await make_request(
        db_session,
        representative,
        planning_date=planning_date,
    )
    client = _NetworkClient([_link(root, representative)])

    snapshot = await RuntimePlannerFacade(
        rwms_client=client,  # type: ignore[arg-type]
    )._load_snapshot(db_session, root.id, planning_date, None, None)

    assert {item.id for item in snapshot.input_data.requests} == {
        str(root_request.id),
        str(representative_request.id),
    }
    assert str(representative_request.id) in snapshot.request_task_uuids
    assert representative_request.warehouse_id == representative.id


@pytest.mark.asyncio
async def test_root_plan_assignment_retains_representative_service_warehouse(
    db_session: AsyncSession,
) -> None:
    """Apply keeps the route origin at root and identifies the regional order owner."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    request = await make_request(
        db_session,
        representative,
        planning_date=planning_date,
        quantity=1,
    )
    unit_id = uuid4()
    source = RwmsPlanningRequest(
        orderId=uuid4(),
        orderVersion=2,
        sourceRevision="a" * 64,
        orderNumber="REG-1",
        clientName="Regional client",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unitIds=[unit_id],
        dateOptions=[
            RwmsPlanningDateOption(
                date=planning_date,
                priority=1,
                isHard=False,
            )
        ],
        trailerAccessAllowed=True,
        createdAt=datetime(2026, 8, 29, 8, tzinfo=UTC),
    )
    request.source_system = "RWMS"
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    driver = await make_driver(db_session, root)
    vehicle = await make_vehicle(db_session, root)
    await make_shift(
        db_session,
        root,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    await db_session.flush()
    client = _NetworkClient([_link(root, representative)])
    planner = RuntimePlannerFacade(rwms_client=client)  # type: ignore[arg-type]

    run = await planner.generate_plan(
        db_session,
        root.id,
        GeneratePlanRequest(date=planning_date, seed=root.seed),
    )
    assert run.plan_id is not None
    plan = await _load_plan_for_rwms_apply(db_session, run.plan_id)
    command = build_assignments_command(plan)

    assert command.warehouse_id == root.external_warehouse_id
    assert len(command.assignments) == 1
    assert command.assignments[0].service_warehouse_id == (
        representative.external_warehouse_id
    )
    assert command.assignments[0].unit_ids == [unit_id]


@pytest.mark.asyncio
async def test_group_refresh_invalidates_only_recomputable_root_plan(
    db_session: AsyncSession,
) -> None:
    """Regional feed refresh removes stale drafts but never deletes a confirmed route."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    draft = RoutePlan(
        warehouse_id=root.id,
        date=planning_date,
        name="Draft",
        status=PlanStatus.DRAFT,
    )
    confirmed = RoutePlan(
        warehouse_id=root.id,
        date=planning_date,
        name="Confirmed",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add_all([draft, confirmed])
    await db_session.flush()

    await invalidate_mutable_group_root_plans(
        db_session,
        root.id,
        (planning_date,),
    )

    remaining = set(
        await db_session.scalars(
            select(RoutePlan.id).where(RoutePlan.warehouse_id == root.id)
        )
    )
    assert draft.id not in remaining
    assert confirmed.id in remaining
