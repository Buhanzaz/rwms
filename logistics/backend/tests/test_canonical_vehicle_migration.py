"""Disposable-database probe for the canonical vehicle physical model migrations."""

from __future__ import annotations

import importlib
import os
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Connection

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_LEGACY_REVISION = "20260907_0037"
_CANONICAL_REVISION = "20261020_0039"
_HEAD_REVISION = "20261020_0040"
pytest_plugins = ("tests.migration_database",)

_LEGACY_COLUMNS = (
    "tare_weight_kg",
    "max_gross_weight_kg",
    "length_mm",
    "width_mm",
    "height_mm",
    "max_axle_load_kg",
    "payload_capacity_kg",
    "platform_length_mm",
    "platform_width_mm",
    "platform_height_from_ground_mm",
    "max_platform_payload_kg",
    "max_cargo_length_mm",
    "max_cargo_width_mm",
    "max_cargo_height_mm",
    "max_cargo_weight_kg",
    "can_use_trailer",
    "combined_length_with_trailer_mm",
    "coupling_length_mm",
    "height_safety_margin_mm",
    "width_safety_margin_mm",
    "weight_safety_margin_kg",
    "is_hgv",
)
_TRAILER_LEGACY_COLUMNS = (
    "tare_weight_kg",
    "max_gross_weight_kg",
    "length_mm",
    "width_mm",
    "height_mm",
    "platform_length_mm",
    "platform_width_mm",
    "platform_height_from_ground_mm",
    "max_platform_payload_kg",
    "payload_capacity_kg",
    "max_axle_load_kg",
    "max_cargo_length_mm",
    "max_cargo_width_mm",
    "max_cargo_height_mm",
    "max_cargo_weight_kg",
)


def _psycopg_database_url(database_url: str) -> str:
    """Normalize the disposable URL for the synchronous migration probe."""

    for prefix in ("postgresql+asyncpg://", "postgresql://"):
        if database_url.startswith(prefix):
            return database_url.replace(prefix, "postgresql+psycopg://", 1)
    return database_url


def _alembic(database_url: str, *arguments: str) -> None:
    """Run one explicit Alembic command against the disposable database."""

    environment = os.environ.copy()
    environment["LOGISTICS_DATABASE_URL"] = _psycopg_database_url(database_url)
    existing_pythonpath = environment.get("PYTHONPATH")
    environment["PYTHONPATH"] = str(_BACKEND_ROOT) + (
        os.pathsep + existing_pythonpath if existing_pythonpath else ""
    )
    subprocess.run(
        [sys.executable, "-m", "alembic", *arguments],
        cwd=_BACKEND_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )


