"""Focused API and adapter tests for private Yandex address geocoding."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.geocoding import YandexGeocodingClient, get_yandex_geocoding_client
from app.config import Settings, get_settings
from app.main import create_app
from tests.auth import admin_access_token_verifier


def _settings(**overrides: object) -> Settings:
    """Build isolated provider settings without reading a developer environment file."""

    values: dict[str, object] = {
        "yandex_geosuggest_api_key": "suggest-secret",
        "yandex_geocoder_api_key": "geocoder-secret",
        "yandex_geocoding_timeout_seconds": 1,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)  # type: ignore[arg-type]


@contextmanager
def _api_client(
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None = None,
) -> Iterator[TestClient]:
    """Run the real API routes with an optional in-memory provider transport."""

    application = create_app(access_token_verifier=admin_access_token_verifier())
    application.dependency_overrides[get_settings] = lambda: settings
    if transport is not None:

        async def override_client() -> Any:
            """Yield and close the transport-backed adapter used by this test."""

            client = YandexGeocodingClient(settings, transport=transport)
            try:
                yield client
            finally:
                await client.aclose()

        application.dependency_overrides[get_yandex_geocoding_client] = override_client
    with TestClient(application) as client:
        yield client


def _geocoder_payload(
    *,
    address: str = "Россия, Санкт-Петербург, Шереметевский сквер",
    position: str = "30.34831 59.67426",
) -> dict[str, object]:
    """Build the minimal real Geocoder nesting accepted by the adapter."""

    return {
        "response": {
            "GeoObjectCollection": {
                "featureMember": [
                    {
                        "GeoObject": {
                            "metaDataProperty": {
                                "GeocoderMetaData": {
                                    "text": "fallback address",
                                    "Address": {"formatted": address},
                                }
                            },
                            "Point": {"pos": position},
                        }
                    }
                ]
            }
        }
    }


def test_suggestions_are_sanitized_and_geographically_biased() -> None:
    """Autocomplete forwards only the fixed Yandex contract and returns UI-safe fields."""

    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "results": [
                    {
                        "title": {"text": "Шереметевский сквер"},
                        "subtitle": {"text": "Санкт-Петербург, Россия"},
                        "address": {
                            "formatted_address": "Россия, Санкт-Петербург, Шереметевский сквер"
                        },
                        "uri": "ymapsbm1://geo?data=example",
                    },
                    {
                        "id": "provider-id",
                        "title": "Шереметевский проспект",
                        "uri": "ymapsbm1://geo?data=other",
                    },
                ]
            },
        )

    with _api_client(_settings(), httpx.MockTransport(handler)) as client:
        response = client.get(
            "/api/geocoding/suggestions",
            params={"text": "  Шереметевский  ", "latitude": 59.93, "longitude": 30.32},
        )

    assert response.status_code == 200
    assert response.json() == [
        {
            "id": "ymapsbm1://geo?data=example",
            "title": "Шереметевский сквер",
            "subtitle": "Санкт-Петербург, Россия",
            "address": "Россия, Санкт-Петербург, Шереметевский сквер",
            "uri": "ymapsbm1://geo?data=example",
        },
        {
            "id": "provider-id",
            "title": "Шереметевский проспект",
            "subtitle": None,
            "address": None,
            "uri": "ymapsbm1://geo?data=other",
        },
    ]
    assert len(captured) == 1
    request = captured[0]
    assert str(request.url.copy_with(query=None)) == "https://suggest-maps.yandex.ru/v1/suggest"
    assert dict(request.url.params) == {
        "apikey": "suggest-secret",
        "text": "Шереметевский",
        "lang": "ru_RU",
        "results": "7",
        "print_address": "1",
        "attrs": "uri",
        "ll": "30.32,59.93",
    }


def test_resolve_and_reverse_return_address_with_provider_coordinates() -> None:
    """Suggestion URIs and clicked map points use the same strict Geocoder parser."""

    captured_lookups: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured_lookups.append(dict(request.url.params))
        assert request.url.params["apikey"] == "geocoder-secret"
        assert request.url.params["format"] == "json"
        assert request.url.params["lang"] == "ru_RU"
        assert request.url.params["results"] == "1"
        return httpx.Response(200, json=_geocoder_payload())

    with _api_client(_settings(), httpx.MockTransport(handler)) as client:
        resolved = client.get(
            "/api/geocoding/resolve",
            params={"uri": "ymapsbm1://geo?data=example&lang=ru"},
        )
        reversed_point = client.get(
            "/api/geocoding/reverse",
            params={"latitude": 59.67426, "longitude": 30.34831},
        )

    expected = {
        "address": "Россия, Санкт-Петербург, Шереметевский сквер",
        "latitude": 59.67426,
        "longitude": 30.34831,
    }
    assert resolved.status_code == 200
    assert resolved.json() == expected
    assert reversed_point.status_code == 200
    assert reversed_point.json() == expected
    assert captured_lookups[0]["uri"] == "ymapsbm1://geo?data=example&lang=ru"
    assert "geocode" not in captured_lookups[0]
    assert captured_lookups[1]["geocode"] == "30.34831,59.67426"
    assert "uri" not in captured_lookups[1]


@pytest.mark.parametrize(
    ("path", "missing_field", "detail"),
    [
        (
            "/api/geocoding/suggestions?text=Москва",
            "yandex_geosuggest_api_key",
            "Geosuggest",
        ),
        (
            "/api/geocoding/resolve?uri=ymapsbm1%3A%2F%2Fgeo%3Fdata%3Dx",
            "yandex_geocoder_api_key",
            "Geocoder",
        ),
    ],
)
def test_missing_provider_key_is_an_explicit_problem(
    path: str,
    missing_field: str,
    detail: str,
) -> None:
    """Absent server credentials fail before any external request is attempted."""

    with _api_client(_settings(**{missing_field: None})) as client:
        response = client.get(path)

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "GEOCODING_NOT_CONFIGURED"
    assert detail in response.json()["detail"]


@pytest.mark.parametrize(
    ("provider_response", "expected_code"),
    [
        (
            httpx.Response(502, json={"message": "upstream failure"}),
            "GEOCODING_PROVIDER_UNAVAILABLE",
        ),
        (httpx.Response(200, content=b"not-json"), "GEOCODING_PROVIDER_INVALID_RESPONSE"),
        (httpx.Response(200, json={"results": {}}), "GEOCODING_PROVIDER_INVALID_RESPONSE"),
    ],
)
def test_suggestion_provider_failures_are_sanitized(
    provider_response: httpx.Response,
    expected_code: str,
) -> None:
    """Provider status and payload failures never leak credentials or raw bodies."""

    transport = httpx.MockTransport(lambda _: provider_response)
    with _api_client(_settings(), transport) as client:
        response = client.get("/api/geocoding/suggestions", params={"text": "Москва"})

    assert response.status_code == 503
    assert response.json()["code"] == expected_code
    assert "secret" not in response.text
    assert "upstream failure" not in response.text


def test_resolve_returns_not_found_for_an_empty_feature_list() -> None:
    """A valid empty Geocoder response is a stable 404 rather than fabricated coordinates."""

    payload = {"response": {"GeoObjectCollection": {"featureMember": []}}}
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json=payload))
    with _api_client(_settings(), transport) as client:
        response = client.get("/api/geocoding/resolve", params={"uri": "ymapsbm1://geo?data=x"})

    assert response.status_code == 404
    assert response.json()["code"] == "ADDRESS_NOT_FOUND"


def test_malformed_geocoder_point_is_rejected() -> None:
    """Coordinates outside the provider contract cannot enter slot-planning state."""

    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, json=_geocoder_payload(position="not-a-point"))
    )
    with _api_client(_settings(), transport) as client:
        response = client.get(
            "/api/geocoding/reverse",
            params={"latitude": 59.9, "longitude": 30.3},
        )

    assert response.status_code == 503
    assert response.json()["code"] == "GEOCODING_PROVIDER_INVALID_RESPONSE"


def test_yandex_settings_have_safe_defaults_and_validation() -> None:
    """Provider URLs are official HTTPS endpoints and request deadlines stay bounded."""

    settings = Settings(_env_file=None)
    assert settings.yandex_geosuggest_url == "https://suggest-maps.yandex.ru/v1/suggest"
    assert settings.yandex_geocoder_url == "https://geocode-maps.yandex.ru/v1"
    assert settings.yandex_geocoding_timeout_seconds == 5

    with pytest.raises(ValidationError, match="absolute HTTPS"):
        _settings(yandex_geocoder_url="http://geocode-maps.yandex.ru/v1")
    with pytest.raises(ValidationError, match="between 0 and 30"):
        _settings(yandex_geocoding_timeout_seconds=31)
