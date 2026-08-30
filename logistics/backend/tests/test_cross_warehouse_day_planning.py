"""Focused day-planner integration tests for routed support-warehouse resources."""

from __future__ import annotations

from datetime import date, datetime, time
from types import SimpleNamespace
from typing import Any, cast
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

import app.services.planner_runtime as runtime_module
from app.models import Vehicle as DbVehicle
from app.models import Warehouse as DbWarehouse
from app.models import WarehouseIsochroneTariff
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
from app.services.planner_runtime import RuntimePlannerFacade, _RuntimeSnapshot
from app.slot_planning.models import PlanningReason

MOSCOW = ZoneInfo("Europe/Moscow")


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
        core_task_by_uuid={},
        request_task_uuids={},
        warehouse_fingerprint="local-revision",
        support_resources={},
        support_reason_codes=(),
        support_source_revision=None,
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
        id=uuid4(),
        external_warehouse_id=support_id,
        name="Support warehouse",
    )
    fact = SimpleNamespace(
        shift=SimpleNamespace(
            id=shift_id,
            driver_id=uuid4(),
            vehicle_id=support_vehicle.id,
            vehicle=support_vehicle,
            break_minutes=0,
        ),
        identity=SimpleNamespace(worker_id=worker_id, display_name="Support driver"),
        link=SimpleNamespace(support_link_id=link_id, priority=2),
        support_warehouse=support_warehouse,
    )
    candidate = SimpleNamespace(
        fact=fact,
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

    facade = RuntimePlannerFacade(rwms_client=cast(Any, object()))
    augmented = await facade._with_support_resources(
        cast(AsyncSession, object()), snapshot(served)
    )

    assert [item.id for item in augmented.input_data.shifts] == [str(shift_id)]
    appended_shift = augmented.input_data.shifts[0]
    assert appended_shift.start_at > datetime(2026, 9, 14, 11, 30, tzinfo=MOSCOW)
    assert appended_shift.start_at < available
    assert appended_shift.end_at == latest_finish
    assert [item.id for item in augmented.input_data.vehicles] == [
        str(support_vehicle.id)
    ]
    assert augmented.input_data.warehouse_id == str(served.id)

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
    assert len(metrics["positioning_outbound_geometry"]["coordinates"]) == 3
    assert len(metrics["positioning_return_geometry"]["coordinates"]) == 3
    assert metrics["returns_to_origin"] is True
    assert metrics["reason_codes"] == [
        "SUPPORT_DRIVER_AVAILABLE",
        "SLOT_AFTER_RESOURCE_ARRIVAL",
        "VEHICLE_CAPACITY_ONE_CABIN",
    ]


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
    facade = RuntimePlannerFacade(rwms_client=cast(Any, object()))
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
