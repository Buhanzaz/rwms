"""Composition root for the version-one public API router."""

from fastapi import APIRouter

from app.api import catalog, health, plans, routing, rwms, scenarios

api_router = APIRouter(prefix="/api")
api_router.include_router(health.router)
api_router.include_router(scenarios.router)
api_router.include_router(catalog.router)
api_router.include_router(plans.router)
api_router.include_router(rwms.router)
api_router.include_router(routing.router)
