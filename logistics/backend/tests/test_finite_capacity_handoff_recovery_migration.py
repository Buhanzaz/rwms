"""Disposable-database probe for fenced contractor handoff recovery migration."""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

from tests.factories import make_warehouse

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_PREVIOUS_REVISION = "20260906_0035"
_REVISION = "20260906_0036"


def _psycopg_database_url(database_url: str) -> str:
    """Use psycopg consistently for both synchronous and asynchronous probe engines."""

    if database_url.startswith("postgresql+asyncpg://"):
        return database_url.replace("postgresql+asyncpg://", "postgresql+psycopg://", 1)
    if database_url.startswith("postgresql://"):
        return database_url.replace("postgresql://", "postgresql+psycopg://", 1)
    return database_url


def _migration_environment(database_url: str) -> dict[str, str]:
    """Point Alembic exclusively at the explicitly provisioned disposable database."""

    environment = os.environ.copy()
    environment["LOGISTICS_DATABASE_URL"] = _psycopg_database_url(database_url)
    return environment


def _alembic(environment: dict[str, str], *arguments: str) -> subprocess.CompletedProcess[str]:
    """Run one Alembic command and retain its output as migration probe evidence."""

    return subprocess.run(
        [sys.executable, "-m", "alembic", *arguments],
        cwd=_BACKEND_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )


async def _insert_pre_fence_applying_handoff(database_url: str) -> tuple[UUID, UUID]:
    """Create an APPLYING V35 row whose lease must receive a durable V36 token."""

    engine = create_async_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    command_id = uuid4()
    try:
        async with AsyncSession(engine, expire_on_commit=False) as session:
            warehouse = await make_warehouse(session, name=f"V36 migration probe {command_id}")
            assert warehouse.external_warehouse_id is not None
            request_id = uuid4()
            await session.execute(
                text(
                    """
                    INSERT INTO contractor_handoff_commands (
                        id, warehouse_id, external_warehouse_id, planning_date, mode,
                        request_ids, command_payload, contractor_worker_id, contractor_name,
                        contractor_phone, assigned_by, status, attempts, next_attempt_at,
                        lease_until, error_code, rejection_codes, completed_at, created_at,
                        updated_at
                    ) VALUES (
                        :id, :warehouse_id, :external_warehouse_id, CURRENT_DATE, 'MANUAL',
                        CAST(:request_ids AS jsonb), CAST(:command_payload AS jsonb),
                        :contractor_worker_id, 'Migration contractor', '+79990000000',
                        'migration-probe',
                        'APPLYING', 1, NULL, CURRENT_TIMESTAMP, NULL,
                        CAST('[]' AS jsonb), NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """
                ),
                {
                    "id": command_id,
                    "warehouse_id": warehouse.id,
                    "external_warehouse_id": warehouse.external_warehouse_id,
                    "request_ids": json.dumps([str(request_id)]),
                    "command_payload": json.dumps({"assignments": []}),
                    "contractor_worker_id": uuid4(),
                },
            )
            await session.commit()
            return command_id, warehouse.id
    finally:
        await engine.dispose()


@pytest.mark.integration
def test_v36_backfills_applying_lease_token_and_installs_fencing_constraints() -> None:
    """V35 APPLYING leases retain recovery ownership after V36 adds token fencing."""

    database_url = os.getenv("MIGRATION_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MIGRATION_TEST_DATABASE_URL is not configured")
    environment = _migration_environment(database_url)
    _alembic(environment, "upgrade", _PREVIOUS_REVISION)
    engine = create_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            previous_columns = set(
                connection.scalars(
                    text(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'contractor_handoff_commands'
                        """
                    )
                )
            )
        assert {"id", "lease_until", "status", "request_ids", "command_payload"} <= previous_columns
        assert "lease_token" not in previous_columns
    finally:
        engine.dispose()

    command_id, warehouse_id = asyncio.run(_insert_pre_fence_applying_handoff(database_url))
    _alembic(environment, "upgrade", _REVISION)

    engine = create_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            head = connection.scalar(text("SELECT version_num FROM alembic_version"))
            row = connection.execute(
                text(
                    """
                    SELECT status, lease_until, lease_token
                    FROM contractor_handoff_commands
                    WHERE id = :command_id
                    """
                ),
                {"command_id": command_id},
            ).one()
            constraints = dict(
                connection.execute(
                    text(
                        """
                        SELECT conname, pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'contractor_handoff_commands'::regclass
                          AND conname IN (
                              'ck_contractor_handoff_commands_lease_matches_applying',
                              'ck_contractor_handoff_commands_valid_status'
                          )
                        """
                    )
                ).all()
            )
        assert head == _REVISION
        assert row.status == "APPLYING"
        assert row.lease_until is not None
        assert row.lease_token is not None
        assert "lease_token IS NOT NULL" in constraints[
            "ck_contractor_handoff_commands_lease_matches_applying"
        ]
        assert "REVIEW_REQUIRED" in constraints["ck_contractor_handoff_commands_valid_status"]
        print(
            "V35->V36 migration proof: "
            f"head={head}, lease_token={row.lease_token}, "
            f"constraints={sorted(constraints)}"
        )
    finally:
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM contractor_handoff_commands WHERE id = :command_id"),
                {"command_id": command_id},
            )
            connection.execute(
                text("DELETE FROM warehouses WHERE id = :warehouse_id"),
                {"warehouse_id": warehouse_id},
            )
        engine.dispose()
