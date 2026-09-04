"""Unit regressions for bounded, fenced, server-owned demand ingestion."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import asynccontextmanager
from types import SimpleNamespace
from typing import Any, cast
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.schemas.domain import RwmsSyncResult
from app.services import demand_ingestion_worker as worker
from app.services.plans import PlannerFacade


class _SessionContext:
    """Yield one predetermined async session from a lightweight context manager."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def __aenter__(self) -> AsyncSession:
        """Expose the configured session."""

        return self.session

    async def __aexit__(self, *args: object) -> None:
        """Leave lifecycle assertions to the test-owned mocks."""


class _SessionFactory:
    """Return distinct sessions in the worker's expected transaction order."""

    def __init__(self, sessions: list[AsyncSession]) -> None:
        self._sessions: Iterator[AsyncSession] = iter(sessions)
        self.calls = 0

    def __call__(self) -> _SessionContext:
        """Return the next directory, selection, ingestion, or planning session."""

        self.calls += 1
        return _SessionContext(next(self._sessions))


def _session() -> AsyncSession:
    """Build one fully awaitable AsyncSession test double."""

    return cast(AsyncSession, MagicMock(spec=AsyncSession))


@pytest.mark.asyncio
async def test_batch_skips_all_work_when_another_instance_holds_fence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A failed PostgreSQL claim deduplicates the complete periodic batch."""

    @asynccontextmanager
    async def denied_fence(database_engine: AsyncEngine) -> Any:
        """Represent a lock already held by another server instance."""

        del database_engine
        yield False

    session_factory = MagicMock()
    monkeypatch.setattr(worker, "demand_ingestion_fence", denied_fence)
    cursor = uuid4()
    result = await worker.run_demand_ingestion_batch(
        cast(Any, MagicMock()),
        cast(Any, SimpleNamespace(forward=AsyncMock())),
        cast(PlannerFacade, MagicMock()),
        cursor=cursor,
        batch_size=7,
        database_engine=cast(AsyncEngine, MagicMock()),
        session_factory=cast(async_sessionmaker[AsyncSession], session_factory),
    )

    assert result == worker.DemandIngestionBatchResult(
        processed=0,
        next_cursor=cursor,
        fence_acquired=False,
    )
    session_factory.assert_not_called()


@pytest.mark.asyncio
async def test_batch_commits_sync_before_bounded_group_planning(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """One selected page persists demand before external topology and a new planning session."""

    @asynccontextmanager
    async def acquired_fence(database_engine: AsyncEngine) -> Any:
        """Represent this instance owning the cross-server batch claim."""

        del database_engine
        yield True

    warehouse_id = uuid4()
    root_id = uuid4()
    representative_id = warehouse_id
    warehouse = SimpleNamespace(
        id=warehouse_id,
        external_warehouse_id=uuid4(),
        routing_ready=True,
        representative=True,
        timezone="Europe/Moscow",
    )
    root = SimpleNamespace(id=root_id, external_warehouse_id=uuid4())
    representative = SimpleNamespace(
        id=representative_id,
        external_warehouse_id=warehouse.external_warehouse_id,
    )
    directory_session = _session()
    selection_session = _session()
    ingestion_session = _session()
    planning_session = _session()
    directory_session.commit = AsyncMock()
    selection_session.scalars = AsyncMock(return_value=(warehouse_id,))
    ingestion_session.get = AsyncMock(return_value=warehouse)
    ingestion_session.commit = AsyncMock()
    ingestion_session.rollback = AsyncMock()
    planning_session.commit = AsyncMock()
    planning_session.rollback = AsyncMock()
    sessions = _SessionFactory(
        [directory_session, selection_session, ingestion_session, planning_session]
    )
    refresh_directory = AsyncMock()
    synchronize = AsyncMock(
        return_value=RwmsSyncResult(imported=0, updated=1, skipped=0)
    )
    invalidation = AsyncMock()
    generation_calls: list[dict[str, object]] = []

    async def fetch_network(client: object, selected: object) -> worker._PlanningNetwork:
        """Prove the ingestion transaction committed before any topology HTTP call."""

        del client
        assert selected is warehouse
        ingestion_session.commit.assert_awaited_once()
        return worker._PlanningNetwork(root_external_id=root.external_warehouse_id, links=())

    async def group_dates(
        session: AsyncSession,
        selected: object,
        network: worker._PlanningNetwork,
        planning_dates: tuple[object, ...],
    ) -> tuple[object, dict[tuple[object, ...], tuple[object, ...]]]:
        """Admit the representative for every date in this focused topology."""

        del network
        assert session is planning_session
        assert selected is warehouse
        assert len(planning_dates) == 31
        return root, {(root.id, representative.id): planning_dates}

    async def generate(
        session: AsyncSession,
        planner: object,
        warehouse_id_arg: object,
        planning_dates: tuple[object, ...],
        **kwargs: object,
    ) -> tuple[()]:
        """Capture exact request and resource admission passed to the planner boundary."""

        del planner
        assert session is planning_session
        generation_calls.append(
            {
                "warehouse_id": warehouse_id_arg,
                "planning_dates": planning_dates,
                **kwargs,
            }
        )
        return ()

    monkeypatch.setattr(worker, "demand_ingestion_fence", acquired_fence)
    monkeypatch.setattr(worker, "refresh_warehouse_directory", refresh_directory)
    monkeypatch.setattr(worker, "sync_warehouse_requests", synchronize)
    monkeypatch.setattr(worker, "_fetch_planning_network", fetch_network)
    monkeypatch.setattr(worker, "_group_planning_dates", group_dates)
    monkeypatch.setattr(worker, "invalidate_mutable_group_root_plans", invalidation)
    monkeypatch.setattr(worker, "generate_missing_draft_plans", generate)

    result = await worker.run_demand_ingestion_batch(
        cast(Any, MagicMock()),
        cast(Any, SimpleNamespace(forward=AsyncMock())),
        cast(PlannerFacade, MagicMock()),
        cursor=None,
        batch_size=1,
        database_engine=cast(AsyncEngine, MagicMock()),
        session_factory=cast(async_sessionmaker[AsyncSession], sessions),
    )

    assert result == worker.DemandIngestionBatchResult(
        processed=1,
        next_cursor=warehouse_id,
        fence_acquired=True,
    )
    assert sessions.calls == 4
    refresh_directory.assert_awaited_once()
    synchronize.assert_awaited_once()
    ingestion_session.commit.assert_awaited_once()
    ingestion_session.rollback.assert_not_awaited()
    invalidation.assert_awaited_once()
    planning_session.commit.assert_awaited_once()
    planning_session.rollback.assert_not_awaited()
    assert len(generation_calls) == 1
    assert generation_calls[0]["warehouse_id"] == root.id
    assert generation_calls[0]["request_warehouse_ids"] == (
        root.id,
        representative.id,
    )
    assert generation_calls[0]["resource_warehouse_ids"] == (
        root.id,
        representative.id,
    )
    statement = selection_session.scalars.await_args.args[0]  # type: ignore[union-attr]
    assert statement._limit_clause.value == 1


@pytest.mark.asyncio
async def test_planning_failure_does_not_rollback_committed_ingestion(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Optimizer failure rolls back only the later planning transaction."""

    @asynccontextmanager
    async def acquired_fence(database_engine: AsyncEngine) -> Any:
        """Represent this instance owning the batch claim."""

        del database_engine
        yield True

    warehouse_id = uuid4()
    external_id = uuid4()
    warehouse = SimpleNamespace(
        id=warehouse_id,
        external_warehouse_id=external_id,
        routing_ready=True,
        representative=False,
        timezone="Europe/Moscow",
    )
    directory_session = _session()
    selection_session = _session()
    ingestion_session = _session()
    planning_session = _session()
    directory_session.commit = AsyncMock()
    selection_session.scalars = AsyncMock(return_value=(warehouse_id,))
    ingestion_session.get = AsyncMock(return_value=warehouse)
    ingestion_session.commit = AsyncMock()
    ingestion_session.rollback = AsyncMock()
    planning_session.commit = AsyncMock()
    planning_session.rollback = AsyncMock()
    sessions = _SessionFactory(
        [directory_session, selection_session, ingestion_session, planning_session]
    )

    monkeypatch.setattr(worker, "demand_ingestion_fence", acquired_fence)
    monkeypatch.setattr(worker, "refresh_warehouse_directory", AsyncMock())
    monkeypatch.setattr(
        worker,
        "sync_warehouse_requests",
        AsyncMock(return_value=RwmsSyncResult(imported=1, updated=0, skipped=0)),
    )
    monkeypatch.setattr(
        worker,
        "_fetch_planning_network",
        AsyncMock(
            return_value=worker._PlanningNetwork(
                root_external_id=external_id,
                links=(),
            )
        ),
    )

    async def group_dates(
        session: AsyncSession,
        selected: object,
        network: worker._PlanningNetwork,
        planning_dates: tuple[object, ...],
    ) -> tuple[object, dict[tuple[object, ...], tuple[object, ...]]]:
        """Keep this focused warehouse as its own exact-date group."""

        del session, selected, network
        return warehouse, {(warehouse_id,): planning_dates}

    monkeypatch.setattr(worker, "_group_planning_dates", group_dates)
    monkeypatch.setattr(
        worker,
        "generate_missing_draft_plans",
        AsyncMock(side_effect=RuntimeError("optimizer unavailable")),
    )

    result = await worker.run_demand_ingestion_batch(
        cast(Any, MagicMock()),
        cast(Any, SimpleNamespace(forward=AsyncMock())),
        cast(PlannerFacade, MagicMock()),
        cursor=None,
        batch_size=1,
        database_engine=cast(AsyncEngine, MagicMock()),
        session_factory=cast(async_sessionmaker[AsyncSession], sessions),
    )

    assert result.processed == 1
    ingestion_session.commit.assert_awaited_once()
    ingestion_session.rollback.assert_not_awaited()
    planning_session.commit.assert_not_awaited()
    planning_session.rollback.assert_awaited_once()
