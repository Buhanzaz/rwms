"""Isolated administrator writes and read-only authenticated map configuration."""

from typing import cast

from fastapi import APIRouter, Response

from app.api.dependencies import SessionDep
from app.models.map_settings import MapDisplaySettings
from app.schemas.map_settings import (
    AdminMapSettingsRead,
    MapProvider,
    MapSettingsRead,
    MapSettingsUpdate,
)
from app.services.map_settings import read_map_settings, replace_map_settings

admin_router = APIRouter(tags=["admin map settings"])
router = APIRouter(tags=["map settings"])


def _admin_read(settings: MapDisplaySettings) -> AdminMapSettingsRead:
    return AdminMapSettingsRead(
        version=settings.version,
        provider=cast(MapProvider, settings.provider),
        yandex_api_key_configured=bool(settings.yandex_api_key),
    )


@admin_router.get(
    "/map-settings",
    response_model=AdminMapSettingsRead,
    operation_id="plannerAdminGetMapSettings",
)
async def get_admin_map_settings(session: SessionDep, response: Response) -> AdminMapSettingsRead:
    """The parent router requires the isolated SYSTEM_ADMIN administration client."""

    response.headers["Cache-Control"] = "no-store"
    return _admin_read(await read_map_settings(session))


@admin_router.put(
    "/map-settings",
    response_model=AdminMapSettingsRead,
    operation_id="plannerAdminReplaceMapSettings",
)
async def put_admin_map_settings(
    payload: MapSettingsUpdate, session: SessionDep, response: Response
) -> AdminMapSettingsRead:
    """Commit the global presentation setting under its own expected-version fence."""

    response.headers["Cache-Control"] = "no-store"
    return _admin_read(await replace_map_settings(session, payload))


@router.get("/map-settings", response_model=MapSettingsRead, operation_id="plannerGetMapSettings")
async def get_map_settings(session: SessionDep, response: Response) -> MapSettingsRead:
    """The parent router requires the panel USER session and rwms.read scope."""

    settings = await read_map_settings(session)
    response.headers["Cache-Control"] = "no-store"
    return MapSettingsRead(
        version=settings.version,
        provider=cast(MapProvider, settings.provider),
        yandex_api_key=settings.yandex_api_key if settings.provider == "YANDEX" else None,
    )
