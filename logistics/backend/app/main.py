"""FastAPI application composition root for the standalone logistics backend."""

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError

from app.api.geocoding import YandexGeocodingClient
from app.api.router import api_router
from app.config import get_settings
from app.db import engine
from app.errors import ApiError, api_error_handler
from app.integrations.rwms import get_rwms_planning_client
from app.security import AccessTokenVerifier, JwksAccessTokenVerifier
from app.services.capacity_publication_worker import run_capacity_publication_worker
from app.services.contractor_handoff_worker import run_contractor_handoff_worker
from app.services.demand_ingestion_worker import run_demand_ingestion_worker
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.plans import PlannerFacade, UnavailablePlannerFacade
from app.services.request_reschedule_worker import run_request_reschedule_worker


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    """Run server-owned RWMS workers and release their resources on graceful shutdown."""

    settings = get_settings()
    stop = asyncio.Event()
    worker_tasks: list[asyncio.Task[None]] = []
    rwms_client = get_rwms_planning_client(settings)
    geocoder: YandexGeocodingClient | None = None
    if settings.rwms_sync_enabled:
        geocoder = YandexGeocodingClient(settings)
        worker_tasks.append(
            asyncio.create_task(
                run_demand_ingestion_worker(
                    rwms_client,
                    geocoder,
                    application.state.planner_facade,
                    stop,
                    interval_seconds=settings.rwms_demand_sync_interval_seconds,
                    batch_size=settings.rwms_demand_sync_batch_size,
                ),
                name="rwms-demand-ingestion-worker",
            )
        )
        worker_tasks.append(
            asyncio.create_task(
                run_contractor_handoff_worker(
                    rwms_client,
                    stop,
                    interval_seconds=(
                        settings.rwms_contractor_handoff_retry_interval_seconds
                    ),
                    batch_size=settings.rwms_contractor_handoff_retry_batch_size,
                ),
                name="rwms-contractor-handoff-worker",
            )
        )
        worker_tasks.append(
            asyncio.create_task(
                run_request_reschedule_worker(
                    rwms_client,
                    stop,
                    interval_seconds=(
                        settings.rwms_request_reschedule_retry_interval_seconds
                    ),
                    batch_size=settings.rwms_request_reschedule_retry_batch_size,
                    max_attempts=(
                        settings.rwms_request_reschedule_retry_max_attempts
                    ),
                ),
                name="rwms-request-reschedule-worker",
            )
        )
    if settings.rwms_capacity_publish_enabled:
        worker_tasks.append(
            asyncio.create_task(
                run_capacity_publication_worker(
                    rwms_client,
                    stop,
                    interval_seconds=settings.rwms_capacity_retry_interval_seconds,
                ),
                name="rwms-capacity-publication-worker",
            )
        )
    try:
        yield
    finally:
        stop.set()
        if worker_tasks:
            _, pending = await asyncio.wait(worker_tasks, timeout=5)
            for task in pending:
                task.cancel()
            await asyncio.gather(*worker_tasks, return_exceptions=True)
        if geocoder is not None:
            await geocoder.aclose()
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


def create_app(
    planner_facade: PlannerFacade | None = None,
    access_token_verifier: AccessTokenVerifier | None = None,
) -> FastAPI:
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
    application.state.access_token_verifier = (
        access_token_verifier or JwksAccessTokenVerifier(settings)
    )
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
        valhalla_url=runtime_settings.valhalla_url,
        valhalla_timeout_seconds=runtime_settings.valhalla_timeout_seconds,
        osm_data_version=runtime_settings.osm_data_version,
        rwms_client=(
            get_rwms_planning_client(runtime_settings)
            if runtime_settings.rwms_sync_enabled
            else None
        ),
    )
)


def openapi_document() -> dict[str, Any]:
    """Return the generated OpenAPI document for tooling and parity tests."""

    return app.openapi()
