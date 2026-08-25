"""PostGIS integration tests for demo data and atomic scenario interchange."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.errors import ApiError
from app.models import Scenario, Trailer, VehicleLoadProfile
from app.models.domain import PlanStatus, StopType
from app.schemas.domain import (
    ExportRouteCycle,
    ExportRoutePlan,
    ExportRouteSegment,
    ExportRouteStop,
    ExportTaskReference,
    RequestScheduleInput,
    ScenarioCreate,
    ScenarioExportDocument,
)
from app.services import catalog, scenarios

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_demo_export_import_preserves_seed_versions_and_counts(
    db_session: AsyncSession,
) -> None:
    """The deterministic demo round-trips with remapped IDs and preserved snapshots."""

    settings = Settings(planner_default_seed=77)
    source = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Demo", seed=77), settings
    )
    await scenarios.reset_demo_scenario(db_session, source.id)
    source_requests = await catalog.list_requests(db_session, source.id)
    source_requests[0].source_system = "RWMS"
    source_requests[0].external_id = uuid4()
    source_requests[0].external_version = 7
    source_requests[0].external_payload = {
        "orderNumber": "R-007",
        "nested": {"unitIds": [str(uuid4())]},
    }
    expected_source_metadata = (
        source_requests[0].source_system,
        source_requests[0].external_id,
        source_requests[0].external_version,
        deepcopy(source_requests[0].external_payload),
    )
    await db_session.flush()
    selected_date = source.default_planning_date
    assert selected_date is not None
    await catalog.schedule_request(
        db_session,
        source_requests[0].id,
        RequestScheduleInput(date=selected_date),
    )
    document = await scenarios.export_scenario(db_session, source.id, include_plans=True)
    shift = document.shifts[0]
    routed_vehicle = next(
        item for item in document.vehicles if item.id == shift.data.vehicle_id
    )
    routed_request = next(
        item
        for item in document.requests
        if item.data.type == "DELIVERY" and item.data.quantity == 1
    )
    routed_at = datetime(2026, 8, 25, 5, tzinfo=UTC)
    departure_at = routed_at + timedelta(minutes=30)
    arrival_at = departure_at + timedelta(minutes=45)
    profile_snapshot = {
        "vehicleId": str(routed_vehicle.id),
        "trailerId": str(routed_vehicle.data.default_trailer_id),
        "trailerAttached": True,
        "cargoCount": 1,
        "cargoPlacements": [
            {"cargoId": "source-task-part-1", "position": "TRUCK_PLATFORM"}
        ],
        "configurationType": "CARGO_ON_TRUCK_WITH_TRAILER",
        "effectiveHeightMeters": 3.7,
        "effectiveWidthMeters": 2.5,
        "effectiveLengthMeters": 18.5,
        "actualWeightTons": 15.0,
        "maxAxleLoadTons": 7.5,
    }
    document.plans.append(
        ExportRoutePlan(
            id=uuid4(),
            warehouse_id=document.warehouses[0].id,
            date=selected_date,
            name="Truck snapshot",
            version=1,
            status=PlanStatus.GENERATED,
            score=45,
            metrics={},
            validation_errors=[],
            validation_warnings=[],
            manually_changed=False,
            cycles=[
                ExportRouteCycle(
                    driver_shift_id=shift.id,
                    sequence=1,
                    planned_start=departure_at,
                    planned_finish=arrival_at,
                    total_distance_meters=12_000,
                    total_travel_seconds=2_700,
                    total_service_seconds=0,
                    empty_distance_meters=0,
                    detour_seconds=0,
                    score=45,
                    locked=False,
                    manually_changed=False,
                    metrics={},
                    stops=[
                        ExportRouteStop(
                            sequence=0,
                            task=None,
                            stop_type=StopType.DEPOT_LOAD,
                            planned_arrival=departure_at,
                            planned_departure=departure_at,
                            service_seconds=0,
                            quantity_delta=1,
                            load_before=0,
                            load_after=1,
                            latitude=document.warehouses[0].data.latitude,
                            longitude=document.warehouses[0].data.longitude,
                        ),
                        ExportRouteStop(
                            sequence=1,
                            task=ExportTaskReference(
                                request_id=routed_request.id,
                                part_number=1,
                            ),
                            stop_type=StopType.DELIVERY,
                            planned_arrival=arrival_at,
                            planned_departure=arrival_at,
                            service_seconds=0,
                            quantity_delta=-1,
                            load_before=1,
                            load_after=0,
                            latitude=routed_request.data.latitude,
                            longitude=routed_request.data.longitude,
                        ),
                    ],
                    segments=[
                        ExportRouteSegment(
                            sequence=1,
                            from_stop_sequence=0,
                            to_stop_sequence=1,
                            departure_at=departure_at,
                            arrival_at=arrival_at,
                            distance_meters=12_000,
                            travel_seconds=2_700,
                            geometry={
                                "type": "LineString",
                                "coordinates": [
                                    [
                                        document.warehouses[0].data.longitude,
                                        document.warehouses[0].data.latitude,
                                    ],
                                    [
                                        routed_request.data.longitude,
                                        routed_request.data.latitude,
                                    ],
                                ],
                            },
                            routing_profile_snapshot=profile_snapshot,
                            routing_provider="valhalla",
                            osm_data_version="moscow-2026-08-24",
                            routed_at=routed_at,
                        )
                    ],
                    explanations=[],
                )
            ],
            unassigned_tasks=[],
        )
    )
    document.zones[0].data.delivery_price = 12_500
    document.zones[0].data.pickup_price = 9_000
    imported = await scenarios.import_scenario(db_session, document, settings, name="Imported demo")
    imported_document = await scenarios.export_scenario(db_session, imported.id, include_plans=True)

    assert imported.id != source.id
    assert imported.seed == source.seed == 77
    assert [zone.version for zone in imported_document.zones] == [
        zone.version for zone in document.zones
    ]
    assert len(imported_document.warehouses) == 1
    assert len(imported_document.zones) == 4
    assert imported_document.zones[0].data.delivery_price == 12_500
    assert imported_document.zones[0].data.pickup_price == 9_000
    assert len(imported_document.drivers) == 3
    assert len(imported_document.vehicles) == 3
    assert len(imported_document.trailers) == 3
    assert len(imported_document.vehicle_load_profiles) == 18
    assert len(imported_document.shifts) == 3
    assert len(imported_document.requests) == 6
    imported_trailer_ids = {item.id for item in imported_document.trailers}
    source_trailer_ids = {item.id for item in document.trailers}
    assert imported_trailer_ids.isdisjoint(source_trailer_ids)
    assert all(
        item.data.default_trailer_id in imported_trailer_ids
        for item in imported_document.vehicles
    )
    assert all(
        item.data.default_trailer_id not in source_trailer_ids
        for item in imported_document.vehicles
    )
    assert {
        (item.data.registration_number, item.data.length_mm, item.data.tare_weight_kg)
        for item in imported_document.trailers
    } == {
        (item.data.registration_number, item.data.length_mm, item.data.tare_weight_kg)
        for item in document.trailers
    }
    source_vehicle_ids = {item.id for item in document.vehicles}
    imported_vehicle_ids = {item.id for item in imported_document.vehicles}
    assert imported_vehicle_ids.isdisjoint(source_vehicle_ids)
    assert {
        (item.data.configuration_type, item.data.max_actual_axle_load_kg)
        for item in imported_document.vehicle_load_profiles
    } == {
        (item.data.configuration_type, item.data.max_actual_axle_load_kg)
        for item in document.vehicle_load_profiles
    }
    assert {
        item.id for item in imported_document.vehicle_load_profiles
    }.isdisjoint({item.id for item in document.vehicle_load_profiles})
    assert all(
        item.vehicle_id in imported_vehicle_ids
        for item in imported_document.vehicle_load_profiles
    )
    assert all(
        item.data.cargo_length_mm == 6_000
        and item.data.cargo_width_mm == 2_400
        and item.data.cargo_height_mm == 2_400
        and item.data.cargo_weight_kg == 2_500
        for item in imported_document.requests
    )
    imported_segment = imported_document.plans[0].cycles[0].segments[0]
    assert imported_segment.routing_profile_snapshot == profile_snapshot
    assert imported_segment.routing_provider == "valhalla"
    assert imported_segment.osm_data_version == "moscow-2026-08-24"
    assert imported_segment.routed_at == routed_at
    assert [item.scheduled_date for item in imported_document.requests].count(selected_date) == 1
    imported_source = next(
        item
        for item in imported_document.requests
        if item.external_id == expected_source_metadata[1]
    )
    assert (
        imported_source.source_system,
        imported_source.external_id,
        imported_source.external_version,
        imported_source.external_payload,
    ) == expected_source_metadata


@pytest.mark.asyncio
async def test_invalid_import_can_be_rolled_back_without_partial_scenario(
    db_session: AsyncSession,
) -> None:
    """A broken cross-reference aborts every row created in the import transaction."""

    settings = Settings()
    source = await scenarios.create_scenario(db_session, ScenarioCreate(name="Source"), settings)
    await scenarios.reset_demo_scenario(db_session, source.id)
    document = await scenarios.export_scenario(db_session, source.id, include_plans=False)
    document.zone_relations[0].data.from_zone_id = uuid4()
    before = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)

    with pytest.raises(ApiError) as failure:
        async with db_session.begin_nested():
            await scenarios.import_scenario(db_session, document, settings)
    assert failure.value.code == "IMPORT_REFERENCE_INVALID"
    after = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)
    assert after == before


@pytest.mark.asyncio
async def test_legacy_document_without_truck_collections_still_imports(
    db_session: AsyncSession,
) -> None:
    """Schema-version-one files created before truck profiles remain importable."""

    settings = Settings()
    source = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Legacy source"), settings
    )
    await scenarios.reset_demo_scenario(db_session, source.id)
    document = await scenarios.export_scenario(db_session, source.id, include_plans=False)
    legacy_payload = document.model_dump(mode="json")
    legacy_payload.pop("trailers")
    legacy_payload.pop("vehicle_load_profiles")
    legacy_vehicle_fields = {
        "name",
        "registration_number",
        "capacity",
        "active",
        "average_speed_city",
        "average_speed_region",
        "notes",
    }
    for item in legacy_payload["vehicles"]:
        item["data"] = {
            key: value
            for key, value in item["data"].items()
            if key in legacy_vehicle_fields
        }
    legacy_document = ScenarioExportDocument.model_validate(legacy_payload)

    imported = await scenarios.import_scenario(
        db_session, legacy_document, settings, name="Legacy imported"
    )
    imported_document = await scenarios.export_scenario(
        db_session, imported.id, include_plans=False
    )

    assert len(imported_document.vehicles) == 3
    assert imported_document.trailers == []
    assert imported_document.vehicle_load_profiles == []
    assert all(item.data.default_trailer_id is None for item in imported_document.vehicles)


@pytest.mark.asyncio
async def test_unknown_default_trailer_rolls_back_all_imported_truck_rows(
    db_session: AsyncSession,
) -> None:
    """A vehicle cannot retain a source or foreign-scenario trailer reference."""

    settings = Settings()
    source = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Invalid truck source"), settings
    )
    await scenarios.reset_demo_scenario(db_session, source.id)
    document = await scenarios.export_scenario(db_session, source.id, include_plans=False)
    document.vehicles[0].data.default_trailer_id = uuid4()
    before_scenarios = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)
    before_trailers = int(await db_session.scalar(select(func.count(Trailer.id))) or 0)
    before_profiles = int(
        await db_session.scalar(select(func.count(VehicleLoadProfile.id))) or 0
    )

    with pytest.raises(ApiError) as failure:
        async with db_session.begin_nested():
            await scenarios.import_scenario(db_session, document, settings)

    assert failure.value.code == "IMPORT_REFERENCE_INVALID"
    assert int(await db_session.scalar(select(func.count(Scenario.id))) or 0) == before_scenarios
    assert int(await db_session.scalar(select(func.count(Trailer.id))) or 0) == before_trailers
    assert (
        int(await db_session.scalar(select(func.count(VehicleLoadProfile.id))) or 0)
        == before_profiles
    )


@pytest.mark.asyncio
async def test_unknown_load_profile_vehicle_rolls_back_import(
    db_session: AsyncSession,
) -> None:
    """An axle profile must point to a vehicle declared by the imported document."""

    settings = Settings()
    source = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Invalid axle profile source"), settings
    )
    await scenarios.reset_demo_scenario(db_session, source.id)
    document = await scenarios.export_scenario(db_session, source.id, include_plans=False)
    document.vehicle_load_profiles[0].vehicle_id = uuid4()
    before_scenarios = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)
    before_profiles = int(
        await db_session.scalar(select(func.count(VehicleLoadProfile.id))) or 0
    )

    with pytest.raises(ApiError) as failure:
        async with db_session.begin_nested():
            await scenarios.import_scenario(db_session, document, settings)

    assert failure.value.code == "IMPORT_REFERENCE_INVALID"
    assert int(await db_session.scalar(select(func.count(Scenario.id))) or 0) == before_scenarios
    assert (
        int(await db_session.scalar(select(func.count(VehicleLoadProfile.id))) or 0)
        == before_profiles
    )
