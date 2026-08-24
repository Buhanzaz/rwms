"""FastAPI application composition root for the standalone logistics backend."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError

from app.api.router import api_router
from app.config import get_settings
from app.db import engine
from app.errors import ApiError, api_error_handler
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.plans import PlannerFacade, UnavailablePlannerFacade


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    """Release pooled database resources on graceful process shutdown."""

    yield
    await engine.dispose()


async def integrity_error_handler(request: Request, exc: IntegrityError) -> JSONResponse:
    """Map database invariant violations to a sanitized conflict response."""

    return JSONResponse(
        {
            "type": "urn:rwms:logistics:error:database-constraint-violation",
            "title": "DATABASE_CONSTRAINT_VIOLATION",
            "status": 409,
            "detail": "The requested change conflicts with existing logistics data",
            "instance": str(request.url.path),
            "code": "DATABASE_CONSTRAINT_VIOLATION",
        },
        status_code=409,
        media_type="application/problem+json",
    )


def create_app(planner_facade: PlannerFacade | None = None) -> FastAPI:
    """Create an application with an injectable planner integration boundary."""

    settings = get_settings()
    application = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        description="Internal logistics planning, route editing, and simulation API.",
        openapi_url="/api/openapi.json",
        docs_url="/api/docs",
        redoc_url="/api/redoc",
        lifespan=lifespan,
    )
    application.state.planner_facade = planner_facade or UnavailablePlannerFacade()
    application.add_exception_handler(ApiError, api_error_handler)  # type: ignore[arg-type]
    application.add_exception_handler(IntegrityError, integrity_error_handler)  # type: ignore[arg-type]
    application.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_credentials="*" not in settings.cors_origins,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["ETag"],
    )
    application.include_router(api_router)
    return application


runtime_settings = get_settings()
app = create_app(
    RuntimePlannerFacade(
        runtime_settings.routing_provider,
        osrm_base_url=runtime_settings.osrm_base_url,
        osrm_profile=runtime_settings.osrm_profile,
        osrm_timeout_seconds=runtime_settings.osrm_timeout_seconds,
    )
)


def openapi_document() -> dict[str, Any]:
    """Return the generated OpenAPI document for tooling and parity tests."""

    return app.openapi()
