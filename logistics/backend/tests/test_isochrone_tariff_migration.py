"""Focused schema checks for normalized warehouse isochrone tariffs."""

from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


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
