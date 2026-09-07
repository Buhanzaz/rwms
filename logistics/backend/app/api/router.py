"""Composition root for the version-one public API router."""

from fastapi import APIRouter, Depends

from app.api import (
    admin_catalog,
    admin_settings,
    catalog,
    dynamic_operations,
    geocoding,
    health,
    map_settings,
    plans,
    policy_zones,
    request_rescheduling,
    routing,
    rwms,
    slot_planning,
)
from app.api.dependencies import get_current_admin, get_current_user

api_router = APIRouter(prefix="/api")
api_router.include_router(health.router)
admin_router = APIRouter(prefix="/admin", dependencies=[Depends(get_current_admin)])
admin_router.include_router(admin_catalog.router)
admin_router.include_router(admin_settings.router)
admin_router.include_router(map_settings.admin_router)
api_router.include_router(admin_router)
protected_router = APIRouter(dependencies=[Depends(get_current_user)])
protected_router.include_router(catalog.router)
protected_router.include_router(dynamic_operations.router)
protected_router.include_router(policy_zones.router)
protected_router.include_router(plans.router)
protected_router.include_router(request_rescheduling.router)
protected_router.include_router(rwms.router)
protected_router.include_router(routing.router)
protected_router.include_router(slot_planning.router)
protected_router.include_router(geocoding.router)
protected_router.include_router(map_settings.router)
api_router.include_router(protected_router)
