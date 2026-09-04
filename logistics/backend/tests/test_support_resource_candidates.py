"""Focused routed support-resource and exact-slot timing invariants."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime, time, timedelta, timezone
from types import SimpleNamespace
from typing import cast
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.integrations.rwms import RwmsPlanningClient
from app.models import Driver, DriverShift, Warehouse
from app.routing import GeoJsonLineString, GeoPoint
from app.schemas.domain import RwmsDriverIdentity, RwmsWarehouseSupportLink
from app.schemas.slot_planning import SlotAvailabilityRequest
from app.services.support_resource_candidates import (
    SupportResourceFact,
    SupportResourceFacts,
    _identity_rank,
    route_support_resource_facts,
)
from app.slot_planning.application import SlotPlanningApplication, SlotPlanningContext
from app.slot_planning.models import (
    DayPlan,
    DriverPlan,
    PlanningReason,
    RoadMetric,
    SlotAvailabilityStatus,
    VehicleLegState,
    WarehouseSlotConfiguration,
)
from app.slot_planning.planner import FeasibleSlotPlanner
from app.slot_planning.routing_adapter import CachedTruckTravelTimeProvider
from tests.factories import make_routable_vehicle, make_shift, make_warehouse

PLANNING_DATE = date(2026, 9, 14)
SUPPORT = GeoPoint(30.0, 59.0)
SERVED = GeoPoint(31.0, 58.0)
CUSTOMER = GeoPoint(31.1, 58.1)


class DirectedRoadProvider:
    """Exact road matrix stub whose values are never derived from coordinates."""

    def __init__(self, inbound_minutes: int, return_minutes: int) -> None:
        self.inbound_minutes = inbound_minutes
        self.return_minutes = return_minutes
        self.geometry_calls: list[tuple[GeoPoint, GeoPoint]] = []

    async def travel_time(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> RoadMetric:
        """Return the configured directed road duration and record no side effects."""

        del departure_at, vehicle_state
        minutes = (
            self.inbound_minutes
            if origin.coordinates == SUPPORT.coordinates
            and destination.coordinates == SERVED.coordinates
            else self.return_minutes
            if origin.coordinates == SERVED.coordinates
            and destination.coordinates == SUPPORT.coordinates
            else 10
        )
        return RoadMetric(minutes * 60, minutes * 1_000)

    async def route_geometry(
        self,
        points: tuple[GeoPoint, ...],
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> GeoJsonLineString:
        """Return a deliberately bent road line so tests reject straight-line synthesis."""

        del departure_at, vehicle_state
        origin, destination = points
        self.geometry_calls.append((origin, destination))
        return {
            "type": "LineString",
            "coordinates": [
                [origin.lon, origin.lat],
                [(origin.lon + destination.lon) / 2, origin.lat + 0.25],
                [destination.lon, destination.lat],
            ],
        }


def _configuration() -> WarehouseSlotConfiguration:
    """Build a no-extra-road-buffer policy with real warehouse operation durations."""

    return WarehouseSlotConfiguration(
        warehouse_id="served",
        point=SERVED,
        timezone="UTC",
        driver_day_start=time(8),
        delivery_day_start=time(9),
        delivery_day_end=time(18),
        hard_finish=time(20),
        load_one_minutes=30,
        load_two_minutes=45,
        unload_per_cabin_minutes=30,
        turnaround_minutes=15,
        travel_time_multiplier=1.0,
        fixed_travel_buffer_minutes=0,
    )


def _identity(
    *,
    employment_type: str = "STAFF",
    availability_kind: str = "HOME",
    available_from: datetime | None = None,
    available_until: datetime | None = None,
) -> RwmsDriverIdentity:
    """Create one externally confirmed worker availability fact."""

    return RwmsDriverIdentity.model_validate(
        {
            "workerId": str(uuid4()),
            "displayName": "Петров Алексей",
            "employmentType": employment_type,
            "phone": "+79990000000" if employment_type == "CONTRACTOR" else None,
            "operationalWarehouseId": str(SUPPORT_WAREHOUSE_ID),
            "availableFrom": available_from.isoformat() if available_from else None,
            "availableUntil": available_until.isoformat() if available_until else None,
            "availabilityKind": availability_kind,
        }
    )


SUPPORT_WAREHOUSE_ID = uuid4()
SERVED_WAREHOUSE_ID = uuid4()


def _link(*, priority: int = 1, contractor_fallback: bool = True) -> RwmsWarehouseSupportLink:
    """Create one owner-filtered support edge with canonical route coordinates."""

    warehouse_shape = {
        "warehouseVersion": 3,
        "city": "Тест",
        "address": None,
        "timeZone": "UTC",
        "representative": False,
        "routingReady": True,
    }
    return RwmsWarehouseSupportLink.model_validate(
        {
            "supportLinkId": str(uuid4()),
            "supportLinkVersion": 2,
            "supportWarehouse": {
                **warehouse_shape,
                "warehouseId": str(SUPPORT_WAREHOUSE_ID),
                "name": "Опорный",
                "latitude": SUPPORT.lat,
                "longitude": SUPPORT.lon,
            },
            "servedWarehouse": {
                **warehouse_shape,
                "warehouseId": str(SERVED_WAREHOUSE_ID),
                "name": "Представительский",
                "latitude": SERVED.lat,
                "longitude": SERVED.lon,
                "representative": True,
            },
            "priority": priority,
            "allowDrivers": True,
            "allowVehicles": True,
            "allowInventory": True,
            "allowDirectFulfillment": True,
            "allowInterwarehouseTransfer": True,
            "allowContractorFallback": contractor_fallback,
            "allowedWeekdays": ["MONDAY"],
            "allowedDates": [],
            "excludedDates": [],
            "serviceStart": "08:00:00",
            "serviceEnd": "20:00:00",
        }
    )


def _fact(identity: RwmsDriverIdentity | None = None) -> SupportResourceFact:
    """Join one real persisted-shape shift to a current RWMS worker fact."""

    vehicle = SimpleNamespace(
        capacity=1,
        default_trailer=None,
        can_use_trailer=False,
    )
    shift = SimpleNamespace(
        id=uuid4(),
        driver_id=uuid4(),
        vehicle_id=uuid4(),
        vehicle=vehicle,
        start_time=time(8),
        end_time=time(20),
    )
    support = SimpleNamespace(
        external_warehouse_id=SUPPORT_WAREHOUSE_ID,
        timezone="UTC",
    )
    return SupportResourceFact(
        link=_link(),
        support_warehouse=cast(Warehouse, support),
        shift=cast(DriverShift, shift),
        identity=identity or _identity(),
        eligible_from=datetime(2026, 9, 14, 8, tzinfo=UTC),
        eligible_until=datetime(2026, 9, 14, 20, tzinfo=UTC),
        planning_date=PLANNING_DATE,
    )


def _facts(*items: SupportResourceFact) -> SupportResourceFacts:
    """Wrap facts without needing persistence or a routing adapter."""

    return SupportResourceFacts(
        candidates=items,
        equipment={},
        source_revision="a" * 64,
        contractor_fallback_allowed=True,
        had_local_staff_fact=any(item.identity.employment_type == "STAFF" for item in items),
        local_identities=(),
    )


def test_identity_rank_compares_aware_availability_as_absolute_instants() -> None:
    """Different UTC offsets cannot make a later incoming fact win lexically."""

    plus_two = timezone(timedelta(hours=2))
    earlier = _identity(
        availability_kind="INCOMING",
        available_from=datetime(2026, 9, 14, 9, 30, tzinfo=plus_two),
        available_until=datetime(2026, 9, 14, 18, tzinfo=plus_two),
    )
    later = _identity(
        availability_kind="INCOMING",
        available_from=datetime(2026, 9, 14, 8, tzinfo=UTC),
        available_until=datetime(2026, 9, 14, 18, tzinfo=UTC),
    )

    assert _identity_rank(earlier) < _identity_rank(later)


@pytest.mark.asyncio
async def test_support_arrival_and_operations_delay_exact_customer_slots() -> None:
    """An 11:20 road arrival becomes usable only after unload, technical, and load work."""

    resolution = await route_support_resource_facts(
        _facts(_fact()),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=200, return_minutes=60),
    )

    assert len(resolution.candidates) == 1
    routed = resolution.candidates[0]
    assert routed.inbound_departure_at == datetime(2026, 9, 14, 8, tzinfo=UTC)
    assert routed.inbound_raw_arrival_at == datetime(
        2026, 9, 14, 11, 20, tzinfo=UTC
    )
    assert routed.inbound_arrival_at == datetime(2026, 9, 14, 11, 20, tzinfo=UTC)
    assert routed.inbound_travel_seconds == 12_000
    assert routed.return_travel_seconds == 3_600
    assert routed.available_at_served == datetime(2026, 9, 14, 12, 35, tzinfo=UTC)
    assert routed.latest_served_finish == datetime(2026, 9, 14, 18, 15, tzinfo=UTC)
    assert routed.reason_codes == (
        PlanningReason.SUPPORT_DRIVER_AVAILABLE,
        PlanningReason.SLOT_AFTER_RESOURCE_ARRIVAL,
    )

    driver = DriverPlan(
        driver_id="support-driver",
        shift_id="support-shift",
        vehicle_id="support-vehicle",
        shift_start=routed.available_at_served,
        shift_end=routed.latest_served_finish,
        vehicle_capacity=1,
        has_trailer=False,
        resource_origin_warehouse_id=str(SUPPORT_WAREHOUSE_ID),
        available_from=routed.available_at_served,
        support_link_id=str(routed.fact.link.support_link_id),
        return_required=True,
        reason_codes=routed.reason_codes,
    )
    result = await FeasibleSlotPlanner(
        DirectedRoadProvider(inbound_minutes=10, return_minutes=10),
        _configuration(),
    ).calculate(
        DayPlan(PLANNING_DATE, (driver,)),
        new_task_id="new",
        address="Клиент",
        point=CUSTOMER,
        cabin_count=1,
        site_cabin_capacity=1,
    )

    assert result.slots[0].status is SlotAvailabilityStatus.UNAVAILABLE
    assert result.slots[1].status is SlotAvailabilityStatus.AVAILABLE


@pytest.mark.asyncio
async def test_support_positioning_retains_exact_truck_road_geometry() -> None:
    """The selected route context carries routed outbound and return lines for the map."""

    provider = DirectedRoadProvider(inbound_minutes=200, return_minutes=60)
    resolution = await route_support_resource_facts(
        _facts(_fact()),
        _configuration(),
        provider,
        provider,
    )

    routed = resolution.candidates[0]
    assert routed.inbound_distance_meters == 200_000
    assert routed.return_distance_meters == 60_000
    assert routed.inbound_geometry is not None
    assert routed.return_geometry is not None
    assert len(routed.inbound_geometry["coordinates"]) == 3
    assert len(routed.return_geometry["coordinates"]) == 3
    assert [
        (origin.coordinates, destination.coordinates)
        for origin, destination in provider.geometry_calls
    ] == [
        (SUPPORT.coordinates, SERVED.coordinates),
        (SERVED.coordinates, SUPPORT.coordinates),
    ]


@pytest.mark.asyncio
async def test_return_route_and_shift_limit_reject_cross_warehouse_candidate() -> None:
    """A road-feasible arrival is still rejected when mandatory return cannot fit."""

    resolution = await route_support_resource_facts(
        _facts(_fact()),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=300, return_minutes=300),
    )

    assert resolution.candidates == ()
    assert PlanningReason.SHIFT_LIMIT_EXCEEDED in resolution.reasons
    assert PlanningReason.CONTRACTOR_REQUIRED in resolution.reasons


@pytest.mark.asyncio
async def test_second_support_warehouse_is_selected_when_first_is_shift_limited() -> None:
    """An unavailable first warehouse cannot hide a feasible lower-priority warehouse."""

    unavailable = _fact()
    first = replace(
        unavailable,
        link=_link(priority=1),
        eligible_until=datetime(2026, 9, 14, 10, tzinfo=UTC),
    )
    available = _fact()
    second_support_id = uuid4()
    second_link = _link(priority=2)
    second_link = second_link.model_copy(
        update={
            "support_warehouse": second_link.support_warehouse.model_copy(
                update={
                    "warehouse_id": second_support_id,
                    "name": "Второй опорный",
                }
            )
        }
    )
    available.support_warehouse.external_warehouse_id = second_support_id
    second = replace(
        available,
        link=second_link,
        eligible_until=datetime(2026, 9, 14, 20, tzinfo=UTC),
    )

    resolution = await route_support_resource_facts(
        _facts(first, second),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
    )

    assert len(resolution.candidates) == 1
    assert resolution.candidates[0].fact.link.support_link_id == second.link.support_link_id
    assert PlanningReason.SHIFT_LIMIT_EXCEEDED in resolution.reasons


@pytest.mark.asyncio
async def test_same_physical_support_candidate_is_deduplicated_across_links() -> None:
    """Repeated directory/link facts remain separately routed but yield one shift candidate."""

    physical = _fact()
    preferred = replace(physical, link=_link(priority=1))
    alternate = replace(physical, link=_link(priority=2))

    resolution = await route_support_resource_facts(
        _facts(alternate, preferred),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
    )

    assert len(resolution.candidates) == 1
    assert resolution.candidates[0].fact.link.support_link_id == preferred.link.support_link_id


@pytest.mark.asyncio
async def test_same_physical_shift_keeps_distinct_served_demand_options() -> None:
    """Demand-aware dedupe cannot let one served member hide another member's link."""

    physical = _fact()
    served_b_id = uuid4()
    served_b_link = _link(priority=2)
    served_b_link = served_b_link.model_copy(
        update={
            "served_warehouse": served_b_link.served_warehouse.model_copy(
                update={"warehouse_id": served_b_id, "name": "Представитель B"}
            )
        }
    )
    served_b = replace(physical, link=served_b_link)

    resolution = await route_support_resource_facts(
        _facts(physical, served_b),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
    )

    assert {
        candidate.fact.link.served_warehouse.warehouse_id
        for candidate in resolution.candidates
    } == {SERVED_WAREHOUSE_ID, served_b_id}


