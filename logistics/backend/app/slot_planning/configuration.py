"""Shared pure adapters for warehouse slot policy and physical truck equipment."""

from __future__ import annotations

from collections.abc import Collection, Mapping
from datetime import time
from typing import Any

from app.config import Settings
from app.domain.vehicle import (
    kilograms_to_tonnes,
    load_profile_axle_load_t,
    millimetres_to_metres,
    trailer_specification,
    vehicle_specification,
)
from app.errors import ApiError
from app.models import Trailer, Vehicle, Warehouse
from app.routing import (
    GeoPoint,
    MockRoutingProvider,
    RoutingProvider,
    RoutingSettings,
    ValhallaRoutingProvider,
)
from app.routing.truck_profile import (
    CargoDimensions,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckConfigurationType,
    VehicleRoutingSpec,
)

from .models import WarehouseSlotConfiguration
from .routing_adapter import CachedTruckTravelTimeProvider, VehicleEquipmentSnapshot


def vehicle_has_available_trailer(
    vehicle: Vehicle,
    unavailable_trailer_ids: Collection[str] = (),
) -> bool:
    """Return whether the current vehicle snapshot can actually use its assigned trailer."""

    has_trailer = getattr(vehicle, "has_trailer", None)
    if has_trailer is None:
        has_trailer = getattr(vehicle, "can_use_trailer", None)
    return (
        has_trailer is True
        and vehicle.default_trailer is not None
        and vehicle.default_trailer.active
        and str(vehicle.default_trailer.id) not in unavailable_trailer_ids
    )


def effective_vehicle_cabin_capacity(
    vehicle: Vehicle,
    unavailable_trailer_ids: Collection[str] = (),
) -> int:
    """One truck platform plus at most one available trailer platform, never more than two."""

    return (
        min(2, vehicle.capacity)
        if vehicle_has_available_trailer(vehicle, unavailable_trailer_ids)
        else 1
    )


def vehicle_routing_spec(vehicle: Vehicle) -> VehicleRoutingSpec:
    """Translate the canonical physical model without legacy fallbacks."""

    physical = vehicle_specification(vehicle)
    return VehicleRoutingSpec(
        vehicle_id=vehicle.id,
        tare_weight_t=physical.tare_weight_t,
        max_gross_weight_t=physical.max_gross_weight_t,
        length_m=physical.length_m,
        width_m=physical.width_m,
        height_m=physical.height_m,
        axle_count=vehicle.axle_count,
        axle_load_t=physical.axle_load_t,
        payload_capacity_t=physical.payload_capacity_t,
        platform_length_m=physical.platform_length_m,
        platform_height_from_ground_m=physical.platform_height_from_ground_m,
        max_platform_payload_t=physical.max_platform_payload_t,
        max_cargo_length_m=physical.max_cargo_length_m,
        max_cargo_width_m=physical.max_cargo_width_m,
        max_cargo_height_m=physical.max_cargo_height_m,
        max_cargo_weight_t=physical.max_cargo_weight_t,
        has_trailer=physical.has_trailer,
        coupling_length_m=physical.coupling_length_m,
        height_safety_margin_m=physical.height_safety_margin_m,
        width_safety_margin_m=physical.width_safety_margin_m,
        weight_safety_margin_t=physical.weight_safety_margin_t,
    )


def vehicle_axle_profiles(vehicle: Vehicle) -> tuple[OperationalAxleLoadProfile, ...]:
    """Translate every measured operational axle load into canonical tonnes."""

    return tuple(
        OperationalAxleLoadProfile(
            configuration_type=TruckConfigurationType(profile.configuration_type),
            max_actual_axle_load_t=load_profile_axle_load_t(profile),
        )
        for profile in vehicle.load_profiles
    )


def trailer_routing_spec(trailer: Trailer) -> TrailerSpec:
    """Translate nullable persisted trailer values without selecting availability."""

    physical = trailer_specification(trailer)
    return TrailerSpec(
        trailer_id=trailer.id,
        tare_weight_t=physical.tare_weight_t,
        max_gross_weight_t=physical.max_gross_weight_t,
        length_m=physical.length_m,
        width_m=physical.width_m,
        height_m=physical.height_m,
        platform_length_m=physical.platform_length_m,
        platform_height_from_ground_m=physical.platform_height_from_ground_m,
        max_platform_payload_t=physical.max_platform_payload_t,
        payload_capacity_t=physical.payload_capacity_t,
        axle_count=trailer.axle_count,
        axle_load_t=physical.axle_load_t,
        max_cargo_length_m=physical.max_cargo_length_m,
        max_cargo_width_m=physical.max_cargo_width_m,
        max_cargo_height_m=physical.max_cargo_height_m,
        max_cargo_weight_t=physical.max_cargo_weight_t,
    )


