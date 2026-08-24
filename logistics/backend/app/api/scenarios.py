"""Scenario lifecycle, clone, demo, and interchange endpoints."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Query, Response, status

from app.api.dependencies import SessionDep, SettingsDep
from app.models import Scenario
from app.repositories import get_required
from app.schemas.domain import (
    CloneRequest,
    ScenarioCreate,
    ScenarioExportDocument,
    ScenarioImportRequest,
    ScenarioRead,
    ScenarioUpdate,
)
from app.services import scenarios as service

router = APIRouter(prefix="/scenarios", tags=["scenarios"])


@router.get("", response_model=list[ScenarioRead])
async def list_scenarios(session: SessionDep) -> list[Scenario]:
    """List all local logistics experiments."""

    return await service.list_scenarios(session)


@router.post("", response_model=ScenarioRead, status_code=status.HTTP_201_CREATED)
async def create_scenario(
    payload: ScenarioCreate, session: SessionDep, settings: SettingsDep
) -> Scenario:
    """Create an empty scenario using configured defaults for omitted fields."""

    return await service.create_scenario(session, payload, settings)


@router.get("/{scenario_id}", response_model=ScenarioRead)
async def get_scenario(scenario_id: UUID, session: SessionDep) -> Scenario:
    """Read one scenario by UUID."""

    return await get_required(session, Scenario, scenario_id, "scenario")


@router.patch("/{scenario_id}", response_model=ScenarioRead)
async def update_scenario(
    scenario_id: UUID, payload: ScenarioUpdate, session: SessionDep
) -> Scenario:
    """Update scenario metadata or planner settings."""

    return await service.update_scenario(session, scenario_id, payload)


@router.delete("/{scenario_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_scenario(scenario_id: UUID, session: SessionDep) -> Response:
    """Delete one explicitly selected test scenario."""

    await service.delete_scenario(session, scenario_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{scenario_id}/clone", response_model=ScenarioRead, status_code=201)
async def clone_scenario(
    scenario_id: UUID,
    payload: CloneRequest,
    session: SessionDep,
    settings: SettingsDep,
) -> Scenario:
    """Clone scenario inputs and plans with remapped UUIDs."""

    return await service.clone_scenario(session, scenario_id, settings, name=payload.name)


@router.post("/{scenario_id}/export", response_model=ScenarioExportDocument)
async def export_scenario(
    scenario_id: UUID,
    session: SessionDep,
    include_plans: Annotated[bool, Query()] = False,
) -> ScenarioExportDocument:
    """Export one reproducible, schema-versioned scenario JSON document."""

    return await service.export_scenario(session, scenario_id, include_plans=include_plans)


@router.post("/import", response_model=ScenarioRead, status_code=201)
async def import_scenario(
    payload: ScenarioImportRequest,
    session: SessionDep,
    settings: SettingsDep,
) -> Scenario:
    """Atomically import a validated scenario or create nothing on failure."""

    return await service.import_scenario(session, payload.document, settings, name=payload.name)


@router.post("/{scenario_id}/generate-demo", response_model=ScenarioRead)
async def generate_demo(scenario_id: UUID, session: SessionDep) -> Scenario:
    """Explicitly reset one scenario to deterministic demo contents."""

    return await service.reset_demo_scenario(session, scenario_id)


@router.post("/generate-multi-day-demo", response_model=ScenarioRead, status_code=201)
async def generate_multi_day_demo(session: SessionDep, settings: SettingsDep) -> Scenario:
    """Create a separate three-day scenario with parallel shifts and alternatives."""

    return await service.create_multi_day_demo_scenario(session, settings)