@pytest.mark.asyncio
async def test_support_shift_end_before_start_rolls_to_next_local_day() -> None:
    """Support positioning and mandatory return remain feasible across month midnight."""

    physical = _fact()
    physical.shift.start_time = time(22)
    physical.shift.end_time = time(6)
    overnight = replace(
        physical,
        planning_date=date(2026, 8, 31),
        eligible_from=datetime(2026, 8, 31, 22, tzinfo=UTC),
        eligible_until=datetime(2026, 9, 1, 6, tzinfo=UTC),
    )

    resolution = await route_support_resource_facts(
        _facts(overnight),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
    )

    assert len(resolution.candidates) == 1
    assert resolution.candidates[0].available_at_served == datetime(
        2026,
        8,
        31,
        23,
        45,
        tzinfo=UTC,
    )
    assert resolution.candidates[0].latest_served_finish == datetime(
        2026,
        9,
        1,
        4,
        45,
        tzinfo=UTC,
    )


@pytest.mark.asyncio
async def test_zero_duration_support_shift_fails_fast() -> None:
    """Equal local clock bounds are invalid rather than a fabricated 24-hour shift."""

    physical = _fact()
    physical.shift.start_time = time(8)
    physical.shift.end_time = time(8)

    with pytest.raises(ValueError, match="non-zero duration"):
        await route_support_resource_facts(
            _facts(physical),
            _configuration(),
            DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
        )


