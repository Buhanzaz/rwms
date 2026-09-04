"""Focused day-planner integration tests for routed support-warehouse resources."""

from __future__ import annotations

from datetime import UTC, date, datetime, time
from types import SimpleNamespace
from typing import Any, cast
from unittest.mock import AsyncMock
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

import app.services.planner_runtime as runtime_module
from app.models import PlanningDayMode, WarehouseIsochroneTariff
from app.models import Vehicle as DbVehicle
from app.models import Warehouse as DbWarehouse
from app.planner import (
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlanningInput,
    PlanningSettings,
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    TaskType,
)
from app.planner import Vehicle as PlannerVehicle
from app.planner import Warehouse as PlannerWarehouse
from app.routing import GeoPoint, MockRoutingProvider, RoutingSettings
from app.schemas.domain import RwmsVehicleOperationalAssignment
from app.services.planner_runtime import (
    RuntimePlannerFacade,
    _RuntimeSnapshot,
    warehouse_shift_interval,
)
from app.services.planning_group import PlanningWarehouseGroup
from app.slot_planning.models import PlanningReason
from tests.factories import make_driver, make_routable_vehicle, make_shift, make_warehouse

MOSCOW = ZoneInfo("Europe/Moscow")


def rwms_client_without_vehicle_assignments() -> AsyncMock:
    """Return a directory mock whose vehicle overlay is explicitly empty."""

    client = AsyncMock()
    client.list_support_network.return_value = []
    client.list_vehicle_assignments.return_value = []
    return client


def test_zero_duration_warehouse_shift_fails_fast() -> None:
    """Equal local clocks cannot be reinterpreted as an implicit 24-hour shift."""

    with pytest.raises(ValueError, match="non-zero duration"):
        warehouse_shift_interval(
            date(2026, 8, 31),
            time(8),
            time(8),
            MOSCOW,
        )


def warehouse(*, representative: bool = True) -> DbWarehouse:
    """Build a routing-ready canonical representative projection without persistence."""

    return DbWarehouse(
        id=uuid4(),
        external_warehouse_id=uuid4(),
        external_warehouse_version=3,
        name="Regional warehouse",
        city="Region",
        address=None,
        timezone="Europe/Moscow",
        representative=representative,
        routing_ready=True,
        seed=1,
        capacity_generation=0,
        settings={},
        latitude=58.5,
        longitude=31.2,
        loading_minutes=30,
        unloading_minutes=30,
        turnaround_minutes=15,
        working_day_start=time(8),
        working_day_end=time(20),
        isochrone_tariffs=[
            WarehouseIsochroneTariff(travel_minutes=60, price_rubles=10_000),
            WarehouseIsochroneTariff(travel_minutes=120, price_rubles=15_000),
            WarehouseIsochroneTariff(travel_minutes=180, price_rubles=20_000),
            WarehouseIsochroneTariff(travel_minutes=240, price_rubles=25_000),
        ],
    )


def vehicle(warehouse_id: UUID) -> DbVehicle:
    """Build one catalog vehicle that can be translated by the shared adapter."""

    result = DbVehicle(
        id=uuid4(),
        warehouse_id=warehouse_id,
        name="Support truck",
        registration_number="SUPPORT-01",
        capacity=1,
        active=True,
        average_speed_city=35,
        average_speed_region=65,
        height_safety_margin_mm=0,
        width_safety_margin_mm=0,
        weight_safety_margin_kg=0,
        notes="",
    )
    result.default_trailer = None
    result.load_profiles = []
    return result


def snapshot(served: DbWarehouse) -> _RuntimeSnapshot:
    """Build the local-only immutable boundary before support resources are appended."""

    depot = PlannerWarehouse(
        id=str(served.id),
        name=served.name,
        point=GeoPoint(served.longitude, served.latitude, is_city=True),
    )
    return _RuntimeSnapshot(
        warehouse=served,
        input_data=PlanningInput(
            warehouse_id=str(served.id),
            planning_date=date(2026, 9, 14),
            warehouse=depot,
            requests=(),
            shifts=(),
            vehicles=(),
        ),
        settings=PlanningSettings(),
        routing_settings=RoutingSettings(),
        task_uuid_by_core_id={},
        source_task_uuid_by_core_id={},
        core_task_by_uuid={},
        request_task_uuids={},
        policy_unassigned_by_task={},
        warehouse_fingerprint="local-revision",
        support_resources={},
        support_reason_codes=(),
        support_source_revision=None,
        day_mode=PlanningDayMode.DELIVERIES_AND_PICKUPS,
        unavailable_vehicle_ids=frozenset(),
        unavailable_shift_ids=frozenset(),
    )


