"""FastAPI and Pydantic contract tests that do not require a database."""

from datetime import datetime

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app, openapi_document
from app.schemas.domain import (
    DriverUnavailableRequest,
    LogisticsRequestCreate,
    ScenarioSettings,
    ShiftCreate,
    SimulationDelayRequest,
    VehicleCreate,
)
from app.services.catalog import split_quantities


def test_openapi_is_served_under_api_and_contains_core_operations() -> None:
    """Generated OpenAPI exposes the stable paths consumed by frontend generation."""

    document = openapi_document()
    paths = document["paths"]
    assert "/api/health" in paths
    assert "/api/scenarios" in paths
    assert "/api/scenarios/{scenario_id}/requests" in paths
    assert "/api/scenarios/{scenario_id}/plans/generate" in paths
    assert "/api/optimization-runs/{run_id}/stream" in paths
    assert "/api/plans/{plan_id}/simulation/delay" in paths
    assert "/api/plans/{plan_id}/simulation/driver-unavailable" in paths
    with TestClient(create_app()) as client:
        response = client.get("/api/openapi.json")
    assert response.status_code == 200
    assert response.json()["info"]["title"] == "RWMS Logistics Simulator"


def test_unwired_planner_returns_honest_problem_details() -> None:
    """A missing engine is explicit and never fabricates a successful run."""

    with TestClient(create_app()) as client:
        response = client.post(
            "/api/scenarios/00000000-0000-0000-0000-000000000001/plans/generate",
            json={"date": "2026-08-25"},
        )
    assert response.status_code == 503
    assert response.json()["code"] == "PLANNER_NOT_CONFIGURED"


def test_request_create_forbids_client_zone_override() -> None:
    """The request input contract has no zone override escape hatch."""

    with pytest.raises(ValidationError, match="zone_id"):
        LogisticsRequestCreate.model_validate(
            {
                "type": "DELIVERY",
                "name": "Точка",
                "latitude": 55.75,
                "longitude": 37.61,
                "quantity": 1,
                "zone_id": "00000000-0000-0000-0000-000000000001",
            }
        )


def test_shift_rejects_naive_datetimes() -> None:
    """Every absolute shift timestamp must carry an explicit UTC offset."""

    with pytest.raises(ValidationError, match="timezone"):
        ShiftCreate(
            driver_id="00000000-0000-0000-0000-000000000001",
            vehicle_id="00000000-0000-0000-0000-000000000002",
            date=datetime(2026, 8, 25).date(),
            start_at=datetime(2026, 8, 25, 8),
            end_at=datetime(2026, 8, 25, 20),
        )


def test_plain_compose_environment_aliases_are_supported(monkeypatch: pytest.MonkeyPatch) -> None:
    """Root Compose variable names bind without requiring a second prefix."""

    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@db:5432/test")
    monkeypatch.setenv("ROUTING_PROVIDER", "mock")
    monkeypatch.setenv("OSRM_BASE_URL", "http://osrm:5000")
    monkeypatch.setenv("OSRM_PROFILE", "driving")
    monkeypatch.setenv("OSRM_TIMEOUT_SECONDS", "12")
    monkeypatch.setenv("DEFAULT_SCENARIO_TIMEZONE", "Asia/Yekaterinburg")
    monkeypatch.setenv("PLANNER_DEFAULT_SEED", "42")
    settings = Settings()
    assert settings.database_url.endswith("/test")
    assert settings.routing_provider == "mock"
    assert settings.osrm_base_url == "http://osrm:5000"
    assert settings.osrm_profile == "driving"
    assert settings.osrm_timeout_seconds == 12
    assert settings.default_scenario_timezone == "Asia/Yekaterinburg"
    assert settings.planner_default_seed == 42


@pytest.mark.parametrize(
    ("quantity", "expected"),
    [(1, [1]), (2, [2]), (3, [2, 1]), (4, [2, 2]), (5, [2, 2, 1])],
)
def test_request_quantity_split(quantity: int, expected: list[int]) -> None:
    """Request quantities split into stable transport parts capped at two."""

    assert split_quantities(quantity) == expected


@pytest.mark.parametrize("capacity", [0, 3, 10])
def test_capacity_above_mvp_limit_is_rejected(capacity: int) -> None:
    """Settings are fixed at two and an individual test vehicle may only use one or two."""

    with pytest.raises(ValidationError):
        ScenarioSettings(vehicle_capacity=capacity)
    with pytest.raises(ValidationError):
        VehicleCreate(
            name="Oversized",
            registration_number="TEST",
            capacity=capacity,
        )


def test_simulation_override_contracts_require_aware_time_and_positive_delay() -> None:
    """Simulation commands cannot introduce ambiguous timestamps or negative time travel."""

    common = {
        "expected_version": 1,
        "driver_shift_id": "00000000-0000-0000-0000-000000000001",
        "effective_at": "2026-08-25T10:00:00+03:00",
        "reason": "Пробка",
        "persist": False,
    }
    assert SimulationDelayRequest(**common, delay_minutes=20).delay_minutes == 20
    assert DriverUnavailableRequest(**common).effective_at.utcoffset() is not None

    with pytest.raises(ValidationError):
        SimulationDelayRequest(**common, delay_minutes=0)
    with pytest.raises(ValidationError):
        DriverUnavailableRequest(**{**common, "effective_at": "2026-08-25T10:00:00"})
