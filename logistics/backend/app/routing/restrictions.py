"""Classification and GeoJSONSeq parsing for OSM truck restrictions."""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from shapely.geometry import shape
from shapely.geometry.base import BaseGeometry


class TruckRestrictionCategory(StrEnum):
    """Stable map categories understood by the backend and frontend."""

    HGV_ACCESS = "HGV_ACCESS"
    MAX_HEIGHT = "MAX_HEIGHT"
    MAX_WIDTH = "MAX_WIDTH"
    MAX_LENGTH = "MAX_LENGTH"
    MAX_WEIGHT = "MAX_WEIGHT"
    MAX_AXLE_LOAD = "MAX_AXLE_LOAD"
    CONDITIONAL = "CONDITIONAL"
    TRAILER_ACCESS = "TRAILER_ACCESS"


class RestrictionSupportStatus(StrEnum):
    """Truthful Valhalla support level for one source restriction."""

    SUPPORTED = "SUPPORTED"
    PARTIAL = "PARTIAL"
    UNSUPPORTED = "UNSUPPORTED"


@dataclass(frozen=True, slots=True)
class RestrictionClassification:
    """Selected primary category and support status for a set of OSM tags."""

    category: TruckRestrictionCategory
    primary_tag: str
    value: str
    support_status: RestrictionSupportStatus


@dataclass(frozen=True, slots=True)
class ParsedTruckRestriction:
    """Validated OSM object ready for transactional PostGIS persistence."""

    osm_type: str
    osm_id: int
    classification: RestrictionClassification
    tags: dict[str, str]
    geometry: BaseGeometry


class RestrictionFeatureError(ValueError):
    """Signal malformed relevant extraction output that must abort an import."""


_UNRESTRICTED_ACCESS_VALUES = frozenset({"yes", "designated", "permissive"})
_STATIC_DIMENSION_TAGS: tuple[
    tuple[str, TruckRestrictionCategory, RestrictionSupportStatus], ...
] = (
    (
        "maxheight:physical",
        TruckRestrictionCategory.MAX_HEIGHT,
        RestrictionSupportStatus.SUPPORTED,
    ),
    ("maxheight", TruckRestrictionCategory.MAX_HEIGHT, RestrictionSupportStatus.SUPPORTED),
    (
        "maxwidth:physical",
        TruckRestrictionCategory.MAX_WIDTH,
        RestrictionSupportStatus.SUPPORTED,
    ),
    ("maxwidth", TruckRestrictionCategory.MAX_WIDTH, RestrictionSupportStatus.SUPPORTED),
    ("maxlength", TruckRestrictionCategory.MAX_LENGTH, RestrictionSupportStatus.SUPPORTED),
    ("maxweight", TruckRestrictionCategory.MAX_WEIGHT, RestrictionSupportStatus.SUPPORTED),
    (
        "maxweightrating:hgv",
        TruckRestrictionCategory.MAX_WEIGHT,
        RestrictionSupportStatus.UNSUPPORTED,
    ),
    (
        "maxaxleload",
        TruckRestrictionCategory.MAX_AXLE_LOAD,
        RestrictionSupportStatus.SUPPORTED,
    ),
)
_STATIC_ACCESS_TAGS = ("hgv", "access", "motor_vehicle", "vehicle")
_PARTIAL_CONDITIONAL_TAGS = (
    "hgv:conditional",
    "access:conditional",
    "motor_vehicle:conditional",
    "vehicle:conditional",
)
_UNSUPPORTED_CONDITIONAL_TAGS = (
    "maxheight:physical:conditional",
    "maxheight:conditional",
    "maxwidth:physical:conditional",
    "maxwidth:conditional",
    "maxlength:conditional",
    "maxweight:conditional",
    "maxaxleload:conditional",
)
_TRAILER_TAGS = ("hgv_articulated", "trailer")
_GEOMETRY_TYPES = frozenset({"Point", "LineString", "MultiLineString", "Polygon"})
_LONG_ID_PATTERN = re.compile(r"^(node|way)/([1-9][0-9]*)$")
_SHORT_ID_PATTERN = re.compile(r"^([nw])([1-9][0-9]*)$")


