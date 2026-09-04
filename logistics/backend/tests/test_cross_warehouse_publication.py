"""Focused publication tests for regional orders and support-warehouse resources."""

from __future__ import annotations

from datetime import UTC, date, datetime, time
from uuid import UUID, uuid4

import pytest

from app.errors import ApiError
from app.integrations.rwms_sync import build_assignments_command
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    Vehicle,
    Warehouse,
)
from app.schemas.domain import (
    RwmsPlanningDateOption,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
)

PLANNING_DATE = date(2026, 9, 14)


def _warehouse(name: str) -> Warehouse:
    """Build one linked standalone warehouse identity."""

    return Warehouse(
        id=uuid4(),
        external_warehouse_id=uuid4(),
        name=name,
        city=name,
        address=f"{name}, тестовый адрес",
        timezone="Europe/Moscow",
        latitude=59.9,
        longitude=30.3,
    )


def _source_request(source_by_unit: dict[UUID, UUID]) -> RwmsPlanningRequest:
    """Build one strict authoritative order with complete physical-source mapping."""

    units = list(source_by_unit)
    return RwmsPlanningRequest(
        order_id=uuid4(),
        order_version=4,
        source_revision="a" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="REG-42",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address="Великий Новгород, тестовый адрес",
        latitude=58.52,
        longitude=31.27,
        quantity=len(units),
        unit_ids=units,
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=source_warehouse_id,
            )
            for unit_id, source_warehouse_id in source_by_unit.items()
        ],
        date_options=[
            RwmsPlanningDateOption(
                date=PLANNING_DATE,
                priority=1,
                is_hard=False,
            )
        ],
        trailer_access_allowed=True,
        delivery_price_rubles=25_000,
        price_isochrone_minutes=240,
        created_at=datetime(2026, 9, 1, tzinfo=UTC),
    )


def _publication_plan(
    *,
    root: Warehouse,
    origin: Warehouse,
    source: RwmsPlanningRequest,
    metrics_by_cycle: tuple[dict[str, object], ...],
    cycle_distances: tuple[int, ...],
    assigned_driver: bool = True,
) -> RoutePlan:
    """Build one in-memory exact plan using a physical shift from the supplied origin."""

    request = LogisticsRequest(
        id=uuid4(),
        warehouse=root,
        warehouse_id=root.id,
        source_system="RWMS",
        external_id=source.order_id,
        external_version=source.order_version,
        external_payload=source.model_dump(mode="json", by_alias=True),
        type="DELIVERY",
        customer_delivery_purpose=source.customer_delivery_purpose,
        name=f"Заказ {source.order_number}",
        address_label=source.address,
        latitude=source.latitude,
        longitude=source.longitude,
        quantity=source.quantity,
        service_minutes=30,
        priority=0,
        mandatory=False,
        status="READY",
        split_allowed=True,
    )
    task = PlanningTask(
        id=uuid4(),
        request=request,
        part_number=1,
        quantity=source.quantity,
        type="DELIVERY",
        latitude=source.latitude,
        longitude=source.longitude,
        service_minutes=30,
        priority=0,
        mandatory=False,
        status="READY",
    )
    driver = Driver(
        id=uuid4(),
        warehouse_id=origin.id,
        external_worker_id=uuid4() if assigned_driver else None,
        rwms_assignment_mode=(
            "ASSIGNED_DRIVER" if assigned_driver else "WAREHOUSE_DRIVERS"
        ),
        name="Тестовый водитель",
    )
    vehicle = Vehicle(
        id=uuid4(),
        warehouse_id=origin.id,
        name="Тестовый автомобиль",
        registration_number="A123AA78",
    )
    shift = DriverShift(
        id=uuid4(),
        warehouse=origin,
        warehouse_id=origin.id,
        driver=driver,
        driver_id=driver.id,
        vehicle=vehicle,
        vehicle_id=vehicle.id,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
        start_time=time(8),
        end_time=time(20),
    )
    plan = RoutePlan(
        id=uuid4(),
        warehouse=root,
        warehouse_id=root.id,
        date=PLANNING_DATE,
        version=3,
    )
    for sequence, (metrics, distance_meters) in enumerate(
        zip(metrics_by_cycle, cycle_distances, strict=True),
        start=1,
    ):
        cycle = RouteCycle(
            id=uuid4(),
            route_plan=plan,
            driver_shift=shift,
            driver_shift_id=shift.id,
            sequence=sequence,
            planned_start=datetime(2026, 9, 14, 8 + sequence, tzinfo=UTC),
            planned_finish=datetime(2026, 9, 14, 9 + sequence, tzinfo=UTC),
            total_distance_meters=distance_meters,
            metrics=metrics,
        )
        if sequence == 1:
            RouteStop(
                id=uuid4(),
                route_cycle=cycle,
                sequence=1,
                task=task,
                task_id=task.id,
                stop_type="DELIVERY",
                planned_arrival=cycle.planned_start,
                planned_departure=cycle.planned_finish,
                service_seconds=30 * 60,
                quantity_delta=-source.quantity,
                load_before=source.quantity,
                load_after=0,
                latitude=task.latitude,
                longitude=task.longitude,
            )
    return plan


