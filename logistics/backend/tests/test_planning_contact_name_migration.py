"""Disposable-database checks for the planning contact-name widening migration."""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
from pathlib import Path
from uuid import UUID

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

from app.schemas.domain import LogisticsRequestCreate
from app.services import catalog
from tests.factories import make_warehouse

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_PREVIOUS_REVISION = "20260902_0034"
_REVISION = "20260906_0035"


def _migration_environment(database_url: str) -> dict[str, str]:
    """Require an explicitly provisioned disposable database for destructive DDL checks."""

    environment = os.environ.copy()
    environment["LOGISTICS_DATABASE_URL"] = database_url
    return environment


def _alembic(
    environment: dict[str, str], *arguments: str, check: bool = True
) -> subprocess.CompletedProcess[str]:
    """Run the repository migration command against only the explicit test database."""

    return subprocess.run(
        [sys.executable, "-m", "alembic", *arguments],
        cwd=_BACKEND_ROOT,
        env=environment,
        check=check,
        capture_output=True,
        text=True,
    )


async def _insert_request(database_url: str, contact_name: str) -> tuple[UUID, UUID]:
    """Persist one ordinary request using the same ORM path as the planner."""

    engine = create_async_engine(database_url, pool_pre_ping=True)
    try:
        async with AsyncSession(engine, expire_on_commit=False) as session:
            warehouse = await make_warehouse(session)
            request = await catalog.create_request(
                session,
                warehouse.id,
                LogisticsRequestCreate(
                    type="DELIVERY",
                    name="Тестовая точка",
                    latitude=55.75,
                    longitude=37.61,
                    quantity=1,
                    contact_name=contact_name,
                ),
            )
            await session.commit()
            return request.id, warehouse.id
    finally:
        await engine.dispose()


@pytest.mark.integration
def test_contact_name_migration_preserves_data_and_refuses_lossy_downgrade() -> None:
    """Upgrade a declared disposable predecessor schema and prove its lossless downgrade fence."""

    database_url = os.getenv("MIGRATION_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MIGRATION_TEST_DATABASE_URL is not configured")
    environment = _migration_environment(database_url)
    _alembic(environment, "upgrade", _PREVIOUS_REVISION)
    current = _alembic(environment, "current")
    assert _PREVIOUS_REVISION in current.stdout
    assert _REVISION not in current.stdout

    old_contact = "Ж" * 200
    old_request_id, old_warehouse_id = asyncio.run(_insert_request(database_url, old_contact))
    _alembic(environment, "upgrade", _REVISION)

    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            maximum_length = connection.scalar(
                text(
                    "SELECT character_maximum_length "
                    "FROM information_schema.columns "
                    "WHERE table_schema = current_schema() "
                    "AND table_name = 'logistics_requests' AND column_name = 'contact_name'"
                )
            )
            preserved_contact = connection.scalar(
                text("SELECT contact_name FROM logistics_requests WHERE id = :request_id"),
                {"request_id": old_request_id},
            )

        long_contact = "Ж" * 512
        long_request_id, long_warehouse_id = asyncio.run(
            _insert_request(database_url, long_contact)
        )
        with engine.connect() as connection:
            persisted_long_contact = connection.scalar(
                text("SELECT contact_name FROM logistics_requests WHERE id = :request_id"),
                {"request_id": long_request_id},
            )

        assert maximum_length == 512
        assert preserved_contact == old_contact
        assert persisted_long_contact == long_contact

        downgrade = _alembic(environment, "downgrade", _PREVIOUS_REVISION, check=False)
        assert downgrade.returncode != 0
        with engine.connect() as connection:
            assert connection.scalar(
                text("SELECT contact_name FROM logistics_requests WHERE id = :request_id"),
                {"request_id": long_request_id},
            ) == long_contact

        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM logistics_requests WHERE id IN (:old_id, :long_id)"),
                {"old_id": old_request_id, "long_id": long_request_id},
            )
            connection.execute(
                text("DELETE FROM warehouses WHERE id IN (:old_id, :long_id)"),
                {"old_id": old_warehouse_id, "long_id": long_warehouse_id},
            )
    finally:
        engine.dispose()

    _alembic(environment, "downgrade", _PREVIOUS_REVISION)
