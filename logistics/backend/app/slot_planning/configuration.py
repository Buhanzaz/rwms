"""Shared pure adapters for warehouse slot policy and physical truck equipment."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import time
from typing import Any

from app.config import Settings
from app.errors import ApiError
from app.models import Vehicle, Warehouse
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


def vehicle_has_available_trailer(vehicle: Vehicle) -> bool:
    """Return whether the current vehicle snapshot can actually use its assigned trailer."""

    return (
        vehicle.can_use_trailer is True
        and vehicle.default_trailer is not None
        and vehicle.default_trailer.active
    )


def effective_vehicle_cabin_capacity(vehicle: Vehicle) -> int:
    """Return the shared one-or-two-cabin capacity for planning and slot calculations."""

    return min(2, vehicle.capacity) if vehicle_has_available_trailer(vehicle) else 1


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
) -> VehicleEquipmentSnapshot:
    """Build the shared complete physical snapshot used by truck-profile routing."""

    trailer = vehicle.default_trailer if vehicle_has_available_trailer(vehicle) else None
    return VehicleEquipmentSnapshot(
        vehicle_id=str(vehicle.id),
        vehicle=VehicleRoutingSpec(
            vehicle_id=vehicle.id,
            is_hgv=vehicle.is_hgv,
            tare_weight_kg=vehicle.tare_weight_kg,
            max_gross_weight_kg=vehicle.max_gross_weight_kg,
            length_mm=vehicle.length_mm,
            width_mm=vehicle.width_mm,
            height_mm=vehicle.height_mm,
            axle_count=vehicle.axle_count,
            max_axle_load_kg=vehicle.max_axle_load_kg,
            payload_capacity_kg=vehicle.payload_capacity_kg,
            platform_length_mm=vehicle.platform_length_mm,
            platform_width_mm=vehicle.platform_width_mm,
            platform_height_from_ground_mm=vehicle.platform_height_from_ground_mm,
            max_platform_payload_kg=vehicle.max_platform_payload_kg,
            max_cargo_length_mm=vehicle.max_cargo_length_mm,
            max_cargo_width_mm=vehicle.max_cargo_width_mm,
            max_cargo_height_mm=vehicle.max_cargo_height_mm,
            max_cargo_weight_kg=vehicle.max_cargo_weight_kg,
            can_use_trailer=vehicle.can_use_trailer,
            combined_length_with_trailer_mm=vehicle.combined_length_with_trailer_mm,
            coupling_length_mm=vehicle.coupling_length_mm,
            height_safety_margin_mm=vehicle.height_safety_margin_mm,
            width_safety_margin_mm=vehicle.width_safety_margin_mm,
            weight_safety_margin_kg=vehicle.weight_safety_margin_kg,
        ),
        trailer=(
            TrailerSpec(
                trailer_id=trailer.id,
                tare_weight_kg=trailer.tare_weight_kg,
                max_gross_weight_kg=trailer.max_gross_weight_kg,
                length_mm=trailer.length_mm,
                width_mm=trailer.width_mm,
                height_mm=trailer.height_mm,
                platform_length_mm=trailer.platform_length_mm,
                platform_width_mm=trailer.platform_width_mm,
                platform_height_from_ground_mm=trailer.platform_height_from_ground_mm,
                max_platform_payload_kg=trailer.max_platform_payload_kg,
                payload_capacity_kg=trailer.payload_capacity_kg,
                axle_count=trailer.axle_count,
                max_axle_load_kg=trailer.max_axle_load_kg,
                max_cargo_length_mm=trailer.max_cargo_length_mm,
                max_cargo_width_mm=trailer.max_cargo_width_mm,
                max_cargo_height_mm=trailer.max_cargo_height_mm,
                max_cargo_weight_kg=trailer.max_cargo_weight_kg,
            )
            if trailer is not None
            else None
        ),
        axle_profiles=tuple(
            OperationalAxleLoadProfile(
                configuration_type=TruckConfigurationType(profile.configuration_type),
                max_actual_axle_load_kg=profile.max_actual_axle_load_kg,
            )
            for profile in vehicle.load_profiles
        ),
        cabin=default_slot_cabin(settings),
    )


def default_slot_cabin(settings: Mapping[str, Any]) -> CargoDimensions:
    """Return configurable conservative cabin dimensions for profile calculation."""

    return CargoDimensions(
        length_mm=int(settings.get("slot_cabin_length_mm", 6_000)),
        width_mm=int(settings.get("slot_cabin_width_mm", 2_400)),
        height_mm=int(settings.get("slot_cabin_height_mm", 2_400)),
        weight_kg=int(settings.get("slot_cabin_weight_kg", 1_200)),
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
