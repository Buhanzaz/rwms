"""Readiness endpoint that verifies both PostgreSQL and PostGIS."""

from fastapi import APIRouter
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.api.dependencies import SessionDep, SettingsDep
from app.errors import ApiError
from app.schemas.domain import HealthRead

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthRead)
async def health(session: SessionDep, settings: SettingsDep) -> HealthRead:
    """Report ready only when a live database connection can call PostGIS."""

    try:
        postgis = str(await session.scalar(text("SELECT PostGIS_Version()")))
    except SQLAlchemyError as exc:
        raise ApiError(
            503,
            "DATABASE_NOT_READY",
            "PostgreSQL or the required PostGIS extension is unavailable",
        ) from exc
    return HealthRead(
        status="ok",
        database="ready",
        postgis=postgis,
        routing_provider=settings.routing_provider,
    )
