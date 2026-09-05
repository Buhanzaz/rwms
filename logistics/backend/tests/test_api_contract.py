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
    ContractorDispatchCreate,
    DriverCreate,
    LogisticsRequestCreate,
    PlanningSettings,
    RwmsAppliedAssignment,
    RwmsPlanningCapacityShift,
    ShiftCreate,
    VehicleCreate,
    WarehouseCreate,
    WorkloadGeneratorInput,
)
from app.services.catalog import split_quantities
from app.services.planner_runtime import request_is_available_on_date


def test_breakdown_contract_exposes_explicit_recovery_mode_and_trailer_identity() -> None:
    """The checked-in generated consumer boundary keeps manual mode backward compatible."""

    schemas = openapi_document()["components"]["schemas"]
    incident = schemas["LogisticsEventCreate"]
    assert incident["properties"]["recovery_mode"]["default"] == "MANUAL"
    assert incident["properties"]["recovery_mode"]["enum"] == ["MANUAL", "AUTO"]
    assert "recovery_mode" not in incident["required"]
    assert "trailer_id" in incident["properties"]
    assert "TRAILER_BREAKDOWN" in schemas["LogisticsEventType"]["enum"]


def test_openapi_exposes_only_warehouse_rooted_product_operations() -> None:
    """Generated OpenAPI contains the active workspace flow and no removed operations."""

    document = openapi_document()
    paths = document["paths"]
    assert "/api/health" in paths
    assert "/api/warehouses" in paths
    assert "/api/warehouses/available" in paths
    assert "/api/warehouses/{warehouse_id}/workspace" in paths
    assert "/api/warehouses/{warehouse_id}/zones" not in paths
    assert "/api/zones" not in paths
    assert "/api/zones/{zone_id}" not in paths
    assert "/api/warehouses/{warehouse_id}/generate-three-day-test" not in paths
    assert "/api/warehouses/{warehouse_id}/identity/refresh" not in paths
    assert "/api/warehouses/{warehouse_id}/plans/ensure" in paths
    assert "/api/plans/{plan_id}/confirm" in paths
    assert "/api/plans/{plan_id}/manual-changes/reset" in paths
    assert "/api/warehouses/{warehouse_id}/planning-days/{planning_date}/close" in paths
    assert "/api/requests/{request_id}/contractor-assignment" in paths
    assert "/api/warehouses/{warehouse_id}/contractor-dispatches" in paths
    assert "/api/planning/slot-availability" in paths
    assert "/api/warehouses/{warehouse_id}/plans/generate" not in paths
    assert document["components"]["securitySchemes"] == {
        "HTTPBearer": {"type": "http", "scheme": "bearer"}
    }
    assert "security" not in paths["/api/health"]["get"]
    assert paths["/api/warehouses"]["get"]["security"] == [{"HTTPBearer": []}]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/drivers"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/shifts"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/requests"]
    assert "get" not in paths["/api/warehouses/{warehouse_id}/trailers"]
    assert "get" not in paths["/api/trailers/{trailer_id}"]
    assert "/api/vehicles/{vehicle_id}/load-profiles" not in paths
    assert "/api/vehicle-load-profiles/{profile_id}" not in paths

    workspace = document["components"]["schemas"]["WarehouseWorkspaceRead"]
    assert "planning_root_warehouse_id" in workspace["properties"]
    assert "planning_group_warehouse_ids" in workspace["properties"]
    assert set(workspace["required"]) == {
        "warehouse",
        "planning_date",
        "planning_root_warehouse_id",
        "planning_group_warehouse_ids",
        "warehouses",
        "drivers",
        "vehicles",
        "trailers",
        "shifts",
        "requests",
        "request_total",
    }
    workspace_parameters = {
        parameter["name"]: parameter
        for parameter in paths["/api/warehouses/{warehouse_id}/workspace"]["get"][
            "parameters"
        ]
    }
    assert {"planning_date", "request_limit", "request_cursor"} <= workspace_parameters.keys()
    assert "refresh_rwms" not in workspace_parameters
    assert workspace_parameters["request_limit"]["schema"]["default"] == 250
    assert workspace_parameters["request_limit"]["schema"]["maximum"] == 1000
    trailer_create_parameters = paths["/api/warehouses/{warehouse_id}/trailers"][
        "post"
    ]["parameters"]
    assert any(
        parameter["name"] == "Idempotency-Key"
        and parameter["in"] == "header"
        and parameter["required"] is True
        for parameter in trailer_create_parameters
    )
    trailer_delete_parameters = paths["/api/trailers/{trailer_id}"]["delete"][
        "parameters"
    ]
    assert any(
        parameter["name"] == "expected_version"
        and parameter["in"] == "query"
        and parameter["required"] is True
        for parameter in trailer_delete_parameters
    )
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
    request = document["components"]["schemas"]["LogisticsRequestRead"]
    assert "delivery_price_rubles" in request["properties"]
    assert "price_isochrone_minutes" in request["properties"]
    assert "assignment_type" in request["properties"]
    assert request["properties"]["customer_delivery_purpose"]["anyOf"][0]["$ref"].endswith(
        "/CustomerDeliveryPurpose"
    )
    assert {
        "delivery_price_rubles",
        "price_isochrone_minutes",
        "assignment_type",
        "assigned_contractor_worker_id",
        "assigned_contractor_name",
        "assigned_contractor_phone",
        "assigned_at",
        "assigned_by",
        "contractor_handoff_command_id",
        "contractor_handoff_sequence",
        "external_task_ids",
        "customer_delivery_purpose",
    }.issubset(request["required"])
    contractor = document["components"]["schemas"]["ContractorAssignmentCreate"]
    assert set(contractor["required"]) == {"contractor_worker_id"}
    dispatch = document["components"]["schemas"]["ContractorDispatchCreate"]
    assert set(dispatch["required"]) == {
        "contractor_worker_id",
        "planning_date",
        "mode",
    }
    assert "request_ids" in dispatch["properties"]
    dispatch_read = document["components"]["schemas"]["ContractorDispatchRead"]
    assert {
        "contractor_handoff_command_id",
        "external_task_ids",
    }.issubset(dispatch_read["required"])
    manual_change = document["components"]["schemas"]["ManualChangeRequest"]
    assert "changed_by" not in manual_change["properties"]
    confirm_plan = document["components"]["schemas"]["ConfirmPlanRequest"]
    assert "confirmed_by" not in confirm_plan["properties"]
    with TestClient(create_app()) as client:
        response = client.get("/api/openapi.json")
    assert response.status_code == 200
    assert response.json()["info"]["title"] == "RWMS Logistics Planning"