@pytest.mark.asyncio
async def test_contractor_is_recommended_but_never_routed_as_internal_capacity() -> None:
    """A contractor's unknown vehicle and workload cannot become a fabricated route shift."""

    contractor = _identity(
        employment_type="CONTRACTOR",
        availability_kind="INCOMING",
        available_from=datetime(2026, 9, 14, 11, tzinfo=UTC),
        available_until=datetime(2026, 9, 14, 18, tzinfo=UTC),
    )
    resolution = await route_support_resource_facts(
        _facts(_fact(contractor)),
        _configuration(),
        DirectedRoadProvider(inbound_minutes=30, return_minutes=30),
    )

    assert resolution.candidates == ()
    assert PlanningReason.CONTRACTOR_REQUIRED in resolution.reasons


@pytest.mark.asyncio
async def test_local_contractor_is_excluded_from_internal_slot_routing() -> None:
    """Even a bounded local contractor remains a direct-handoff resource, not a cycle."""

    worker_id = uuid4()
    identity = RwmsDriverIdentity.model_validate(
        {
            "workerId": str(worker_id),
            "displayName": "Наёмный водитель",
            "employmentType": "CONTRACTOR",
            "phone": "+79990000000",
            "operationalWarehouseId": str(SERVED_WAREHOUSE_ID),
            "availableFrom": "2026-09-14T11:20:00Z",
            "availableUntil": "2026-09-14T18:00:00Z",
            "availabilityKind": "INCOMING",
        }
    )
    driver = DriverPlan(
        "local-contractor",
        "local-shift",
        "local-vehicle",
        datetime(2026, 9, 14, 8, tzinfo=UTC),
        datetime(2026, 9, 14, 20, tzinfo=UTC),
        1,
        False,
        external_worker_id=str(worker_id),
    )
    facts = SupportResourceFacts(
        candidates=(),
        equipment={},
        source_revision="b" * 64,
        contractor_fallback_allowed=True,
        had_local_staff_fact=False,
        local_identities=(identity,),
    )
    context = SlotPlanningContext(
        warehouse=cast(Warehouse, SimpleNamespace()),
        configuration=_configuration(),
        day_plan=DayPlan(PLANNING_DATE, (driver,)),
        equipment={},
        source_revision="c" * 64,
        delivery_price_rubles=None,
        price_isochrone_minutes=None,
        trailer_access_allowed=True,
        support_facts=facts,
    )

    activated = await SlotPlanningApplication(Settings())._activate_support_resources(
        context,
        cast(CachedTruckTravelTimeProvider, DirectedRoadProvider(10, 10)),
    )

    assert activated.day_plan.drivers == ()
    assert PlanningReason.CONTRACTOR_REQUIRED in activated.day_plan.availability_reasons
    assert PlanningReason.NO_LOCAL_DRIVER in activated.day_plan.availability_reasons


