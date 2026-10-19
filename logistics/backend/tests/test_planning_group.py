"""Focused integration tests for direct representative planning groups."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.catalog import get_warehouse_workspace
from app.config import Settings
from app.integrations.rwms_sync import _load_plan_for_rwms_apply, build_assignments_command
from app.models import RoutePlan, Trailer, Warehouse
from app.models.domain import PlanStatus
from app.schemas.domain import (
    GeneratePlanRequest,
    RwmsDriverIdentity,
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
    RwmsWarehouseIdentity,
    RwmsWarehouseSupportLink,
)
from app.services.auto_planning import invalidate_mutable_group_root_plans
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.planning_group import link_allows_group_planning
from tests.auth import admin_principal
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


def _warehouse_local_date(warehouse: Warehouse) -> date:
    """Return the current planning date in the warehouse's canonical timezone."""

    return datetime.now(ZoneInfo(warehouse.timezone)).date()


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

    async def list_vehicle_assignments(
        self,
        warehouse_id: UUID,
        *,
        window_start: datetime,
        window_end: datetime,
    ) -> list[object]:
        """Return no vehicle moves in tests whose frozen input is only the support graph."""

        del warehouse_id, window_start, window_end
        return []

    async def list_drivers(
        self,
        warehouse_id: UUID,
        *,
        at: datetime | None = None,
        include_incoming: bool = False,
    ) -> list[RwmsDriverIdentity]:
        """Return no canonical workers for group tests that exercise demand only."""

        del warehouse_id, at, include_incoming
        return []

    async def list_support_links(
        self,
        served_warehouse_id: UUID,
        *,
        at: datetime,
    ) -> list[RwmsWarehouseSupportLink]:
        """Return active incoming links for the exact served warehouse."""

        del at
        return [
            link
            for link in self.links
            if link.served_warehouse.warehouse_id == served_warehouse_id
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
    """A selected root sees regional demand and every member-owned local resource."""

    planning_date = date(2026, 8, 30)
    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(
        db_session,
        name="Representative",
        timezone="Pacific/Kiritimati",
    )
    representative.representative = True
    root_request = await make_request(db_session, root, planning_date=planning_date)
    representative_request = await make_request(
        db_session,
        representative,
        planning_date=planning_date,
    )
    root_driver = await make_driver(db_session, root)
    representative_driver = await make_driver(db_session, representative)
    root_vehicle = await make_vehicle(db_session, root)
    representative_vehicle = await make_vehicle(db_session, representative)
    root_shift = await make_shift(
        db_session,
        root,
        root_driver,
        root_vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    representative_shift = await make_shift(
        db_session,
        representative,
        representative_driver,
        representative_vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    root_trailer = Trailer(
        warehouse_id=root.id,
        name="Root trailer",
        registration_number="ROOT-TRAILER",
    )
    representative_trailer = Trailer(
        warehouse_id=representative.id,
        name="Representative trailer",
        registration_number="REP-TRAILER",
    )
    db_session.add_all([root_trailer, representative_trailer])
    await db_session.flush()
    client = _NetworkClient([_link(root, representative)])

    workspace = await get_warehouse_workspace(
        root.id,
        db_session,
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        admin_principal(),
        planning_date=planning_date,
    )

    assert workspace.planning_root_warehouse_id == root.id
    assert workspace.planning_group_warehouse_ids == [root.id, representative.id]
    assert {item.id for item in workspace.requests} == {
        root_request.id,
        representative_request.id,
    }
    assert len(workspace.requests) == 2
    assert {item.id: item.warehouse_id for item in workspace.drivers} == {
        root_driver.id: root.id,
        representative_driver.id: representative.id,
    }
    assert {item.id: item.warehouse_id for item in workspace.vehicles} == {
        root_vehicle.id: root.id,
        representative_vehicle.id: representative.id,
    }
    assert {item.id: item.warehouse_id for item in workspace.trailers} == {
        root_trailer.id: root.id,
        representative_trailer.id: representative.id,
    }
    assert {item.id: item.warehouse_id for item in workspace.shifts} == {
        root_shift.id: root.id,
        representative_shift.id: representative.id,
    }


@pytest.mark.asyncio
async def test_representative_selection_resolves_the_same_root_without_duplicates(
    db_session: AsyncSession,
) -> None:
    """Selecting a representative preserves selection but exposes its main planning root."""

    root = await make_warehouse(db_session, name="Main")
    representative = await make_warehouse(
        db_session,
        name="Representative",
        timezone="Pacific/Kiritimati",
    )
    representative.representative = True
    link = _link(root, representative)
    client = _NetworkClient([link, link])

    workspace = await get_warehouse_workspace(
        representative.id,
        db_session,
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        admin_principal(),
    )

    assert workspace.warehouse.id == representative.id
    assert workspace.planning_date == _warehouse_local_date(representative)
    assert workspace.planning_root_warehouse_id == root.id
    assert workspace.planning_group_warehouse_ids == [root.id, representative.id]


@pytest.mark.asyncio
async def test_unchanged_group_workspace_refresh_preserves_mutable_plan_identity(
    db_session: AsyncSession,
) -> None:
    """Repeated pure workspace reads never churn the shared root draft plan."""

    root = await make_warehouse(db_session, name="Main")
    planning_date = _warehouse_local_date(root)
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    assert representative.external_warehouse_id is not None
    draft = RoutePlan(
        warehouse_id=root.id,
        date=planning_date,
        name="Stable draft",
        status=PlanStatus.DRAFT,
    )
    db_session.add(draft)
    await db_session.flush()
    client = _NetworkClient([_link(root, representative)])
    for _ in range(2):
        await get_warehouse_workspace(
            root.id,
            db_session,
            _enabled_settings(),
            client,  # type: ignore[arg-type]
            admin_principal(),
            planning_date=planning_date,
        )

    remaining = await db_session.scalar(select(RoutePlan).where(RoutePlan.id == draft.id))
    assert remaining is not None
    assert remaining.id == draft.id


@pytest.mark.asyncio
async def test_group_workspace_filters_and_keyset_pages_one_exact_date(
    db_session: AsyncSession,
) -> None:
    """The bounded group projection reports an exact-date total and stable UUID cursor."""

    root = await make_warehouse(db_session, name="Main")
    planning_date = _warehouse_local_date(root)
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    expected = {
        (await make_request(db_session, root, planning_date=planning_date)).id,
        (await make_request(db_session, representative, planning_date=planning_date)).id,
        (await make_request(db_session, representative, planning_date=planning_date)).id,
    }
    outside = await make_request(
        db_session,
        root,
        planning_date=planning_date + timedelta(days=1),
    )
    client = _NetworkClient([_link(root, representative)])
    first = await get_warehouse_workspace(
        root.id,
        db_session,
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        admin_principal(),
        planning_date=planning_date,
        request_limit=2,
    )
    assert first.planning_date == planning_date
    assert first.request_total == 3
    assert len(first.requests) == 2
    assert first.request_next_cursor is not None

    second = await get_warehouse_workspace(
        root.id,
        db_session,
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        admin_principal(),
        planning_date=planning_date,
        request_limit=2,
        request_cursor=first.request_next_cursor,
    )
    assert second.request_total == 3
    assert second.request_next_cursor is None
    assert {item.id for item in [*first.requests, *second.requests]} == expected
    assert outside.id not in expected


@pytest.mark.parametrize("selected_member", ["root", "representative"])
@pytest.mark.asyncio
async def test_group_workspace_read_never_archives_root_plan_heads(
    db_session: AsyncSession,
    selected_member: str,
) -> None:
    """Either group member reads the same persisted draft and confirmed heads."""

    root = await make_warehouse(db_session, name="Main")
    planning_date = _warehouse_local_date(root)
    representative = await make_warehouse(db_session, name="Representative")
    representative.representative = True
    draft = RoutePlan(
        warehouse_id=root.id,
        date=planning_date,
        name="Stale draft",
        status=PlanStatus.DRAFT,
    )
    confirmed = RoutePlan(
        warehouse_id=root.id,
        date=planning_date + timedelta(days=1),
        name="Confirmed",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add_all([draft, confirmed])
    await db_session.flush()
    client = _NetworkClient([_link(root, representative)])

    await get_warehouse_workspace(
        root.id if selected_member == "root" else representative.id,
        db_session,
        _enabled_settings(),
        client,  # type: ignore[arg-type]
        admin_principal(),
        planning_date=planning_date,
    )

    remaining_ids = set(
        await db_session.scalars(
            select(RoutePlan.id).where(RoutePlan.warehouse_id == root.id)
        )
    )
    assert draft.id in remaining_ids
    assert confirmed.id in remaining_ids


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
        customerDeliveryPurpose="RENTAL_DELIVERY",
        orderNumber="REG-1",
        clientName="Regional client",
        clientType="LEGAL_ENTITY",
        address=request.address_label,
        latitude=request.latitude,
        longitude=request.longitude,
        quantity=1,
        unitIds=[unit_id],
        unitReservations=[
            RwmsPlanningUnitReservation(
                unitId=unit_id,
                inventorySourceWarehouseId=representative.external_warehouse_id,
            )
        ],
        dateOptions=[
            RwmsPlanningDateOption(
                date=planning_date,
                priority=1,
                isHard=False,
            )
        ],
        trailerAccessAllowed=True,
        deliveryPriceRubles=None,
        priceIsochroneMinutes=None,
        createdAt=datetime(2026, 8, 29, 8, tzinfo=UTC),
    )
    request.source_system = "RWMS"
    request.external_id = source.order_id
    request.external_version = source.order_version
    request.external_payload = source.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = source.customer_delivery_purpose
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
    assert command.assignments[0].inventory_source_warehouse_id == (
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
        date=planning_date + timedelta(days=1),
        name="Confirmed",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add_all([draft, confirmed])
    await db_session.flush()

    await invalidate_mutable_group_root_plans(
        db_session,
        root.id,
        (planning_date, planning_date + timedelta(days=1)),
    )

    await db_session.refresh(draft)
    await db_session.refresh(confirmed)
    assert draft.status == PlanStatus.ARCHIVED
    assert confirmed.status == PlanStatus.CONFIRMED
