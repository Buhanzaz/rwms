"""Confirmation failures stay explicit without retrying an aborted transaction."""

from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy.exc import DBAPIError

from app.errors import ApiError
from app.models import RoutePlan
from app.services import plans


@pytest.mark.asyncio
async def test_unconfigured_validator_cannot_confirm() -> None:
    """The honest default keeps the configured 503 contract at the new boundary."""

    with pytest.raises(ApiError) as rejected:
        await plans.UnavailablePlannerFacade().validate_confirmation(
            AsyncMock(),
            RoutePlan(),
            accept_warnings=True,
        )
    assert rejected.value.code == "PLANNER_NOT_CONFIGURED"
    assert rejected.value.status_code == 503


@pytest.mark.asyncio
@pytest.mark.parametrize("sqlstate", ("55P03", "40P01", "23503"))
async def test_only_nowait_contention_becomes_retryable_conflict(
    monkeypatch: pytest.MonkeyPatch,
    sqlstate: str,
) -> None:
    """No hidden retry or subsequent SQL is issued against an aborted transaction."""

    class DatabaseFailure(Exception):
        """Minimal psycopg-compatible failure retaining its SQLSTATE."""

    original = DatabaseFailure()
    original.sqlstate = sqlstate
    failure = DBAPIError("SELECT", {}, original)
    command = AsyncMock(side_effect=failure)
    monkeypatch.setattr(plans, "_confirm_plan_locked", command)
    session = AsyncMock()
    with pytest.raises(ApiError if sqlstate == "55P03" else DBAPIError) as rejected:
        await plans.confirm_plan(
            session,
            uuid4(),
            1,
            planner=plans.UnavailablePlannerFacade(),
            accept_warnings=True,
            empty_positioning_reason=None,
            confirmed_by="test",
        )
    if sqlstate == "55P03":
        assert rejected.value.code == "PLAN_RESOURCES_BUSY"
        assert rejected.value.status_code == 409
    else:
        assert rejected.value is failure
    command.assert_awaited_once()
    session.execute.assert_not_called()
    session.flush.assert_not_called()
