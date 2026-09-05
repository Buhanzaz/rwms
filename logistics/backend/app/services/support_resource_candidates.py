"""Resolve routed, non-mutating support-warehouse driver candidates."""

from __future__ import annotations

import json
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from hashlib import sha256
from math import ceil
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.integrations.rwms import RwmsPlanningClient
from app.models import DriverShift, Vehicle, Warehouse
from app.routing import GeoJsonLineString, GeoPoint
from app.schemas.domain import RwmsDriverIdentity, RwmsWarehouseSupportLink
from app.services.resource_incidents import NO_RESOURCE_RESTRICTIONS, DayResourceRestrictions
from app.slot_planning.configuration import vehicle_has_available_trailer
from app.slot_planning.models import (
    PlanningReason,
    TravelTimeUnavailable,
    VehicleLegState,
    WarehouseSlotConfiguration,
)
from app.slot_planning.ports import RouteGeometryProvider, TravelTimeProvider
from app.slot_planning.routing_adapter import VehicleEquipmentSnapshot

EquipmentFactory = Callable[[Vehicle, Mapping[str, object]], VehicleEquipmentSnapshot]


@dataclass(frozen=True, slots=True)
class SupportResourceFact:
    """One real support shift joined to one current RWMS worker fact."""

    link: RwmsWarehouseSupportLink
    support_warehouse: Warehouse
    shift: DriverShift
    identity: RwmsDriverIdentity
    eligible_from: datetime
    eligible_until: datetime | None
    planning_date: date


@dataclass(frozen=True, slots=True)
class SupportResourceFacts:
    """Side-effect-free support facts and equipment loaded before road evaluation."""

    candidates: tuple[SupportResourceFact, ...]
    equipment: dict[str, VehicleEquipmentSnapshot]
    source_revision: str
    contractor_fallback_allowed: bool
    had_local_staff_fact: bool
    local_identities: tuple[RwmsDriverIdentity, ...]
    resource_restrictions: DayResourceRestrictions = NO_RESOURCE_RESTRICTIONS


@dataclass(frozen=True, slots=True)
class RoutedSupportResource:
    """One support resource whose inbound work and mandatory return fit its shift."""

    fact: SupportResourceFact
    inbound_departure_at: datetime
    inbound_raw_arrival_at: datetime
    inbound_arrival_at: datetime
    inbound_travel_seconds: int
    return_travel_seconds: int
    available_at_served: datetime
    latest_served_finish: datetime
    inbound_travel_minutes: int
    return_travel_minutes: int
    positioning_distance_meters: int
    inbound_distance_meters: int
    return_distance_meters: int
    inbound_geometry: GeoJsonLineString | None
    return_geometry: GeoJsonLineString | None
    reason_codes: tuple[PlanningReason, ...]


@dataclass(frozen=True, slots=True)
class SupportResourceResolution:
    """Feasible routed support resources plus stable absence explanations."""

    candidates: tuple[RoutedSupportResource, ...]
    reasons: tuple[PlanningReason, ...]


