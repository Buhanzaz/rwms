"""Exact-cycle tests proving that load state changes the routed truck profile."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from itertools import pairwise

import pytest

from app.planner import (
    CandidateRouteRejected,
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlannedLeg,
    PlanningInput,
    PlanningSettings,
    PlanningTask,
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    RouteStop,
    StopType,
    TaskType,
    UnassignedReasonCode,
    ValidationWarningCode,
    Vehicle,
    Warehouse,
)
from app.routing import (
    GeoPoint,
    MockRoutingProvider,
    RouteGeometry,
    RouteLeg,
    RoutingSettings,
)
from app.routing.models import TravelMatrix
from app.routing.truck_profile import (
    CargoDimensions,
    EffectiveTruckProfile,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckConfigurationType,
    VehicleRoutingSpec,
)
from app.routing.valhalla import RoutingProfileIncompleteError
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.truck_cycle_router import ExactTruckCycleRouter

START = datetime(2026, 8, 25, 8, tzinfo=UTC)
DEPOT = GeoPoint(lon=37.4, lat=55.75)
FIRST = GeoPoint(lon=37.5, lat=55.75)
SECOND = GeoPoint(lon=37.6, lat=55.75)
CARGO = CargoDimensions(length_mm=6_000, width_mm=2_400, height_mm=2_400, weight_kg=2_500)


class RecordingTruckProvider:
    """Record exact profiles and return distinct metrics for long combinations."""

    def __init__(self) -> None:
        self.profiles: list[EffectiveTruckProfile] = []
        self.departures: list[datetime] = []

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> TravelMatrix:
        """Remain unused because candidate ranking owns the approximate matrix."""

        raise AssertionError((points, departure_at, profile))

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Return a deterministic longer route whenever a trailer is attached."""

        assert departure_at is not None
        assert profile is not None
        self.profiles.append(profile)
        self.departures.append(departure_at)
        distance = 20_000 if profile.length_meters > 12 else 10_000
        seconds = 1_200 if profile.length_meters > 12 else 600
        geometry = {
            "type": "LineString",
            "coordinates": [list(points[0].coordinates), list(points[1].coordinates)],
        }
        leg = RouteLeg(0, 1, distance, seconds, geometry)
        return RouteGeometry(geometry, (leg,), distance, seconds)


class DepartureSensitiveTruckProvider(RecordingTruckProvider):
    """Shorten a leg after noon to prove delayed departures are rerouted."""

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Return twenty minutes before noon and ten minutes afterwards."""

        route = await super().get_route(points, departure_at, profile=profile)
        assert departure_at is not None
        seconds = 1_200 if departure_at.hour < 12 else 600
        leg = replace(route.legs[0], travel_seconds=seconds)
        return replace(route, legs=(leg,), total_travel_seconds=seconds)


class NoSafeRouteProvider(RecordingTruckProvider):
    """Reject every exact truck route without exposing a car alternative."""

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Raise the same stable code as the production Valhalla adapter."""

        del points, departure_at, profile

        class NoSafeRoute(RuntimeError):
            """Test-only provider failure with the production reason code."""

            code = "NO_SAFE_ROUTE"

        raise NoSafeRoute("restricted truck graph has no path")


