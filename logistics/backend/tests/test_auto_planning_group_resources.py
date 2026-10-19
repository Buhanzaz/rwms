"""Automatic planning regressions for exact-date group resource admission."""

from __future__ import annotations

from types import SimpleNamespace
from typing import cast
from uuid import UUID, uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.domain import GeneratePlanRequest
from app.services.auto_planning import generate_missing_draft_plans
from app.services.plans import PlannerFacade
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


class _RecordingPlanner:
    """Minimal planner boundary that records generation without running optimization."""

    def __init__(self) -> None:
        self.calls: list[tuple[UUID, GeneratePlanRequest]] = []

    async def generate_plan(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        payload: GeneratePlanRequest,
    ) -> SimpleNamespace:
        """Record one eligible root generation and return stable result identities."""

        del session
        self.calls.append((warehouse_id, payload))
        return SimpleNamespace(id=uuid4(), plan_id=uuid4())


@pytest.mark.asyncio
async def test_representative_local_resources_enable_root_auto_plan(
    db_session: AsyncSession,
) -> None:
    """An admitted member's active shift satisfies the root group's resource gate."""

    root = await make_warehouse(db_session, name="Root without fleet")
    representative = await make_warehouse(db_session, name="Representative fleet")
    representative.representative = True
    planning_date = root.default_planning_date
    assert planning_date is not None
    await make_request(db_session, representative, planning_date=planning_date)
    driver = await make_driver(db_session, representative)
    vehicle = await make_vehicle(db_session, representative)
    await make_shift(
        db_session,
        representative,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    planner = _RecordingPlanner()

    runs = await generate_missing_draft_plans(
        db_session,
        cast(PlannerFacade, planner),
        root.id,
        (planning_date,),
        request_warehouse_ids=(root.id, representative.id),
        resource_warehouse_ids=(root.id, representative.id),
    )

    assert len(runs) == 1
    assert [(warehouse_id, payload.date) for warehouse_id, payload in planner.calls] == [
        (root.id, planning_date)
    ]


@pytest.mark.asyncio
async def test_forbidden_member_resources_do_not_enable_root_auto_plan(
    db_session: AsyncSession,
) -> None:
    """A member omitted by the exact-date link calendar cannot lend its local shift."""

    root = await make_warehouse(db_session, name="Root without permitted fleet")
    representative = await make_warehouse(db_session, name="Forbidden representative fleet")
    representative.representative = True
    planning_date = root.default_planning_date
    assert planning_date is not None
    await make_request(db_session, representative, planning_date=planning_date)
    driver = await make_driver(db_session, representative)
    vehicle = await make_vehicle(db_session, representative)
    await make_shift(
        db_session,
        representative,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    planner = _RecordingPlanner()

    runs = await generate_missing_draft_plans(
        db_session,
        cast(PlannerFacade, planner),
        root.id,
        (planning_date,),
        request_warehouse_ids=(root.id, representative.id),
        resource_warehouse_ids=(root.id,),
    )

    assert runs == ()
    assert planner.calls == []
