"""Server-side Yandex geocoding boundary used by the logistics operator UI."""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from math import isfinite
from typing import Annotated, Any

import httpx
from fastapi import APIRouter, Depends, Query

from app.api.dependencies import SettingsDep
from app.config import Settings
from app.errors import ApiError
from app.schemas.geocoding import (
    AddressSuggestion,
    GeocodingResolveQuery,
    GeocodingSuggestionsQuery,
    ResolvedAddress,
    ReverseGeocodingQuery,
)

router = APIRouter(prefix="/geocoding", tags=["geocoding"])


class YandexGeocodingClient:
    """Private credential-bearing adapter for Yandex Suggest and Geocoder HTTP APIs."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            timeout=settings.yandex_geocoding_timeout_seconds,
            transport=transport,
        )

    async def aclose(self) -> None:
        """Release the adapter's connection pool."""

        await self._client.aclose()

    async def suggest(self, query: GeocodingSuggestionsQuery) -> list[AddressSuggestion]:
        """Return strict, UI-safe suggestions for normalized address text."""

        api_key = self._require_key(
            self._settings.yandex_geosuggest_api_key,
            "Yandex Geosuggest is not configured.",
        )
        params: dict[str, str | int] = {
            "apikey": api_key,
            "text": query.text,
            "lang": "ru_RU",
            "results": 7,
            "print_address": 1,
            "attrs": "uri",
        }
        if query.latitude is not None and query.longitude is not None:
            params["ll"] = f"{query.longitude},{query.latitude}"
        payload = await self._request_json(self._settings.yandex_geosuggest_url, params)
        results = payload.get("results")
        if not isinstance(results, list):
            raise self._invalid_response("Yandex Geosuggest returned an invalid result set.")

        suggestions: list[AddressSuggestion] = []
        for result in results:
            if not isinstance(result, Mapping):
                raise self._invalid_response("Yandex Geosuggest returned an invalid suggestion.")
            title = self._nested_text(result.get("title"))
            subtitle = self._nested_text(result.get("subtitle"))
            uri = self._non_blank_text(result.get("uri"))
            provider_id = self._non_blank_text(result.get("id")) or uri
            address = self._formatted_suggestion_address(result.get("address"))
            if title is None or uri is None or provider_id is None:
                raise self._invalid_response("Yandex Geosuggest returned an incomplete suggestion.")
            suggestions.append(
                AddressSuggestion(
                    id=provider_id,
                    title=title,
                    subtitle=subtitle,
                    address=address,
                    uri=uri,
                )
            )
        return suggestions

    async def resolve(self, uri: str) -> ResolvedAddress:
        """Resolve one opaque suggestion URI to a formatted address and point."""

        return await self._geocode(uri=uri)

    async def forward(self, address: str) -> ResolvedAddress:
        """Resolve canonical free-form address text for server-owned warehouse binding."""

        normalized = address.strip()
        if not normalized:
            raise ApiError(422, "WAREHOUSE_ADDRESS_REQUIRED", "RWMS warehouse address is missing")
        return await self._geocode(geocode=normalized)

    async def reverse(self, query: ReverseGeocodingQuery) -> ResolvedAddress:
        """Resolve one map point to a formatted address when Yandex knows it."""

        return await self._geocode(geocode=f"{query.longitude},{query.latitude}")

    async def _geocode(
        self,
        *,
        geocode: str | None = None,
        uri: str | None = None,
    ) -> ResolvedAddress:
        """Execute and validate one forward or reverse Geocoder request."""

        api_key = self._require_key(
            self._settings.yandex_geocoder_api_key,
            "Yandex Geocoder is not configured.",
        )
        if (geocode is None) == (uri is None):
            raise ValueError("Exactly one Geocoder lookup parameter is required")
        if uri is not None:
            lookup = {"uri": uri}
        else:
            assert geocode is not None
            lookup = {"geocode": geocode}
        payload = await self._request_json(
            self._settings.yandex_geocoder_url,
            {
                "apikey": api_key,
                "format": "json",
                "lang": "ru_RU",
                "results": 1,
                **lookup,
            },
        )
        response = payload.get("response")
        if not isinstance(response, Mapping):
            raise self._invalid_response("Yandex Geocoder returned an invalid response.")
        collection = response.get("GeoObjectCollection")
        if not isinstance(collection, Mapping):
            raise self._invalid_response("Yandex Geocoder returned an invalid collection.")
        members = collection.get("featureMember")
        if not isinstance(members, list):
            raise self._invalid_response("Yandex Geocoder returned an invalid feature list.")
        if not members:
            raise ApiError(404, "ADDRESS_NOT_FOUND", "Yandex Geocoder found no matching address.")

        member = members[0]
        if not isinstance(member, Mapping):
            raise self._invalid_response("Yandex Geocoder returned an invalid feature.")
        geo_object = member.get("GeoObject")
        if not isinstance(geo_object, Mapping):
            raise self._invalid_response("Yandex Geocoder returned an invalid GeoObject.")
        address = self._geocoder_address(geo_object)
        latitude, longitude = self._geocoder_coordinates(geo_object)
        if address is None:
            raise self._invalid_response("Yandex Geocoder returned an address without text.")
        return ResolvedAddress(
            address=address,
            latitude=latitude,
            longitude=longitude,
        )

    async def _request_json(
        self,
        url: str,
        params: Mapping[str, str | int],
    ) -> Mapping[str, Any]:
        """Fetch one bounded provider response and map transport failures to Problem Details."""

        try:
            response = await self._client.get(url, params=params)
        except httpx.HTTPError as exc:
            raise ApiError(
                503,
                "GEOCODING_PROVIDER_UNAVAILABLE",
                "Yandex geocoding provider is unavailable.",
            ) from exc
        if not response.is_success:
            raise ApiError(
                503,
                "GEOCODING_PROVIDER_UNAVAILABLE",
                "Yandex geocoding provider rejected the request.",
            )
        try:
            payload = response.json()
        except ValueError as exc:
            raise self._invalid_response(
                "Yandex geocoding provider returned invalid JSON."
            ) from exc
        if not isinstance(payload, Mapping):
            raise self._invalid_response("Yandex geocoding provider returned an invalid payload.")
        return payload

    @staticmethod
    def _require_key(value: str | None, detail: str) -> str:
        """Reject missing provider credentials before making an external request."""

        if value is None:
            raise ApiError(503, "GEOCODING_NOT_CONFIGURED", detail)
        return value

    @staticmethod
    def _invalid_response(detail: str) -> ApiError:
        """Create a sanitized failure for an unusable provider response."""

        return ApiError(503, "GEOCODING_PROVIDER_INVALID_RESPONSE", detail)

    @classmethod
    def _nested_text(cls, value: object) -> str | None:
        """Read either a direct string or the Suggest API's nested text object."""

        if isinstance(value, Mapping):
            return cls._non_blank_text(value.get("text"))
        return cls._non_blank_text(value)

    @classmethod
    def _formatted_suggestion_address(cls, value: object) -> str | None:
        """Extract the optional printable address from one Suggest result."""

        if not isinstance(value, Mapping):
            return None
        return cls._non_blank_text(
            value.get("formatted_address") or value.get("formattedAddress")
        )

    @classmethod
    def _geocoder_address(cls, geo_object: Mapping[str, Any]) -> str | None:
        """Extract the canonical formatted/text address from a Geocoder GeoObject."""

        metadata_property = geo_object.get("metaDataProperty")
        if not isinstance(metadata_property, Mapping):
            return None
        metadata = metadata_property.get("GeocoderMetaData")
        if not isinstance(metadata, Mapping):
            return None
        address = metadata.get("Address")
        formatted = address.get("formatted") if isinstance(address, Mapping) else None
        return cls._non_blank_text(formatted) or cls._non_blank_text(metadata.get("text"))

    @classmethod
    def _geocoder_coordinates(cls, geo_object: Mapping[str, Any]) -> tuple[float, float]:
        """Parse and validate the Geocoder's longitude-first Point.pos value."""

        point = geo_object.get("Point")
        position = point.get("pos") if isinstance(point, Mapping) else None
        if not isinstance(position, str):
            raise cls._invalid_response("Yandex Geocoder returned a point without coordinates.")
        parts = position.split()
        if len(parts) != 2:
            raise cls._invalid_response("Yandex Geocoder returned malformed coordinates.")
        try:
            longitude, latitude = (float(part) for part in parts)
        except ValueError as exc:
            raise cls._invalid_response("Yandex Geocoder returned malformed coordinates.") from exc
        if (
            not isfinite(latitude)
            or not isfinite(longitude)
            or not -90 <= latitude <= 90
            or not -180 <= longitude <= 180
        ):
            raise cls._invalid_response("Yandex Geocoder returned out-of-range coordinates.")
        return latitude, longitude

    @staticmethod
    def _non_blank_text(value: object) -> str | None:
        """Normalize provider strings without coercing non-string JSON values."""

        if not isinstance(value, str):
            return None
        normalized = value.strip()
        return normalized or None