def _vehicle() -> Vehicle:
    """Build one complete truck, trailer, and operational axle profile set."""

    trailer = TrailerSpec(
        trailer_id="trailer-1",
        tare_weight_kg=3_500,
        max_gross_weight_kg=10_000,
        length_mm=8_000,
        width_mm=2_500,
        height_mm=1_600,
        platform_length_mm=6_500,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=900,
        max_platform_payload_kg=5_000,
        payload_capacity_kg=5_000,
        axle_count=2,
        max_axle_load_kg=8_000,
        max_cargo_length_mm=6_500,
        max_cargo_width_mm=2_500,
        max_cargo_height_mm=2_600,
        max_cargo_weight_kg=5_000,
    )
    spec = VehicleRoutingSpec(
        vehicle_id="vehicle-1",
        is_hgv=True,
        tare_weight_kg=9_000,
        max_gross_weight_kg=18_000,
        length_mm=9_200,
        width_mm=2_500,
        height_mm=3_200,
        axle_count=2,
        max_axle_load_kg=9_000,
        payload_capacity_kg=6_000,
        platform_length_mm=6_500,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=1_200,
        max_platform_payload_kg=6_000,
        max_cargo_length_mm=6_500,
        max_cargo_width_mm=2_500,
        max_cargo_height_mm=2_600,
        max_cargo_weight_kg=5_000,
        can_use_trailer=True,
        combined_length_with_trailer_mm=18_500,
        coupling_length_mm=1_300,
        height_safety_margin_mm=100,
    )
    axle_profiles = tuple(
        OperationalAxleLoadProfile(configuration_type=value, max_actual_axle_load_kg=7_500)
        for value in TruckConfigurationType
    )
    return Vehicle(
        id="vehicle-1",
        name="Truck",
        routing_spec=spec,
        default_trailer=trailer,
        axle_load_profiles=axle_profiles,
    )


def _task(identifier: str, point: GeoPoint, part: int) -> PlanningTask:
    """Create one single-cabin delivery task with complete physical data."""

    return PlanningTask(
        id=identifier,
        request_id=f"request-{identifier}",
        part_number=part,
        quantity=1,
        task_type=TaskType.DELIVERY,
        name=identifier,
        address_label=identifier,
        point=point,
        zone_id="zone",
        zone_version=1,
        service_minutes=10,
        priority=1,
        status=RequestStatus.READY,
        created_at=START,
        selected_option=RequestDateOption(date=START.date()),
        remaining_date_count=1,
        is_last_available_date=True,
        cargo_dimensions=CARGO,
    )


def _cycle(tasks: tuple[PlanningTask, ...]) -> RouteCycle:
    """Build the approximate cycle that the exact evaluator must replace."""

    stops: list[RouteStop] = [
        RouteStop(
            sequence=0,
            stop_type=StopType.DEPOT_LOAD,
            point=DEPOT,
            planned_arrival=START,
            planned_departure=START + timedelta(minutes=30),
            service_seconds=1_800,
            quantity_delta=len(tasks),
            load_before=0,
            load_after=len(tasks),
        )
    ]
    load = len(tasks)
    for task in tasks:
        arrival = stops[-1].planned_departure + timedelta(minutes=10)
        load_after = load - 1
        stops.append(
            RouteStop(
                sequence=len(stops),
                stop_type=StopType.DELIVERY,
                point=task.point,
                planned_arrival=arrival,
                planned_departure=arrival + timedelta(minutes=10),
                service_seconds=600,
                quantity_delta=-1,
                load_before=load,
                load_after=load_after,
                task_id=task.id,
                request_id=task.request_id,
            )
        )
        load = load_after
    arrival = stops[-1].planned_departure + timedelta(minutes=10)
    stops.append(
        RouteStop(
            sequence=len(stops),
            stop_type=StopType.DEPOT_RETURN,
            point=DEPOT,
            planned_arrival=arrival,
            planned_departure=arrival,
            service_seconds=0,
            quantity_delta=0,
            load_before=0,
            load_after=0,
        )
    )
    legs = tuple(
        PlannedLeg(
            from_stop_sequence=source.sequence,
            to_stop_sequence=target.sequence,
            departure_at=source.planned_departure,
            arrival_at=target.planned_arrival,
            distance_meters=1_000,
            travel_seconds=600,
            geometry={
                "type": "LineString",
                "coordinates": [list(source.point.coordinates), list(target.point.coordinates)],
            },
        )
        for source, target in pairwise(stops)
    )
    return RouteCycle(
        id="cycle",
        driver_shift_id="shift",
        driver_id="driver",
        vehicle_id="vehicle-1",
        sequence=1,
        planned_start=START,
        planned_finish=stops[-1].planned_departure,
        stops=tuple(stops),
        legs=legs,
        total_distance_meters=sum(item.distance_meters for item in legs),
        total_travel_seconds=sum(item.travel_seconds for item in legs),
        total_service_seconds=sum(item.service_seconds for item in stops),
        waiting_seconds=0,
        empty_distance_meters=1_000,
        detour_seconds=0,
        score=100,
    )


