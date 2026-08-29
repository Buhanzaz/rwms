"""Migration checks for warehouse isochrone tariffs and exceptional zone policies."""

from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


def _migration_source() -> str:
    """Read the immutable isochrone and zone-policy migration."""

    path = (
        Path(__file__).resolve().parents[1]
        / "migrations/versions/20260829_0018_isochrone_tariffs_and_zone_policies.py"
    )
    return path.read_text(encoding="utf-8")


def test_migration_backfills_without_deleting_operational_data() -> None:
    """Existing warehouses and zones receive compatible defaults in place."""

    source = _migration_source()
    assert 'down_revision: str | None = "20260828_0017"' in source
    assert 'server_default="10000"' in source
    assert 'server_default="15000"' in source
    assert 'server_default="20000"' in source
    assert 'server_default="25000"' in source
    assert 'server_default="SPECIAL_PRICE"' in source
    assert "DELETE FROM" not in source.upper()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_migrated_database_enforces_tariff_and_policy_columns(
    db_session: AsyncSession,
) -> None:
    """The test schema exposes non-null tariffs, typed zones, and held price bands."""

    columns = set(
        await db_session.scalars(
            text(
                "SELECT table_name || '.' || column_name "
                "FROM information_schema.columns "
                "WHERE table_schema = current_schema() AND ("
                "(table_name = 'warehouses' AND column_name LIKE 'isochrone_price_%') OR "
                "(table_name = 'zones' AND column_name = 'kind') OR "
                "(table_name = 'slot_holds' AND column_name = 'price_isochrone_minutes'))"
            )
        )
    )
    assert columns == {
        "warehouses.isochrone_price_60_minutes",
        "warehouses.isochrone_price_120_minutes",
        "warehouses.isochrone_price_180_minutes",
        "warehouses.isochrone_price_240_minutes",
        "zones.kind",
        "slot_holds.price_isochrone_minutes",
    }
