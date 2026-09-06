"""Focused schema checks for normalized warehouse isochrone tariffs."""

from __future__ import annotations

import importlib
import json
import os
import subprocess
import sys
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.ext.asyncio import AsyncSession

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_PREVIOUS_REVISION = "20260829_0019"
_REVISION = "20260830_0020"
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


def _migration_source() -> str:
    """Read the immutable tariff-normalization migration."""

    path = (
        Path(__file__).resolve().parents[1]
        / "migrations/versions/20260830_0020_normalize_isochrone_tariffs.py"
    )
    return path.read_text(encoding="utf-8")


def test_upgrade_backfills_all_fixed_prices_before_removing_legacy_state() -> None:
    """The 0019 upgrade preserves prices and calculated delivery amounts."""

    source = _migration_source()
    assert 'down_revision: str | None = "20260829_0019"' in source
    assert "INSERT INTO warehouse_isochrone_tariffs" in source
    assert "(60, isochrone_price_60_minutes)" in source
    assert "(240, isochrone_price_240_minutes)" in source
    assert 'op.drop_table("zones")' in source
    assert 'op.drop_column("slot_holds", "delivery_price")' not in source
    assert 'op.drop_column("logistics_requests", "calculated_delivery_price")' not in source
    assert "DELETE FROM" not in source.upper()