def _shift() -> DriverShift:
    """Return one full-day driver/vehicle assignment."""

    return DriverShift(
        id="shift",
        driver_id="driver",
        driver_name="Driver",
        vehicle_id="vehicle-1",
        start_at=START,
        end_at=START + timedelta(hours=12),
    )


def _with_window(
    cycle: RouteCycle,
    *,
    stop_index: int,
    start: datetime,
    end: datetime,
) -> RouteCycle:
    """Attach one hard service window to an existing customer stop."""

    stops = list(cycle.stops)
    stops[stop_index] = replace(
        stops[stop_index],
        window_start=start,
        window_end=end,
        window_is_hard=True,
    )
    return replace(cycle, stops=tuple(stops))


@pytest.mark.asyncio
async def test_first_delivery_window_delays_cycle_instead_of_on_site_wait() -> None:
    """A 15:00 window shifts loading and uses the final delayed route departure."""

    provider = DepartureSensitiveTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    task = _task("afternoon", FIRST, 1)
    window_start = START.replace(hour=15)
    cycle = _with_window(
        _cycle((task,)),
        stop_index=1,
        start=window_start,
        end=START.replace(hour=18),
    )

    result = await router.route_candidate(
        cycle,
        tasks=(task,),
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    depot, delivery = result.stops[:2]
    first_leg = result.legs[0]
    assert provider.departures[:3] == [
        START + timedelta(minutes=30),
        START.replace(hour=14, minute=40),
        START.replace(hour=14, minute=50),
    ]
    assert result.planned_start == START.replace(hour=14, minute=20)
    assert depot.planned_arrival == START.replace(hour=14, minute=20)
    assert depot.planned_departure == START.replace(hour=14, minute=50)
    assert depot.planned_departure - depot.planned_arrival == timedelta(minutes=30)
    assert first_leg.departure_at == START.replace(hour=14, minute=50)
    assert first_leg.arrival_at == window_start
    assert delivery.planned_arrival == window_start
    assert delivery.planned_departure == window_start + timedelta(minutes=10)
    assert delivery.planned_departure - delivery.planned_arrival == timedelta(minutes=10)
    assert result.waiting_seconds == 0


@pytest.mark.asyncio
async def test_short_later_window_gap_is_absorbed_at_warehouse() -> None:
    """A later gap moves the complete feasible prefix to the warehouse."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("morning", FIRST, 1), _task("afternoon", SECOND, 2))
    window_start = START.replace(hour=10)
    cycle = _with_window(
        _cycle(tasks),
        stop_index=2,
        start=window_start,
        end=START.replace(hour=12),
    )

    result = await router.route_candidate(
        cycle,
        tasks=tasks,
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    depot = result.stops[0]
    first_delivery = result.stops[1]
    second_delivery = result.stops[2]
    connecting_leg = result.legs[1]
    assert depot.planned_arrival == START.replace(hour=8, minute=40)
    assert depot.planned_departure == START.replace(hour=9, minute=10)
    assert first_delivery.planned_arrival == START.replace(hour=9, minute=30)
    assert first_delivery.planned_departure == START.replace(hour=9, minute=40)
    assert connecting_leg.departure_at == START.replace(hour=9, minute=40)
    assert connecting_leg.arrival_at == window_start
    assert second_delivery.planned_arrival == window_start
    assert second_delivery.planned_departure == window_start + timedelta(minutes=10)
    assert result.waiting_seconds == 0


@pytest.mark.asyncio
async def test_warehouse_delay_reroutes_time_dependent_prefix_to_fixed_point() -> None:
    """Every depot shift reroutes prior legs until the late arrival is exact."""

    provider = DepartureSensitiveTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("morning", FIRST, 1), _task("afternoon", SECOND, 2))
    window_start = START.replace(hour=12, minute=30)
    cycle = _with_window(
        _cycle(tasks),
        stop_index=2,
        start=window_start,
        end=START.replace(hour=14),
    )

    result = await router.route_candidate(
        cycle,
        tasks=tasks,
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    depot, first_delivery, second_delivery = result.stops[:3]
    assert depot.planned_arrival == START.replace(hour=11, minute=20)
    assert depot.planned_departure == START.replace(hour=11, minute=50)
    assert first_delivery.planned_arrival == START.replace(hour=12, minute=10)
    assert first_delivery.planned_departure == START.replace(hour=12, minute=20)
    assert result.legs[1].departure_at == START.replace(hour=12, minute=20)
    assert second_delivery.planned_arrival == window_start
    assert result.waiting_seconds == 0
    assert provider.departures.count(START.replace(hour=11, minute=50)) >= 1
    assert provider.departures.count(START.replace(hour=12, minute=20)) >= 1


@pytest.mark.asyncio
async def test_residual_wait_stays_at_previous_source_when_prior_window_blocks_shift() -> None:
    """Only wait that cannot move to the depot remains at the prior customer."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("morning", FIRST, 1), _task("afternoon", SECOND, 2))
    cycle = _with_window(
        _with_window(
            _cycle(tasks),
            stop_index=1,
            start=START.replace(hour=9),
            end=START.replace(hour=9, minute=30),
        ),
        stop_index=2,
        start=START.replace(hour=10),
        end=START.replace(hour=12),
    )

    result = await router.route_candidate(
        cycle,
        tasks=tasks,
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    depot, first_delivery, second_delivery = result.stops[:3]
    connecting_leg = result.legs[1]
    assert depot.planned_arrival == START.replace(hour=8, minute=30)
    assert depot.planned_departure == START.replace(hour=9)
    assert first_delivery.planned_arrival == START.replace(hour=9, minute=20)
    assert first_delivery.planned_departure == START.replace(hour=9, minute=30)
    assert connecting_leg.departure_at == START.replace(hour=9, minute=40)
    assert connecting_leg.arrival_at == START.replace(hour=10)
    assert second_delivery.planned_arrival == START.replace(hour=10)
    assert result.waiting_seconds == 600


@pytest.mark.asyncio
async def test_multi_hour_later_window_gap_rejects_the_combined_cycle() -> None:
    """A long residual gap must be replanned as another depot cycle."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("morning", FIRST, 1), _task("afternoon", SECOND, 2))
    cycle = _with_window(
        _with_window(
            _cycle(tasks),
            stop_index=1,
            start=START.replace(hour=8, minute=50),
            end=START.replace(hour=9),
        ),
        stop_index=2,
        start=START.replace(hour=15),
        end=START.replace(hour=18),
    )

    with pytest.raises(CandidateRouteRejected) as captured:
        await router.route_candidate(
            cycle,
            tasks=tasks,
            vehicle=_vehicle(),
            shift=_shift(),
            settings=PlanningSettings(max_customer_wait_minutes=120),
        )

    assert captured.value.reason_code is UnassignedReasonCode.TIME_WINDOW_CONFLICT


@pytest.mark.asyncio
async def test_two_cargo_routes_each_leg_with_attached_trailer_after_unloading() -> None:
    """A remaining or empty trailer keeps combination length on later legs."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("first", FIRST, 1), _task("second", SECOND, 2))

    result = await router.route_candidate(
        _cycle(tasks),
        tasks=tasks,
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    assert [profile.configuration_type for profile in provider.profiles] == [
        TruckConfigurationType.TWO_CARGO_SPLIT,
        TruckConfigurationType.CARGO_ON_TRAILER_WITH_TRAILER,
        TruckConfigurationType.EMPTY_COMBINATION,
    ]
    assert all(profile.trailer_attached for profile in provider.profiles)
    assert all(profile.length_meters == 18.5 for profile in provider.profiles)
    assert all(leg.distance_meters == 20_000 for leg in result.legs)
    assert all(leg.routing_profile_snapshot is not None for leg in result.legs)


@pytest.mark.asyncio
async def test_one_cargo_uses_short_truck_without_trailer() -> None:
    """One cargo gets a distinct nine-metre profile and shorter provider route."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    tasks = (_task("only", FIRST, 1),)

    result = await router.route_candidate(
        _cycle(tasks),
        tasks=tasks,
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    assert [profile.configuration_type for profile in provider.profiles] == [
        TruckConfigurationType.CARGO_ON_TRUCK,
        TruckConfigurationType.EMPTY_TRUCK,
    ]
    assert all(not profile.trailer_attached for profile in provider.profiles)
    assert all(profile.length_meters == 9.2 for profile in provider.profiles)
    assert all(leg.distance_meters == 10_000 for leg in result.legs)


@pytest.mark.asyncio
async def test_exact_route_replaces_stale_approximate_time_warnings() -> None:
    """Exact timings remove matrix warnings that no longer describe the cycle."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    task = _task("only", FIRST, 1)
    approximate = replace(
        _cycle((task,)),
        warnings=(
            ValidationWarningCode.OVERTIME_WARNING,
            ValidationWarningCode.SOFT_WINDOW_RISK,
        ),
    )

    result = await router.route_candidate(
        approximate,
        tasks=(task,),
        vehicle=_vehicle(),
        shift=_shift(),
        settings=PlanningSettings(),
    )

    assert ValidationWarningCode.OVERTIME_WARNING not in result.warnings
    assert ValidationWarningCode.SOFT_WINDOW_RISK not in result.warnings


@pytest.mark.asyncio
async def test_exact_route_keeps_warning_for_actual_allowed_overtime() -> None:
    """A cycle that really exceeds the shift keeps the soft-overtime warning."""

    provider = RecordingTruckProvider()
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    task = _task("only", FIRST, 1)

    result = await router.route_candidate(
        _cycle((task,)),
        tasks=(task,),
        vehicle=_vehicle(),
        shift=replace(_shift(), end_at=START + timedelta(minutes=50)),
        settings=PlanningSettings(
            allow_soft_overtime=True,
            soft_overtime_limit_minutes=10,
        ),
    )

    assert result.planned_finish == START + timedelta(hours=1)
    assert ValidationWarningCode.OVERTIME_WARNING in result.warnings


@pytest.mark.asyncio
async def test_no_safe_truck_route_is_rejected_without_car_fallback() -> None:
    """A truck graph rejection remains NO_SAFE_ROUTE and invokes no alternate provider."""

    router = ExactTruckCycleRouter(
        NoSafeRouteProvider(),
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    task = _task("only", FIRST, 1)

    with pytest.raises(CandidateRouteRejected) as captured:
        await router.route_candidate(
            _cycle((task,)),
            tasks=(task,),
            vehicle=_vehicle(),
            shift=_shift(),
            settings=PlanningSettings(),
        )

    assert captured.value.reason_code is UnassignedReasonCode.NO_SAFE_ROUTE


@pytest.mark.asyncio
async def test_missing_cargo_dimensions_rejects_before_provider_call() -> None:
    """Incomplete physical input never reaches the routing engine."""

    provider = RecordingTruckProvider()
    task = replace(_task("only", FIRST, 1), cargo_dimensions=None)
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )

    with pytest.raises(CandidateRouteRejected) as captured:
        await router.route_candidate(
            _cycle((task,)),
            tasks=(task,),
            vehicle=_vehicle(),
            shift=_shift(),
            settings=PlanningSettings(),
        )

    assert captured.value.reason_code is UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE
    assert provider.profiles == []


@pytest.mark.asyncio
async def test_trailer_denied_address_rejects_two_cargo_before_provider_call() -> None:
    """Exact routing cannot override a dispatcher's address access agreement."""

    provider = RecordingTruckProvider()
    tasks = (
        replace(_task("denied", FIRST, 1), trailer_access_allowed=False),
        _task("allowed", SECOND, 2),
    )
    router = ExactTruckCycleRouter(
        provider,
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )

    with pytest.raises(CandidateRouteRejected) as captured:
        await router.route_candidate(
            _cycle(tasks),
            tasks=tasks,
            vehicle=_vehicle(),
            shift=_shift(),
            settings=PlanningSettings(),
        )

    assert captured.value.reason_code is UnassignedReasonCode.TRAILER_ACCESS_NOT_ALLOWED
    assert provider.profiles == []


def test_legacy_locked_cycle_cannot_bypass_truck_verification() -> None:
    """A lock preserves safe routes, but cannot grandfather a car/matrix route."""

    legacy = replace(_cycle((_task("only", FIRST, 1),)), locked=True)

    with pytest.raises(RoutingProfileIncompleteError) as captured:
        RuntimePlannerFacade._assert_truck_verified_locked_cycles((legacy,))

    assert any("routing_profile_snapshot" in item for item in captured.value.missing_fields)


def test_verified_locked_cycle_remains_eligible_without_rerouting() -> None:
    """A previously exact-routed lock is accepted without mutating its snapshot."""

    source = replace(_cycle((_task("only", FIRST, 1),)), locked=True)
    snapshot = {
        "vehicleId": "vehicle-1",
        "trailerAttached": False,
        "cargoPlacements": [],
        "configurationType": "EMPTY_TRUCK",
        "effectiveHeightMeters": 3.2,
        "effectiveWidthMeters": 2.5,
        "effectiveLengthMeters": 9.2,
        "actualWeightTons": 9.0,
        "maxAxleLoadTons": 7.5,
        "routingProvider": "valhalla",
        "osmDataVersion": "2026-08-24",
        "calculatedAt": START.isoformat(),
    }
    verified = replace(
        source,
        legs=tuple(
            replace(
                leg,
                routing_profile_snapshot=snapshot,
                routing_provider="valhalla",
                osm_data_version="2026-08-24",
                routed_at=START,
            )
            for leg in source.legs
        ),
    )

    RuntimePlannerFacade._assert_truck_verified_locked_cycles((verified,))

    assert verified == replace(verified)


@pytest.mark.asyncio
async def test_optimizer_does_not_assign_candidate_rejected_by_exact_truck_route() -> None:
    """Approximate ranking cannot promote a cycle whose exact truck leg is unsafe."""

    request = LogisticsRequest(
        id="request-only",
        request_type=TaskType.DELIVERY,
        name="only",
        address_label="only",
        point=FIRST,
        quantity=1,
        service_minutes=10,
        priority=1,
        status=RequestStatus.READY,
        zone_id="zone",
        zone_version=1,
        date_options=(RequestDateOption(date=START.date()),),
        created_at=START,
        cargo_dimensions=CARGO,
    )
    input_data = PlanningInput(
        warehouse_id="warehouse",
        planning_date=START.date(),
        warehouse=Warehouse(
            id="warehouse",
            name="Depot",
            point=DEPOT,
            loading_minutes=30,
            unloading_minutes=20,
            turnaround_minutes=10,
        ),
        requests=(request,),
        shifts=(_shift(),),
        vehicles=(_vehicle(),),
    )
    exact = ExactTruckCycleRouter(
        NoSafeRouteProvider(),
        provider_name="valhalla",
        osm_data_version="2026-08-24",
        now=lambda: START,
    )
    planner = HeuristicPlanner(MockRoutingProvider(RoutingSettings()), exact)

    result = await planner.generate_plan(
        input_data,
        PlanningSettings(),
        NullProgressPublisher(),
    )

    assert result.cycles == ()
    assert len(result.unassigned) == 1
    assert result.unassigned[0].reason_codes == (UnassignedReasonCode.NO_SAFE_ROUTE,)
