"""Pure classification and extraction tests for OSM truck restrictions."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.routing.restrictions import (
    RestrictionFeatureError,
    RestrictionSupportStatus,
    TruckRestrictionCategory,
    classify_truck_restriction,
    parse_geojsonseq_feature,
)
from app.services import osm_restriction_indexer
from app.services.osm_restriction_indexer import (
    extract_restrictions,
    iter_extracted_restrictions,
    iter_unique_extracted_restrictions,
)


@pytest.mark.parametrize(
    ("tags", "category", "primary_tag", "status"),
    [
        (
            {"hgv": "no"},
            TruckRestrictionCategory.HGV_ACCESS,
            "hgv",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"access": "private"},
            TruckRestrictionCategory.HGV_ACCESS,
            "access",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxheight": "3.8"},
            TruckRestrictionCategory.MAX_HEIGHT,
            "maxheight",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxwidth": "2.5"},
            TruckRestrictionCategory.MAX_WIDTH,
            "maxwidth",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxwidth:physical": "2.45"},
            TruckRestrictionCategory.MAX_WIDTH,
            "maxwidth:physical",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxlength": "12"},
            TruckRestrictionCategory.MAX_LENGTH,
            "maxlength",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxweight": "20"},
            TruckRestrictionCategory.MAX_WEIGHT,
            "maxweight",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"maxweightrating:hgv": "18"},
            TruckRestrictionCategory.MAX_WEIGHT,
            "maxweightrating:hgv",
            RestrictionSupportStatus.UNSUPPORTED,
        ),
        (
            {"maxaxleload": "8"},
            TruckRestrictionCategory.MAX_AXLE_LOAD,
            "maxaxleload",
            RestrictionSupportStatus.SUPPORTED,
        ),
        (
            {"hgv:conditional": "no @ (Mo-Fr 08:00-20:00)"},
            TruckRestrictionCategory.CONDITIONAL,
            "hgv:conditional",
            RestrictionSupportStatus.PARTIAL,
        ),
        (
            {"maxweight:conditional": "12 @ (wet)"},
            TruckRestrictionCategory.CONDITIONAL,
            "maxweight:conditional",
            RestrictionSupportStatus.UNSUPPORTED,
        ),
        (
            {"maxwidth:physical:conditional": "2.4 @ (snow)"},
            TruckRestrictionCategory.CONDITIONAL,
            "maxwidth:physical:conditional",
            RestrictionSupportStatus.UNSUPPORTED,
        ),
        (
            {"trailer": "no"},
            TruckRestrictionCategory.TRAILER_ACCESS,
            "trailer",
            RestrictionSupportStatus.UNSUPPORTED,
        ),
    ],
)
def test_restriction_support_mapping(
    tags: dict[str, str],
    category: TruckRestrictionCategory,
    primary_tag: str,
    status: RestrictionSupportStatus,
) -> None:
    """Every advertised category reports the audited Valhalla support level."""

    result = classify_truck_restriction(tags)
    assert result is not None
    assert (result.category, result.primary_tag, result.support_status) == (
        category,
        primary_tag,
        status,
    )


@pytest.mark.parametrize("value", ["yes", "designated", "permissive"])
def test_unrestricted_access_values_are_not_map_features(value: str) -> None:
    """Positive access declarations do not clutter the restrictions overlay."""

    assert classify_truck_restriction({"access": value}) is None
    assert classify_truck_restriction({"hgv": value}) is None


def test_static_dimension_wins_category_priority_and_all_tags_survive() -> None:
    """A dimensional limit styles a multi-tag object while preserving diagnostics."""

    feature = {
        "type": "Feature",
        "id": "w987",
        "properties": {
            "@id": "way/987",
            "highway": "secondary",
            "name": "Низкий мост",
            "hgv": "no",
            "maxheight:physical": "3.9",
        },
        "geometry": {
            "type": "LineString",
            "coordinates": [[37.60, 55.70], [37.61, 55.71]],
        },
    }
    parsed = parse_geojsonseq_feature(feature)
    assert parsed is not None
    assert (parsed.osm_type, parsed.osm_id) == ("way", 987)
    assert parsed.classification.category == TruckRestrictionCategory.MAX_HEIGHT
    assert parsed.classification.primary_tag == "maxheight:physical"
    assert parsed.tags == {
        "highway": "secondary",
        "name": "Низкий мост",
        "hgv": "no",
        "maxheight:physical": "3.9",
    }
    assert parsed.geometry.geom_type == "LineString"


def test_geojsonseq_parser_handles_record_separator_and_short_node_id(tmp_path: Path) -> None:
    """The importer accepts osmium's RFC 8142 prefix and ``n123`` unique IDs."""

    path = tmp_path / "restrictions.geojsonseq"
    feature = {
        "type": "Feature",
        "id": "n123",
        "properties": {"maxweight": "20", "barrier": "height_restrictor"},
        "geometry": {"type": "Point", "coordinates": [37.6, 55.7]},
    }
    path.write_text("\x1e" + json.dumps(feature) + "\n", encoding="utf-8")
    restrictions = list(iter_extracted_restrictions(path))
    assert len(restrictions) == 1
    assert restrictions[0].osm_type == "node"
    assert restrictions[0].osm_id == 123


