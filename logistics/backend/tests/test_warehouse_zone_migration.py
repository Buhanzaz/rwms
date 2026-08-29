"""Focused contract checks for the warehouse-owned zone migration."""

from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


def _migration_source() -> str:
    """Read the authoritative ownership migration from the backend tree."""

    path = (
        Path(__file__).resolve().parents[1]
        / "migrations/versions/20260828_0017_warehouse_owned_zones.py"
    )
    return path.read_text(encoding="utf-8")


def test_migration_contains_all_legacy_assignment_safety_guards() -> None:
    """Nearest-owner backfill must fail closed on ambiguity or inconsistent consumers."""

    source = _migration_source()
    assert 'down_revision: str | None = "20260828_0016"' in source
    assert "ST_PointOnSurface(zone.geometry)::geography" in source
    assert "zone without a surface point" in source
    assert "next_distance_m - distance_m <= 1.0" in source
    assert "cross-warehouse request classification" in source
    assert "cross-warehouse task classification" in source
    assert "cross-warehouse slot hold" in source
    assert "left a warehouse outside all assigned zones" in source
    assert "DELETE FROM" not in source.upper()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_migrated_zone_owner_column_is_required_indexed_and_cascading(
    db_session: AsyncSession,
) -> None:
    """The migrated database enforces one non-null cascading warehouse owner per zone."""

    nullable = await db_session.scalar(
        text(
            "SELECT is_nullable FROM information_schema.columns "
            "WHERE table_schema = current_schema() "
            "AND table_name = 'zones' AND column_name = 'warehouse_id'"
        )
    )
    delete_action = await db_session.scalar(
        text(
            "SELECT confdeltype "
            "FROM pg_constraint "
            "WHERE conname = 'fk_zones_warehouse_id_warehouses'"
        )
    )
    index_definition = await db_session.scalar(
        text(
            "SELECT indexdef FROM pg_indexes "
            "WHERE schemaname = current_schema() "
            "AND indexname = 'ix_zones_warehouse_id'"
        )
    )

    assert nullable == "NO"
    assert delete_action == "c"
    assert index_definition is not None and "(warehouse_id)" in index_definition
