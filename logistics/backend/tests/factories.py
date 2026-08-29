"""Small warehouse-rooted factories shared by backend integration tests."""

from __future__ import annotations

from datetime import date, time
from uuid import uuid4

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Driver, DriverShift, LogisticsRequest, Vehicle, Warehouse, Zone, ZoneKind
from app.schemas.domain import (
    DriverCreate,
    GeoJsonGeometry,
    LogisticsRequestCreate,
    PlanningSettings,
    RequestDateOptionInput,
    ShiftCreate,
    VehicleConfigurationCreate,
    VehicleCreate,
    VehicleLoadProfileCreate,
    ZoneCreate,
)
from app.services import catalog


async def make_warehouse(
    session: AsyncSession,
    *,
    name: str = "Test warehouse",
    latitude: float = 59.93,
    longitude: float = 30.32,
    timezone: str = "Europe/Moscow",
    default_planning_date: date | None = date(2026, 8, 30),
) -> Warehouse:
    """Persist an isolated RWMS-bound warehouse without invoking an external directory."""

    warehouse = Warehouse(
        external_warehouse_id=uuid4(),
        name=name,
        city="Санкт-Петербург",
        address=f"{name}, тестовый адрес",
        timezone=timezone,
        latitude=latitude,
        longitude=longitude,
        default_planning_date=default_planning_date,
        seed=17,
        settings=PlanningSettings().model_dump(mode="json"),
        capacity_generation=0,
        loading_minutes=30,
        unloading_minutes=30,
        turnaround_minutes=15,
        working_day_start=time(8),
        working_day_end=time(20),
    )
    session.add(warehouse)
    await session.flush()
    return warehouse


async def make_zone(
    session: AsyncSession,
    warehouse: Warehouse,
    *,
    name: str = "Test zone",
    color: str = "#22C55E",
    kind: ZoneKind = ZoneKind.SPECIAL_PRICE,
    west: float = 29.0,
    south: float = 59.0,
    east: float = 32.0,
    north: float = 61.0,
) -> Zone:
    """Persist a rectangular tariff zone owned by the supplied warehouse."""

    return await catalog.create_zone(
        session,
        warehouse.id,
        ZoneCreate(
            name=name,
            kind=kind,
            color=color,
            delivery_price=2_000 if kind is ZoneKind.SPECIAL_PRICE else 0,
            pickup_price=1_000 if kind is ZoneKind.SPECIAL_PRICE else 0,
            geometry=GeoJsonGeometry(
                type="Polygon",
                coordinates=[
                    [
                        [west, south],
                        [east, south],
                        [east, north],
                        [west, north],
                        [west, south],
                    ]
                ],
            ),
        ),
    )


async def make_driver(session: AsyncSession, warehouse: Warehouse) -> Driver:
    """Persist a shared-audience driver that needs no fake RWMS worker UUID."""

    return await catalog.create_driver(
        session,
        warehouse.id,
        DriverCreate(
            rwms_assignment_mode="WAREHOUSE_DRIVERS",
        ),
        None,
    )


async def make_vehicle(session: AsyncSession, warehouse: Warehouse) -> Vehicle:
    """Persist a basic active two-cabin vehicle."""

    return await catalog.create_vehicle(
        session,
        warehouse.id,
        VehicleCreate(
            name="Test truck",
            registration_number=f"TEST-{str(uuid4())[:8]}",
            capacity=2,
        ),
    )


async def make_routable_vehicle(session: AsyncSession, warehouse: Warehouse) -> Vehicle:
    """Persist a complete solo-truck profile accepted by exact slot routing."""

    return await catalog.create_vehicle_configuration(
        session,
        warehouse.id,
        VehicleConfigurationCreate(
            vehicle=VehicleCreate(
                name="Routable test truck",
                registration_number=f"ROUTE-{str(uuid4())[:8]}",
                capacity=1,
                vehicle_type="FLATBED_CRANE",
                manufacturer="Test",
                model="Solo",
                is_hgv=True,
                tare_weight_kg=10_000,
                max_gross_weight_kg=20_000,
                length_mm=9_000,
                width_mm=2_500,
                height_mm=3_200,
                axle_count=3,
                max_axle_load_kg=10_000,
                payload_capacity_kg=8_000,
                platform_length_mm=7_000,
                platform_width_mm=2_500,
                platform_height_from_ground_mm=1_200,
                max_platform_payload_kg=7_000,
                max_cargo_length_mm=7_000,
                max_cargo_width_mm=2_600,
                max_cargo_height_mm=3_000,
                max_cargo_weight_kg=6_000,
                can_use_trailer=False,
            ),
            load_profiles=[
                VehicleLoadProfileCreate(
                    configuration_type="EMPTY_TRUCK",
                    max_actual_axle_load_kg=5_000,
                ),
                VehicleLoadProfileCreate(
                    configuration_type="CARGO_ON_TRUCK",
                    max_actual_axle_load_kg=7_500,
                ),
            ],
        ),
    )


async def make_shift(
    session: AsyncSession,
    warehouse: Warehouse,
    driver: Driver,
    vehicle: Vehicle,
    *,
    date_from: date = date(2026, 8, 1),
    date_to: date = date(2026, 8, 31),
    start_time: time = time(8),
    end_time: time = time(20),
) -> DriverShift:
    """Persist one repeated daily shift for a bounded monthly range."""

    return await catalog.create_shift(
        session,
        warehouse.id,
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date_from,
            date_to=date_to,
            start_time=start_time,
            end_time=end_time,
        ),
    )


async def make_request(
    session: AsyncSession,
    warehouse: Warehouse,
    *,
    planning_date: date = date(2026, 8, 30),
    mandatory: bool = False,
    quantity: int = 2,
) -> LogisticsRequest:
    """Persist a classified delivery request and its vehicle-sized task rows."""

    return await catalog.create_request(
        session,
        warehouse.id,
        LogisticsRequestCreate(
            type="DELIVERY",
            name="Test delivery",
            address_label="Test address",
            latitude=59.94,
            longitude=30.33,
            quantity=quantity,
            cargo_length_mm=6_000,
            cargo_width_mm=2_400,
            cargo_height_mm=2_400,
            cargo_weight_kg=1_200,
            mandatory=mandatory,
            trailer_access_allowed=True,
            date_options=[
                RequestDateOptionInput(
                    date=planning_date,
                    window_start=time(10),
                    window_end=time(14),
                    is_hard=True,
                )
            ],
        ),
    )
