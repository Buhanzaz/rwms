"""Typed FastAPI dependencies shared by route modules."""

from typing import Annotated, cast

from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db import get_session
from app.services.plans import PlannerFacade

# Commit write transactions before the response becomes observable. This keeps an
# immediate client-side refetch from seeing a partially old scenario snapshot.
SessionDep = Annotated[AsyncSession, Depends(get_session, scope="function")]
SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_planner_facade(request: Request) -> PlannerFacade:
    """Resolve the configured planner integration from application state."""

    return cast(PlannerFacade, request.app.state.planner_facade)


PlannerDep = Annotated[PlannerFacade, Depends(get_planner_facade)]