@pytest.mark.integration
def test_v20_upgrade_preserves_tariffs_and_persisted_delivery_amounts(
    disposable_migration_database_url: str,
) -> None:
    """A seeded V19 database retains every tariff and captured price through V20."""

    database_url = disposable_migration_database_url
    models = importlib.import_module("app.models")
    assert Path(models.__file__).resolve().is_relative_to(_BACKEND_ROOT)
    _alembic(database_url, _PREVIOUS_REVISION)
    engine = create_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    warehouse_prices: dict[UUID, tuple[int, int, int, int]] = {
        uuid4(): (1_001, 1_002, 1_003, 1_004),
        uuid4(): (2_001, 2_002, 2_003, 2_004),
    }
    request_amounts = (31_001, 31_002)
    hold_amounts = (41_001, 41_002)
    request_ids: list[UUID] = []
    hold_ids: list[UUID] = []
    try:
        with engine.begin() as connection:
            for index, (warehouse_id, prices) in enumerate(warehouse_prices.items()):
                connection.execute(
                    text(
                        """
                        INSERT INTO warehouses (
                            id, external_warehouse_id, name, latitude, longitude,
                            loading_minutes, unloading_minutes, turnaround_minutes,
                            working_day_start, working_day_end, timezone, seed, settings,
                            capacity_generation, isochrone_price_60_minutes,
                            isochrone_price_120_minutes, isochrone_price_180_minutes,
                            isochrone_price_240_minutes
                        ) VALUES (
                            :id, :external_id, :name, 59.9, 30.3,
                            30, 30, 15, TIME '08:00', TIME '20:00',
                            'Europe/Moscow', 17, CAST('{}' AS jsonb), 0,
                            :price_60, :price_120, :price_180, :price_240
                        )
                        """
                    ),
                    {
                        "id": warehouse_id,
                        "external_id": uuid4(),
                        "name": f"V19 migration warehouse {index}",
                        "price_60": prices[0],
                        "price_120": prices[1],
                        "price_180": prices[2],
                        "price_240": prices[3],
                    },
                )
                request_id = uuid4()
                request_ids.append(request_id)
                hold_id = uuid4()
                hold_ids.append(hold_id)
                connection.execute(
                    text(
                        """
                        INSERT INTO logistics_requests (
                            id, warehouse_id, type, name, address_label, latitude,
                            longitude, quantity, service_minutes, priority, status,
                            zone_classification_status, split_allowed, notes,
                            external_payload
                        ) VALUES (
                            :id, :warehouse_id, 'DELIVERY', :name, 'Test address',
                            59.91, 30.31, 1, 30, 3, 'NEW', 'UNCLASSIFIED', false, '',
                            CAST(:payload AS jsonb)
                        )
                        """
                    ),
                    {
                        "id": request_id,
                        "warehouse_id": warehouse_id,
                        "name": f"V19 priced request {index}",
                        "payload": json.dumps({"calculatedDeliveryPrice": request_amounts[index]}),
                    },
                )
                day_plan_id = uuid4()
                connection.execute(
                    text(
                        """
                        INSERT INTO slot_day_plans (
                            id, warehouse_id, date, version, source_revision
                        ) VALUES (
                            :id, :warehouse_id, DATE '2026-09-01', 1, :revision
                        )
                        """
                    ),
                    {
                        "id": day_plan_id,
                        "warehouse_id": warehouse_id,
                        "revision": "a" * 64,
                    },
                )
                connection.execute(
                    text(
                        """
                        INSERT INTO slot_holds (
                            id, day_plan_id, plan_version, source_revision,
                            client_session_id, status, expires_at, request_snapshot,
                            candidate_snapshot, delivery_price_rubles,
                            price_isochrone_minutes
                        ) VALUES (
                            :id, :day_plan_id, 1, :revision, :session_id, 'HELD',
                            CURRENT_TIMESTAMP + INTERVAL '1 hour', CAST('{}' AS jsonb),
                            CAST('{}' AS jsonb), :amount, 60
                        )
                        """
                    ),
                    {
                        "id": hold_id,
                        "day_plan_id": day_plan_id,
                        "revision": "b" * 64,
                        "session_id": f"migration-{index}",
                        "amount": hold_amounts[index],
                    },
                )
        _alembic(database_url, _REVISION)
        with engine.connect() as connection:
            tariffs = connection.execute(
                text(
                    """
                    SELECT warehouse_id, travel_minutes, price_rubles
                    FROM warehouse_isochrone_tariffs
                    WHERE warehouse_id = ANY(:warehouse_ids)
                    ORDER BY warehouse_id, travel_minutes
                    """
                ),
                {"warehouse_ids": list(warehouse_prices)},
            ).all()
            hold_total = connection.scalar(
                text("SELECT sum(delivery_price_rubles) FROM slot_holds WHERE id = ANY(:hold_ids)"),
                {"hold_ids": hold_ids},
            )
            request_total = connection.scalar(
                text(
                    """
                    SELECT sum((external_payload->>'calculatedDeliveryPrice')::bigint)
                    FROM logistics_requests
                    WHERE id = ANY(:request_ids)
                    """
                ),
                {"request_ids": request_ids},
            )
        expected_tariffs = sorted(
            (
                warehouse_id,
                travel_minutes,
                price,
            )
            for warehouse_id, prices in warehouse_prices.items()
            for travel_minutes, price in zip((60, 120, 180, 240), prices, strict=True)
        )
        assert tariffs == expected_tariffs
        assert hold_total == sum(hold_amounts)
        assert request_total == sum(request_amounts)
    finally:
        engine.dispose()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_head_schema_has_tariff_rows_and_no_runtime_zone_storage(
    db_session: AsyncSession,
) -> None:
    """A clean head exposes normalized tariffs and removes legacy zone references."""

    tables = set(
        await db_session.scalars(
            text(
                "SELECT table_name FROM information_schema.tables "
                "WHERE table_schema = current_schema() AND table_name IN "
                "('warehouse_isochrone_tariffs', 'zones')"
            )
        )
    )
    assert tables == {"warehouse_isochrone_tariffs"}

    removed_columns = set(
        await db_session.scalars(
            text(
                "SELECT table_name || '.' || column_name "
                "FROM information_schema.columns "
                "WHERE table_schema = current_schema() AND ("
                "(table_name = 'warehouses' AND column_name LIKE 'isochrone_price_%') OR "
                "(table_name IN ('logistics_requests', 'planning_tasks') "
                " AND column_name IN ('zone_id', 'zone_version', 'zone_classification_status')) OR "
                "(table_name = 'slot_holds' AND column_name = 'price_zone_id'))"
            )
        )
    )
    assert removed_columns == set()

    check_definition = await db_session.scalar(
        text(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            "WHERE conrelid = 'warehouse_isochrone_tariffs'::regclass "
            "AND conname = 'ck_warehouse_isochrone_tariffs_valid_travel_minutes'"
        )
    )
    assert check_definition is not None
    assert "720" in check_definition
    assert "60" in check_definition