def _support_metrics(
    root: Warehouse,
    origin: Warehouse,
    link_id: UUID,
    *,
    inbound: int = 200_000,
    returning: int = 210_000,
) -> dict[str, object]:
    """Return the immutable cross-warehouse evidence persisted by the planner runtime."""

    return {
        "execution_mode": "CROSS_WAREHOUSE_SERVICE",
        "service_warehouse_id": str(root.external_warehouse_id),
        "resource_origin_warehouse_id": str(origin.external_warehouse_id),
        "support_warehouse_link_id": str(link_id),
        "inbound_distance_meters": inbound,
        "return_distance_meters": returning,
        "positioning_distance_meters": inbound + returning,
        "inbound_departure_at": "2026-09-14T06:00:00+00:00",
        "inbound_raw_arrival_at": "2026-09-14T08:50:00+00:00",
        "inbound_arrival_at": "2026-09-14T09:00:00+00:00",
        "inbound_travel_seconds": 10_800,
        "return_travel_seconds": 11_400,
        "returns_to_origin": True,
        "changes_operational_warehouse": False,
    }


def test_assignment_rejects_mixed_inventory_sources_inside_one_task_slice() -> None:
    """Never publish one vehicle-sized shipment whose concrete cabins start apart."""

    root = _warehouse("Склад назначения")
    units = {uuid4(): uuid4(), uuid4(): uuid4()}
    plan = _publication_plan(
        root=root,
        origin=root,
        source=_source_request(units),
        metrics_by_cycle=({},),
        cycle_distances=(10_000,),
        assigned_driver=False,
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "RWMS_MIXED_INVENTORY_SOURCE"


def test_local_shift_keeps_legacy_origin_fields_null() -> None:
    """Local publication keeps the root owner and omits redundant support evidence."""

    root = _warehouse("Основной склад")
    source_id = root.external_warehouse_id
    assert source_id is not None
    plan = _publication_plan(
        root=root,
        origin=root,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(
            {
                "execution_mode": "LOCAL",
                "resource_origin_warehouse_id": str(source_id),
            },
        ),
        cycle_distances=(12_345,),
    )

    command = build_assignments_command(plan)

    assignment = command.assignments[0]
    shift = command.driver_shift_plans[0]
    assert assignment.inventory_source_warehouse_id == source_id
    assert shift.warehouse_id == root.external_warehouse_id
    assert shift.route_origin_warehouse_id is None
    assert shift.support_warehouse_link_id is None
    assert shift.route_distance_meters == 12_345
    assert [operation.kind for operation in shift.operations] == ["DELIVERY"]


def test_cross_warehouse_shift_publishes_proven_origin_link_and_positioning_once() -> None:
    """Add exact inbound/return road legs once to a multi-cycle support workday."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    link_id = uuid4()
    metrics = _support_metrics(root, origin, link_id)
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(dict(metrics), dict(metrics)),
        cycle_distances=(15_000, 25_000),
    )

    command = build_assignments_command(plan)

    shift = command.driver_shift_plans[0]
    assert command.warehouse_id == root.external_warehouse_id
    assert shift.warehouse_id == root.external_warehouse_id
    assert shift.route_origin_warehouse_id == origin.external_warehouse_id
    assert shift.support_warehouse_link_id == link_id
    assert shift.trip_count == 2
    assert shift.route_distance_meters == 450_000
    assert [operation.sequence for operation in shift.operations] == [1, 2, 3, 4]
    assert [operation.kind for operation in shift.operations] == [
        "ORIGIN_START",
        "INBOUND_POSITIONING",
        "DELIVERY",
        "RETURN_POSITIONING",
    ]
    assert shift.operations[0].planned_departure == datetime(
        2026, 9, 14, 6, tzinfo=UTC
    )
    assert shift.operations[1].planned_arrival == datetime(
        2026, 9, 14, 9, tzinfo=UTC
    )
    assert shift.operations[-1].planned_departure == datetime(
        2026, 9, 14, 11, tzinfo=UTC
    )
    assert shift.operations[-1].planned_arrival == datetime(
        2026, 9, 14, 14, 10, tzinfo=UTC
    )


def test_foreign_shift_without_support_link_evidence_is_rejected() -> None:
    """A foreign warehouse UUID alone never authorizes cross-warehouse execution."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    metrics = _support_metrics(root, origin, uuid4())
    metrics.pop("support_warehouse_link_id")
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(metrics,),
        cycle_distances=(10_000,),
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "CROSS_WAREHOUSE_SUPPORT_LINK_MISSING"


def test_cross_warehouse_evidence_must_match_real_shift_origin() -> None:
    """Reject persisted evidence that names an arbitrary warehouse as route origin."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    metrics = _support_metrics(root, origin, uuid4())
    metrics["resource_origin_warehouse_id"] = str(uuid4())
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(metrics,),
        cycle_distances=(10_000,),
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "CROSS_WAREHOUSE_PLAN_EVIDENCE_INVALID"


def test_foreign_shift_without_exact_return_distance_fails_closed() -> None:
    """Do not substitute a straight-line estimate for a missing positioning route leg."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    metrics = _support_metrics(root, origin, uuid4())
    metrics.pop("return_distance_meters")
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(metrics,),
        cycle_distances=(10_000,),
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "CROSS_WAREHOUSE_POSITIONING_DISTANCE_MISSING"


def test_foreign_shift_without_exact_positioning_time_fails_closed() -> None:
    """Do not reconstruct origin departure from a later driver-availability instant."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    metrics = _support_metrics(root, origin, uuid4())
    metrics.pop("inbound_departure_at")
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(metrics,),
        cycle_distances=(10_000,),
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "CROSS_WAREHOUSE_POSITIONING_TIME_MISSING"


def test_one_shift_cannot_mix_different_support_link_evidence() -> None:
    """Keep one physical workday bound to one exact directed support link."""

    root = _warehouse("Представительский склад")
    origin = _warehouse("Опорный склад")
    source_id = origin.external_warehouse_id
    assert source_id is not None
    plan = _publication_plan(
        root=root,
        origin=origin,
        source=_source_request({uuid4(): source_id}),
        metrics_by_cycle=(
            _support_metrics(root, origin, uuid4()),
            _support_metrics(root, origin, uuid4()),
        ),
        cycle_distances=(10_000, 20_000),
    )

    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)

    assert error.value.code == "DUPLICATE_DRIVER_SHIFT_CONFLICT"
