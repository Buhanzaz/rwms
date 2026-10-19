"""Validation tests for selecting the private Valhalla truck provider."""

from __future__ import annotations

from math import inf, nan

import pytest
from pydantic import ValidationError

from app.config import Settings


def test_valhalla_settings_accept_explicit_enabled_provider() -> None:
    """The selected provider retains a normalized URL, deadline, and data identity."""

    settings = Settings(
        _env_file=None,
        routing_provider=" VALHALLA ",
        valhalla_enabled=True,
        valhalla_url="http://valhalla:8002/",
        valhalla_timeout_seconds=45,
        osm_data_version="central-2026-08-22",
    )

    assert settings.routing_provider == "valhalla"
    assert settings.valhalla_enabled is True
    assert settings.valhalla_url == "http://valhalla:8002"
    assert settings.valhalla_timeout_seconds == 45
    assert settings.osm_data_version == "central-2026-08-22"


def test_valhalla_provider_cannot_be_selected_while_disabled() -> None:
    """Misconfiguration fails during startup instead of choosing another provider."""

    with pytest.raises(ValidationError, match="VALHALLA_ENABLED=true"):
        Settings(
            _env_file=None,
            routing_provider="valhalla",
            valhalla_enabled=False,
        )


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("valhalla_url", "valhalla:8002", "absolute http"),
        ("valhalla_url", "http://valhalla:8002?unsafe=1", "query or fragment"),
        ("valhalla_timeout_seconds", -0.1, "non-negative"),
        ("valhalla_timeout_seconds", inf, "non-negative"),
        ("valhalla_timeout_seconds", nan, "non-negative"),
        ("osm_data_version", " ", "non-blank"),
        ("osm_data_version", "central 2026-08-22", "contain no spaces"),
    ],
)
def test_invalid_valhalla_settings_are_rejected(
    field: str,
    value: object,
    message: str,
) -> None:
    """URLs, timeout, and tileset identity remain bounded and log-safe."""

    values: dict[str, object] = {"routing_provider": "mock", field: value}
    with pytest.raises(ValidationError, match=message):
        Settings.model_validate(values)


def test_zero_valhalla_deadline_is_explicitly_bounded() -> None:
    """A zero deadline is permitted by the non-negative configuration contract."""

    settings = Settings(
        _env_file=None,
        routing_provider="mock",
        valhalla_timeout_seconds=0,
    )

    assert settings.valhalla_timeout_seconds == 0


def test_mock_and_osrm_remain_explicit_backward_compatible_choices() -> None:
    """Existing development providers remain selectable without enabling Valhalla."""

    assert Settings(_env_file=None, routing_provider="mock").routing_provider == "mock"
    assert Settings(_env_file=None, routing_provider="osrm").routing_provider == "osrm"


def test_unknown_provider_never_falls_back_to_car_routing() -> None:
    """An unsupported provider name is rejected instead of silently selecting OSRM."""

    with pytest.raises(ValidationError, match=r"mock.*osrm.*valhalla"):
        Settings(_env_file=None, routing_provider="car")
