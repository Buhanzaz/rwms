"""Atomic scenario lifecycle, reproducible interchange, cloning, and demo data."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo

from geoalchemy2.shape import from_shape
from shapely.geometry import LineString, shape
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import Settings
from app.db import utc_now
from app.errors import ApiError, not_found
from app.geo import geometry_from_geojson, geometry_to_geojson
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    OptimizationRun,
    RouteCycle,
    RouteExplanation,
    RoutePlan,
    RouteSegment,
    RouteStop,
    Scenario,
    Trailer,
    UnassignedTask,
    Vehicle,
    VehicleLoadProfile,
    Warehouse,
    Zone,
    ZoneRelation,
)
from app.repositories import get_required
from app.schemas.domain import (
    DriverCreate,
    ExportDriver,
    ExportRelation,
    ExportRequest,
    ExportRouteCycle,
    ExportRoutePlan,
    ExportRouteSegment,
    ExportRouteStop,
    ExportShift,
    ExportTaskReference,
    ExportTrailer,
    ExportUnassignedTask,
    ExportVehicle,
    ExportVehicleLoadProfile,
    ExportWarehouse,
    ExportZone,
    GeoJsonGeometry,
    LogisticsRequestCreate,
    RequestDateOptionInput,
    ScenarioCreate,
    ScenarioExportDocument,
    ScenarioSettings,
    ScenarioUpdate,
    ShiftCreate,
    TrailerCreate,
    VehicleCreate,
    VehicleLoadProfileCreate,
    WarehouseCreate,
    ZoneCreate,
    ZoneRelationCreate,
)
from app.services.catalog import create_request

_DEMO_CARGO_DIMENSIONS = {
    "cargo_length_mm": 6_000,
    "cargo_width_mm": 2_400,
    "cargo_height_mm": 2_400,
    "cargo_weight_kg": 1_200,
}


async def _create_demo_vehicle_bundle(
    session: AsyncSession,
    *,
    scenario_id: UUID,
    index: int,
    name: str,
    registration_number: str,
    notes: str,
) -> Vehicle:
    """Create one complete test truck, its own trailer, and observed axle profiles."""

    trailer = Trailer(
        scenario_id=scenario_id,
        name=f"Прицеп {index}",
        registration_number=f"ПР{index:02d}ТЕСТ",  # noqa: RUF001 - test plate
        active=True,
        tare_weight_kg=3_500,
        max_gross_weight_kg=10_000,
        length_mm=8_000,
        width_mm=2_500,
        height_mm=1_600,
        platform_length_mm=6_500,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=900,
        max_platform_payload_kg=5_000,
        payload_capacity_kg=5_000,
        axle_count=2,
        max_axle_load_kg=8_000,
        max_cargo_length_mm=6_500,
        max_cargo_width_mm=2_500,
        max_cargo_height_mm=2_600,
        max_cargo_weight_kg=5_000,
        notes="Тестовый прицеп для второй бытовки.",
    )
    session.add(trailer)
    await session.flush()
    vehicle = Vehicle(
        scenario_id=scenario_id,
        name=name,
        registration_number=registration_number,
        capacity=2,
        active=True,
        average_speed_city=35,
        average_speed_region=65,
        vehicle_type="PLATFORM_TRUCK",
        manufacturer="Demo",
        model="Cabin carrier",
        is_hgv=True,
        tare_weight_kg=9_000,
        max_gross_weight_kg=18_000,
        length_mm=9_200,
        width_mm=2_500,
        height_mm=3_200,
        axle_count=2,
        max_axle_load_kg=9_000,
        payload_capacity_kg=6_000,
        platform_length_mm=6_500,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=1_200,
        max_platform_payload_kg=6_000,
        max_cargo_length_mm=6_500,
        max_cargo_width_mm=2_500,
        max_cargo_height_mm=2_600,
        max_cargo_weight_kg=5_000,
        can_use_trailer=True,
        default_trailer_id=trailer.id,
        combined_length_with_trailer_mm=18_500,
        coupling_length_mm=1_300,
        height_safety_margin_mm=100,
        width_safety_margin_mm=0,
        weight_safety_margin_kg=0,
        notes=notes,
    )
    session.add(vehicle)
    await session.flush()
    axle_loads = {
        "EMPTY_TRUCK": 6_000,
        "CARGO_ON_TRUCK": 7_500,
        "EMPTY_COMBINATION": 6_500,
        "CARGO_ON_TRUCK_WITH_TRAILER": 7_500,
        "CARGO_ON_TRAILER_WITH_TRAILER": 7_000,
        "TWO_CARGO_SPLIT": 7_800,
    }
    session.add_all(
        [
            VehicleLoadProfile(
                vehicle_id=vehicle.id,
                configuration_type=configuration_type,
                max_actual_axle_load_kg=max_axle_load_kg,
            )
            for configuration_type, max_axle_load_kg in axle_loads.items()
        ]
    )
    return vehicle


def scenario_create_values(payload: ScenarioCreate, settings: Settings) -> dict[str, Any]:
    """Apply process defaults only when the caller omitted the corresponding field."""

    values = payload.model_dump()
    if "timezone" not in payload.model_fields_set:
        values["timezone"] = settings.default_scenario_timezone
    if "seed" not in payload.model_fields_set:
        values["seed"] = settings.planner_default_seed
    values["settings"] = payload.settings.model_dump()
    return values


async def create_scenario(
    session: AsyncSession, payload: ScenarioCreate, settings: Settings
) -> Scenario:
    """Create an empty scenario with fully expanded deterministic settings."""

    entity = Scenario(**scenario_create_values(payload, settings))
    session.add(entity)
    await session.flush()
    return entity


async def list_scenarios(session: AsyncSession) -> list[Scenario]:
    """List scenarios by creation time and UUID for stable client rendering."""

    return list(
        (
            await session.scalars(
                select(Scenario).order_by(Scenario.created_at.asc(), Scenario.id.asc())
            )
        ).all()
    )


async def update_scenario(
    session: AsyncSession, scenario_id: UUID, payload: ScenarioUpdate
) -> Scenario:
    """Patch scenario metadata without replacing omitted settings."""

    entity = await get_required(session, Scenario, scenario_id, "scenario")
    values = payload.model_dump(exclude_unset=True)
    if payload.settings is not None:
        values["settings"] = payload.settings.model_dump()
    for field, value in values.items():
        setattr(entity, field, value)
    await session.flush()
    await session.refresh(entity)
    return entity


async def delete_scenario(session: AsyncSession, scenario_id: UUID) -> None:
    """Delete plans before resources so history-protecting FKs cannot block cleanup."""

    entity = await get_required(session, Scenario, scenario_id, "scenario")
    await session.execute(delete(RoutePlan).where(RoutePlan.scenario_id == scenario_id))
    await session.flush()
    await session.delete(entity)
    await session.flush()


def _scenario_graph_statement(scenario_id: UUID, *, include_plans: bool) -> Any:
    """Build the eager-load graph required for deterministic export or cloning."""

    options: list[Any] = [
        selectinload(Scenario.warehouses),
        selectinload(Scenario.zones),
        selectinload(Scenario.zone_relations),
        selectinload(Scenario.drivers),
        selectinload(Scenario.vehicles),
        selectinload(Scenario.vehicles).selectinload(Vehicle.load_profiles),
        selectinload(Scenario.trailers),
        selectinload(Scenario.shifts),
        selectinload(Scenario.requests).selectinload(LogisticsRequest.date_options),
        selectinload(Scenario.requests).selectinload(LogisticsRequest.tasks),
    ]
    if include_plans:
        cycles = selectinload(Scenario.plans).selectinload(RoutePlan.cycles)
        options.extend(
            [
                cycles.selectinload(RouteCycle.stops).selectinload(RouteStop.task),
                cycles.selectinload(RouteCycle.segments),
                cycles.selectinload(RouteCycle.explanations),
                selectinload(Scenario.plans)
                .selectinload(RoutePlan.unassigned_tasks)
                .selectinload(UnassignedTask.task),
            ]
        )
    return select(Scenario).where(Scenario.id == scenario_id).options(*options)


def _export_route_stop(stop: RouteStop) -> ExportRouteStop:
    """Convert one route stop to a UUID-independent task reference."""

    task = None
    if stop.task is not None:
        task = ExportTaskReference(
            request_id=stop.task.request_id,
            part_number=stop.task.part_number,
        )
    return ExportRouteStop(
        sequence=stop.sequence,
        task=task,
        stop_type=stop.stop_type,
        planned_arrival=stop.planned_arrival,
        planned_departure=stop.planned_departure,
        service_seconds=stop.service_seconds,
        quantity_delta=stop.quantity_delta,
        load_before=stop.load_before,
        load_after=stop.load_after,
        latitude=stop.latitude,
        longitude=stop.longitude,
        warnings=stop.warnings,
        locked=stop.locked,
    )


def _export_route_segment(
    segment: RouteSegment, stop_sequences: dict[UUID, int]
) -> ExportRouteSegment:
    """Convert one segment while replacing stop UUIDs with stable sequences."""

    return ExportRouteSegment(
        sequence=segment.sequence,
        from_stop_sequence=stop_sequences[segment.from_stop_id],
        to_stop_sequence=stop_sequences[segment.to_stop_id],
        departure_at=segment.departure_at,
        arrival_at=segment.arrival_at,
        distance_meters=segment.distance_meters,
        travel_seconds=segment.travel_seconds,
        geometry=geometry_to_geojson(segment.geometry),
        routing_profile_snapshot=segment.routing_profile_snapshot,
        routing_provider=segment.routing_provider,
        osm_data_version=segment.osm_data_version,
        routed_at=segment.routed_at,
    )


def _export_route_cycle(cycle: RouteCycle) -> ExportRouteCycle:
    """Convert a loaded cycle into its validated interchange representation."""

    stop_sequences = {stop.id: stop.sequence for stop in cycle.stops}
    return ExportRouteCycle(
        driver_shift_id=cycle.driver_shift_id,
        sequence=cycle.sequence,
        planned_start=cycle.planned_start,
        planned_finish=cycle.planned_finish,
        total_distance_meters=cycle.total_distance_meters,
        total_travel_seconds=cycle.total_travel_seconds,
        total_service_seconds=cycle.total_service_seconds,
        empty_distance_meters=cycle.empty_distance_meters,
        detour_seconds=cycle.detour_seconds,
        score=cycle.score,
        locked=cycle.locked,
        manually_changed=cycle.manually_changed,
        metrics=cycle.metrics,
        stops=[_export_route_stop(stop) for stop in cycle.stops],
        segments=[_export_route_segment(segment, stop_sequences) for segment in cycle.segments],
        explanations=[
            {
                "explanation_type": explanation.explanation_type,
                "summary_ru": explanation.summary_ru,
                "facts": explanation.facts,
            }
            for explanation in cycle.explanations
        ],
    )


def _export_route_plan(plan: RoutePlan) -> ExportRoutePlan:
    """Convert one saved plan including explainability and unassigned reasons."""

    return ExportRoutePlan(
        id=plan.id,
        warehouse_id=plan.warehouse_id,
        date=plan.date,
        name=plan.name,
        version=plan.version,
        status=plan.status,
        score=plan.score,
        metrics=plan.metrics,
        validation_errors=plan.validation_errors,
        validation_warnings=plan.validation_warnings,
        manually_changed=plan.manually_changed,
        cycles=[_export_route_cycle(cycle) for cycle in plan.cycles],
        unassigned_tasks=[
            ExportUnassignedTask(
                task=ExportTaskReference(
                    request_id=item.task.request_id,
                    part_number=item.task.part_number,
                ),
                reason_codes=item.reason_codes,
                descriptions_ru=item.descriptions_ru,
                nearest_option=item.nearest_option,
                recommendation_ru=item.recommendation_ru,
            )
            for item in plan.unassigned_tasks
        ],
    )


async def export_scenario(
    session: AsyncSession, scenario_id: UUID, *, include_plans: bool
) -> ScenarioExportDocument:
    """Build one schema-validated JSON document from authoritative rows."""

    scenario = await session.scalar(
        _scenario_graph_statement(scenario_id, include_plans=include_plans)
    )
    if scenario is None:
        raise not_found("scenario", scenario_id)
    request_exports: list[ExportRequest] = []
    for request in scenario.requests:
        request_exports.append(
            ExportRequest(
                id=request.id,
                data=LogisticsRequestCreate(
                    type=request.type,
                    name=request.name,
                    address_label=request.address_label,
                    latitude=request.latitude,
                    longitude=request.longitude,
                    quantity=request.quantity,
                    cargo_length_mm=request.cargo_length_mm,
                    cargo_width_mm=request.cargo_width_mm,
                    cargo_height_mm=request.cargo_height_mm,
                    cargo_weight_kg=request.cargo_weight_kg,
                    service_minutes=request.service_minutes,
                    priority=request.priority,
                    status=request.status,
                    split_allowed=request.split_allowed,
                    notes=request.notes,
                    date_options=[
                        RequestDateOptionInput.model_validate(option)
                        for option in request.date_options
                    ],
                ),
                source_system=request.source_system,
                external_id=request.external_id,
                external_version=request.external_version,
                external_payload=request.external_payload,
                scheduled_date=request.scheduled_date,
                zone_id=request.zone_id,
                zone_version=request.zone_version,
                zone_classification_status=request.zone_classification_status,
            )
        )
    return ScenarioExportDocument(
        exported_at=utc_now(),
        scenario=ScenarioCreate(
            name=scenario.name,
            description=scenario.description,
            timezone=scenario.timezone,
            default_planning_date=scenario.default_planning_date,
            seed=scenario.seed,
            settings=ScenarioSettings.model_validate(scenario.settings),
        ),
        warehouses=[
            ExportWarehouse(id=item.id, data=WarehouseCreate.model_validate(item))
            for item in scenario.warehouses
        ],
        zones=[
            ExportZone(
                id=item.id,
                data=ZoneCreate(
                    name=item.name,
                    code=item.code,
                    route_group=item.route_group,
                    geometry=GeoJsonGeometry.model_validate(geometry_to_geojson(item.geometry)),
                    priority=item.priority,
                    delivery_price=item.delivery_price,
                    pickup_price=item.pickup_price,
                    locked=item.locked,
                ),
                version=item.version,
            )
            for item in scenario.zones
        ],
        zone_relations=[
            ExportRelation(id=item.id, data=ZoneRelationCreate.model_validate(item))
            for item in scenario.zone_relations
        ],
        drivers=[
            ExportDriver(id=item.id, data=DriverCreate.model_validate(item))
            for item in scenario.drivers
        ],
        vehicles=[
            ExportVehicle(id=item.id, data=VehicleCreate.model_validate(item))
            for item in scenario.vehicles
        ],
        trailers=[
            ExportTrailer(id=item.id, data=TrailerCreate.model_validate(item))
            for item in scenario.trailers
        ],
        vehicle_load_profiles=[
            ExportVehicleLoadProfile(
                id=profile.id,
                vehicle_id=vehicle.id,
                data=VehicleLoadProfileCreate.model_validate(profile),
            )
            for vehicle in scenario.vehicles
            for profile in vehicle.load_profiles
        ],
        shifts=[
            ExportShift(id=item.id, data=ShiftCreate.model_validate(item))
            for item in scenario.shifts
        ],
        requests=request_exports,
        plans=[_export_route_plan(plan) for plan in scenario.plans] if include_plans else [],
    )


def _mapped(mapping: dict[UUID, UUID], old_id: UUID, resource: str) -> UUID:
    """Resolve a validated cross-reference or fail the entire import."""

    try:
        return mapping[old_id]
    except KeyError as exc:
        raise ApiError(
            422,
            "IMPORT_REFERENCE_INVALID",
            f"Imported {resource} references unknown id {old_id}",
        ) from exc


def _valid_linestring(payload: dict[str, Any]) -> LineString:
    """Validate imported route geometry as a non-empty LineString."""

    geometry = shape(payload)
    if not isinstance(geometry, LineString) or geometry.is_empty or not geometry.is_valid:
        raise ApiError(
            422,
            "IMPORT_ROUTE_GEOMETRY_INVALID",
            "Route segment geometry must be a valid non-empty LineString",
        )
    return geometry


async def import_scenario(
    session: AsyncSession,
    document: ScenarioExportDocument,
    settings: Settings,
    *,
    name: str | None = None,
) -> Scenario:
    """Import a complete document with new UUIDs in the caller's transaction."""

    scenario_payload = document.scenario.model_copy(
        update={"name": name} if name is not None else {}
    )
    scenario = await create_scenario(session, scenario_payload, settings)
    warehouse_ids: dict[UUID, UUID] = {}
    zone_ids: dict[UUID, UUID] = {}
    driver_ids: dict[UUID, UUID] = {}
    trailer_ids: dict[UUID, UUID] = {}
    vehicle_ids: dict[UUID, UUID] = {}
    shift_ids: dict[UUID, UUID] = {}
    tasks: dict[tuple[UUID, int], UUID] = {}

    for warehouse_item in document.warehouses:
        warehouse = Warehouse(scenario_id=scenario.id, **warehouse_item.data.model_dump())
        session.add(warehouse)
        await session.flush()
        warehouse_ids[warehouse_item.id] = warehouse.id
    for zone_item in document.zones:
        values = zone_item.data.model_dump(exclude={"geometry"})
        zone = Zone(
            scenario_id=scenario.id,
            geometry=geometry_from_geojson(zone_item.data.geometry),
            version=zone_item.version,
            **values,
        )
        session.add(zone)
        await session.flush()
        zone_ids[zone_item.id] = zone.id
    for relation_item in document.zone_relations:
        values = relation_item.data.model_dump(exclude={"from_zone_id", "to_zone_id"})
        relation = ZoneRelation(
            scenario_id=scenario.id,
            from_zone_id=_mapped(zone_ids, relation_item.data.from_zone_id, "zone relation"),
            to_zone_id=_mapped(zone_ids, relation_item.data.to_zone_id, "zone relation"),
            **values,
        )
        session.add(relation)
    for driver_item in document.drivers:
        driver = Driver(scenario_id=scenario.id, **driver_item.data.model_dump())
        session.add(driver)
        await session.flush()
        driver_ids[driver_item.id] = driver.id
    for trailer_item in document.trailers:
        trailer = Trailer(scenario_id=scenario.id, **trailer_item.data.model_dump())
        session.add(trailer)
        await session.flush()
        trailer_ids[trailer_item.id] = trailer.id
    for vehicle_item in document.vehicles:
        values = vehicle_item.data.model_dump(exclude={"default_trailer_id"})
        default_trailer_id = vehicle_item.data.default_trailer_id
        vehicle = Vehicle(
            scenario_id=scenario.id,
            default_trailer_id=(
                _mapped(trailer_ids, default_trailer_id, "vehicle default trailer")
                if default_trailer_id is not None
                else None
            ),
            **values,
        )
        session.add(vehicle)
        await session.flush()
        vehicle_ids[vehicle_item.id] = vehicle.id
    for profile_item in document.vehicle_load_profiles:
        session.add(
            VehicleLoadProfile(
                vehicle_id=_mapped(
                    vehicle_ids, profile_item.vehicle_id, "vehicle load profile"
                ),
                **profile_item.data.model_dump(),
            )
        )
    for shift_item in document.shifts:
        values = shift_item.data.model_dump(exclude={"driver_id", "vehicle_id"})
        shift = DriverShift(
            scenario_id=scenario.id,
            driver_id=_mapped(driver_ids, shift_item.data.driver_id, "shift driver"),
            vehicle_id=_mapped(vehicle_ids, shift_item.data.vehicle_id, "shift vehicle"),
            **values,
        )
        session.add(shift)
        await session.flush()
        shift_ids[shift_item.id] = shift.id
    for request_item in document.requests:
        logistics_request = await create_request(session, scenario.id, request_item.data)
        logistics_request.source_system = request_item.source_system
        logistics_request.external_id = request_item.external_id
        logistics_request.external_version = request_item.external_version
        logistics_request.external_payload = request_item.external_payload
        logistics_request.scheduled_date = request_item.scheduled_date
        if request_item.zone_id is None:
            logistics_request.zone_id = None
            logistics_request.zone_version = request_item.zone_version
            logistics_request.zone_classification_status = request_item.zone_classification_status
        else:
            logistics_request.zone_id = _mapped(zone_ids, request_item.zone_id, "request zone")
            logistics_request.zone_version = request_item.zone_version
            logistics_request.zone_classification_status = request_item.zone_classification_status
        for task in logistics_request.tasks:
            task.zone_id = logistics_request.zone_id
            task.zone_version = logistics_request.zone_version
            tasks[(request_item.id, task.part_number)] = task.id

    for plan_item in document.plans:
        plan = RoutePlan(
            scenario_id=scenario.id,
            warehouse_id=_mapped(warehouse_ids, plan_item.warehouse_id, "plan warehouse"),
            date=plan_item.date,
            name=plan_item.name,
            version=plan_item.version,
            status=plan_item.status,
            score=plan_item.score,
            metrics=plan_item.metrics,
            validation_errors=plan_item.validation_errors,
            validation_warnings=plan_item.validation_warnings,
            manually_changed=plan_item.manually_changed,
        )
        session.add(plan)
        await session.flush()
        for cycle_item in plan_item.cycles:
            cycle = RouteCycle(
                route_plan_id=plan.id,
                driver_shift_id=_mapped(
                    shift_ids, cycle_item.driver_shift_id, "cycle driver shift"
                ),
                sequence=cycle_item.sequence,
                planned_start=cycle_item.planned_start,
                planned_finish=cycle_item.planned_finish,
                total_distance_meters=cycle_item.total_distance_meters,
                total_travel_seconds=cycle_item.total_travel_seconds,
                total_service_seconds=cycle_item.total_service_seconds,
                empty_distance_meters=cycle_item.empty_distance_meters,
                detour_seconds=cycle_item.detour_seconds,
                score=cycle_item.score,
                locked=cycle_item.locked,
                manually_changed=cycle_item.manually_changed,
                metrics=cycle_item.metrics,
            )
            session.add(cycle)
            await session.flush()
            stop_ids: dict[int, UUID] = {}
            for stop_item in cycle_item.stops:
                task_id = None
                if stop_item.task is not None:
                    task_id = tasks.get((stop_item.task.request_id, stop_item.task.part_number))
                    if task_id is None:
                        raise ApiError(
                            422,
                            "IMPORT_REFERENCE_INVALID",
                            "Route stop references an unknown request task part",
                        )
                stop = RouteStop(
                    route_cycle_id=cycle.id,
                    task_id=task_id,
                    **stop_item.model_dump(exclude={"task"}),
                )
                session.add(stop)
                await session.flush()
                stop_ids[stop.sequence] = stop.id
            for segment_item in cycle_item.segments:
                from_stop_id = stop_ids.get(segment_item.from_stop_sequence)
                to_stop_id = stop_ids.get(segment_item.to_stop_sequence)
                if from_stop_id is None or to_stop_id is None:
                    raise ApiError(
                        422,
                        "IMPORT_REFERENCE_INVALID",
                        "Route segment references an unknown stop sequence",
                    )
                geometry = _valid_linestring(segment_item.geometry)
                session.add(
                    RouteSegment(
                        route_cycle_id=cycle.id,
                        from_stop_id=from_stop_id,
                        to_stop_id=to_stop_id,
                        geometry=from_shape(geometry, srid=4326, extended=True),
                        **segment_item.model_dump(
                            exclude={
                                "from_stop_sequence",
                                "to_stop_sequence",
                                "geometry",
                            }
                        ),
                    )
                )
            for explanation in cycle_item.explanations:
                try:
                    explanation_type = str(explanation["explanation_type"])
                    summary_ru = str(explanation["summary_ru"])
                    facts = list(explanation.get("facts", []))
                except (KeyError, TypeError, ValueError) as exc:
                    raise ApiError(
                        422,
                        "IMPORT_EXPLANATION_INVALID",
                        "Cycle explanation requires explanation_type, summary_ru, and facts",
                    ) from exc
                session.add(
                    RouteExplanation(
                        route_cycle_id=cycle.id,
                        explanation_type=explanation_type,
                        summary_ru=summary_ru,
                        facts=facts,
                    )
                )
        for unassigned_item in plan_item.unassigned_tasks:
            task_id = tasks.get((unassigned_item.task.request_id, unassigned_item.task.part_number))
            if task_id is None:
                raise ApiError(
                    422,
                    "IMPORT_REFERENCE_INVALID",
                    "Unassigned result references an unknown request task part",
                )
            session.add(
                UnassignedTask(
                    route_plan_id=plan.id,
                    task_id=task_id,
                    **unassigned_item.model_dump(exclude={"task"}),
                )
            )
    await session.flush()
    await session.refresh(scenario)
    return scenario