@pytest.mark.asyncio
async def test_local_candidate_has_soft_preference_over_equivalent_support_route() -> None:
    """Equivalent slot insertions prefer local work without hard-coding first-match selection."""

    local = DriverPlan(
        "local",
        "local-shift",
        "local-vehicle",
        datetime(2026, 9, 14, 8, tzinfo=UTC),
        datetime(2026, 9, 14, 20, tzinfo=UTC),
        1,
        False,
    )
    support = replace(
        local,
        driver_id="support",
        shift_id="support-shift",
        vehicle_id="support-vehicle",
        support_link_id=str(uuid4()),
        positioning_travel_minutes=120,
        support_priority=1,
    )

    result = await FeasibleSlotPlanner(
        DirectedRoadProvider(inbound_minutes=10, return_minutes=10),
        _configuration(),
    ).calculate(
        DayPlan(PLANNING_DATE, (support, local)),
        new_task_id="new",
        address="Клиент",
        point=CUSTOMER,
        cabin_count=1,
        site_cabin_capacity=1,
    )

    assert result.slots[0].best_candidate is not None
    assert result.slots[0].best_candidate.driver_plan.driver_id == "local"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_external_link_or_driver_change_advances_slot_source_revision(
    db_session: AsyncSession,
) -> None:
    """Hold fencing hashes current support-link and worker facts on every context reload."""

    served = await make_warehouse(
        db_session,
        name="Representative",
        latitude=SERVED.lat,
        longitude=SERVED.lon,
        timezone="UTC",
        default_planning_date=PLANNING_DATE,
    )
    served.representative = True
    served.external_warehouse_id = SERVED_WAREHOUSE_ID
    support = await make_warehouse(
        db_session,
        name="Support",
        latitude=SUPPORT.lat,
        longitude=SUPPORT.lon,
        timezone="UTC",
        default_planning_date=PLANNING_DATE,
    )
    support.external_warehouse_id = SUPPORT_WAREHOUSE_ID
    worker_id = uuid4()
    driver = Driver(
        warehouse_id=support.id,
        external_worker_id=worker_id,
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Петров Алексей",
        active=True,
    )
    db_session.add(driver)
    vehicle = await make_routable_vehicle(db_session, support)
    await db_session.flush()
    await make_shift(
        db_session,
        support,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    await db_session.flush()

    served_external_id = served.external_warehouse_id
    support_external_id = support.external_warehouse_id
    base_link = _link()
    link = base_link.model_copy(
        update={
            "support_warehouse": base_link.support_warehouse.model_copy(
                update={"warehouse_id": support_external_id}
            ),
            "served_warehouse": base_link.served_warehouse.model_copy(
                update={"warehouse_id": served_external_id}
            ),
        }
    )
    identity = RwmsDriverIdentity.model_validate(
        {
            "workerId": str(worker_id),
            "displayName": "Петров Алексей",
            "employmentType": "STAFF",
            "phone": None,
            "operationalWarehouseId": str(support_external_id),
            "availableFrom": None,
            "availableUntil": None,
            "availabilityKind": "HOME",
        }
    )
    client = SimpleNamespace()

    async def list_support_network(
        warehouse_id: object,
    ) -> list[RwmsWarehouseSupportLink]:
        """Expose the real adjacent edge used to resolve the selected planning group."""

        assert warehouse_id in {served_external_id, support_external_id}
        return [link]

    async def list_support_links(
        served_warehouse_id: object,
        *,
        at: datetime,
    ) -> list[RwmsWarehouseSupportLink]:
        """Return the same calendar-filtered edge for each standard slot instant."""

        del at
        assert served_warehouse_id == served_external_id
        return [link]

    current_identity = identity

    async def list_drivers(
        warehouse_id: object,
        *,
        at: datetime,
        include_incoming: bool,
    ) -> list[RwmsDriverIdentity]:
        """Return mutable external availability so revision fencing can be observed."""

        del at
        assert include_incoming is True
        if warehouse_id != support_external_id:
            return []
        return [current_identity]

    client.list_support_network = list_support_network
    client.list_support_links = list_support_links
    client.list_drivers = list_drivers
    settings = Settings(
        routing_provider="mock",
        rwms_sync_enabled=True,
        rwms_logistics_base_url="http://rwms.invalid",
        rwms_token_url="http://auth.invalid/token",
        rwms_client_id="test-client",
        rwms_client_secret="test-secret",
    )
    application = SlotPlanningApplication(settings, cast(RwmsPlanningClient, client))
    command = SlotAvailabilityRequest(
        warehouse_id=served.id,
        date=PLANNING_DATE,
        address="Клиент",
        latitude=CUSTOMER.lat,
        longitude=CUSTOMER.lon,
        cabin_count=1,
        site_cabin_capacity=1,
    )

    first = await application._load_context(db_session, command)
    current_identity = identity.model_copy(
        update={"available_until": datetime(2026, 9, 14, 17, tzinfo=UTC)}
    )
    changed = await application._load_context(db_session, command)

    assert first.support_facts is not None
    assert len(first.support_facts.candidates) == 1
    assert changed.source_revision != first.source_revision
