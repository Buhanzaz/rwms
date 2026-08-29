"""Composition root for the version-one public API router."""

from fastapi import APIRouter

from app.api import catalog, geocoding, health, plans, routing, rwms, slot_planning

api_router = APIRouter(prefix="/api")
api_router.include_router(health.router)
api_router.include_router(catalog.router)
api_router.include_router(plans.router)
api_router.include_router(rwms.router)
api_router.include_router(routing.router)
api_router.include_router(slot_planning.router)
api_router.include_router(geocoding.router)