def test_multi_extract_union_deduplicates_shared_boundary_objects(tmp_path: Path) -> None:
    """Adjacent regional PBFs must publish one row for a shared OSM identity."""

    shared = {
        "type": "Feature",
        "id": "n123",
        "properties": {"maxweight": "20"},
        "geometry": {"type": "Point", "coordinates": [37.6, 55.7]},
    }
    northwest_only = {
        "type": "Feature",
        "id": "w456",
        "properties": {"hgv": "no"},
        "geometry": {
            "type": "LineString",
            "coordinates": [[30.3, 59.9], [30.4, 60.0]],
        },
    }
    central_path = tmp_path / "central.geojsonseq"
    northwest_path = tmp_path / "northwestern.geojsonseq"
    central_path.write_text(json.dumps(shared) + "\n", encoding="utf-8")
    northwest_path.write_text(
        json.dumps(shared) + "\n" + json.dumps(northwest_only) + "\n",
        encoding="utf-8",
    )

    restrictions = list(iter_unique_extracted_restrictions((central_path, northwest_path)))

    assert [(item.osm_type, item.osm_id) for item in restrictions] == [
        ("node", 123),
        ("way", 456),
    ]


def test_irrelevant_dependency_object_is_skipped_before_geometry_validation() -> None:
    """Tagged dependency nodes without truck tags cannot break an extraction."""

    assert (
        parse_geojsonseq_feature(
            {
                "type": "Feature",
                "id": "n1",
                "properties": {"highway": "traffic_signals"},
                "geometry": None,
            }
        )
        is None
    )


def test_osmium_derived_area_duplicate_is_skipped() -> None:
    """An ``a<id>`` copy of a closed way cannot duplicate its canonical line."""

    assert (
        parse_geojsonseq_feature(
            {
                "type": "Feature",
                "id": "a45764942",
                "properties": {"access": "customers", "amenity": "parking"},
                "geometry": {
                    "type": "MultiPolygon",
                    "coordinates": [[[[37.54, 55.91], [37.55, 55.91], [37.54, 55.91]]]],
                },
            }
        )
        is None
    )


def test_extractor_requests_only_canonical_road_geometries(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Osmium must not emit derived area copies of closed tagged ways."""

    source = tmp_path / "region.osm.pbf"
    source.write_bytes(b"pbf")
    calls: list[tuple[str, ...]] = []

    def fake_run(arguments: tuple[str, ...], *, phase: str) -> None:
        calls.append(arguments)
        if phase == "export":
            Path(arguments[arguments.index("-o") + 1]).write_text("{}\n", encoding="utf-8")

    monkeypatch.setattr(osm_restriction_indexer, "_run_osmium", fake_run)
    extract_restrictions(source, tmp_path)
    assert "--geometry-types=point,linestring" in calls[-1]


def test_relevant_feature_without_supported_identity_fails_explicitly() -> None:
    """Relations/areas cannot be silently misidentified as node or way restrictions."""

    with pytest.raises(RestrictionFeatureError, match="identifier"):
        parse_geojsonseq_feature(
            {
                "type": "Feature",
                "id": "r55",
                "properties": {"hgv": "no"},
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[37.6, 55.7], [37.7, 55.8]],
                },
            }
        )
