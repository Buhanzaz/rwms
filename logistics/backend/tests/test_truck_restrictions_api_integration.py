"""PostGIS HTTP tests for the truck-restrictions viewport boundary."""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi import FastAPI
from geoalchemy2.shape import from_shape
from httpx import ASGITransport, AsyncClient
from shapely.geometry import LineString, Point
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db import get_session
from app.main import create_app
from app.models import OsmRestrictionImport, OsmTruckRestriction
from app.services.osm_restriction_indexer import _import_atomically

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is not configured"
    ),
]

OSM_VERSION = "test-truck-restrictions-2026-08-25"


def _restriction_app(
    db_session: AsyncSession,
    *,
    osm_data_version: str = OSM_VERSION,
) -> FastAPI:
    """Build an application whose request session shares the rollback fixture."""

    application = create_app()

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Yield the integration-test transaction to the endpoint."""

        yield db_session

    def settings_override() -> Settings:
        """Select the synthetic OSM data version populated by this test."""

        return Settings.model_validate({"OSM_DATA_VERSION": osm_data_version})

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_settings] = settings_override
    return application


def _restriction(
    *,
    osm_id: int,
    geometry: Point | LineString,
    category: str,
    primary_tag: str,
    value: str,
) -> OsmTruckRestriction:
    """Create one current-version ORM row for a spatial API assertion."""

    return OsmTruckRestriction(
        id=uuid4(),
        osm_data_version=OSM_VERSION,
        osm_type="node" if isinstance(geometry, Point) else "way",
        osm_id=osm_id,
        category=category,
        primary_tag=primary_tag,
        value=value,
        tags={primary_tag: value, "name": f"restriction-{osm_id}"},
        support_status="SUPPORTED",
        geometry=from_shape(geometry, srid=4326),
    )


@pytest.mark.asyncio
async def test_bbox_query_metadata_outside_filter_and_truncation(
    db_session: AsyncSession,
) -> None:
    """The endpoint uses spatial intersection, current metadata, and limit+1."""

    generated_at = datetime(2026, 8, 25, 10, 30, tzinfo=UTC)
    db_session.add(
        OsmRestrictionImport(
            osm_data_version=OSM_VERSION,
            source_file="test.osm.pbf",
            restriction_count=3,
            imported_at=generated_at,
        )
    )
    db_session.add_all(
        [
            _restriction(
                osm_id=101,
                geometry=Point(37.60, 55.70),
                category="MAX_HEIGHT",
                primary_tag="maxheight",
                value="3.9",
            ),
            _restriction(
                osm_id=102,
                geometry=LineString([(37.55, 55.65), (37.75, 55.85)]),
                category="HGV_ACCESS",
                primary_tag="hgv",
                value="no",
            ),
            _restriction(
                osm_id=103,
                geometry=Point(39.0, 57.0),
                category="MAX_WEIGHT",
                primary_tag="maxweight",
                value="20",
            ),
        ]
    )
    await db_session.flush()

    application = _restriction_app(db_session)
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/routing/truck-restrictions",
            params={
                "west": 37.5,
                "south": 55.6,
                "east": 37.8,
                "north": 55.9,
            },
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["type"] == "FeatureCollection"
        assert {feature["properties"]["osm_id"] for feature in body["features"]} == {
            101,
            102,
        }
        assert body["metadata"] == {
            "osm_data_version": OSM_VERSION,
            "count": 2,
            "truncated": False,
            "generated_at": "2026-08-25T10:30:00Z",
        }

        limited = await client.get(
            "/api/routing/truck-restrictions",
            params={
                "west": 37.5,
                "south": 55.6,
                "east": 37.8,
                "north": 55.9,
                "limit": 1,
            },
        )
        assert limited.status_code == 200
        assert limited.json()["metadata"]["count"] == 1
        assert limited.json()["metadata"]["truncated"] is True

        empty = await client.get(
            "/api/routing/truck-restrictions",
            params={
                "west": 30,
                "south": 50,
                "east": 31,
                "north": 51,
            },
        )
        assert empty.status_code == 200
        assert empty.json()["features"] == []
        assert empty.json()["metadata"]["count"] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "params",
    [
        {"west": 38, "south": 55, "east": 37, "north": 56},
        {"west": 37, "south": 56, "east": 38, "north": 55},
        {"west": -181, "south": 55, "east": 38, "north": 56},
        {"west": 37, "south": 55, "east": 38, "north": 91},
        {"west": 37, "south": 55, "east": 38, "north": 56, "limit": 5001},
    ],
)
async def test_bbox_query_validation(db_session: AsyncSession, params: dict[str, float]) -> None:
    """Invalid coordinates, ordering, and limits fail at the HTTP boundary."""

    application = _restriction_app(db_session)
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/routing/truck-restrictions", params=params)
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_missing_active_version_index_fails_explicitly(db_session: AsyncSession) -> None:
    """An absent import cannot masquerade as a restriction-free viewport."""

    missing_version = "missing-osm-restriction-index"
    application = _restriction_app(db_session, osm_data_version=missing_version)
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/routing/truck-restrictions",
            params={"west": 37, "south": 55, "east": 38, "north": 56},
        )
    assert response.status_code == 503
    assert response.json()["code"] == "OSM_RESTRICTIONS_NOT_INDEXED"
    assert response.json()["osm_data_version"] == missing_version


@pytest.mark.asyncio
async def test_indexer_replaces_old_version_atomically(
    db_session: AsyncSession,
    tmp_path: Path,
) -> None:
    """A validated import publishes one version and removes older derived data."""

    old_version = "test-old-osm-version"
    db_session.add(
        OsmRestrictionImport(
            osm_data_version=old_version,
            source_file="old.osm.pbf",
            restriction_count=0,
            imported_at=datetime(2026, 8, 24, tzinfo=UTC),
        )
    )
    await db_session.flush()
    extraction = tmp_path / "restriction.geojsonseq"
    extraction.write_text(
        "\x1e"
        + json.dumps(
            {
                "type": "Feature",
                "id": "n700",
                "properties": {"hgv": "no", "name": "Тестовый знак"},
                "geometry": {"type": "Point", "coordinates": [37.6, 55.7]},
            },
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    connection = await db_session.connection()
    imported = await _import_atomically(
        connection,
        extraction_path=extraction,
        source_file="current.osm.pbf",
        osm_data_version=OSM_VERSION,
        expected_count=1,
        batch_size=1,
    )
    assert imported is True
    versions = set(
        (
            await connection.scalars(
                select(OsmRestrictionImport.osm_data_version).order_by(
                    OsmRestrictionImport.osm_data_version
                )
            )
        ).all()
    )
    assert versions == {OSM_VERSION}
    assert (
        await connection.scalar(
            select(func.count())
            .select_from(OsmTruckRestriction)
            .where(OsmTruckRestriction.osm_data_version == OSM_VERSION)
        )
        == 1
    )