async def get_yandex_geocoding_client(
    settings: SettingsDep,
) -> AsyncIterator[YandexGeocodingClient]:
    """Yield a request-scoped adapter and always close its outbound connection pool."""

    client = YandexGeocodingClient(settings)
    try:
        yield client
    finally:
        await client.aclose()


GeocodingClientDep = Annotated[YandexGeocodingClient, Depends(get_yandex_geocoding_client)]


@router.get("/suggestions", response_model=list[AddressSuggestion])
async def suggest_addresses(
    query: Annotated[GeocodingSuggestionsQuery, Query()],
    client: GeocodingClientDep,
) -> list[AddressSuggestion]:
    """Return address autocomplete results without exposing the provider credential."""

    return await client.suggest(query)


@router.get("/resolve", response_model=ResolvedAddress)
async def resolve_address(
    query: Annotated[GeocodingResolveQuery, Query()],
    client: GeocodingClientDep,
) -> ResolvedAddress:
    """Resolve an autocomplete result to the canonical address and map point."""

    return await client.resolve(query.uri)


@router.get("/reverse", response_model=ResolvedAddress)
async def reverse_geocode(
    query: Annotated[ReverseGeocodingQuery, Query()],
    client: GeocodingClientDep,
) -> ResolvedAddress:
    """Return the address known for a point placed directly on the map."""

    return await client.reverse(query)
