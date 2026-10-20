"""Focused cache and failure tests for dynamic-slot truck routing."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from types import SimpleNamespace
from typing import cast
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

from app.config import Settings
from app.routing import GeoPoint, TravelMatrix, TravelMetric
from app.routing.truck_profile import (
    CargoDimensions,
    EffectiveTruckProfile,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckConfigurationType,
    VehicleRoutingSpec,
)
from app.slot_planning.application import SlotPlanningApplication
from app.slot_planning.models import (
    DayPlan,
    ScheduledTrip,
    SlotCandidate,
    TimelineStop,
    TimelineStopType,
    TravelTimeUnavailable,
    VehicleLegState,
)
from app.slot_planning.routing_adapter import (
    CachedTruckTravelTimeProvider,
    VehicleEquipmentSnapshot,
)


class RecordingTruckDelegate:
    """Minimal routing provider that records exact profiles and directed calls."""

    def __init__(self, failure: Exception | None = None) -> None:
        self.failure = failure
        self.matrix_calls: list[tuple[tuple[GeoPoint, ...], EffectiveTruckProfile]] = []

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime,
        *,
        profile: EffectiveTruckProfile,
    ) -> TravelMatrix:
        """Return one directed synthetic matrix or the configured provider failure."""

        del departure_at
        self.matrix_calls.append((tuple(points), profile))
        if self.failure is not None:
            raise self.failure
        return TravelMatrix(
            points=tuple(points),
            rows=(
                (
                    TravelMetric(distance_meters=0, travel_seconds=0),
                    TravelMetric(distance_meters=1_200, travel_seconds=180),
                ),
                (
                    TravelMetric(distance_meters=1_400, travel_seconds=210),
                    TravelMetric(distance_meters=0, travel_seconds=0),
                ),
            ),
        )


def _equipment() -> VehicleEquipmentSnapshot:
    """Build complete solo/trailer profiles with every load-state axle measurement."""

    vehicle_id = uuid4()
    trailer_id = uuid4()
    return VehicleEquipmentSnapshot(
        vehicle_id=str(vehicle_id),
        vehicle=VehicleRoutingSpec(
            vehicle_id=vehicle_id,
            is_hgv=True,
            tare_weight_t=Decimal("9"),
            max_gross_weight_t=Decimal("18"),
            length_m=Decimal("9.2"),
            width_m=Decimal("2.5"),
            height_m=Decimal("3.2"),
            axle_count=2,
            axle_load_t=Decimal("9"),
            payload_capacity_t=Decimal("6"),
            platform_length_m=Decimal("6.5"),
            platform_height_from_ground_m=Decimal("1.2"),
            max_platform_payload_t=Decimal("6"),
            max_cargo_length_m=Decimal("6.5"),
            max_cargo_width_m=Decimal("2.5"),
            max_cargo_height_m=Decimal("2.6"),
            max_cargo_weight_t=Decimal("5"),
            has_trailer=True,
            coupling_length_m=Decimal("1.3"),
            height_safety_margin_m=Decimal("0.1"),
            width_safety_margin_m=Decimal(0),
            weight_safety_margin_t=Decimal(0),
        ),
        trailer=TrailerSpec(
            trailer_id=trailer_id,
            tare_weight_t=Decimal("3.5"),
            max_gross_weight_t=Decimal("10"),
            length_m=Decimal("8"),
            width_m=Decimal("2.5"),
            height_m=Decimal("1.6"),
            platform_length_m=Decimal("6.5"),
            platform_height_from_ground_m=Decimal("0.9"),
            max_platform_payload_t=Decimal("5"),
            payload_capacity_t=Decimal("5"),
            axle_count=2,
            axle_load_t=Decimal("8"),
            max_cargo_length_m=Decimal("6.5"),
            max_cargo_width_m=Decimal("2.5"),
            max_cargo_height_m=Decimal("2.6"),
            max_cargo_weight_t=Decimal("5"),
        ),
        axle_profiles=tuple(
            OperationalAxleLoadProfile(configuration_type, axle_load)
            for configuration_type, axle_load in (
                (TruckConfigurationType.EMPTY_TRUCK, Decimal("6")),
                (TruckConfigurationType.CARGO_ON_TRUCK, Decimal("7.5")),
                (TruckConfigurationType.EMPTY_COMBINATION, Decimal("6.5")),
                (TruckConfigurationType.CARGO_ON_TRUCK_WITH_TRAILER, Decimal("7.5")),
                (TruckConfigurationType.CARGO_ON_TRAILER_WITH_TRAILER, Decimal("7")),
                (TruckConfigurationType.TWO_CARGO_SPLIT, Decimal("7.8")),
            )
        ),
        cabin=CargoDimensions(
            length_m=Decimal("6"), width_m=Decimal("2.4"), height_m=Decimal("2.4"),
            weight_t=Decimal("1.2"),
        ),
    )


@pytest.mark.asyncio
async def test_cache_separates_direction_departure_band_solo_and_trailer() -> None:
    """Reuse exact duplicates while partitioning every route-identity dimension."""

    equipment = _equipment()
    delegate = RecordingTruckDelegate()
    provider = CachedTruckTravelTimeProvider(
        delegate, {equipment.vehicle_id: equipment}, routing_version=("valhalla", "tiles-v1")
    )
    first = GeoPoint(30.0, 59.0)
    second = GeoPoint(30.2, 59.2)
    departure = datetime(2026, 8, 29, 9, 5, tzinfo=ZoneInfo("Europe/Moscow"))
    solo = VehicleLegState(equipment.vehicle_id, False, 1, 1)
    trailer = VehicleLegState(equipment.vehicle_id, True, 2, 2)

    await provider.travel_time(first, second, departure, solo)
    await provider.travel_time(first, second, departure.replace(minute=45), solo)
    assert len(delegate.matrix_calls) == 1

    await provider.travel_time(second, first, departure, solo)
    await provider.travel_time(first, second, departure, trailer)
    await provider.travel_time(first, second, departure.replace(hour=10), solo)

    assert len(delegate.matrix_calls) == 4
    solo_profile = delegate.matrix_calls[0][1]
    trailer_profile = delegate.matrix_calls[2][1]
    assert solo_profile.trailer_attached is False
    assert trailer_profile.trailer_attached is True


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", (TimeoutError("deadline"), RuntimeError("no truck path")))
async def test_provider_failure_is_terminal_without_route_or_car_fallback(
    failure: Exception,
) -> None:
    """Convert timeout/no-path into an unavailable truck leg after one exact call."""

    equipment = _equipment()
    delegate = RecordingTruckDelegate(failure)
    provider = CachedTruckTravelTimeProvider(
        delegate, {equipment.vehicle_id: equipment}, routing_version=("valhalla", "tiles-v1")
    )

    with pytest.raises(TravelTimeUnavailable):
        await provider.travel_time(
            GeoPoint(30.0, 59.0),
            GeoPoint(30.2, 59.2),
            datetime(2026, 8, 29, 9, tzinfo=ZoneInfo("Europe/Moscow")),
            VehicleLegState(equipment.vehicle_id, False, 1, 1),
        )

    assert len(delegate.matrix_calls) == 1


@pytest.mark.asyncio
async def test_candidate_read_skips_isochrones_and_unrelated_trip_geometry() -> None:
    """Serialize a candidate without contour calls or routes from unaffected trips."""

    departure = datetime(2026, 8, 29, 9, tzinfo=ZoneInfo("Europe/Moscow"))

    def scheduled_trip(trip_id: str, longitude: float) -> ScheduledTrip:
        """Build one minimal depot-to-customer timeline for route evidence."""

        warehouse_stop = TimelineStop(
            id=f"{trip_id}:warehouse",
            stop_type=TimelineStopType.WAREHOUSE_LOAD,
            point=GeoPoint(longitude, 59.0),
            arrival=departure,
            service_start=departure,
            service_end=departure,
            departure=departure,
            waiting_minutes=0,
            load_before=0,
            load_after=1,
            service_minutes=0,
        )
        delivery_stop = TimelineStop(
            id=f"{trip_id}:delivery",
            stop_type=TimelineStopType.DELIVERY,
            point=GeoPoint(longitude + 0.1, 59.1),
            arrival=departure,
            service_start=departure,
            service_end=departure,
            departure=departure,
            waiting_minutes=0,
            load_before=1,
            load_after=0,
            service_minutes=0,
            task_id=f"{trip_id}:task",
        )
        return ScheduledTrip(
            trip_id=trip_id,
            stops=(warehouse_stop, delivery_stop),
            travel_seconds=600,
            distance_meters=1_000,
            waiting_minutes=0,
            minimum_slack_minutes=60,
            finish=departure,
        )

    target_trip = scheduled_trip("target-trip", 30.0)
    unrelated_trip = scheduled_trip("unrelated-trip", 31.0)
    source_target = SimpleNamespace(id="target-trip", outbound_load=1, pickups=())
    source_unrelated = SimpleNamespace(id="unrelated-trip", outbound_load=1, pickups=())
    driver = SimpleNamespace(
        driver_id="driver-1",
        shift_id="shift-1",
        vehicle_id="vehicle-1",
        trips=(source_target, source_unrelated),
    )
    scheduled_day = SimpleNamespace(
        driver=driver,
        trips=(target_trip, unrelated_trip),
        deferred_pickup_ids=(),
    )
    baseline = SimpleNamespace(drivers=(driver,), pickup_pool=())
    candidate = SimpleNamespace(
        driver_plan=driver,
        baseline_scheduled_day=scheduled_day,
        scheduled_day=scheduled_day,
        trip_id="target-trip",
        insert_after_stop_id="target-trip:warehouse",
        insert_before_stop_id="target-trip:delivery",
        new_task_id="target-trip:task",
        inserted_task_ids=("target-trip:task",),
        estimated_service_start=departure,
        estimated_finish=departure,
        warehouse_return_time=departure,
        minimum_slack_minutes=60,
        incremental_travel_minutes=10,
        incremental_distance_meters=1_000,
        waiting_minutes=0,
        pickup_count=0,
        affected_stop_ids=("target-trip:task",),
    )
    route_calls: list[tuple[GeoPoint, ...]] = []
    isochrone_calls = 0

    async def route_geometry(
        points: tuple[GeoPoint, ...],
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> dict[str, object]:
        """Record each explanatory route request and return its input line."""

        del departure_at, vehicle_state
        route_calls.append(points)
        return {
            "type": "LineString",
            "coordinates": [[point.lon, point.lat] for point in points],
        }

    async def isochrones(*args: object, **kwargs: object) -> tuple[object, ...]:
        """Record forbidden contour work if candidate serialization regresses."""

        nonlocal isochrone_calls
        del args, kwargs
        isochrone_calls += 1
        return ()

    provider = SimpleNamespace(route_geometry=route_geometry, isochrones=isochrones)
    result = await SlotPlanningApplication(Settings())._candidate_read(
        cast(DayPlan, baseline),
        cast(SlotCandidate, candidate),
        cast(CachedTruckTravelTimeProvider, provider),
    )

    assert isochrone_calls == 0
    assert len(route_calls) == 2
    assert all(points[0].lon == 30.0 for points in route_calls)
    serialized = result.model_dump(mode="json")
    assert "isochrones" not in serialized
    assert "insertion_lens" not in serialized