def classify_truck_restriction(
    tags: Mapping[str, str],
) -> RestrictionClassification | None:
    """Classify relevant tags using documented priority and support semantics."""

    for tag, category, support_status in _STATIC_DIMENSION_TAGS:
        value = tags.get(tag)
        if value is not None and value.strip():
            return RestrictionClassification(
                category=category,
                primary_tag=tag,
                value=value,
                support_status=support_status,
            )

    for tag in _STATIC_ACCESS_TAGS:
        value = tags.get(tag)
        if value is None or not value.strip():
            continue
        if value.strip().lower() in _UNRESTRICTED_ACCESS_VALUES:
            continue
        return RestrictionClassification(
            category=TruckRestrictionCategory.HGV_ACCESS,
            primary_tag=tag,
            value=value,
            support_status=RestrictionSupportStatus.SUPPORTED,
        )

    for tag in _PARTIAL_CONDITIONAL_TAGS:
        value = tags.get(tag)
        if value is not None and value.strip():
            return RestrictionClassification(
                category=TruckRestrictionCategory.CONDITIONAL,
                primary_tag=tag,
                value=value,
                support_status=RestrictionSupportStatus.PARTIAL,
            )

    for tag in _UNSUPPORTED_CONDITIONAL_TAGS:
        value = tags.get(tag)
        if value is not None and value.strip():
            return RestrictionClassification(
                category=TruckRestrictionCategory.CONDITIONAL,
                primary_tag=tag,
                value=value,
                support_status=RestrictionSupportStatus.UNSUPPORTED,
            )

    for tag in _TRAILER_TAGS:
        value = tags.get(tag)
        if value is not None and value.strip():
            return RestrictionClassification(
                category=TruckRestrictionCategory.TRAILER_ACCESS,
                primary_tag=tag,
                value=value,
                support_status=RestrictionSupportStatus.UNSUPPORTED,
            )
    return None


def parse_geojsonseq_feature(feature: Mapping[str, Any]) -> ParsedTruckRestriction | None:
    """Parse one osmium GeoJSON feature, skipping objects without relevant tags."""

    properties_raw = feature.get("properties")
    if not isinstance(properties_raw, Mapping):
        return None
    tags = {
        str(key): str(value)
        for key, value in properties_raw.items()
        if not str(key).startswith("@") and value is not None
    }
    classification = classify_truck_restriction(tags)
    if classification is None:
        return None

    osm_identity = _parse_osm_identity(properties_raw.get("@id"), feature.get("id"))
    if osm_identity is None:
        raise RestrictionFeatureError("Relevant feature has no supported node/way identifier")
    osm_type, osm_id = osm_identity

    geometry_raw = feature.get("geometry")
    if not isinstance(geometry_raw, Mapping):
        raise RestrictionFeatureError(f"{osm_type}/{osm_id} has no geometry")
    try:
        geometry = shape(geometry_raw)
    except (AttributeError, KeyError, TypeError, ValueError) as exc:
        raise RestrictionFeatureError(f"{osm_type}/{osm_id} has invalid geometry") from exc
    if geometry.is_empty or geometry.geom_type not in _GEOMETRY_TYPES:
        raise RestrictionFeatureError(
            f"{osm_type}/{osm_id} has unsupported geometry {geometry.geom_type}"
        )
    return ParsedTruckRestriction(
        osm_type=osm_type,
        osm_id=osm_id,
        classification=classification,
        tags=tags,
        geometry=geometry,
    )


def _parse_osm_identity(*candidates: object) -> tuple[str, int] | None:
    """Resolve osmium's long ``@id`` or unique ``n123``/``w123`` feature ID."""

    for candidate in candidates:
        if not isinstance(candidate, str):
            continue
        value = candidate.strip()
        long_match = _LONG_ID_PATTERN.fullmatch(value)
        if long_match:
            return long_match.group(1), int(long_match.group(2))
        short_match = _SHORT_ID_PATTERN.fullmatch(value)
        if short_match:
            return ("node" if short_match.group(1) == "n" else "way"), int(short_match.group(2))
    return None
