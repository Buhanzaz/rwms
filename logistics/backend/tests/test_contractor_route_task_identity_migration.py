"""Disposable-database probe for contractor route task identity migration."""

from __future__ import annotations

import importlib
import json
import os
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, text

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_PREVIOUS_REVISION = "20260831_0029"
_REVISION = "20260901_0030"
pytest_plugins = ("tests.migration_database",)


def _psycopg_database_url(database_url: str) -> str:
    if database_url.startswith("postgresql+asyncpg://"):
        return database_url.replace("postgresql+asyncpg://", "postgresql+psycopg://", 1)
    if database_url.startswith("postgresql://"):
        return database_url.replace("postgresql://", "postgresql+psycopg://", 1)
    return database_url


def _alembic(database_url: str, revision: str) -> None:
    environment = os.environ.copy()
    environment["LOGISTICS_DATABASE_URL"] = _psycopg_database_url(database_url)
    environment["PYTHONPATH"] = str(_BACKEND_ROOT)
    subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", revision],
        cwd=_BACKEND_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )


@pytest.mark.integration
def test_v30_backfills_only_succeeded_contractor_requests_in_command_order(
    disposable_migration_database_url: str,
) -> None:
    """V30 stores zero-based succeeded-command order and leaves other rows unsequenced."""

    database_url = disposable_migration_database_url
    models = importlib.import_module("app.models")
    assert Path(models.__file__).resolve().is_relative_to(_BACKEND_ROOT)
    _alembic(database_url, _PREVIOUS_REVISION)
    engine = create_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    warehouse_id = uuid4()
    succeeded_command_id = uuid4()
    pending_command_id = uuid4()
    succeeded_request_ids = [uuid4(), uuid4(), uuid4()]
    pending_request_id = uuid4()
    unrelated_request_id = uuid4()
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO warehouses (
                        id, external_warehouse_id, name, latitude, longitude,
                        loading_minutes, unloading_minutes, turnaround_minutes,
                        working_day_start, working_day_end, timezone, seed, settings,
                        capacity_generation
                    ) VALUES (
                        :id, :external_id, 'V29 migration warehouse', 59.9, 30.3,
                        30, 30, 15, TIME '08:00', TIME '20:00',
                        'Europe/Moscow', 17, CAST('{}' AS jsonb), 0
                    )
                    """
                ),
                {"id": warehouse_id, "external_id": uuid4()},
            )
            for command_id, request_ids, status in (
                (succeeded_command_id, succeeded_request_ids, "SUCCEEDED"),
                (pending_command_id, [pending_request_id], "PENDING"),
            ):
                connection.execute(
                    text(
                        """
                        INSERT INTO contractor_handoff_commands (
                            id, warehouse_id, external_warehouse_id, planning_date,
                            mode, request_ids, command_payload, contractor_worker_id,
                            contractor_name, contractor_phone, assigned_by, status,
                            attempts, next_attempt_at, lease_until, error_code,
                            rejection_codes, completed_at
                        ) VALUES (
                            :id, :warehouse_id, :external_id, DATE '2026-09-01',
                            'MANUAL', CAST(:request_ids AS jsonb), CAST('{}' AS jsonb),
                            :worker_id, 'Migration contractor', '+79990000000',
                            'migration-probe', CAST(:status AS varchar), 0, NULL, NULL, NULL,
                            CAST('[]' AS jsonb),
                            CASE WHEN CAST(:status AS varchar) = 'SUCCEEDED'
                                THEN CURRENT_TIMESTAMP ELSE NULL END
                        )
                        """
                    ),
                    {
                        "id": command_id,
                        "warehouse_id": warehouse_id,
                        "external_id": uuid4(),
                        "request_ids": json.dumps([str(value) for value in request_ids]),
                        "worker_id": uuid4(),
                        "status": status,
                    },
                )
            for index, (request_id, command_id, assignment_type) in enumerate(
                [
                    *(
                        (value, succeeded_command_id, "CONTRACTOR_HANDOFF")
                        for value in succeeded_request_ids
                    ),
                    (pending_request_id, pending_command_id, "CONTRACTOR_HANDOFF"),
                    (unrelated_request_id, None, None),
                ]
            ):
                assigned_worker_id = uuid4() if assignment_type else None
                connection.execute(
                    text(
                        """
                        INSERT INTO logistics_requests (
                            id, warehouse_id, type, name, address_label, latitude,
                            longitude, quantity, service_minutes, priority, status,
                            split_allowed, notes, assignment_type,
                            assigned_contractor_worker_id, assigned_contractor_name,
                            assigned_contractor_phone, assigned_at, assigned_by,
                            contractor_handoff_command_id
                        ) VALUES (
                            :id, :warehouse_id, 'DELIVERY', :name, 'Test address',
                            59.91, 30.31, 1, 30, 3, 'NEW', false, '', :assignment_type,
                            :assigned_worker_id, :assigned_name, :assigned_phone,
                            :assigned_at, :assigned_by,
                            :command_id
                        )
                        """
                    ),
                    {
                        "id": request_id,
                        "warehouse_id": warehouse_id,
                        "name": f"V29 migration request {index}",
                        "assignment_type": assignment_type,
                        "assigned_worker_id": assigned_worker_id,
                        "assigned_name": "Contractor" if assignment_type else None,
                        "assigned_phone": "+79990000000" if assignment_type else None,
                        "assigned_at": datetime.now(UTC) if assignment_type else None,
                        "assigned_by": "migration-probe" if assignment_type else None,
                        "command_id": command_id,
                    },
                )
        _alembic(database_url, _REVISION)
        with engine.connect() as connection:
            rows = dict(
                connection.execute(
                    text(
                        """
                        SELECT id, contractor_handoff_sequence
                        FROM logistics_requests
                        WHERE id = ANY(:request_ids)
                        """
                    ),
                    {
                        "request_ids": [
                            *succeeded_request_ids,
                            pending_request_id,
                            unrelated_request_id,
                        ]
                    },
                ).all()
            )
        assert [rows[request_id] for request_id in succeeded_request_ids] == [0, 1, 2]
        assert rows[pending_request_id] is None
        assert rows[unrelated_request_id] is None
    finally:
        engine.dispose()