@pytest.mark.asyncio
async def test_day_plan_appends_routed_support_shift_without_repositioning(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Expose an external shift only after arrival and retain its original base warehouse."""

    served = warehouse()
    support_id = uuid4()
    support_vehicle = vehicle(support_id)
    shift_id = uuid4()
    worker_id = uuid4()
    link_id = uuid4()
    available = datetime(2026, 9, 14, 12, tzinfo=MOSCOW)
    latest_finish = datetime(2026, 9, 14, 17, 30, tzinfo=MOSCOW)
    support_warehouse = SimpleNamespace(
        id=support_id,
        external_warehouse_id=support_id,
        name="Support warehouse",
        timezone="Europe/Moscow",
    )
    fact = SimpleNamespace(
        shift=SimpleNamespace(
            id=shift_id,
            driver_id=uuid4(),
            vehicle_id=support_vehicle.id,
            vehicle=support_vehicle,
            break_minutes=0,
            start_time=time(8),
            end_time=time(20),
        ),
        identity=SimpleNamespace(worker_id=worker_id, display_name="Support driver"),
        link=SimpleNamespace(
            support_link_id=link_id,
            priority=2,
            served_warehouse=SimpleNamespace(
                warehouse_id=served.external_warehouse_id
            ),
        ),
        support_warehouse=support_warehouse,
    )
    candidate = SimpleNamespace(
        fact=fact,
        inbound_departure_at=datetime(2026, 9, 14, 8, 50, tzinfo=MOSCOW),
        inbound_raw_arrival_at=datetime(2026, 9, 14, 11, 55, tzinfo=MOSCOW),
        inbound_arrival_at=datetime(2026, 9, 14, 12, tzinfo=MOSCOW),
        inbound_travel_seconds=11_400,
        return_travel_seconds=11_700,
        available_at_served=available,
        latest_served_finish=latest_finish,
        inbound_travel_minutes=190,
        return_travel_minutes=195,
        inbound_distance_meters=200_000,
        return_distance_meters=210_000,
        positioning_distance_meters=410_000,
        inbound_geometry={
            "type": "LineString",
            "coordinates": [[30.0, 59.0], [30.5, 58.7], [31.0, 58.0]],
        },
        return_geometry={
            "type": "LineString",
            "coordinates": [[31.0, 58.0], [30.4, 58.8], [30.0, 59.0]],
        },
        reason_codes=(
            PlanningReason.SUPPORT_DRIVER_AVAILABLE,
            PlanningReason.SLOT_AFTER_RESOURCE_ARRIVAL,
        ),
    )

    async def load_facts(*_args: object, **_kwargs: object) -> Any:
        return SimpleNamespace(
            candidates=(fact,),
            equipment={},
            source_revision="support-revision",
            contractor_fallback_allowed=True,
        )

    async def route_facts(*_args: object, **_kwargs: object) -> Any:
        return SimpleNamespace(candidates=(candidate,), reasons=())

    monkeypatch.setattr(runtime_module, "load_support_resource_facts", load_facts)
    monkeypatch.setattr(runtime_module, "route_support_resource_facts", route_facts)

    facade = RuntimePlannerFacade(
        rwms_client=cast(Any, rwms_client_without_vehicle_assignments())
    )
    augmented = await facade._with_support_resources(
        cast(AsyncSession, object()), snapshot(served)
    )

    assert [item.id for item in augmented.input_data.shifts] == [str(shift_id)]
    appended_shift = augmented.input_data.shifts[0]
    assert appended_shift.start_at > datetime(2026, 9, 14, 11, 30, tzinfo=MOSCOW)
    assert appended_shift.start_at < available
    assert appended_shift.end_at == latest_finish
    assert appended_shift.route_depot is not None
    assert appended_shift.route_depot.id == augmented.input_data.warehouse.id
    assert appended_shift.route_depot.point == augmented.input_data.warehouse.point
    assert appended_shift.allowed_service_warehouse_ids == frozenset(
        {str(served.external_warehouse_id)}
    )
    assert [item.id for item in augmented.input_data.vehicles] == [
        str(support_vehicle.id)
    ]
    assert augmented.input_data.warehouse_id == str(served.id)
    assert augmented.warehouse is served
    assert augmented.input_data.warehouse.id == str(served.id)

    cycle = RouteCycle(
        id=str(uuid4()),
        driver_shift_id=str(shift_id),
        driver_id=appended_shift.driver_id,
        vehicle_id=str(support_vehicle.id),
        sequence=1,
        planned_start=available,
        planned_finish=available,
        stops=(),
        legs=(),
        total_distance_meters=0,
        total_travel_seconds=0,
        total_service_seconds=0,
        waiting_seconds=0,
        empty_distance_meters=0,
        detour_seconds=0,
        score=0,
        resource_option_id=appended_shift.resource_option_id,
    )
    metrics = facade._cycle_metrics(cycle, augmented)
    assert metrics["execution_mode"] == "CROSS_WAREHOUSE_SERVICE"
    assert metrics["resource_origin_warehouse_id"] == str(support_id)
    assert metrics["service_warehouse_id"] == str(served.external_warehouse_id)
    assert metrics["changes_operational_warehouse"] is False
    assert metrics["available_transfer_cabin_capacity"] == 1
    assert metrics["trailer_available"] is False
    assert "VEHICLE_CAPACITY_ONE_CABIN" in metrics["reason_codes"]
    assert metrics["inbound_distance_meters"] == 200_000
    assert metrics["return_distance_meters"] == 210_000
    assert metrics["inbound_departure_at"] == "2026-09-14T08:50:00+03:00"
    assert metrics["inbound_raw_arrival_at"] == "2026-09-14T11:55:00+03:00"
    assert metrics["inbound_arrival_at"] == "2026-09-14T12:00:00+03:00"
    assert metrics["inbound_travel_seconds"] == 11_400
    assert metrics["return_travel_seconds"] == 11_700
    assert len(metrics["positioning_outbound_geometry"]["coordinates"]) == 3
    assert len(metrics["positioning_return_geometry"]["coordinates"]) == 3
    assert metrics["returns_to_origin"] is True
    assert metrics["reason_codes"] == [
        "SUPPORT_DRIVER_AVAILABLE",
        "SLOT_AFTER_RESOURCE_ARRIVAL",
        "VEHICLE_CAPACITY_ONE_CABIN",
    ]


@pytest.mark.asyncio
async def test_support_route_candidate_is_excluded_when_vehicle_is_rebased_elsewhere(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A driver fact cannot lend a support vehicle whose active placement has moved."""

    served = warehouse()
    support_id, moved_destination_id = uuid4(), uuid4()
    support_vehicle = vehicle(support_id)
    support_warehouse = SimpleNamespace(
        id=support_id,
        external_warehouse_id=support_id,
        timezone="Europe/Moscow",
    )
    fact = SimpleNamespace(
        shift=SimpleNamespace(
            id=uuid4(),
            driver_id=uuid4(),
            vehicle_id=support_vehicle.id,
            vehicle=support_vehicle,
            break_minutes=0,
            start_time=time(8),
            end_time=time(20),
        ),
        support_warehouse=support_warehouse,
    )
    candidate = SimpleNamespace(fact=fact)

    async def load_facts(*_args: object, **_kwargs: object) -> Any:
        return SimpleNamespace(
            candidates=(fact,),
            equipment={},
            source_revision="support-revision",
            contractor_fallback_allowed=False,
        )

    async def route_facts(*_args: object, **_kwargs: object) -> Any:
        return SimpleNamespace(candidates=(candidate,), reasons=())

    monkeypatch.setattr(runtime_module, "load_support_resource_facts", load_facts)
    monkeypatch.setattr(runtime_module, "route_support_resource_facts", route_facts)
    client = rwms_client_without_vehicle_assignments()
    client.list_vehicle_assignments.return_value = [
        RwmsVehicleOperationalAssignment(
            assignment_id=uuid4(),
            version=1,
            transfer_id=uuid4(),
            vehicle_id=support_vehicle.id,
            source_warehouse_id=support_id,
            destination_warehouse_id=moved_destination_id,
            mode="TEMPORARY",
            status="ACTIVE",
            travel_starts_at=datetime(2026, 9, 14, 2, tzinfo=UTC),
            effective_from=datetime(2026, 9, 14, 4, tzinfo=UTC),
            effective_until=datetime(2026, 9, 15, 4, tzinfo=UTC),
            created_at=datetime(2026, 9, 13, 2, tzinfo=UTC),
            updated_at=datetime(2026, 9, 14, 4, tzinfo=UTC),
        )
    ]

    augmented = await RuntimePlannerFacade(
        rwms_client=cast(Any, client)
    )._with_support_resources(cast(AsyncSession, object()), snapshot(served))

    assert augmented.input_data.shifts == ()
    assert augmented.support_resources == {}
    assert augmented.support_reason_codes == ("NO_SUPPORT_RESOURCE",)


@pytest.mark.asyncio
async def test_day_plan_recommends_contractor_without_mutating_local_inputs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Keep candidate evaluation side-effect free when owner links allow fallback only."""

    served = warehouse()

    async def load_facts(*_args: object, **_kwargs: object) -> Any:
        return SimpleNamespace(
            candidates=(),
            equipment={},
            source_revision="no-resource-revision",
            contractor_fallback_allowed=True,
        )

    monkeypatch.setattr(runtime_module, "load_support_resource_facts", load_facts)
    facade = RuntimePlannerFacade(
        rwms_client=cast(Any, rwms_client_without_vehicle_assignments())
    )
    original = snapshot(served)
    augmented = await facade._with_support_resources(
        cast(AsyncSession, object()), original
    )

    assert augmented.input_data == original.input_data
    assert augmented.support_resources == {}
    assert augmented.support_reason_codes == ("CONTRACTOR_REQUIRED",)
    assert augmented.support_source_revision == "no-resource-revision"


@pytest.mark.asyncio
async def test_non_representative_day_plan_never_reads_support_directory() -> None:
    """Ordinary warehouses retain the local planner path even with an injected client."""

    served = warehouse(representative=False)
    original = snapshot(served)
    facade = RuntimePlannerFacade(rwms_client=cast(Any, object()))
    unchanged = await facade._with_support_resources(
        cast(AsyncSession, object()), original
    )

    assert unchanged is original


@pytest.mark.asyncio
async def test_ordinary_root_loads_support_for_each_representative_group_member(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Root selection cannot suppress a representative member's incoming links."""

    root = warehouse(representative=False)
    representative = warehouse(representative=True)
    visited: list[UUID] = []

    async def load_facts(
        _session: object,
        _client: object,
        served_member: DbWarehouse,
        *_args: object,
        **_kwargs: object,
    ) -> Any:
        visited.append(served_member.external_warehouse_id)
        return SimpleNamespace(
            candidates=(),
            equipment={},
            source_revision="representative-revision",
            contractor_fallback_allowed=False,
        )

    monkeypatch.setattr(runtime_module, "load_support_resource_facts", load_facts)
    facade = RuntimePlannerFacade(rwms_client=cast(Any, object()))

    augmented = await facade._with_support_resources(
        cast(AsyncSession, object()),
        snapshot(root),
        representative_members=(representative,),
    )

    assert visited == [representative.external_warehouse_id]
    assert augmented.warehouse is root
    assert augmented.input_data.warehouse.id == str(root.id)
    assert augmented.support_reason_codes == ("NO_SUPPORT_RESOURCE",)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_group_snapshot_includes_each_members_local_resources_with_true_origin(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Local representative capacity is first-class while the ordinary root stays root."""

    planning_date = date(2026, 9, 1)
    root = await make_warehouse(
        db_session,
        name="Root",
        default_planning_date=planning_date,
    )
    representative = await make_warehouse(
        db_session,
        name="Representative",
        default_planning_date=planning_date,
    )
    representative.representative = True
    direct_only = await make_warehouse(
        db_session,
        name="Direct fulfillment only",
        default_planning_date=planning_date,
    )
    direct_only.representative = True
    root_driver = await make_driver(db_session, root)
    root_vehicle = await make_routable_vehicle(db_session, root)
    root_shift = await make_shift(
        db_session,
        root,
        root_driver,
        root_vehicle,
        date_from=date(2026, 8, 25),
        date_to=date(2026, 9, 5),
    )
    representative_driver = await make_driver(db_session, representative)
    representative_vehicle = await make_routable_vehicle(
        db_session,
        representative,
    )
    representative_shift = await make_shift(
        db_session,
        representative,
        representative_driver,
        representative_vehicle,
        date_from=date(2026, 8, 25),
        date_to=date(2026, 9, 5),
    )
    await db_session.flush()

    async def resolve_group(*_args: object, **_kwargs: object) -> PlanningWarehouseGroup:
        return PlanningWarehouseGroup(
            root=root,
            members=(root, representative, direct_only),
            links=cast(
                Any,
                (
                    SimpleNamespace(
                        allow_drivers=True,
                        support_warehouse=SimpleNamespace(
                            warehouse_id=root.external_warehouse_id
                        ),
                        served_warehouse=SimpleNamespace(
                            warehouse_id=representative.external_warehouse_id
                        ),
                    ),
                    SimpleNamespace(
                        allow_drivers=False,
                        support_warehouse=SimpleNamespace(
                            warehouse_id=root.external_warehouse_id
                        ),
                        served_warehouse=SimpleNamespace(
                            warehouse_id=direct_only.external_warehouse_id
                        ),
                    ),
                ),
            ),
        )

    async def leave_support_unchanged(
        _self: RuntimePlannerFacade,
        _session: AsyncSession,
        current: _RuntimeSnapshot,
        **_kwargs: object,
    ) -> _RuntimeSnapshot:
        return current

    monkeypatch.setattr(runtime_module, "resolve_planning_warehouse_group", resolve_group)
    monkeypatch.setattr(
        RuntimePlannerFacade,
        "_with_support_resources",
        leave_support_unchanged,
    )
    facade = RuntimePlannerFacade(
        rwms_client=cast(Any, rwms_client_without_vehicle_assignments())
    )

    loaded = await facade._load_snapshot(
        db_session,
        root.id,
        planning_date,
        None,
        17,
    )

    shifts = {shift.id: shift for shift in loaded.input_data.shifts}
    assert set(shifts) == {str(root_shift.id), str(representative_shift.id)}
    assert shifts[str(root_shift.id)].resource_origin_warehouse_id == str(
        root.external_warehouse_id
    )
    assert shifts[str(representative_shift.id)].resource_origin_warehouse_id == str(
        representative.external_warehouse_id
    )
    assert shifts[str(root_shift.id)].route_depot == loaded.input_data.warehouse
    assert shifts[str(root_shift.id)].allowed_service_warehouse_ids == frozenset(
        {
            str(root.external_warehouse_id),
            str(representative.external_warehouse_id),
        }
    )
    assert str(direct_only.external_warehouse_id) not in (
        shifts[str(root_shift.id)].allowed_service_warehouse_ids or frozenset()
    )
    assert shifts[str(representative_shift.id)].route_depot is not None
    assert shifts[str(representative_shift.id)].route_depot.point == GeoPoint(
        representative.longitude,
        representative.latitude,
        is_city=True,
    )
    assert shifts[
        str(representative_shift.id)
    ].allowed_service_warehouse_ids == frozenset(
        {str(representative.external_warehouse_id)}
    )
    assert {vehicle.id for vehicle in loaded.input_data.vehicles} == {
        str(root_vehicle.id),
        str(representative_vehicle.id),
    }
    assert loaded.warehouse.id == root.id
    assert loaded.input_data.warehouse.id == str(root.id)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_source_shift_is_excluded_while_vehicle_is_actively_rebased(
    db_session: AsyncSession,
) -> None:
    """A source-owned recurring shift cannot route a vehicle placed at another warehouse."""

    planning_date = date(2026, 9, 1)
    source = await make_warehouse(
        db_session,
        name="Source",
        default_planning_date=planning_date,
    )
    destination = await make_warehouse(
        db_session,
        name="Destination",
        default_planning_date=planning_date,
    )
    driver = await make_driver(db_session, source)
    vehicle = await make_routable_vehicle(db_session, source)
    await make_shift(
        db_session,
        source,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    client = rwms_client_without_vehicle_assignments()
    client.list_support_network.return_value = []
    client.list_vehicle_assignments.return_value = [
        RwmsVehicleOperationalAssignment(
            assignment_id=uuid4(),
            version=1,
            transfer_id=uuid4(),
            vehicle_id=vehicle.id,
            source_warehouse_id=source.external_warehouse_id,
            destination_warehouse_id=destination.external_warehouse_id,
            mode="TEMPORARY",
            status="ACTIVE",
            travel_starts_at=datetime(2026, 9, 1, 2, tzinfo=UTC),
            effective_from=datetime(2026, 9, 1, 4, tzinfo=UTC),
            effective_until=datetime(2026, 9, 2, 4, tzinfo=UTC),
            created_at=datetime(2026, 8, 31, 2, tzinfo=UTC),
            updated_at=datetime(2026, 9, 1, 4, tzinfo=UTC),
        )
    ]

    loaded = await RuntimePlannerFacade(rwms_client=cast(Any, client))._load_snapshot(
        db_session,
        source.id,
        planning_date,
        None,
        17,
    )

    assert loaded.input_data.shifts == ()
    assert vehicle.warehouse_id == source.id


@pytest.mark.asyncio
async def test_day_planner_scores_full_support_positioning_before_link_priority() -> None:
    """Choose the lower full-road external visit when two support shifts otherwise match."""

    planning_date = date(2026, 9, 14)
    near_vehicle = PlannerVehicle("near-vehicle", "Near truck", capacity=1)
    far_vehicle = PlannerVehicle("far-vehicle", "Far truck", capacity=1)
    near_shift = DriverShift(
        "near-shift",
        "near-driver",
        "Near driver",
        near_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 18, tzinfo=MOSCOW),
        resource_origin_warehouse_id="near-warehouse",
        support_link_id="near-link",
        support_priority=2,
        positioning_travel_minutes=120,
        positioning_distance_meters=180_000,
        return_required=True,
    )
    far_shift = DriverShift(
        "far-shift",
        "far-driver",
        "Far driver",
        far_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 18, tzinfo=MOSCOW),
        resource_origin_warehouse_id="far-warehouse",
        support_link_id="far-link",
        support_priority=1,
        positioning_travel_minutes=360,
        positioning_distance_meters=540_000,
        return_required=True,
    )
    request = LogisticsRequest(
        id="regional-delivery",
        request_type=TaskType.DELIVERY,
        name="Regional delivery",
        address_label="Customer",
        point=GeoPoint(31.25, 58.55, is_city=True),
        quantity=1,
        service_minutes=20,
        priority=1,
        status=RequestStatus.READY,
        date_options=(
            RequestDateOption(
                planning_date,
                priority=1,
                window_start=datetime(2026, 9, 14, 10, tzinfo=MOSCOW),
                window_end=datetime(2026, 9, 14, 17, tzinfo=MOSCOW),
                is_hard=True,
            ),
        ),
        created_at=datetime(2026, 9, 14, 7, tzinfo=MOSCOW),
    )
    planning_input = PlanningInput(
        warehouse_id="served",
        planning_date=planning_date,
        warehouse=PlannerWarehouse(
            "served",
            "Served warehouse",
            GeoPoint(31.2, 58.5, is_city=True),
            loading_minutes=10,
            unloading_minutes=10,
            turnaround_minutes=10,
        ),
        requests=(request,),
        shifts=(far_shift, near_shift),
        vehicles=(far_vehicle, near_vehicle),
    )

    result = await HeuristicPlanner(
        MockRoutingProvider(
            RoutingSettings(seed=3, deterministic_noise_ratio=0, road_factor=1.1)
        )
    ).generate_plan(
        planning_input,
        PlanningSettings(seed=3),
        NullProgressPublisher(),
    )

    assert [cycle.driver_shift_id for cycle in result.cycles] == ["near-shift"]
    assert result.unassigned == ()


@pytest.mark.asyncio
async def test_representative_local_driver_competes_with_support_and_keeps_origin() -> None:
    """Equivalent local capacity wins through zero positioning cost, not candidate filtering."""

    planning_date = date(2026, 9, 14)
    local_vehicle = PlannerVehicle("local-vehicle", "Local truck", capacity=1)
    support_vehicle = PlannerVehicle("support-vehicle", "Support truck", capacity=1)
    local_shift = DriverShift(
        "local-shift",
        "local-driver",
        "Local driver",
        local_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 18, tzinfo=MOSCOW),
        resource_origin_warehouse_id="representative-origin",
    )
    support_shift = DriverShift(
        "support-shift",
        "support-driver",
        "Support driver",
        support_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 18, tzinfo=MOSCOW),
        resource_origin_warehouse_id="support-origin",
        support_link_id="support-link",
        support_priority=1,
        positioning_travel_minutes=60,
        positioning_distance_meters=80_000,
        return_required=True,
    )
    demand = LogisticsRequest(
        id="local-demand",
        request_type=TaskType.DELIVERY,
        name="Local demand",
        address_label="Customer",
        point=GeoPoint(31.25, 58.55, is_city=True),
        quantity=1,
        service_minutes=20,
        priority=1,
        status=RequestStatus.READY,
        date_options=(
            RequestDateOption(
                planning_date,
                priority=1,
                window_start=datetime(2026, 9, 14, 10, tzinfo=MOSCOW),
                window_end=datetime(2026, 9, 14, 17, tzinfo=MOSCOW),
                is_hard=True,
            ),
        ),
        created_at=datetime(2026, 9, 14, 7, tzinfo=MOSCOW),
    )
    input_data = PlanningInput(
        warehouse_id="ordinary-root",
        planning_date=planning_date,
        warehouse=PlannerWarehouse(
            "ordinary-root",
            "Root depot",
            GeoPoint(31.2, 58.5, is_city=True),
            loading_minutes=10,
            unloading_minutes=10,
            turnaround_minutes=10,
        ),
        requests=(demand,),
        shifts=(support_shift, local_shift),
        vehicles=(support_vehicle, local_vehicle),
    )

    result = await HeuristicPlanner(
        MockRoutingProvider(
            RoutingSettings(seed=3, deterministic_noise_ratio=0, road_factor=1.1)
        )
    ).generate_plan(
        input_data,
        PlanningSettings(seed=3),
        NullProgressPublisher(),
    )

    assert [cycle.driver_shift_id for cycle in result.cycles] == ["local-shift"]
    assert local_shift.resource_origin_warehouse_id == "representative-origin"
    assert input_data.warehouse.id == "ordinary-root"


@pytest.mark.asyncio
async def test_multi_depot_routes_use_physical_origins_and_service_scopes() -> None:
    """Far-apart resources start at their own depots and cannot cross served scopes."""

    planning_date = date(2026, 9, 14)
    root_depot = PlannerWarehouse("root", "Root", GeoPoint(30.0, 60.0, True), 5, 5, 5)
    served_a = PlannerWarehouse("served-a", "Served A", GeoPoint(40.0, 50.0, True), 5, 5, 5)
    served_b = PlannerWarehouse("served-b", "Served B", GeoPoint(50.0, 55.0, True), 5, 5, 5)
    root_vehicle = PlannerVehicle("root-vehicle", "Root truck", capacity=2)
    support_vehicle = PlannerVehicle("support-vehicle-a", "Support A truck", capacity=2)
    representative_vehicle = PlannerVehicle("representative-vehicle-b", "B truck", capacity=2)
    root_shift = DriverShift(
        "root-shift",
        "root-driver",
        "Root driver",
        root_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 20, tzinfo=MOSCOW),
        route_depot=root_depot,
        allowed_service_warehouse_ids=frozenset({"root-service", "service-a"}),
    )
    support_a_shift = DriverShift(
        "support-a-shift",
        "support-a-driver",
        "Support A driver",
        support_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 20, tzinfo=MOSCOW),
        resource_origin_warehouse_id="support-origin-a",
        support_link_id="link-a",
        positioning_travel_minutes=30,
        route_depot=served_a,
        allowed_service_warehouse_ids=frozenset({"service-a"}),
    )
    representative_b_shift = DriverShift(
        "representative-b-shift",
        "representative-b-driver",
        "Representative B driver",
        representative_vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 20, tzinfo=MOSCOW),
        route_depot=served_b,
        allowed_service_warehouse_ids=frozenset({"service-b"}),
    )

    def demand(
        request_id: str,
        point: GeoPoint,
        service_warehouse_id: str,
        priority: int,
    ) -> LogisticsRequest:
        return LogisticsRequest(
            id=request_id,
            request_type=TaskType.DELIVERY,
            name=request_id,
            address_label=request_id,
            point=point,
            quantity=2,
            service_minutes=10,
            priority=priority,
            status=RequestStatus.READY,
            date_options=(
                RequestDateOption(
                    planning_date,
                    window_start=datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
                    window_end=datetime(2026, 9, 14, 19, tzinfo=MOSCOW),
                    is_hard=True,
                ),
            ),
            created_at=datetime(2026, 9, 14, 7, tzinfo=MOSCOW),
            service_warehouse_id=service_warehouse_id,
        )

    result = await HeuristicPlanner(
        MockRoutingProvider(
            RoutingSettings(seed=7, deterministic_noise_ratio=0, road_factor=1.1)
        )
    ).generate_plan(
        PlanningInput(
            warehouse_id=root_depot.id,
            planning_date=planning_date,
            warehouse=root_depot,
            requests=(
                demand("root-demand", GeoPoint(30.02, 60.01, True), "root-service", 30),
                demand("a-demand", GeoPoint(40.02, 50.01, True), "service-a", 20),
                demand("b-demand", GeoPoint(50.02, 55.01, True), "service-b", 10),
            ),
            shifts=(root_shift, support_a_shift, representative_b_shift),
            vehicles=(root_vehicle, support_vehicle, representative_vehicle),
        ),
        PlanningSettings(seed=7),
        NullProgressPublisher(),
    )

    assert result.unassigned == ()
    cycle_by_task = {
        cycle.task_ids[0].split(":part:", maxsplit=1)[0]: cycle
        for cycle in result.cycles
    }
    assert cycle_by_task["root-demand"].driver_shift_id == root_shift.id
    assert cycle_by_task["a-demand"].driver_shift_id == support_a_shift.id
    assert cycle_by_task["b-demand"].driver_shift_id == representative_b_shift.id
    assert cycle_by_task["root-demand"].stops[0].point == root_depot.point
    assert cycle_by_task["root-demand"].stops[-1].point == root_depot.point
    assert cycle_by_task["a-demand"].stops[0].point == served_a.point
    assert cycle_by_task["a-demand"].stops[-1].point == served_a.point
    assert cycle_by_task["b-demand"].stops[0].point == served_b.point
    assert cycle_by_task["b-demand"].stops[-1].point == served_b.point
    assert all(cycle.total_distance_meters < 100_000 for cycle in result.cycles)


@pytest.mark.asyncio
async def test_root_driver_can_serve_only_an_allow_drivers_representative_scope() -> None:
    """A root option may take admitted representative demand but no unrelated member."""

    planning_date = date(2026, 9, 14)
    root_depot = PlannerWarehouse("root", "Root", GeoPoint(30.0, 60.0, True), 5, 5, 5)
    vehicle = PlannerVehicle("root-vehicle", "Root truck", capacity=2)
    shift = DriverShift(
        "root-shift",
        "root-driver",
        "Root driver",
        vehicle.id,
        datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
        datetime(2026, 9, 14, 23, tzinfo=MOSCOW),
        route_depot=root_depot,
        allowed_service_warehouse_ids=frozenset({"root-service", "service-a"}),
    )
    request = LogisticsRequest(
        id="admitted-a",
        request_type=TaskType.DELIVERY,
        name="Admitted A",
        address_label="A",
        point=GeoPoint(31.0, 59.0, True),
        quantity=1,
        service_minutes=10,
        priority=1,
        status=RequestStatus.READY,
        date_options=(
            RequestDateOption(
                planning_date,
                window_start=datetime(2026, 9, 14, 8, tzinfo=MOSCOW),
                window_end=datetime(2026, 9, 14, 22, tzinfo=MOSCOW),
                is_hard=True,
            ),
        ),
        created_at=datetime(2026, 9, 14, 7, tzinfo=MOSCOW),
        service_warehouse_id="service-a",
    )

    result = await HeuristicPlanner(
        MockRoutingProvider(
            RoutingSettings(seed=11, deterministic_noise_ratio=0, road_factor=1.1)
        )
    ).generate_plan(
        PlanningInput(
            warehouse_id=root_depot.id,
            planning_date=planning_date,
            warehouse=root_depot,
            requests=(request,),
            shifts=(shift,),
            vehicles=(vehicle,),
        ),
        PlanningSettings(seed=11),
        NullProgressPublisher(),
    )

    assert result.unassigned == ()
    assert result.cycles[0].driver_shift_id == shift.id
    assert result.cycles[0].stops[0].point == root_depot.point
