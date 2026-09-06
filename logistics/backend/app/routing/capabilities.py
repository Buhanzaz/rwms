"""Audited Valhalla 3.8.3 support for OSM truck-routing restrictions."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

VALHALLA_VERSION = "3.8.3"


class RestrictionSupport(StrEnum):
    """How reliably the pinned routing engine applies one OSM restriction family."""

    SUPPORTED = "supported"
    PARTIAL = "partial"
    UNSUPPORTED = "unsupported"


@dataclass(frozen=True, slots=True)
class OsmTruckRestrictionCapability:
    """One operator-facing capability claim backed by a pinned Valhalla source."""

    osm_tag: str
    support: RestrictionSupport
    notes: str


OSM_TRUCK_RESTRICTIONS: tuple[OsmTruckRestrictionCapability, ...] = (
    OsmTruckRestrictionCapability(
        "hgv / access / motor_vehicle / vehicle",
        RestrictionSupport.SUPPORTED,
        "Truck access masks and designated/destination access participate in truck costing.",
    ),
    OsmTruckRestrictionCapability(
        "maxheight / maxheight:physical",
        RestrictionSupport.SUPPORTED,
        "Static height is enforced; maxheight:physical is used as the parsed fallback.",
    ),
    OsmTruckRestrictionCapability(
        "maxwidth / maxwidth:physical",
        RestrictionSupport.SUPPORTED,
        "Static width is enforced; the physical value is used as a parsed fallback.",
    ),
    OsmTruckRestrictionCapability(
        "maxlength",
        RestrictionSupport.SUPPORTED,
        "Static maximum vehicle length is checked against the effective combination.",
    ),
    OsmTruckRestrictionCapability(
        "maxweight",
        RestrictionSupport.SUPPORTED,
        "Static maximum gross weight is checked against the actual routed weight.",
    ),
    OsmTruckRestrictionCapability(
        "maxaxleload / maxaxles",
        RestrictionSupport.SUPPORTED,
        "Configured operational axle load and axle count are checked independently.",
    ),
    OsmTruckRestrictionCapability(
        "oneway / turn restrictions / conditional turn restrictions",
        RestrictionSupport.SUPPORTED,
        "Directed edges and parsed turn restrictions are applied by the graph search.",
    ),
    OsmTruckRestrictionCapability(
        "road classification / closures",
        RestrictionSupport.SUPPORTED,
        "Road class and graph/live closures are honored when the tiles/feed contain them.",
    ),
    OsmTruckRestrictionCapability(
        "hgv:conditional / access:conditional / motor_vehicle:conditional",
        RestrictionSupport.SUPPORTED,
        "Parsed time-conditioned truck/access rules are evaluated for a supplied local time.",
    ),
    OsmTruckRestrictionCapability(
        "destination / delivery exemptions",
        RestrictionSupport.PARTIAL,
        "Access semantics are supported, but dimensional destination/delivery exemptions cover "
        "only the forms parsed by Valhalla 3.8.3.",
    ),
    OsmTruckRestrictionCapability(
        "directional dimensional restrictions",
        RestrictionSupport.PARTIAL,
        "Supported where the corresponding forward/backward tag is parsed into the graph.",
    ),
    OsmTruckRestrictionCapability(
        "maxweight:conditional / maxheight:conditional / maxwidth:conditional / "
        "maxlength:conditional / maxaxleload:conditional",
        RestrictionSupport.UNSUPPORTED,
        "General time-varying numeric limits are not guaranteed by Valhalla 3.8.3.",
    ),
    OsmTruckRestrictionCapability(
        "maxweightrating:hgv",
        RestrictionSupport.UNSUPPORTED,
        "Not parsed as a truck-costing restriction by the pinned engine.",
    ),
    OsmTruckRestrictionCapability(
        "hgv_articulated / trailer",
        RestrictionSupport.UNSUPPORTED,
        "No dedicated tag semantics; combination dimensions still constrain the route.",
    ),
)