def warehouse_slot_configuration(warehouse: Warehouse) -> WarehouseSlotConfiguration:
    """Build the single slot policy used by exact slots and day-plan support routing."""

    source = warehouse.settings
    return WarehouseSlotConfiguration(
        warehouse_id=str(warehouse.id),
        point=GeoPoint(warehouse.longitude, warehouse.latitude, is_city=True),
        timezone=warehouse.timezone,
        driver_day_start=_setting_time(source, "slot_driver_day_start", time(8)),
        delivery_day_start=_setting_time(source, "slot_delivery_day_start", time(9)),
        delivery_day_end=_setting_time(source, "slot_delivery_day_end", time(18)),
        hard_finish=_setting_time(source, "slot_hard_finish", warehouse.working_day_end),
        customer_slots=_setting_slots(source),
        delivery_service_minutes=int(source.get("delivery_service_minutes", 60)),
        pickup_service_minutes=int(source.get("pickup_service_minutes", 60)),
        load_one_minutes=int(source.get("warehouse_load_one_minutes", warehouse.loading_minutes)),
        load_two_minutes=int(
            source.get("warehouse_load_two_minutes", warehouse.loading_minutes + 15)
        ),
        unload_per_cabin_minutes=int(
            source.get("warehouse_unload_minutes", warehouse.unloading_minutes)
        ),
        turnaround_minutes=int(
            source.get("warehouse_turnaround_minutes", warehouse.turnaround_minutes)
        ),
        travel_time_multiplier=float(source.get("travel_time_multiplier", 1.15)),
        fixed_travel_buffer_minutes=int(source.get("fixed_travel_buffer_minutes", 5)),
        hold_ttl_minutes=int(source.get("slot_hold_ttl_minutes", 10)),
    )


def truck_travel_time_provider(
    settings: Settings,
    warehouse: Warehouse,
    equipment: dict[str, VehicleEquipmentSnapshot],
) -> CachedTruckTravelTimeProvider:
    """Build the shared exact truck provider for slots, support routing, and day plans."""

    delegate: RoutingProvider
    if settings.routing_provider == "valhalla":
        valhalla = ValhallaRoutingProvider(
            settings.valhalla_url,
            timeout_seconds=settings.valhalla_timeout_seconds,
            osm_data_version=settings.osm_data_version,
        )
        delegate = valhalla
        version: tuple[object, ...] = valhalla.cache_key
    elif settings.routing_provider == "mock":
        source = warehouse.settings
        routing = RoutingSettings(
            city_speed_kmh=float(source.get("city_speed_kmh", 35)),
            region_speed_kmh=float(source.get("region_speed_kmh", 60)),
            road_factor=float(source.get("road_factor", 1.25)),
            morning_traffic_multiplier=float(source.get("morning_traffic_multiplier", 1.35)),
            evening_traffic_multiplier=float(source.get("evening_traffic_multiplier", 1.25)),
            seed=warehouse.seed,
        )
        delegate = MockRoutingProvider(routing)
        version = ("mock-test-provider", warehouse.seed)
    else:
        raise ApiError(
            503,
            "TRUCK_ROUTING_NOT_CONFIGURED",
            "Dynamic slots require Valhalla truck routing",
        )
    return CachedTruckTravelTimeProvider(
        delegate,
        equipment,
        routing_version=version,
    )


def vehicle_equipment_snapshot(
    vehicle: Vehicle,
    settings: Mapping[str, Any],
    unavailable_trailer_ids: Collection[str] = (),
) -> VehicleEquipmentSnapshot:
    """Build the shared complete physical snapshot used by truck-profile routing."""

    trailer = (
        vehicle.default_trailer
        if vehicle_has_available_trailer(vehicle, unavailable_trailer_ids)
        else None
    )
    return VehicleEquipmentSnapshot(
        vehicle_id=str(vehicle.id),
        vehicle=vehicle_routing_spec(vehicle),
        trailer=trailer_routing_spec(trailer) if trailer is not None else None,
        axle_profiles=vehicle_axle_profiles(vehicle),
        cabin=default_slot_cabin(settings),
    )


def default_slot_cabin(settings: Mapping[str, Any]) -> CargoDimensions:
    """Return configurable conservative cabin dimensions for profile calculation."""

    length_m = millimetres_to_metres(int(settings.get("slot_cabin_length_mm", 6_000)))
    width_m = millimetres_to_metres(int(settings.get("slot_cabin_width_mm", 2_400)))
    height_m = millimetres_to_metres(int(settings.get("slot_cabin_height_mm", 2_400)))
    weight_t = kilograms_to_tonnes(int(settings.get("slot_cabin_weight_kg", 1_200)))
    assert length_m is not None and width_m is not None
    assert height_m is not None and weight_t is not None
    return CargoDimensions(
        length_m=length_m,
        width_m=width_m,
        height_m=height_m,
        weight_t=weight_t,
    )


def _setting_time(source: Mapping[str, Any], key: str, default: time) -> time:
    """Parse a warehouse wall-clock setting without allowing an invalid fallback."""

    value = source.get(key)
    if value is None:
        return default
    if isinstance(value, time):
        return value.replace(tzinfo=None)
    try:
        return time.fromisoformat(str(value))
    except ValueError as exc:
        raise ApiError(422, "SLOT_CONFIGURATION_INVALID", f"Invalid {key}") from exc


def _setting_slots(source: Mapping[str, Any]) -> tuple[tuple[time, time], ...]:
    """Parse exactly three warehouse-local customer windows from workspace settings."""

    value = source.get("slot_customer_windows")
    if value is None:
        return ((time(9), time(12)), (time(12), time(15)), (time(15), time(18)))
    try:
        slots = tuple(
            (
                _setting_time({"value": pair[0]}, "value", time(9)),
                _setting_time({"value": pair[1]}, "value", time(12)),
            )
            for pair in value
        )
    except (IndexError, TypeError) as exc:
        raise ApiError(
            422,
            "SLOT_CONFIGURATION_INVALID",
            "Invalid slot_customer_windows",
        ) from exc
    return slots
