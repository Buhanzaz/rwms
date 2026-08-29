"""FastAPI and Pydantic contract tests that do not require a database."""

import json
from datetime import date, time
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app, openapi_document
from app.schemas.domain import (
    DriverCreate,
    LogisticsRequestCreate,
    PlanningSettings,
    ShiftCreate,
    VehicleCreate,
    WarehouseCreate,
    WorkloadGeneratorInput,
    ZoneCreate,
)
from app.services.catalog import split_quantities
from app.services.planner_runtime import request_is_available_on_date


def test_openapi_exposes_only_warehouse_rooted_product_operations() -> None:
    """Generated OpenAPI contains the active workspace flow and no removed operations."""

    document = openapi_document()
    paths = document["paths"]
    assert "/api/health" in paths
    assert "/api/warehouses" in paths
    assert "/api/warehouses/available" in paths
    assert "/api/warehouses/{warehouse_id}/workspace" in paths
    assert "/api/warehouses/{warehouse_id}/zones" in paths
    assert "/api/warehouses/{warehouse_id}/zones/{zone_id}" in paths
    assert "/api/warehouses/{warehouse_id}/zones/{zone_id}/cutouts" in paths
    assert "/api/warehouses/{warehouse_id}/zones/{zone_id}/lock" in paths
    assert "/api/zones" not in paths
    assert "/api/zones/{zone_id}" not in paths
    assert "/api/warehouses/{warehouse_id}/generate-three-day-test" not in paths
    assert "/api/warehouses/{warehouse_id}/identity/refresh" not in paths
    assert "/api/warehouses/{warehouse_id}/plans/ensure" in paths
    assert "/api/plans/{plan_id}/confirm" in paths
    assert "/api/plans/{plan_id}/manual-changes/reset" in paths
    assert "/api/warehouses/{warehouse_id}/planning-days/{planning_date}/close" in paths
    assert "/api/planning/slot-availability" in paths
    assert "/api/warehouses/{warehouse_id}/plans/generate" not in paths
    assert "get" not in paths["/api/warehouses/{warehouse_id}/drivers"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/shifts"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/requests"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/trailers"]
    assert "get" not in paths["/api/trailers/{trailer_id}"]
    assert "/api/vehicles/{vehicle_id}/load-profiles" not in paths
    assert "/api/vehicle-load-profiles/{profile_id}" not in paths

    workspace = document["components"]["schemas"]["WarehouseWorkspaceRead"]
    assert set(workspace["required"]) == {
        "warehouse",
        "warehouses",
        "zones",
        "drivers",
        "vehicles",
        "trailers",
        "shifts",
        "requests",
    }
    vehicle = document["components"]["schemas"]["VehicleRead"]
    assert "load_profiles" in vehicle["required"]
    profile = document["components"]["schemas"]["VehicleLoadProfileCreate"]
    assert set(profile["properties"]) == {
        "configuration_type",
        "max_actual_axle_load_kg",
    }
    planning_day = document["components"]["schemas"]["PlanningDayStatusRead"]
    assert set(planning_day["required"]) == {
        "warehouse_id",
        "date",
        "accepting_requests",
    }
    with TestClient(create_app()) as client:
        response = client.get("/api/openapi.json")
    assert response.status_code == 200
    assert response.json()["info"]["title"] == "RWMS Logistics Planning"


def test_slot_planning_openapi_exposes_warehouse_and_named_tariff_zone() -> None:
    """Slot availability exposes isochrone pricing and exceptional-zone policy."""

    schemas = openapi_document()["components"]["schemas"]
    request = schemas["SlotAvailabilityRequest"]
    assert set(request["required"]) == {
        "warehouse_id",
        "date",
        "address",
        "latitude",
        "longitude",
        "cabin_count",
        "site_cabin_capacity",
    }
    response = schemas["SlotAvailabilityRead"]
    assert {
        "price_isochrone_minutes",
        "price_zone_id",
        "price_zone_name",
        "trailer_access_allowed",
    } <= set(response["properties"])
    unassigned = schemas["UnassignedTaskRead"]
    nearest = unassigned["properties"]["nearest_option"]
    assert nearest["anyOf"][0]["$ref"].endswith("/NearestOptionRead")


def test_checked_in_openapi_matches_generated_contract() -> None:
    """The checked-in schema cannot silently drift from the FastAPI application."""

    schema_path = Path(__file__).resolve().parents[1] / "openapi.json"
    checked_in = json.loads(schema_path.read_text(encoding="utf-8"))
    assert checked_in == openapi_document()


def test_request_input_forbids_zone_override_and_carries_mandatory() -> None:
    """Classification remains server-owned while mandatory work is request-owned."""

    payload = {
        "type": "DELIVERY",
        "name": "Точка",
        "latitude": 55.75,
        "longitude": 37.61,
        "quantity": 1,
        "mandatory": True,
    }
    assert LogisticsRequestCreate.model_validate(payload).mandatory is True
    with pytest.raises(ValidationError, match="zone_id"):
        LogisticsRequestCreate.model_validate({**payload, "zone_id": str(uuid4())})


def test_zone_contract_types_only_exceptional_delivery_policies() -> None:
    """Polygons represent access restrictions or an explicit special-price override."""

    geometry = {
        "type": "Polygon",
        "coordinates": [[[37, 55], [38, 55], [38, 56], [37, 56], [37, 55]]],
    }
    zone = ZoneCreate(
        name="Тарифная зона",
        color="#A855F7",
        geometry=geometry,
        delivery_price=12_500,
        pickup_price=9_000,
    )
    assert zone.color == "#A855F7"
    assert zone.kind == "SPECIAL_PRICE"
    with pytest.raises(ValidationError):
        ZoneCreate(name="Ошибка", color="purple", geometry=geometry)
    with pytest.raises(ValidationError, match="code"):
        ZoneCreate.model_validate({"name": "Old", "code": "OLD", "geometry": geometry})

    warehouse = WarehouseCreate(external_warehouse_id=uuid4())
    assert warehouse.initial_zone is None
    assert warehouse.isochrone_price_60_minutes == 10_000
    assert warehouse.isochrone_price_120_minutes == 15_000
    assert warehouse.isochrone_price_180_minutes == 20_000
    assert warehouse.isochrone_price_240_minutes == 25_000
    warehouse = WarehouseCreate(external_warehouse_id=uuid4(), initial_zone=zone)
    assert warehouse.initial_zone == zone
    schemas = openapi_document()["components"]["schemas"]
    assert "initial_zone" not in schemas["WarehouseCreate"]["required"]
    assert "initial_zone" not in schemas["WarehouseRead"]["properties"]
    assert "warehouse_id" in schemas["ZoneRead"]["required"]
    assert "kind" in schemas["ZoneRead"]["required"]


def test_monthly_shift_requires_one_bounded_calendar_range() -> None:
    """Runtime shift input contains only inclusive dates and daily local times."""

    valid = ShiftCreate(
        driver_id=uuid4(),
        vehicle_id=uuid4(),
        date_from=date(2026, 8, 1),
        date_to=date(2026, 8, 31),
        start_time=time(8),
        end_time=time(20),
    )
    assert valid.date_to == date(2026, 8, 31)
    with pytest.raises(ValidationError, match="calendar month"):
        ShiftCreate(
            driver_id=uuid4(),
            vehicle_id=uuid4(),
            date_from=date(2026, 8, 31),
            date_to=date(2026, 9, 1),
            start_time=time(8),
            end_time=time(20),
        )
    with pytest.raises(ValidationError, match="date"):
        ShiftCreate.model_validate(
            {
                "driver_id": str(uuid4()),
                "vehicle_id": str(uuid4()),
                "date": "2026-08-30",
                "start_at": "2026-08-30T08:00:00+03:00",
                "end_at": "2026-08-30T20:00:00+03:00",
            }
        )


def test_driver_audience_mode_never_invents_a_worker_identity() -> None:
    """Exact and shared RWMS audiences have mutually exclusive worker identity rules."""

    worker_id = uuid4()
    exact = DriverCreate(
        rwms_assignment_mode="ASSIGNED_DRIVER",
        external_worker_id=worker_id,
    )
    shared = DriverCreate(rwms_assignment_mode="WAREHOUSE_DRIVERS")
    assert exact.external_worker_id == worker_id
    assert shared.external_worker_id is None
    with pytest.raises(ValidationError, match="name"):
        DriverCreate.model_validate(
            {"rwms_assignment_mode": "WAREHOUSE_DRIVERS", "name": "Browser label"}
        )
    with pytest.raises(ValidationError, match="requires external_worker_id"):
        DriverCreate(rwms_assignment_mode="ASSIGNED_DRIVER")
    with pytest.raises(ValidationError, match="to be null"):
        DriverCreate(
            rwms_assignment_mode="WAREHOUSE_DRIVERS",
            external_worker_id=worker_id,
        )


def test_plain_compose_environment_aliases_are_supported(monkeypatch: pytest.MonkeyPatch) -> None:
    """Root runtime variable names bind without a second application prefix."""

    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@db:5432/test")
    monkeypatch.setenv("ROUTING_PROVIDER", "mock")
    monkeypatch.setenv("DEFAULT_WAREHOUSE_TIMEZONE", "Asia/Yekaterinburg")
    monkeypatch.setenv("PLANNER_DEFAULT_SEED", "42")
    settings = Settings()
    assert settings.database_url.endswith("/test")
    assert settings.routing_provider == "mock"
    assert settings.default_warehouse_timezone == "Asia/Yekaterinburg"
    assert settings.planner_default_seed == 42


def test_workload_generator_contract_keeps_alternatives_inside_horizon() -> None:
    """Generated alternatives always fit beside the primary planning date."""

    payload = WorkloadGeneratorInput(
        start_date=date(2026, 8, 24),
        days=4,
        deliveries_per_day=2,
        pickups_per_day=1,
        alternative_dates_count=3,
        seed=42,
    )
    assert payload.alternative_dates_count == 3
    with pytest.raises(ValidationError, match="smaller than days"):
        WorkloadGeneratorInput(
            start_date=date(2026, 8, 24),
            days=2,
            deliveries_per_day=0,
            pickups_per_day=1,
            alternative_dates_count=2,
            seed=42,
        )


@pytest.mark.parametrize(
    ("quantity", "expected"),
    [(1, [1]), (2, [2]), (3, [2, 1]), (4, [2, 2]), (5, [2, 2, 1])],
)
def test_request_quantity_split(quantity: int, expected: list[int]) -> None:
    """Request quantities split into stable transport parts capped at two."""

    assert split_quantities(quantity) == expected


def test_explicit_request_date_overrides_other_accepted_options() -> None:
    """An assigned request belongs to one day; an unassigned one remains negotiable."""

    first = date(2026, 8, 25)
    second = date(2026, 8, 26)
    assert request_is_available_on_date(None, [first, second], first)
    assert request_is_available_on_date(first, [first, second], first)
    assert not request_is_available_on_date(first, [first, second], second)


@pytest.mark.parametrize("capacity", [0, 3, 10])
def test_capacity_above_supported_limit_is_rejected(capacity: int) -> None:
    """Global settings and a vehicle support one or two cabins only."""

    with pytest.raises(ValidationError):
        PlanningSettings(vehicle_capacity=capacity)
    with pytest.raises(ValidationError):
        VehicleCreate(name="Oversized", registration_number="TEST", capacity=capacity)
