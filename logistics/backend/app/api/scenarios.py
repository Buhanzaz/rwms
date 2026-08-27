"""Scenario lifecycle, clone, demo, and interchange endpoints."""

from datetime import date
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response, status

from app.api.dependencies import RoadSnapperDep, SessionDep, SettingsDep
from app.integrations.rwms import RwmsPlanningClient, get_rwms_planning_client
from app.models import Scenario
from app.repositories import get_required
from app.schemas.domain import (
    CloneRequest,
    ScenarioCreate,
    ScenarioExportDocument,
    ScenarioImportRequest,
    ScenarioRead,
    ScenarioUpdate,
    WorkloadDeletionResult,
    WorkloadGenerationResult,
    WorkloadGeneratorInput,
)
from app.services import scenarios as service
from app.services.capacity_projection import publish_scenario_capacity
from app.services.workload_generator import (
    delete_generated_workload,
    generate_scenario_workload,
)

router = APIRouter(prefix="/scenarios", tags=["scenarios"])


def capacity_rwms_client(settings: SettingsDep) -> RwmsPlanningClient:
    """Resolve the shared authenticated client for optional generator publication."""

    return get_rwms_planning_client(settings)


CapacityRwmsClientDep = Annotated[RwmsPlanningClient, Depends(capacity_rwms_client)]


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


@router.post(
    "/{scenario_id}/generate-workload",
    response_model=WorkloadGenerationResult,
    status_code=status.HTTP_201_CREATED,
)
async def generate_workload(
    scenario_id: UUID,
    payload: WorkloadGeneratorInput,
    session: SessionDep,
    snapper: RoadSnapperDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> WorkloadGenerationResult:
    """Delete affected plans, then replace deterministic workload inside current zones."""

    result = await generate_scenario_workload(session, scenario_id, payload, snapper)
    if settings.rwms_capacity_publish_enabled:
        await session.commit()
        await publish_scenario_capacity(session, scenario_id, client)
    return result


@router.delete(
    "/{scenario_id}/generated-workload",
    response_model=WorkloadDeletionResult,
)
async def delete_workload(
    scenario_id: UUID,
    target_date: Annotated[date, Query(alias="date")],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> WorkloadDeletionResult:
    """Delete generator-owned workload and every saved plan for one exact date."""

    result = await delete_generated_workload(session, scenario_id, target_date)
    if settings.rwms_capacity_publish_enabled and result.deleted_requests > 0:
        await session.commit()
        await publish_scenario_capacity(session, scenario_id, client)
    return result


@router.post("/generate-multi-day-demo", response_model=ScenarioRead, status_code=201)
async def generate_multi_day_demo(session: SessionDep, settings: SettingsDep) -> Scenario:
    """Create a separate three-day scenario with parallel shifts and alternatives."""

    return await service.create_multi_day_demo_scenario(session, settings)