def test_contractor_dispatch_contract_uses_header_date_and_server_owned_selection() -> None:
    """AUTO rejects browser-selected rows while MANUAL requires unique request ids."""

    contractor_id = uuid4()
    request_id = uuid4()
    automatic = ContractorDispatchCreate(
        contractor_worker_id=contractor_id,
        planning_date=date(2026, 8, 31),
        mode="AUTO",
    )
    assert automatic.request_ids == []
    with pytest.raises(ValidationError, match="must not contain"):
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=date(2026, 8, 31),
            mode="AUTO",
            request_ids=[request_id],
        )
    with pytest.raises(ValidationError, match="requires request_ids"):
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=date(2026, 8, 31),
            mode="MANUAL",
        )
    with pytest.raises(ValidationError, match="must be unique"):
        ContractorDispatchCreate(
            contractor_worker_id=contractor_id,
            planning_date=date(2026, 8, 31),
            mode="MANUAL",
            request_ids=[request_id, request_id],
        )


def test_rwms_applied_assignment_requires_exact_external_task_identity() -> None:
    """The strict upstream model rejects a success that omits its canonical task UUID."""

    task_id = uuid4()
    result = RwmsAppliedAssignment.model_validate(
        {
            "orderId": str(uuid4()),
            "orderVersion": 11,
            "documentId": str(uuid4()),
            "externalTaskId": str(task_id),
            "taskVersion": 7,
            "replayed": False,
        }
    )
    assert result.external_task_id == task_id
    assert result.task_version == 7
    with pytest.raises(ValidationError, match="externalTaskId"):
        RwmsAppliedAssignment.model_validate(
            {
                "orderId": str(uuid4()),
                "orderVersion": 11,
                "documentId": str(uuid4()),
                "taskVersion": 7,
                "replayed": False,
            }
        )


def test_contractor_task_identity_migration_extends_current_alembic_head() -> None:
    """Migration 0030 adds exact task IDs and backfills confirmed command ordering."""

    migration = (
        Path(__file__).parents[1]
        / "migrations/versions/20260901_0030_contractor_route_task_identities.py"
    ).read_text(encoding="utf-8")
    assert 'revision: str = "20260901_0030"' in migration
    assert 'down_revision: str | None = "20260831_0029"' in migration
    assert '"contractor_external_task_ids"' in migration
    assert '"contractor_handoff_sequence"' in migration
    assert "jsonb_array_elements_text(command.request_ids)" in migration
    assert "command.status = 'SUCCEEDED'" in migration
    assert "contractor_handoff_sequence >= 0" in migration
    assert "nullable=False" in migration
    assert "'[]'::jsonb" in migration


