"""Disposable-database probe for the canonical vehicle physical model migration."""

from __future__ import annotations

import importlib
import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, text

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_PREVIOUS_REVISION = "20260907_0037"
_REVISION = "20260908_0038"
pytest_plugins = ("tests.migration_database",)


def _psycopg_database_url(database_url: str) -> str:
    """Normalize the disposable URL for the synchronous migration probe."""

    for prefix in ("postgresql+asyncpg://", "postgresql://"):
        if database_url.startswith(prefix):
            return database_url.replace(prefix, "postgresql+psycopg://", 1)
    return database_url


def _alembic(database_url: str, revision: str, *, check: bool = True) -> None:
    """Run one explicit Alembic revision against the disposable database."""

    environment = os.environ.copy()
    environment["LOGISTICS_DATABASE_URL"] = _psycopg_database_url(database_url)
    existing_pythonpath = environment.get("PYTHONPATH")
    environment["PYTHONPATH"] = str(_BACKEND_ROOT) + (
        os.pathsep + existing_pythonpath if existing_pythonpath else ""
    )
    subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", revision],
        cwd=_BACKEND_ROOT,
        env=environment,
        check=check,
        capture_output=True,
        text=True,
    )


@pytest.mark.integration
def test_canonical_vehicle_migration_backfills_exact_units(
    disposable_migration_database_url: str,
) -> None:
    """V38 derives canonical tonnes/metres and preserves every legacy column."""

    database_url = disposable_migration_database_url
    models = importlib.import_module("app.models")
    assert Path(models.__file__).resolve().is_relative_to(_BACKEND_ROOT)
    _alembic(database_url, _PREVIOUS_REVISION)

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
                    INSERT INTO trailers (id, warehouse_id, name, registration_number)
                    VALUES (:id, :warehouse_id, 'Migration trailer', 'MIG-01')
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
                        notes, tare_weight_kg, max_gross_weight_kg, length_mm,
                        width_mm, height_mm, axle_count, max_axle_load_kg,
                        payload_capacity_kg, can_use_trailer, default_trailer_id,
                        combined_length_with_trailer_mm, coupling_length_mm
                    ) VALUES (
                        :id, :warehouse_id, 'Migration truck', 'MIG-02',
                        2, true, 35, 65, '', 12000, 22000, 9200,
                        2500, 3200, 3, 10000, 8000, true, :trailer_id,
                        18500, 1300
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

        _alembic(database_url, _REVISION)

        with engine.connect() as connection:
            vehicle = connection.execute(
                text(
                    """
                    SELECT tare_weight_t, max_gross_weight_t, axle_load_t,
                           payload_capacity_t, length_m, width_m, height_m,
                           has_trailer, can_use_trailer, tare_weight_kg, length_mm,
                           platform_length_mm
                    FROM vehicles WHERE id = :id
                    """
                ),
                {"id": vehicle_id},
            ).one()
            profile = connection.execute(
                text(
                    """
                    SELECT max_actual_axle_load_kg, max_actual_axle_load_t
                    FROM vehicle_load_profiles WHERE id = :id
                    """
                ),
                {"id": profile_id},
            ).one()

        canonical = {
            name: str(value)
            for name, value in vehicle._mapping.items()
            if name.endswith(("_t", "_m"))
        }
        assert canonical == {
            "tare_weight_t": "12.000",
            "max_gross_weight_t": "22.000",
            "axle_load_t": "10.000",
            "payload_capacity_t": "8.000",
            "length_m": "9.200",
            "width_m": "2.500",
            "height_m": "3.200",
        }
        assert vehicle.has_trailer is True
        assert vehicle.can_use_trailer is True
        # The legacy projection is preserved unchanged for old consumers.
        assert vehicle.tare_weight_kg == 12000
        assert vehicle.length_mm == 9200
        assert vehicle.platform_length_mm is None
        assert str(profile.max_actual_axle_load_t) == "7.800"
        assert profile.max_actual_axle_load_kg == 7800
    finally:
        engine.dispose()
