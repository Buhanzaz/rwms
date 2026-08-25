"""PostGIS-backed viewport queries for indexed OSM truck restrictions."""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import OsmRestrictionImport, OsmTruckRestriction
from app.schemas.routing import (
    TruckRestrictionFeature,
    TruckRestrictionFeatureCollection,
    TruckRestrictionGeometry,
    TruckRestrictionMetadata,
    TruckRestrictionProperties,
    TruckRestrictionQuery,
)


async def find_truck_restrictions(
    session: AsyncSession,
    *,
    osm_data_version: str,
    query: TruckRestrictionQuery,
) -> TruckRestrictionFeatureCollection:
    """Return active-version restrictions intersecting a WGS84 viewport."""

    imported = await session.get(OsmRestrictionImport, osm_data_version)
    if imported is None:
        raise ApiError(
            503,
            "OSM_RESTRICTIONS_NOT_INDEXED",
            "Truck restrictions have not been indexed for the active OSM data version",
            extra={"osm_data_version": osm_data_version},
        )
    viewport = func.ST_MakeEnvelope(query.west, query.south, query.east, query.north, 4326)
    statement = (
        select(
            OsmTruckRestriction.osm_type,
            OsmTruckRestriction.osm_id,
            OsmTruckRestriction.category,
            OsmTruckRestriction.primary_tag,
            OsmTruckRestriction.value,
            OsmTruckRestriction.tags,
            OsmTruckRestriction.support_status,
            func.ST_AsGeoJSON(OsmTruckRestriction.geometry).label("geometry_geojson"),
        )
        .where(
            OsmTruckRestriction.osm_data_version == osm_data_version,
            func.ST_Intersects(OsmTruckRestriction.geometry, viewport),
        )
        .order_by(
            OsmTruckRestriction.category,
            OsmTruckRestriction.osm_type,
            OsmTruckRestriction.osm_id,
        )
        .limit(query.limit + 1)
    )
    rows = list((await session.execute(statement)).all())
    truncated = len(rows) > query.limit
    features = [_feature_from_row(row._mapping) for row in rows[: query.limit]]
    return TruckRestrictionFeatureCollection(
        features=features,
        metadata=TruckRestrictionMetadata(
            osm_data_version=osm_data_version,
            count=len(features),
            truncated=truncated,
            generated_at=imported.imported_at,
        ),
    )


def _feature_from_row(row: Any) -> TruckRestrictionFeature:
    """Convert one typed SQLAlchemy row mapping into a GeoJSON feature."""

    geometry_payload = json.loads(str(row["geometry_geojson"]))
    return TruckRestrictionFeature(
        geometry=TruckRestrictionGeometry.model_validate(geometry_payload),
        properties=TruckRestrictionProperties(
            osm_type=row["osm_type"],
            osm_id=row["osm_id"],
            category=row["category"],
            primary_tag=row["primary_tag"],
            value=row["value"],
            tags=row["tags"],
            support_status=row["support_status"],
        ),
    )