async def clone_scenario(
    session: AsyncSession,
    scenario_id: UUID,
    settings: Settings,
    *,
    name: str | None,
) -> Scenario:
    """Clone inputs and saved plans through the same validated interchange model."""

    document = await export_scenario(session, scenario_id, include_plans=True)
    clone_name = name or f"{document.scenario.name} — копия"
    return await import_scenario(session, document, settings, name=clone_name)


async def _clear_scenario_data(session: AsyncSession, scenario_id: UUID) -> None:
    """Delete only data owned by an explicitly reset scenario in safe dependency order."""

    for model in (
        OptimizationRun,
        RoutePlan,
        LogisticsRequest,
        ZoneRelation,
        DriverShift,
        Zone,
        Driver,
        Vehicle,
        Trailer,
        Warehouse,
    ):
        await session.execute(delete(model).where(model.scenario_id == scenario_id))
    await session.flush()


def _zone_geometry(west: float, south: float, east: float, north: float) -> GeoJsonGeometry:
    """Build one rectangular demo zone with longitude-latitude coordinates."""

    return GeoJsonGeometry(
        type="Polygon",
        coordinates=[
            [
                (west, south),
                (east, south),
                (east, north),
                (west, north),
                (west, south),
            ]
        ],
    )


async def reset_demo_scenario(session: AsyncSession, scenario_id: UUID) -> Scenario:
    """Replace scenario contents with the deterministic four-zone Moscow demo."""

    scenario = await get_required(session, Scenario, scenario_id, "scenario")
    await _clear_scenario_data(session, scenario_id)
    scenario.description = "Демонстрационный сценарий: четыре зоны, три смены и парные заявки."
    scenario.settings = ScenarioSettings(
        max_detour_minutes=60,
        max_detour_ratio=3.0,
    ).model_dump()
    zone_info = ZoneInfo(scenario.timezone)
    planning_date = scenario.default_planning_date or datetime.now(zone_info).date()
    scenario.default_planning_date = planning_date

    warehouse = Warehouse(
        scenario_id=scenario.id,
        name="Основной склад",
        latitude=55.75,
        longitude=37.39,
        loading_minutes=30,
        unloading_minutes=30,
        turnaround_minutes=15,
        working_day_start=time(8),
        working_day_end=time(20),
    )
    session.add(warehouse)
    zone_specs = [
        ("Z1", "WEST", 37.40, 55.70, 37.60, 55.80, 20),
        ("Z2", "EAST", 37.60, 55.70, 37.80, 55.80, 20),
        ("Z3", "REGION", 37.40, 55.60, 37.60, 55.70, 10),
        ("Z4", "CITY", 37.60, 55.60, 37.80, 55.70, 10),
    ]
    zones: dict[str, Zone] = {}
    for code, group, west, south, east, north, priority in zone_specs:
        zone = Zone(
            scenario_id=scenario.id,
            name=f"Зона {code}",
            code=code,
            route_group=group,
            geometry=geometry_from_geojson(_zone_geometry(west, south, east, north)),
            version=1,
            priority=priority,
            locked=False,
        )
        session.add(zone)
        zones[code] = zone
    await session.flush()
    for from_code, to_code in (("Z1", "Z2"), ("Z1", "Z3"), ("Z2", "Z4"), ("Z3", "Z4")):
        session.add(
            ZoneRelation(
                scenario_id=scenario.id,
                from_zone_id=zones[from_code].id,
                to_zone_id=zones[to_code].id,
                relation_type="ADJACENT",
                delivery_pair_allowed=True,
                pickup_allowed=True,
                max_detour_minutes=60,
                max_detour_ratio=3.0,
                penalty=0,
                is_bidirectional=True,
            )
        )

    driver_groups = ("WEST", "EAST", "REGION")
    for index, group in enumerate(driver_groups, start=1):
        driver = Driver(
            scenario_id=scenario.id,
            name=f"Водитель {index}",
            preferred_route_group=group,
            active=True,
            notes="",
        )
        vehicle = await _create_demo_vehicle_bundle(
            session,
            scenario_id=scenario.id,
            index=index,
            name=f"Машина {index}",
            registration_number=f"А{index}23БВ",  # noqa: RUF001 - Russian plate label
            notes="Полный профиль truck-routing для демонстрационного сценария.",
        )
        session.add(driver)
        await session.flush()
        session.add(
            DriverShift(
                scenario_id=scenario.id,
                driver_id=driver.id,
                vehicle_id=vehicle.id,
                date=planning_date,
                start_at=datetime.combine(planning_date, time(8), tzinfo=zone_info),
                end_at=datetime.combine(planning_date, time(20), tzinfo=zone_info),
                break_minutes=30,
                preferred_route_group=group,
                active=True,
            )
        )
    await session.flush()

    request_specs = [
        ("DELIVERY", "Доставка Z1", 37.50, 55.75, 1, 100, 30),
        ("DELIVERY", "Доставка Z2", 37.66, 55.75, 1, 90, 30),
        ("PICKUP", "Вывоз Z2", 37.69, 55.73, 1, 80, 5),
        ("PICKUP", "Вывоз Z1", 37.54, 55.72, 1, 70, 5),
        ("DELIVERY", "Доставка Z2 — цикл 2", 37.74, 55.77, 2, 50, 30),
        ("PICKUP", "Вывоз Z3 — кандидат", 37.57, 55.67, 2, 40, 30),
    ]
    for (
        request_type,
        name,
        longitude,
        latitude,
        quantity,
        priority,
        service_minutes,
    ) in request_specs:
        await create_request(
            session,
            scenario.id,
            LogisticsRequestCreate(
                type=request_type,
                name=name,
                address_label=name,
                latitude=latitude,
                longitude=longitude,
                quantity=quantity,
                **_DEMO_CARGO_DIMENSIONS,
                service_minutes=service_minutes,
                priority=priority,
                status="READY",
                split_allowed=True,
                notes="",
                date_options=[
                    RequestDateOptionInput(
                        date=planning_date,
                        priority=100,
                        window_start=time(8),
                        window_end=time(20),
                        is_hard=False,
                    )
                ],
            ),
        )
    await session.flush()
    await session.refresh(scenario)
    return scenario


