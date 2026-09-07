"""Persist map presentation separately from routing and warehouse business settings."""

from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models.map_settings import MapDisplaySettings
from app.schemas.map_settings import MapSettingsUpdate


async def read_map_settings(
    session: AsyncSession, *, for_update: bool = False
) -> MapDisplaySettings:
    """Read the migration-created singleton and fail explicitly if it is unavailable."""

    settings = await session.get(
        MapDisplaySettings, 1, with_for_update=for_update, populate_existing=True
    )
    if settings is None:
        raise ApiError(503, "MAP_SETTINGS_UNAVAILABLE", "Настройки карты недоступны.")
    return settings


async def replace_map_settings(
    session: AsyncSession, payload: MapSettingsUpdate
) -> MapDisplaySettings:
    """Serialize edits and reject stale versions without replacing another admin's key."""

    settings = await read_map_settings(session, for_update=True)
    if settings.version != payload.expected_version:
        raise ApiError(
            409,
            "MAP_SETTINGS_VERSION_CONFLICT",
            "Настройки карты уже изменены. Загрузите актуальные настройки и повторите сохранение.",
        )
    key = (
        payload.yandex_api_key.get_secret_value()
        if payload.yandex_api_key is not None
        else settings.yandex_api_key
    )
    if payload.provider == "YANDEX" and not key:
        raise ApiError(
            422, "YANDEX_MAP_KEY_REQUIRED", "Введите API-ключ JavaScript API Яндекс Карт."
        )
    settings.provider = payload.provider
    settings.yandex_api_key = key
    settings.version += 1
    await session.flush()
    return settings