def _table_columns(connection: Connection, table_name: str) -> set[str]:
    """Return the current columns of one table through the information schema."""

    rows = connection.execute(
        text(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = :table_name
            """
        ),
        {"table_name": table_name},
    ).scalars()
    return set(rows)


@pytest.mark.integration
def test_canonical_migration_backfills_then_drops_every_legacy_column(
    disposable_migration_database_url: str,
) -> None:
    """V39 derives canonical tonnes/metres and V40 removes the legacy projection."""

    database_url = disposable_migration_database_url
    models = importlib.import_module("app.models")
    assert Path(models.__file__).resolve().is_relative_to(_BACKEND_ROOT)
    _alembic(database_url, "upgrade", _LEGACY_REVISION)

    engine = create_engine(_psycopg_database_url(database_url), pool_pre_ping=True)
    warehouse_id = uuid4()
    vehicle_id = uuid4()
    trailer_id = uuid4()
    profile_id = uuid4()
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
                        :id, :external_id, 'V37 migration warehouse', 59.9, 30.3,
                        30, 30, 15, TIME '08:00', TIME '20:00',
                        'Europe/Moscow', 17, CAST('{}' AS jsonb), 0
                    )
                    """
                ),
                {"id": warehouse_id, "external_id": uuid4()},
            )
            connection.execute(
                text(
                    """
                    INSERT INTO trailers (
                        id, warehouse_id, name, registration_number, active,
                        tare_weight_kg, max_gross_weight_kg, length_mm, width_mm,
                        height_mm, platform_length_mm, platform_width_mm,
                        platform_height_from_ground_mm, max_platform_payload_kg,
                        payload_capacity_kg, axle_count, max_axle_load_kg,
                        max_cargo_length_mm, max_cargo_width_mm,
                        max_cargo_height_mm, max_cargo_weight_kg
                    ) VALUES (
                        :id, :warehouse_id, 'Migration trailer', 'MIG-01', true,
                        3000, 10000, 8000, 2500, 1800, 6000, 2500, 900,
                        6000, 7000, 2, 10000, 7000, 2600, 3000, 6000
                    )
                    """
                ),
                {"id": trailer_id, "warehouse_id": warehouse_id},
            )
            connection.execute(
                text(
                    """
                    INSERT INTO vehicles (
                        id, warehouse_id, name, registration_number,
                        capacity, active, average_speed_city, average_speed_region,
                        notes, is_hgv, tare_weight_kg, max_gross_weight_kg, length_mm,
                        width_mm, height_mm, axle_count, max_axle_load_kg,
                        payload_capacity_kg, platform_length_mm, platform_width_mm,
                        platform_height_from_ground_mm, max_platform_payload_kg,
                        max_cargo_length_mm, max_cargo_width_mm, max_cargo_height_mm,
                        max_cargo_weight_kg, can_use_trailer, default_trailer_id,
                        combined_length_with_trailer_mm, coupling_length_mm,
                        height_safety_margin_mm, width_safety_margin_mm,
                        weight_safety_margin_kg
                    ) VALUES (
                        :id, :warehouse_id, 'Migration truck', 'MIG-02',
                        2, true, 35, 65, '', true, 12000, 22000, 9200,
                        2500, 3200, 3, 10000, 8000, 6000, 2500, 1300, 7000,
                        7000, 2600, 3000, 6000, true, :trailer_id,
                        18500, 1300, 100, 50, 200
                    )
                    """
                ),
                {
                    "id": vehicle_id,
                    "warehouse_id": warehouse_id,
                    "trailer_id": trailer_id,
                },
            )
            connection.execute(
                text(
                    """
                    INSERT INTO vehicle_load_profiles (
                        id, vehicle_id, configuration_type, max_actual_axle_load_kg
                    ) VALUES (:id, :vehicle_id, 'CARGO_ON_TRUCK', 7800)
                    """
                ),
                {"id": profile_id, "vehicle_id": vehicle_id},
            )

        _alembic(database_url, "upgrade", _CANONICAL_REVISION)

        with engine.connect() as connection:
            canonical = connection.execute(
                text(
                    """
                    SELECT tare_weight_t, max_gross_weight_t, axle_load_t,
                           payload_capacity_t, length_m, width_m, height_m,
                           platform_length_m, platform_height_from_ground_m,
                           max_platform_payload_t, max_cargo_length_m,
                           max_cargo_width_m, max_cargo_height_m,
                           max_cargo_weight_t, coupling_length_m,
                           height_safety_margin_m, width_safety_margin_m,
                           weight_safety_margin_t, has_trailer
                    FROM vehicles WHERE id = :id
                    """
                ),
                {"id": vehicle_id},
            ).one()
            trailer = connection.execute(
                text(
                    """
                    SELECT tare_weight_t, length_m, max_cargo_weight_t
                    FROM trailers WHERE id = :id
                    """
                ),
                {"id": trailer_id},
            ).one()
            profile = connection.execute(
                text(
                    "SELECT max_actual_axle_load_t "
                    "FROM vehicle_load_profiles WHERE id = :id"
                ),
                {"id": profile_id},
            ).one()
            canonical_vehicle_columns = _table_columns(connection, "vehicles")

        assert canonical.tare_weight_t == Decimal("12.000")
        assert canonical.max_gross_weight_t == Decimal("22.000")
        assert canonical.axle_load_t == Decimal("10.000")
        assert canonical.payload_capacity_t == Decimal("8.000")
        assert canonical.length_m == Decimal("9.200")
        assert canonical.width_m == Decimal("2.500")
        assert canonical.height_m == Decimal("3.200")
        assert canonical.platform_length_m == Decimal("6.000")
        assert canonical.platform_height_from_ground_m == Decimal("1.300")
        assert canonical.max_platform_payload_t == Decimal("7.000")
        assert canonical.max_cargo_length_m == Decimal("7.000")
        assert canonical.max_cargo_width_m == Decimal("2.600")
        assert canonical.max_cargo_height_m == Decimal("3.000")
        assert canonical.max_cargo_weight_t == Decimal("6.000")
        assert canonical.coupling_length_m == Decimal("1.300")
        assert canonical.height_safety_margin_m == Decimal("0.100")
        assert canonical.width_safety_margin_m == Decimal("0.050")
        assert canonical.weight_safety_margin_t == Decimal("0.200")
        assert canonical.has_trailer is True
        assert trailer.tare_weight_t == Decimal("3.000")
        assert trailer.length_m == Decimal("8.000")
        assert trailer.max_cargo_weight_t == Decimal("6.000")
        assert profile.max_actual_axle_load_t == Decimal("7.800")
        # V39 keeps the legacy projection as rollback data.
        assert set(_LEGACY_COLUMNS) <= canonical_vehicle_columns

        _alembic(database_url, "upgrade", _HEAD_REVISION)

        with engine.connect() as connection:
            vehicle_columns = _table_columns(connection, "vehicles")
            trailer_columns = _table_columns(connection, "trailers")
            profile_columns = _table_columns(connection, "vehicle_load_profiles")
            preserved = connection.execute(
                text(
                    "SELECT tare_weight_t, length_m, has_trailer "
                    "FROM vehicles WHERE id = :id"
                ),
                {"id": vehicle_id},
            ).one()

        assert set(_LEGACY_COLUMNS).isdisjoint(vehicle_columns)
        assert set(_TRAILER_LEGACY_COLUMNS).isdisjoint(trailer_columns)
        assert "max_actual_axle_load_kg" not in profile_columns
        assert preserved.tare_weight_t == Decimal("12.000")
        assert preserved.length_m == Decimal("9.200")
        assert preserved.has_trailer is True

        _alembic(database_url, "downgrade", _CANONICAL_REVISION)

        with engine.connect() as connection:
            restored = connection.execute(
                text(
                    """
                    SELECT tare_weight_kg, length_mm, can_use_trailer,
                           combined_length_with_trailer_mm, coupling_length_mm,
                           platform_width_mm, height_safety_margin_mm,
                           width_safety_margin_mm, weight_safety_margin_kg,
                           is_hgv
                    FROM vehicles WHERE id = :id
                    """
                ),
                {"id": vehicle_id},
            ).one()
            restored_trailer = connection.execute(
                text(
                    "SELECT tare_weight_kg, length_mm, platform_width_mm "
                    "FROM trailers WHERE id = :id"
                ),
                {"id": trailer_id},
            ).one()
            restored_profile = connection.execute(
                text(
                    "SELECT max_actual_axle_load_kg "
                    "FROM vehicle_load_profiles WHERE id = :id"
                ),
                {"id": profile_id},
            ).one()

        assert restored.tare_weight_kg == 12_000
        assert restored.length_mm == 9_200
        assert restored.can_use_trailer is True
        assert restored.combined_length_with_trailer_mm == 18_500
        assert restored.coupling_length_mm == 1_300
        assert restored.platform_width_mm is None
        assert restored.height_safety_margin_mm == 100
        assert restored.width_safety_margin_mm == 50
        assert restored.weight_safety_margin_kg == 200
        assert restored.is_hgv is None
        assert restored_trailer.tare_weight_kg == 3_000
        assert restored_trailer.length_mm == 8_000
        assert restored_trailer.platform_width_mm is None
        assert restored_profile.max_actual_axle_load_kg == 7_800
    finally:
        engine.dispose()
