"""One-shot, atomic importer for truck restrictions in the mounted OSM PBF."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import tempfile
from collections.abc import Iterator, Sequence
from pathlib import Path
from typing import Any
from uuid import uuid4

from geoalchemy2.shape import from_shape
from sqlalchemy import delete, insert, select, text
from sqlalchemy.ext.asyncio import AsyncConnection

from app.config import get_settings
from app.db import engine
from app.models import OsmRestrictionImport, OsmTruckRestriction
from app.routing.restrictions import ParsedTruckRestriction, parse_geojsonseq_feature

LOGGER = logging.getLogger("osm-truck-restriction-indexer")

OSMIUM_FILTERS: tuple[str, ...] = (
    "nw/hgv",
    "nw/access",
    "nw/motor_vehicle",
    "nw/vehicle",
    "nw/maxheight",
    "nw/maxheight:physical",
    "nw/maxwidth:physical",
    "nw/maxwidth",
    "nw/maxlength",
    "nw/maxweight",
    "nw/maxweightrating:hgv",
    "nw/maxaxleload",
    "nw/hgv:conditional",
    "nw/access:conditional",
    "nw/motor_vehicle:conditional",
    "nw/vehicle:conditional",
    "nw/maxheight:conditional",
    "nw/maxheight:physical:conditional",
    "nw/maxwidth:physical:conditional",
    "nw/maxwidth:conditional",
    "nw/maxlength:conditional",
    "nw/maxweight:conditional",
    "nw/maxaxleload:conditional",
    "nw/hgv_articulated",
    "nw/trailer",
)


class RestrictionImportError(RuntimeError):
    """Signal an extraction or persistence failure that must stop backend startup."""


def _structured_log(event: str, **fields: object) -> None:
    """Write a compact JSON event without leaking source OSM payloads."""

    LOGGER.info(json.dumps({"event": event, **fields}, ensure_ascii=False, sort_keys=True))


def _run_osmium(arguments: Sequence[str], *, phase: str) -> None:
    """Run one pinned osmium command and surface bounded diagnostics on failure."""

    completed = subprocess.run(
        arguments,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        diagnostic = completed.stderr.strip()[-2000:]
        raise RestrictionImportError(
            f"osmium phase {phase!r} failed with exit {completed.returncode}: {diagnostic}"
        )


def extract_restrictions(source_pbf: Path, work_directory: Path) -> Path:
    """Create complete GeoJSONSeq extraction before opening an import transaction."""

    if not source_pbf.is_file() or source_pbf.stat().st_size <= 0:
        raise RestrictionImportError(f"OSM source PBF is missing or empty: {source_pbf}")
    filtered_pbf = work_directory / "truck-restrictions-unsorted.osm.pbf"
    sorted_pbf = work_directory / "truck-restrictions.osm.pbf"
    geojson_sequence = work_directory / "truck-restrictions.geojsonseq"
    _run_osmium(
        (
            "osmium",
            "tags-filter",
            "--overwrite",
            "-o",
            str(filtered_pbf),
            str(source_pbf),
            *OSMIUM_FILTERS,
        ),
        phase="tags-filter",
    )
    _run_osmium(
        (
            "osmium",
            "sort",
            "--overwrite",
            "-o",
            str(sorted_pbf),
            str(filtered_pbf),
        ),
        phase="sort",
    )
    _run_osmium(
        (
            "osmium",
            "export",
            "--overwrite",
            "--index-type=sparse_file_array",
            "--add-unique-id=type_id",
            "--geometry-types=point,linestring",
            "-f",
            "geojsonseq",
            "-o",
            str(geojson_sequence),
            str(sorted_pbf),
        ),
        phase="export",
    )
    if not geojson_sequence.is_file() or geojson_sequence.stat().st_size <= 0:
        raise RestrictionImportError("osmium extraction produced an empty GeoJSON sequence")
    return geojson_sequence


def iter_extracted_restrictions(path: Path) -> Iterator[ParsedTruckRestriction]:
    """Stream relevant objects and handle RFC 8142 record-separator prefixes."""

    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            payload = line.lstrip("\x1e").strip()
            if not payload:
                continue
            try:
                decoded = json.loads(payload)
            except json.JSONDecodeError as exc:
                raise RestrictionImportError(
                    f"Invalid GeoJSON sequence record at line {line_number}"
                ) from exc
            if not isinstance(decoded, dict):
                raise RestrictionImportError(
                    f"GeoJSON sequence record at line {line_number} is not an object"
                )
            try:
                restriction = parse_geojsonseq_feature(decoded)
            except ValueError as exc:
                raise RestrictionImportError(
                    f"Invalid relevant restriction at line {line_number}: {exc}"
                ) from exc
            if restriction is not None:
                yield restriction


async def _version_is_imported(osm_data_version: str) -> bool:
    """Check whether a completed import already represents the active tileset."""

    async with engine.connect() as connection:
        result = await connection.scalar(
            select(OsmRestrictionImport.osm_data_version).where(
                OsmRestrictionImport.osm_data_version == osm_data_version
            )
        )
    return result is not None


def _restriction_row(
    restriction: ParsedTruckRestriction,
    *,
    osm_data_version: str,
) -> dict[str, Any]:
    """Build one SQLAlchemy parameter mapping with a typed PostGIS geometry."""

    classification = restriction.classification
    return {
        "id": uuid4(),
        "osm_data_version": osm_data_version,
        "osm_type": restriction.osm_type,
        "osm_id": restriction.osm_id,
        "category": classification.category.value,
        "primary_tag": classification.primary_tag,
        "value": classification.value,
        "tags": restriction.tags,
        "support_status": classification.support_status.value,
        "geometry": from_shape(restriction.geometry, srid=4326),
    }


async def _import_atomically(
    connection: AsyncConnection,
    *,
    extraction_path: Path,
    source_file: str,
    osm_data_version: str,
    expected_count: int,
    batch_size: int,
) -> bool:
    """Insert one complete version and remove older versions in one transaction."""

    await connection.execute(
        text("SELECT pg_advisory_xact_lock(hashtext('osm-truck-restriction-import'))")
    )
    already_imported = await connection.scalar(
        select(OsmRestrictionImport.osm_data_version).where(
            OsmRestrictionImport.osm_data_version == osm_data_version
        )
    )
    if already_imported is not None:
        return False

    await connection.execute(
        insert(OsmRestrictionImport).values(
            osm_data_version=osm_data_version,
            source_file=source_file,
            restriction_count=expected_count,
        )
    )
    inserted_count = 0
    batch: list[dict[str, Any]] = []
    for restriction in iter_extracted_restrictions(extraction_path):
        batch.append(_restriction_row(restriction, osm_data_version=osm_data_version))
        if len(batch) >= batch_size:
            await connection.execute(insert(OsmTruckRestriction), batch)
            inserted_count += len(batch)
            batch.clear()
    if batch:
        await connection.execute(insert(OsmTruckRestriction), batch)
        inserted_count += len(batch)
    if inserted_count != expected_count:
        raise RestrictionImportError(
            f"Restriction count changed between validation and import: "
            f"expected {expected_count}, inserted {inserted_count}"
        )
    await connection.execute(
        delete(OsmRestrictionImport).where(
            OsmRestrictionImport.osm_data_version != osm_data_version
        )
    )
    return True


async def run_import(
    *,
    source_pbf: Path,
    osm_data_version: str,
    batch_size: int,
) -> None:
    """Skip completed data or extract, validate, and atomically replace the index."""

    if await _version_is_imported(osm_data_version):
        _structured_log("restriction_import_skipped", osm_data_version=osm_data_version)
        return
    with tempfile.TemporaryDirectory(prefix="osm-truck-restrictions-") as temporary:
        extraction_path = extract_restrictions(source_pbf, Path(temporary))
        expected_count = sum(1 for _ in iter_extracted_restrictions(extraction_path))
        if expected_count <= 0:
            raise RestrictionImportError("No relevant node/way truck restrictions were extracted")
        source_bytes = await asyncio.to_thread(os.path.getsize, source_pbf)
        _structured_log(
            "restriction_extraction_completed",
            osm_data_version=osm_data_version,
            restriction_count=expected_count,
            source_bytes=source_bytes,
        )
        async with engine.begin() as connection:
            imported = await _import_atomically(
                connection,
                extraction_path=extraction_path,
                source_file=source_pbf.name,
                osm_data_version=osm_data_version,
                expected_count=expected_count,
                batch_size=batch_size,
            )
    _structured_log(
        "restriction_import_completed" if imported else "restriction_import_race_skipped",
        osm_data_version=osm_data_version,
        restriction_count=expected_count,
    )


async def _run_and_dispose(*, source_pbf: Path, osm_data_version: str, batch_size: int) -> None:
    """Keep engine use and disposal on one event loop."""

    try:
        await run_import(
            source_pbf=source_pbf,
            osm_data_version=osm_data_version,
            batch_size=batch_size,
        )
    finally:
        await engine.dispose()


def main() -> None:
    """Load validated environment settings and execute the one-shot importer."""

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    settings = get_settings()
    source_pbf = Path(os.getenv("OSM_RESTRICTIONS_PBF_PATH", "/osm-source/region.osm.pbf"))
    try:
        batch_size = int(os.getenv("OSM_RESTRICTIONS_BATCH_SIZE", "1000"))
    except ValueError as exc:
        raise RestrictionImportError("OSM_RESTRICTIONS_BATCH_SIZE must be an integer") from exc
    if batch_size < 1 or batch_size > 10_000:
        raise RestrictionImportError("OSM_RESTRICTIONS_BATCH_SIZE must be between 1 and 10000")
    _structured_log(
        "restriction_import_started",
        osm_data_version=settings.osm_data_version,
        source_file=source_pbf.name,
    )
    try:
        asyncio.run(
            _run_and_dispose(
                source_pbf=source_pbf,
                osm_data_version=settings.osm_data_version,
                batch_size=batch_size,
            )
        )
    except BaseException as exc:
        _structured_log(
            "restriction_import_failed",
            osm_data_version=settings.osm_data_version,
            error_type=type(exc).__name__,
            error=str(exc),
        )
        raise


if __name__ == "__main__":
    main()