def test_slot_planning_openapi_exposes_dynamic_isochrone_tariff() -> None:
    """Slot availability exposes the selected configured hourly price tier."""

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
    assert "price_isochrone_minutes" in response["properties"]
    assert "price_zone_id" not in response["properties"]
    assert "price_zone_name" not in response["properties"]
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


def test_warehouse_contract_uses_contiguous_dynamic_isochrone_tariffs() -> None:
    """Warehouse tariffs default safely and reject gaps or more than twelve hours."""

    warehouse = WarehouseCreate(external_warehouse_id=uuid4())
    assert [item.model_dump() for item in warehouse.isochrone_tariffs] == [
        {"travel_minutes": 60, "price_rubles": 10_000},
        {"travel_minutes": 120, "price_rubles": 15_000},
        {"travel_minutes": 180, "price_rubles": 20_000},
        {"travel_minutes": 240, "price_rubles": 25_000},
    ]
    with pytest.raises(ValidationError, match="contiguous hourly"):
        WarehouseCreate.model_validate(
            {
                "external_warehouse_id": str(uuid4()),
                "isochrone_tariffs": [
                    {"travel_minutes": 60, "price_rubles": 1},
                    {"travel_minutes": 180, "price_rubles": 2},
                ],
            }
        )
    schemas = openapi_document()["components"]["schemas"]
    assert "isochrone_tariffs" in schemas["WarehouseCreate"]["properties"]
    assert "isochrone_tariffs" in schemas["WarehouseRead"]["required"]
    assert "ZoneRead" not in schemas


def test_shift_accepts_bounded_cross_month_and_overnight_ranges() -> None:
    """Runtime shifts span up to 31 start dates and only earlier ends cross midnight."""

    valid = ShiftCreate(
        driver_id=uuid4(),
        vehicle_id=uuid4(),
        date_from=date(2026, 8, 1),
        date_to=date(2026, 8, 31),
        start_time=time(8),
        end_time=time(20),
    )
    assert valid.date_to == date(2026, 8, 31)
    cross_month = ShiftCreate(
        driver_id=uuid4(),
        vehicle_id=uuid4(),
        date_from=date(2026, 8, 31),
        date_to=date(2026, 9, 1),
        start_time=time(22),
        end_time=time(6),
    )
    assert cross_month.end_time < cross_month.start_time
    with pytest.raises(ValidationError, match="31 inclusive days"):
        ShiftCreate(
            driver_id=uuid4(),
            vehicle_id=uuid4(),
            date_from=date(2026, 8, 31),
            date_to=date(2026, 10, 1),
            start_time=time(8),
            end_time=time(20),
        )
    with pytest.raises(ValidationError, match="non-zero shift"):
        ShiftCreate(
            driver_id=uuid4(),
            vehicle_id=uuid4(),
            date_from=date(2026, 8, 31),
            date_to=date(2026, 8, 31),
            start_time=time(8),
            end_time=time(8),
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
    with pytest.raises(ValidationError, match="shorter than the shift duration"):
        ShiftCreate(
            driver_id=uuid4(),
            vehicle_id=uuid4(),
            date_from=date(2026, 8, 31),
            date_to=date(2026, 8, 31),
            start_time=time(8),
            end_time=time(8, 30),
            break_minutes=30,
        )


def test_capacity_shift_reuses_the_positive_usable_interval_invariant() -> None:
    """Producer rejects a snapshot that the canonical capacity owner cannot consume."""

    with pytest.raises(ValidationError, match="shorter than the shift duration"):
        RwmsPlanningCapacityShift(
            sourceShiftId=uuid4(),
            deliveryDate=date(2026, 8, 31),
            shiftStart=time(8),
            shiftEnd=time(8, 30),
            breakMinutes=30,
            cabinCapacity=1,
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

    for name in (
        "LOGISTICS_DATABASE_URL",
        "LOGISTICS_ROUTING_PROVIDER",
        "LOGISTICS_DEFAULT_WAREHOUSE_TIMEZONE",
        "LOGISTICS_PLANNER_DEFAULT_SEED",
    ):
        monkeypatch.delenv(name, raising=False)
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
    )
    assert payload.alternative_dates_count == 3
    with pytest.raises(ValidationError, match="smaller than days"):
        WorkloadGeneratorInput(
            start_date=date(2026, 8, 24),
            days=2,
            deliveries_per_day=0,
            pickups_per_day=1,
            alternative_dates_count=2,
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