async def load_support_resource_facts(
    session: AsyncSession,
    client: RwmsPlanningClient,
    served_warehouse: Warehouse,
    planning_date: date,
    planning_instants: Iterable[datetime],
    equipment_factory: EquipmentFactory,
    *,
    resource_restrictions: DayResourceRestrictions = NO_RESOURCE_RESTRICTIONS,
) -> SupportResourceFacts:
    """Load owner-filtered links, exact workers, and existing local shifts without writes."""

    instants = tuple(sorted(set(planning_instants)))
    if not instants:
        raise ValueError("at least one planning instant is required")
    if any(instant.utcoffset() is None for instant in instants):
        raise ValueError("planning instants must be timezone-aware")

    eligible_by_link: dict[UUID, tuple[RwmsWarehouseSupportLink, list[datetime]]] = {}
    query_facts: list[dict[str, object]] = []
    local_identities_by_worker: dict[UUID, RwmsDriverIdentity] = {}
    for instant in instants:
        local_identities = await client.list_drivers(
            served_warehouse.external_warehouse_id,
            at=instant,
            include_incoming=True,
        )
        query_facts.append(
            {
                "servedWarehouseId": str(served_warehouse.external_warehouse_id),
                "at": instant.isoformat(),
                "localDrivers": [
                    identity.model_dump(mode="json", by_alias=True)
                    for identity in local_identities
                ],
            }
        )
        for identity in local_identities:
            if (
                identity.operational_warehouse_id
                != served_warehouse.external_warehouse_id
                or identity.employment_type != "STAFF"
                or not _confirmed_identity(identity)
            ):
                continue
            previous_identity = local_identities_by_worker.get(identity.worker_id)
            if previous_identity is None or _identity_rank(identity) < _identity_rank(
                previous_identity
            ):
                local_identities_by_worker[identity.worker_id] = identity
        links = await client.list_support_links(
            served_warehouse.external_warehouse_id,
            at=instant,
        )
        query_facts.append(
            {
                "at": instant.isoformat(),
                "links": [link.model_dump(mode="json", by_alias=True) for link in links],
            }
        )
        for link in links:
            if not link.allow_drivers or not link.allow_vehicles:
                continue
            current = eligible_by_link.get(link.support_link_id)
            if current is None:
                eligible_by_link[link.support_link_id] = (link, [instant])
            else:
                current[1].append(instant)

    facts: list[SupportResourceFact] = []
    equipment: dict[str, VehicleEquipmentSnapshot] = {}
    contractor_fallback_allowed = served_warehouse.representative
    had_local_staff_fact = False
    for link, eligible_instants in eligible_by_link.values():
        contractor_fallback_allowed |= link.allow_contractor_fallback
        support_identity = link.support_warehouse
        if not support_identity.routing_ready:
            continue
        assert support_identity.latitude is not None and support_identity.longitude is not None
        support = await session.scalar(
            select(Warehouse)
            .where(Warehouse.external_warehouse_id == support_identity.warehouse_id)
            .execution_options(populate_existing=True)
            .options(
                selectinload(Warehouse.shifts).selectinload(DriverShift.driver),
                selectinload(Warehouse.shifts)
                .selectinload(DriverShift.vehicle)
                .selectinload(Vehicle.default_trailer),
                selectinload(Warehouse.shifts)
                .selectinload(DriverShift.vehicle)
                .selectinload(Vehicle.load_profiles),
            )
        )
        if support is None or not support.routing_ready:
            continue

        identities_by_worker: dict[UUID, list[tuple[datetime, RwmsDriverIdentity]]] = {}
        for instant in sorted(set(eligible_instants)):
            identities = await client.list_drivers(
                support_identity.warehouse_id,
                at=instant,
                include_incoming=True,
            )
            query_facts.append(
                {
                    "supportWarehouseId": str(support_identity.warehouse_id),
                    "at": instant.isoformat(),
                    "drivers": [
                        identity.model_dump(mode="json", by_alias=True)
                        for identity in identities
                    ],
                }
            )
            for identity in identities:
                if identity.operational_warehouse_id != support_identity.warehouse_id:
                    continue
                identities_by_worker.setdefault(identity.worker_id, []).append(
                    (instant, identity)
                )

        served_zone = ZoneInfo(link.served_warehouse.timezone)
        link_available_from, link_available_until = _local_interval(
            planning_date,
            link.service_start or time.min,
            link.service_end,
            served_zone,
        )
        for shift in support.shifts:
            if not (
                shift.active
                and shift.date_from <= planning_date <= shift.date_to
                and shift.driver.active
                and shift.vehicle.active
                and resource_restrictions.allows_shift(shift.id, shift.vehicle_id)
                and shift.driver.external_worker_id is not None
            ):
                continue
            matched = identities_by_worker.get(shift.driver.external_worker_id, ())
            for _, identity in matched:
                if identity.employment_type != "STAFF" or not _confirmed_identity(identity):
                    continue
                had_local_staff_fact = True
                fact = SupportResourceFact(
                    link=link,
                    support_warehouse=support,
                    shift=shift,
                    identity=identity,
                    eligible_from=link_available_from,
                    eligible_until=link_available_until,
                    planning_date=planning_date,
                )
                facts.append(fact)
                equipment[str(shift.vehicle_id)] = equipment_factory(
                    shift.vehicle,
                    dict(served_warehouse.settings),
                )

    unique: dict[tuple[UUID, UUID, UUID], SupportResourceFact] = {}
    for fact in facts:
        key = (fact.link.support_link_id, fact.shift.id, fact.identity.worker_id)
        previous_fact = unique.get(key)
        if previous_fact is None or _identity_rank(fact.identity) < _identity_rank(
            previous_fact.identity
        ):
            unique[key] = fact
    ordered = tuple(
        sorted(
            unique.values(),
            key=lambda item: (
                item.link.priority,
                item.eligible_from,
                str(item.shift.id),
            ),
        )
    )
    revision_payload = {
        "resourceIncidents": {
            "vehicles": sorted(resource_restrictions.vehicle_ids),
            "shifts": sorted(resource_restrictions.shift_ids),
            "trailers": sorted(resource_restrictions.trailer_ids),
        },
        "queries": query_facts,
        "localSupportFacts": [
            {
                "linkId": str(fact.link.support_link_id),
                "linkVersion": fact.link.support_link_version,
                "warehouseId": str(fact.support_warehouse.external_warehouse_id),
                "warehouseVersion": fact.support_warehouse.external_warehouse_version,
                "warehouseCoordinates": [
                    fact.support_warehouse.longitude,
                    fact.support_warehouse.latitude,
                ],
                "shiftId": str(fact.shift.id),
                "driverId": str(fact.shift.driver_id),
                "workerId": str(fact.identity.worker_id),
                "vehicleId": str(fact.shift.vehicle_id),
                "dateFrom": fact.shift.date_from.isoformat(),
                "dateTo": fact.shift.date_to.isoformat(),
                "start": fact.shift.start_time.isoformat(),
                "end": fact.shift.end_time.isoformat(),
                "active": fact.shift.active,
                "driverActive": fact.shift.driver.active,
                "vehicleActive": fact.shift.vehicle.active,
                "vehicleCapacity": fact.shift.vehicle.capacity,
                "trailerId": (
                    str(fact.shift.vehicle.default_trailer_id)
                    if fact.shift.vehicle.default_trailer_id is not None
                    else None
                ),
            }
            for fact in ordered
        ],
    }
    revision = sha256(
        json.dumps(
            revision_payload,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
    ).hexdigest()
    return SupportResourceFacts(
        resource_restrictions=resource_restrictions,
        candidates=ordered,
        equipment=equipment,
        source_revision=revision,
        contractor_fallback_allowed=contractor_fallback_allowed,
        had_local_staff_fact=had_local_staff_fact,
        local_identities=tuple(
            sorted(
                local_identities_by_worker.values(),
                key=lambda item: str(item.worker_id),
            )
        ),
    )


async def route_support_resource_facts(
    facts: SupportResourceFacts,
    configuration: WarehouseSlotConfiguration,
    provider: TravelTimeProvider,
    geometry_provider: RouteGeometryProvider | None = None,
) -> SupportResourceResolution:
    """Route every support shift and optionally retain exact geometry for map rendering."""

    routed: list[RoutedSupportResource] = []
    shift_limited = False
    for fact in facts.candidates:
        if fact.identity.employment_type != "STAFF":
            # Contractors receive a direct task handoff. Their unknown vehicle and
            # workload must never be fabricated as an internal routed shift.
            continue
        shift = fact.shift
        zone = ZoneInfo(fact.support_warehouse.timezone)
        shift_start, shift_end = _local_interval(
            fact.planning_date,
            shift.start_time,
            shift.end_time,
            zone,
        )
        assert shift_end is not None
        departure = max(
            shift_start,
            fact.eligible_from,
            fact.identity.available_from or shift_start,
        )
        resource_end = min(
            shift_end,
            fact.identity.available_until or shift_end,
            fact.eligible_until or shift_end,
        )
        if departure >= resource_end:
            shift_limited = True
            continue
        support_point = GeoPoint(
            fact.link.support_warehouse.longitude,  # type: ignore[arg-type]
            fact.link.support_warehouse.latitude,  # type: ignore[arg-type]
            is_city=True,
        )
        has_trailer = vehicle_has_available_trailer(
            shift.vehicle, facts.resource_restrictions.trailer_ids,
        )
        vehicle_state = VehicleLegState(
            vehicle_id=str(shift.vehicle_id),
            trailer_attached=has_trailer and shift.vehicle.capacity > 1,
            current_load=0,
            trip_peak_load=min(2, shift.vehicle.capacity) if has_trailer else 1,
        )
        try:
            inbound = await provider.travel_time(
                support_point,
                configuration.point,
                departure,
                vehicle_state,
            )
            inbound_seconds = _buffered_seconds(inbound.travel_seconds, configuration)
            arrival = departure + timedelta(seconds=inbound_seconds)
            available = arrival + timedelta(
                minutes=(
                    configuration.unload_per_cabin_minutes
                    + configuration.turnaround_minutes
                    + configuration.load_one_minutes
                )
            )
            reverse = await provider.travel_time(
                configuration.point,
                support_point,
                resource_end,
                vehicle_state,
            )
            reverse_seconds = _buffered_seconds(reverse.travel_seconds, configuration)
        except TravelTimeUnavailable:
            continue
        latest_finish = resource_end - timedelta(
            seconds=reverse_seconds,
            minutes=(
                configuration.unload_per_cabin_minutes
                + configuration.turnaround_minutes
            ),
        )
        if available >= latest_finish:
            shift_limited = True
            continue
        inbound_geometry: GeoJsonLineString | None = None
        return_geometry: GeoJsonLineString | None = None
        if geometry_provider is not None:
            try:
                inbound_geometry = await geometry_provider.route_geometry(
                    (support_point, configuration.point),
                    departure,
                    vehicle_state,
                )
                return_geometry = await geometry_provider.route_geometry(
                    (configuration.point, support_point),
                    latest_finish,
                    vehicle_state,
                )
            except TravelTimeUnavailable:
                # Exact metric feasibility remains authoritative. A temporary geometry
                # enrichment failure must not discard an otherwise executable resource.
                inbound_geometry = None
                return_geometry = None
        candidate_reasons = [
            PlanningReason.SUPPORT_DRIVER_AVAILABLE,
            PlanningReason.SLOT_AFTER_RESOURCE_ARRIVAL,
        ]
        routed.append(
            RoutedSupportResource(
                fact=fact,
                inbound_departure_at=departure,
                inbound_raw_arrival_at=departure
                + timedelta(seconds=inbound.travel_seconds),
                inbound_arrival_at=arrival,
                inbound_travel_seconds=inbound_seconds,
                return_travel_seconds=reverse_seconds,
                available_at_served=available,
                latest_served_finish=latest_finish,
                inbound_travel_minutes=ceil(inbound_seconds / 60),
                return_travel_minutes=ceil(reverse_seconds / 60),
                positioning_distance_meters=(
                    inbound.distance_meters + reverse.distance_meters
                ),
                inbound_distance_meters=inbound.distance_meters,
                return_distance_meters=reverse.distance_meters,
                inbound_geometry=inbound_geometry,
                return_geometry=return_geometry,
                reason_codes=tuple(candidate_reasons),
            )
        )
    routed = list(deduplicate_routed_support_resources(routed))
    resolution_reasons: list[PlanningReason] = []
    if shift_limited:
        resolution_reasons.append(PlanningReason.SHIFT_LIMIT_EXCEEDED)
    if not routed and facts.contractor_fallback_allowed:
        resolution_reasons.append(PlanningReason.CONTRACTOR_REQUIRED)
    return SupportResourceResolution(
        candidates=tuple(routed),
        reasons=tuple(resolution_reasons),
    )


def deduplicate_routed_support_resources(
    candidates: Iterable[RoutedSupportResource],
) -> tuple[RoutedSupportResource, ...]:
    """Keep the best exact-road variant for each physical driver shift and vehicle.

    One worker can be visible through several incoming support links or repeated
    planning instants.  Every link is routed first so an unavailable high-priority
    edge cannot hide a feasible lower-priority edge; only then is the physical
    resource collapsed to one deterministic candidate per served warehouse. A
    physical shift that can serve different demand members stays as distinct
    demand-aware route options until the planner chooses a cycle.
    """

    best_by_resource: dict[
        tuple[UUID, UUID, UUID, UUID, UUID],
        RoutedSupportResource,
    ] = {}
    for candidate in candidates:
        fact = candidate.fact
        key = (
            fact.shift.id,
            fact.shift.driver_id,
            fact.shift.vehicle_id,
            fact.identity.worker_id,
            fact.link.served_warehouse.warehouse_id,
        )
        previous = best_by_resource.get(key)
        if previous is None or _routed_candidate_rank(candidate) < _routed_candidate_rank(
            previous
        ):
            best_by_resource[key] = candidate
    return tuple(
        sorted(
            best_by_resource.values(),
            key=_routed_candidate_rank,
        )
    )


def _routed_candidate_rank(candidate: RoutedSupportResource) -> tuple[object, ...]:
    """Rank already routed variants by usable time, directed road work, and link tie-breaks."""

    return (
        candidate.available_at_served,
        candidate.inbound_travel_minutes + candidate.return_travel_minutes,
        candidate.positioning_distance_meters,
        candidate.fact.link.priority,
        str(candidate.fact.link.support_link_id),
        str(candidate.fact.shift.id),
    )


def _local_interval(
    planning_date: date,
    start: time,
    end: time | None,
    zone: ZoneInfo,
) -> tuple[datetime, datetime | None]:
    """Resolve warehouse-local clock bounds, carrying an overnight end to the next day."""

    start_at = datetime.combine(planning_date, start, tzinfo=zone)
    if end is None:
        return start_at, None
    if end == start:
        raise ValueError("local interval must have a non-zero duration")
    end_date = planning_date + timedelta(days=1) if end < start else planning_date
    return start_at, datetime.combine(end_date, end, tzinfo=zone)


def _confirmed_identity(identity: RwmsDriverIdentity) -> bool:
    """Accept only incoming staff facts with an explicit bounded availability interval."""

    if identity.availability_kind == "INCOMING" and (
        identity.available_from is None or identity.available_until is None
    ):
        return False
    return True


def _identity_rank(identity: RwmsDriverIdentity) -> tuple[object, ...]:
    """Prefer a current home/assignment fact over a later incoming duplicate."""

    available_from = (
        identity.available_from.astimezone(UTC)
        if identity.available_from is not None
        else datetime.min.replace(tzinfo=UTC)
    )
    return (
        {"HOME": 0, "ACTIVE_ASSIGNMENT": 1, "INCOMING": 2}[identity.availability_kind],
        identity.available_from is not None,
        available_from,
    )


def _buffered_seconds(
    travel_seconds: int,
    configuration: WarehouseSlotConfiguration,
) -> int:
    """Apply the exact same conservative travel policy as the customer planner."""

    return ceil(
        travel_seconds * configuration.travel_time_multiplier
        + configuration.fixed_travel_buffer_minutes * 60
    )