async def create_multi_day_demo_scenario(session: AsyncSession, settings: Settings) -> Scenario:
    """Create an independent three-day workload without changing an existing scenario.

    The fixture keeps exactly three drivers and three vehicles available on each
    date. It deliberately overlaps accepted dates: every date shows ten requests,
    while some of them are the same client requests carried forward after a call.
    This makes date switching, negotiated alternatives and return pickups testable
    without replacing an operator's existing scenario.
    """

    timezone = "Europe/Moscow"
    zone_info = ZoneInfo(timezone)
    planning_date = datetime.now(zone_info).date()
    scenario = await create_scenario(
        session,
        ScenarioCreate(
            name="Многодневный тестовый стенд · 3 водителя",
            description=(
                "Три дня, шесть зон, три параллельные смены и по 10 заявок на "
                "каждую дату. Часть клиентов согласна на соседний день. "
                "Исходный demo не изменяется."
            ),
            timezone=timezone,
            default_planning_date=planning_date,
            seed=20260822,
        ),
        settings,
    )
    scenario.settings = ScenarioSettings(
        max_detour_minutes=60,
        max_detour_ratio=3.0,
    ).model_dump()

    warehouse = Warehouse(
        scenario_id=scenario.id,
        name="Основной склад · тест на 3 дня",
        latitude=55.755,
        longitude=37.385,
        loading_minutes=30,
        unloading_minutes=30,
        turnaround_minutes=15,
        working_day_start=time(8),
        working_day_end=time(20),
    )
    session.add(warehouse)
    zone_specs = [
        ("Z1", "WEST", 37.40, 55.75, 37.55, 55.85),
        ("Z2", "CITY", 37.55, 55.75, 37.70, 55.85),
        ("Z3", "EAST", 37.70, 55.75, 37.85, 55.85),
        ("Z4", "SOUTH", 37.40, 55.65, 37.55, 55.75),
        ("Z5", "REGION", 37.55, 55.65, 37.70, 55.75),
        ("Z6", "EAST", 37.70, 55.65, 37.85, 55.75),
    ]
    zones: dict[str, Zone] = {}
    for code, route_group, west, south, east, north in zone_specs:
        zone = Zone(
            scenario_id=scenario.id,
            name=f"Тестовая зона {code}",
            code=code,
            route_group=route_group,
            geometry=geometry_from_geojson(_zone_geometry(west, south, east, north)),
            version=1,
            priority=20,
            locked=False,
        )
        session.add(zone)
        zones[code] = zone
    await session.flush()
    for from_code, to_code in (
        ("Z1", "Z2"),
        ("Z2", "Z3"),
        ("Z4", "Z5"),
        ("Z5", "Z6"),
        ("Z1", "Z4"),
        ("Z2", "Z5"),
        ("Z3", "Z6"),
    ):
        session.add(
            ZoneRelation(
                scenario_id=scenario.id,
                from_zone_id=zones[from_code].id,
                to_zone_id=zones[to_code].id,
                relation_type="ADJACENT",
                delivery_pair_allowed=True,
                pickup_allowed=True,
                max_detour_minutes=60,
                max_detour_ratio=3.0,
                penalty=0,
                is_bidirectional=True,
            )
        )

    driver_groups = ("WEST", "CITY", "EAST")
    drivers: list[Driver] = []
    vehicles: list[Vehicle] = []
    for index, group in enumerate(driver_groups, start=1):
        driver = Driver(
            scenario_id=scenario.id,
            name=f"Тестовый водитель {index}",
            preferred_route_group=group,
            active=True,
            notes="Одна из трёх параллельных смен многодневного стенда.",
        )
        vehicle = await _create_demo_vehicle_bundle(
            session,
            scenario_id=scenario.id,
            index=index + 10,
            name=f"Тестовая машина {index}",
            registration_number=f"T{index}28MC",
            notes="Вместимость две бытовки; заполнен безопасный грузовой профиль.",
        )
        session.add(driver)
        drivers.append(driver)
        vehicles.append(vehicle)
    await session.flush()
    for offset in range(3):
        shift_date = planning_date + timedelta(days=offset)
        for driver, vehicle, group in zip(drivers, vehicles, driver_groups, strict=True):
            session.add(
                DriverShift(
                    scenario_id=scenario.id,
                    driver_id=driver.id,
                    vehicle_id=vehicle.id,
                    date=shift_date,
                    start_at=datetime.combine(shift_date, time(8), tzinfo=zone_info),
                    end_at=datetime.combine(shift_date, time(20), tzinfo=zone_info),
                    break_minutes=30,
                    preferred_route_group=group,
                    active=True,
                )
            )
    await session.flush()

    def date_option(
        option_date: date,
        priority: int = 100,
        *,
        start: time | None = time(8),
        end: time | None = time(20),
        hard: bool = False,
    ) -> RequestDateOptionInput:
        """Build a compact accepted-date fixture value for a source request."""

        return RequestDateOptionInput(
            date=option_date,
            priority=priority,
            window_start=start,
            window_end=end,
            is_hard=hard,
        )

    d0, d1, d2 = (planning_date + timedelta(days=offset) for offset in range(3))
    request_specs: list[tuple[str, str, float, float, int, int, list[RequestDateOptionInput]]] = [
        # День 1: 10 заявок. Пять клиентов готовы перенести работу на день 2.
        (
            "DELIVERY",
            "Аренда «Запад»",
            37.47,
            55.81,
            1,
            95,
            [date_option(d0, 100), date_option(d1, 60)],
        ),
        ("DELIVERY", "Аренда «Центр»", 37.62, 55.81, 1, 90, [date_option(d0)]),
        (
            "PICKUP",
            "Возврат «Центр»",
            37.66,
            55.78,
            1,
            80,
            [date_option(d0, 100), date_option(d1, 60)],
        ),
        ("PICKUP", "Возврат «Запад»", 37.49, 55.78, 1, 75, [date_option(d0)]),
        (
            "DELIVERY",
            "Аренда «Восток» · 2 бытовки",
            37.77,
            55.82,
            2,
            88,
            [date_option(d0, 100), date_option(d1, 60)],
        ),
        ("PICKUP", "Возврат «Восток»", 37.79, 55.79, 1, 77, [date_option(d0)]),
        (
            "DELIVERY",
            "Клиент «Перезвонить» · запад",
            37.51,
            55.70,
            1,
            82,
            [date_option(d0, 100), date_option(d1, 60)],
        ),
        ("PICKUP", "Возврат «Юг»", 37.49, 55.68, 1, 70, [date_option(d0)]),
        (
            "DELIVERY",
            "Аренда «Регион»",
            37.64,
            55.70,
            1,
            86,
            [date_option(d0, 100), date_option(d1, 60)],
        ),
        ("PICKUP", "Возврат «Регион»", 37.66, 55.68, 1, 72, [date_option(d0)]),
        # День 2: 5 заявок выше + 5 новых. Две новые переносятся на день 3.
        (
            "DELIVERY",
            "Аренда «Центр завтра»",
            37.60,
            55.80,
            1,
            90,
            [date_option(d1, 100), date_option(d2, 60)],
        ),
        (
            "PICKUP",
            "Возврат «Центр завтра»",
            37.64,
            55.77,
            1,
            78,
            [date_option(d1, 100), date_option(d2, 60)],
        ),
        ("DELIVERY", "Аренда «Юго-восток» · 2 бытовки", 37.81, 55.69, 2, 92, [date_option(d1)]),
        ("PICKUP", "Возврат «Юго-восток»", 37.79, 55.68, 1, 77, [date_option(d1)]),
        ("DELIVERY", "Аренда «Запад завтра»", 37.46, 55.80, 1, 85, [date_option(d1)]),
        # День 3: 2 заявки выше + 8 новых = ровно 10 видимых заявок.
        ("DELIVERY", "Аренда «Юг послезавтра»", 37.48, 55.70, 1, 88, [date_option(d2)]),
        ("PICKUP", "Возврат «Юг послезавтра»", 37.52, 55.68, 1, 76, [date_option(d2)]),
        ("DELIVERY", "Аренда «Восток послезавтра»", 37.75, 55.81, 1, 87, [date_option(d2)]),
        ("PICKUP", "Возврат «Восток послезавтра»", 37.78, 55.78, 1, 75, [date_option(d2)]),
        (
            "DELIVERY",
            "Аренда «Регион послезавтра» · 2 бытовки",
            37.63,
            55.70,
            2,
            91,
            [date_option(d2)],
        ),
        ("PICKUP", "Возврат «Регион послезавтра»", 37.67, 55.68, 1, 74, [date_option(d2)]),
        ("DELIVERY", "Аренда «Запад 2»", 37.48, 55.82, 1, 84, [date_option(d2)]),
        ("PICKUP", "Возврат «Запад 2»", 37.51, 55.78, 1, 73, [date_option(d2)]),
    ]
    for request_type, name, longitude, latitude, quantity, priority, options in request_specs:
        await create_request(
            session,
            scenario.id,
            LogisticsRequestCreate(
                type=request_type,
                name=name,
                address_label=name,
                latitude=latitude,
                longitude=longitude,
                quantity=quantity,
                **_DEMO_CARGO_DIMENSIONS,
                service_minutes=30,
                priority=priority,
                status="READY",
                split_allowed=True,
                notes="Данные стенда: допустимые даты меняются по итогам звонка.",
                date_options=options,
            ),
        )
    await session.flush()
    await session.refresh(scenario)
    return scenario
