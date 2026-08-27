"""SQLAlchemy persistence model for simulator inputs, plans, and trace data."""

from __future__ import annotations

from datetime import date, datetime, time
from enum import StrEnum
from typing import Any
from uuid import UUID

from geoalchemy2 import Geometry
from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    Time,
    UniqueConstraint,
    Uuid,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.mutable import MutableDict, MutableList
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base, TimestampMixin, UuidPrimaryKeyMixin


class RequestType(StrEnum):
    """Supported physical request directions."""

    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"


class RequestStatus(StrEnum):
    """Lifecycle status of a source logistics request."""

    DRAFT = "DRAFT"
    READY = "READY"
    PLANNED = "PLANNED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    UNASSIGNED = "UNASSIGNED"


class TaskStatus(StrEnum):
    """Planning lifecycle of one vehicle-sized request part."""

    READY = "READY"
    PLANNED = "PLANNED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    UNASSIGNED = "UNASSIGNED"


class ZoneClassificationStatus(StrEnum):
    """Result of authoritative server-side point classification."""

    CLASSIFIED = "CLASSIFIED"
    OUTSIDE_ZONES = "OUTSIDE_ZONES"


class RelationType(StrEnum):
    """Operational meaning of a directed zone transition."""

    ADJACENT = "ADJACENT"
    PREFERRED = "PREFERRED"
    ALLOWED = "ALLOWED"
    DISCOURAGED = "DISCOURAGED"
    BLOCKED = "BLOCKED"


class PlanStatus(StrEnum):
    """Persistence lifecycle of a dated route plan."""

    DRAFT = "DRAFT"
    GENERATED = "GENERATED"
    VALIDATED = "VALIDATED"
    CONFIRMED = "CONFIRMED"
    ARCHIVED = "ARCHIVED"


class OptimizationStatus(StrEnum):
    """Execution lifecycle for an optimizer invocation."""

    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"


class StopType(StrEnum):
    """Operational action performed at a route stop."""

    DEPOT_LOAD = "DEPOT_LOAD"
    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"
    DEPOT_UNLOAD = "DEPOT_UNLOAD"
    DEPOT_RETURN = "DEPOT_RETURN"


class VehicleLoadProfileType(StrEnum):
    """Operational vehicle states with explicitly measured peak axle loads."""

    EMPTY_TRUCK = "EMPTY_TRUCK"
    CARGO_ON_TRUCK = "CARGO_ON_TRUCK"
    EMPTY_COMBINATION = "EMPTY_COMBINATION"
    CARGO_ON_TRUCK_WITH_TRAILER = "CARGO_ON_TRUCK_WITH_TRAILER"
    CARGO_ON_TRAILER_WITH_TRAILER = "CARGO_ON_TRAILER_WITH_TRAILER"
    TWO_CARGO_SPLIT = "TWO_CARGO_SPLIT"


class OsmRestrictionImport(Base):
    """Completed, immutable import metadata for one OpenStreetMap data version."""

    __tablename__ = "osm_restriction_imports"
    __table_args__ = (CheckConstraint("restriction_count >= 0", name="nonnegative_count"),)

    osm_data_version: Mapped[str] = mapped_column(String(128), primary_key=True)
    source_file: Mapped[str] = mapped_column(String(255), nullable=False)
    restriction_count: Mapped[int] = mapped_column(Integer, nullable=False)
    imported_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class OsmTruckRestriction(UuidPrimaryKeyMixin, Base):
    """One spatial OSM truck restriction extracted for map diagnostics."""

    __tablename__ = "osm_truck_restrictions"
    __table_args__ = (
        UniqueConstraint(
            "osm_data_version",
            "osm_type",
            "osm_id",
            name="uq_osm_truck_restrictions_version_object",
        ),
        CheckConstraint("osm_type IN ('node', 'way')", name="supported_osm_type"),
        CheckConstraint(
            "category IN ('HGV_ACCESS', 'MAX_HEIGHT', 'MAX_WIDTH', 'MAX_LENGTH', "
            "'MAX_WEIGHT', 'MAX_AXLE_LOAD', 'CONDITIONAL', 'TRAILER_ACCESS')",
            name="supported_category",
        ),
        CheckConstraint(
            "support_status IN ('SUPPORTED', 'PARTIAL', 'UNSUPPORTED')",
            name="supported_status",
        ),
        Index(
            "ix_osm_truck_restrictions_version_category",
            "osm_data_version",
            "category",
        ),
        Index("ix_osm_truck_restrictions_geometry_gist", "geometry", postgresql_using="gist"),
    )

    osm_data_version: Mapped[str] = mapped_column(
        String(128),
        ForeignKey(
            "osm_restriction_imports.osm_data_version",
            ondelete="CASCADE",
            name="fk_osm_truck_restrictions_import_version",
        ),
        nullable=False,
    )
    osm_type: Mapped[str] = mapped_column(String(8), nullable=False)
    osm_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    category: Mapped[str] = mapped_column(String(32), nullable=False)
    primary_tag: Mapped[str] = mapped_column(String(64), nullable=False)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    tags: Mapped[dict[str, str]] = mapped_column(JSONB, nullable=False)
    support_status: Mapped[str] = mapped_column(String(16), nullable=False)
    geometry: Mapped[Any] = mapped_column(
        Geometry("GEOMETRY", srid=4326, spatial_index=False), nullable=False
    )


class Scenario(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Independent, reproducible logistics experiment and its configuration."""

    __tablename__ = "scenarios"
    __table_args__ = (
        CheckConstraint(
            "capacity_generation >= 0",
            name="nonnegative_capacity_generation",
        ),
    )

    name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    timezone: Mapped[str] = mapped_column(String(100), nullable=False, default="Europe/Moscow")
    default_planning_date: Mapped[date | None] = mapped_column(Date)
    seed: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    capacity_generation: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    settings: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )

    warehouses: Mapped[list[Warehouse]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    zones: Mapped[list[Zone]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    zone_relations: Mapped[list[ZoneRelation]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    drivers: Mapped[list[Driver]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    vehicles: Mapped[list[Vehicle]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    trailers: Mapped[list[Trailer]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    shifts: Mapped[list[DriverShift]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    requests: Mapped[list[LogisticsRequest]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    plans: Mapped[list[RoutePlan]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )
    optimization_runs: Mapped[list[OptimizationRun]] = relationship(
        back_populates="scenario", cascade="all, delete-orphan", passive_deletes=True
    )


class Warehouse(UuidPrimaryKeyMixin, Base):
    """Depot from which route cycles load, depart, return, and unload."""

    __tablename__ = "warehouses"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id",
            "external_warehouse_id",
            name="uq_warehouses_scenario_external_warehouse",
        ),
        CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
        CheckConstraint("loading_minutes >= 0", name="nonnegative_loading"),
        CheckConstraint("unloading_minutes >= 0", name="nonnegative_unloading"),
        CheckConstraint("turnaround_minutes >= 0", name="nonnegative_turnaround"),
        Index("ix_warehouses_scenario_id", "scenario_id"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    external_warehouse_id: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True))
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    latitude: Mapped[float] = mapped_column(Float, nullable=False)
    longitude: Mapped[float] = mapped_column(Float, nullable=False)
    loading_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    unloading_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    turnaround_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=15)
    working_day_start: Mapped[time] = mapped_column(Time, nullable=False, default=time(8))
    working_day_end: Mapped[time] = mapped_column(Time, nullable=False, default=time(20))

    scenario: Mapped[Scenario] = relationship(back_populates="warehouses")
    plans: Mapped[list[RoutePlan]] = relationship(back_populates="warehouse")


class Zone(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Versioned operational polygon with direction-specific test tariffs."""

    __tablename__ = "zones"
    __table_args__ = (
        UniqueConstraint("scenario_id", "code", name="uq_zones_scenario_code"),
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint("delivery_price >= 0", name="nonnegative_delivery_price"),
        CheckConstraint("pickup_price >= 0", name="nonnegative_pickup_price"),
        Index("ix_zones_scenario_priority", "scenario_id", "priority"),
        Index("ix_zones_geometry_gist", "geometry", postgresql_using="gist"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    code: Mapped[str] = mapped_column(String(64), nullable=False)
    route_group: Mapped[str] = mapped_column(String(100), nullable=False)
    geometry: Mapped[Any] = mapped_column(
        Geometry("MULTIPOLYGON", srid=4326, spatial_index=False), nullable=False
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    delivery_price: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    pickup_price: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    locked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    scenario: Mapped[Scenario] = relationship(back_populates="zones")
    requests: Mapped[list[LogisticsRequest]] = relationship(back_populates="zone")
    tasks: Mapped[list[PlanningTask]] = relationship(back_populates="zone")


class ZoneRelation(UuidPrimaryKeyMixin, Base):
    """Directed compatibility and detour policy between two operational zones."""

    __tablename__ = "zone_relations"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id", "from_zone_id", "to_zone_id", name="uq_zone_relations_direction"
        ),
        CheckConstraint("from_zone_id <> to_zone_id", name="different_zones"),
        CheckConstraint("max_detour_minutes >= 0", name="nonnegative_detour_minutes"),
        CheckConstraint("max_detour_ratio >= 0", name="nonnegative_detour_ratio"),
        Index("ix_zone_relations_scenario_id", "scenario_id"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    from_zone_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("zones.id", ondelete="CASCADE"), nullable=False
    )
    to_zone_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("zones.id", ondelete="CASCADE"), nullable=False
    )
    relation_type: Mapped[str] = mapped_column(String(32), nullable=False)
    delivery_pair_allowed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    pickup_allowed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    max_detour_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=35)
    max_detour_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=1.5)
    penalty: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    is_bidirectional: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    scenario: Mapped[Scenario] = relationship(back_populates="zone_relations")
    from_zone: Mapped[Zone] = relationship(foreign_keys=[from_zone_id])
    to_zone: Mapped[Zone] = relationship(foreign_keys=[to_zone_id])


class Driver(UuidPrimaryKeyMixin, Base):
    """Assignable operator whose route-group preference remains a soft signal."""

    __tablename__ = "drivers"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id", "external_worker_id", name="uq_drivers_scenario_external_worker"
        ),
        Index("ix_drivers_scenario_active", "scenario_id", "active"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    external_worker_id: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True))
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    preferred_route_group: Mapped[str | None] = mapped_column(String(100))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    passport_details: Mapped[str] = mapped_column(Text, nullable=False, default="")
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")

    scenario: Mapped[Scenario] = relationship(back_populates="drivers")
    shifts: Mapped[list[DriverShift]] = relationship(
        back_populates="driver", cascade="all, delete-orphan", passive_deletes=True
    )


class Trailer(UuidPrimaryKeyMixin, Base):
    """Scenario-owned trailer whose physical limits participate in truck routing."""

    __tablename__ = "trailers"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id", "registration_number", name="uq_trailers_scenario_registration"
        ),
        CheckConstraint(
            "(tare_weight_kg IS NULL OR tare_weight_kg > 0) AND "
            "(max_gross_weight_kg IS NULL OR max_gross_weight_kg > 0) AND "
            "(length_mm IS NULL OR length_mm > 0) AND "
            "(width_mm IS NULL OR width_mm > 0) AND "
            "(height_mm IS NULL OR height_mm > 0) AND "
            "(platform_length_mm IS NULL OR platform_length_mm > 0) AND "
            "(platform_width_mm IS NULL OR platform_width_mm > 0) AND "
            "(platform_height_from_ground_mm IS NULL OR platform_height_from_ground_mm > 0) AND "
            "(max_platform_payload_kg IS NULL OR max_platform_payload_kg > 0) AND "
            "(payload_capacity_kg IS NULL OR payload_capacity_kg > 0) AND "
            "(axle_count IS NULL OR axle_count > 0) AND "
            "(max_axle_load_kg IS NULL OR max_axle_load_kg > 0) AND "
            "(max_cargo_length_mm IS NULL OR max_cargo_length_mm > 0) AND "
            "(max_cargo_width_mm IS NULL OR max_cargo_width_mm > 0) AND "
            "(max_cargo_height_mm IS NULL OR max_cargo_height_mm > 0) AND "
            "(max_cargo_weight_kg IS NULL OR max_cargo_weight_kg > 0)",
            name="positive_optional_routing_values",
        ),
        CheckConstraint(
            "max_gross_weight_kg IS NULL OR tare_weight_kg IS NULL OR "
            "max_gross_weight_kg >= tare_weight_kg",
            name="gross_not_below_tare",
        ),
        Index("ix_trailers_scenario_active", "scenario_id", "active"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    registration_number: Mapped[str] = mapped_column(String(64), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    tare_weight_kg: Mapped[int | None] = mapped_column(Integer)
    max_gross_weight_kg: Mapped[int | None] = mapped_column(Integer)
    length_mm: Mapped[int | None] = mapped_column(Integer)
    width_mm: Mapped[int | None] = mapped_column(Integer)
    height_mm: Mapped[int | None] = mapped_column(Integer)
    platform_length_mm: Mapped[int | None] = mapped_column(Integer)
    platform_width_mm: Mapped[int | None] = mapped_column(Integer)
    platform_height_from_ground_mm: Mapped[int | None] = mapped_column(Integer)
    max_platform_payload_kg: Mapped[int | None] = mapped_column(Integer)
    payload_capacity_kg: Mapped[int | None] = mapped_column(Integer)
    axle_count: Mapped[int | None] = mapped_column(Integer)
    max_axle_load_kg: Mapped[int | None] = mapped_column(Integer)
    max_cargo_length_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_width_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_height_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_weight_kg: Mapped[int | None] = mapped_column(Integer)
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")

    scenario: Mapped[Scenario] = relationship(back_populates="trailers")
    default_for_vehicles: Mapped[list[Vehicle]] = relationship(
        back_populates="default_trailer", foreign_keys="Vehicle.default_trailer_id"
    )


class Vehicle(UuidPrimaryKeyMixin, Base):
    """Cabin-carrying vehicle with optional complete physical routing data."""

    __tablename__ = "vehicles"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id", "registration_number", name="uq_vehicles_scenario_registration"
        ),
        CheckConstraint("capacity BETWEEN 1 AND 2", name="valid_capacity"),
        CheckConstraint("average_speed_city > 0", name="positive_city_speed"),
        CheckConstraint("average_speed_region > 0", name="positive_region_speed"),
        CheckConstraint(
            "(tare_weight_kg IS NULL OR tare_weight_kg > 0) AND "
            "(max_gross_weight_kg IS NULL OR max_gross_weight_kg > 0) AND "
            "(length_mm IS NULL OR length_mm > 0) AND "
            "(width_mm IS NULL OR width_mm > 0) AND "
            "(height_mm IS NULL OR height_mm > 0) AND "
            "(axle_count IS NULL OR axle_count > 0) AND "
            "(max_axle_load_kg IS NULL OR max_axle_load_kg > 0) AND "
            "(payload_capacity_kg IS NULL OR payload_capacity_kg > 0) AND "
            "(platform_length_mm IS NULL OR platform_length_mm > 0) AND "
            "(platform_width_mm IS NULL OR platform_width_mm > 0) AND "
            "(platform_height_from_ground_mm IS NULL OR platform_height_from_ground_mm > 0) AND "
            "(max_platform_payload_kg IS NULL OR max_platform_payload_kg > 0) AND "
            "(max_cargo_length_mm IS NULL OR max_cargo_length_mm > 0) AND "
            "(max_cargo_width_mm IS NULL OR max_cargo_width_mm > 0) AND "
            "(max_cargo_height_mm IS NULL OR max_cargo_height_mm > 0) AND "
            "(max_cargo_weight_kg IS NULL OR max_cargo_weight_kg > 0) AND "
            "(combined_length_with_trailer_mm IS NULL OR "
            "combined_length_with_trailer_mm > 0) AND "
            "(coupling_length_mm IS NULL OR coupling_length_mm > 0)",
            name="positive_optional_routing_values",
        ),
        CheckConstraint(
            "max_gross_weight_kg IS NULL OR tare_weight_kg IS NULL OR "
            "max_gross_weight_kg >= tare_weight_kg",
            name="gross_not_below_tare",
        ),
        CheckConstraint(
            "height_safety_margin_mm >= 0 AND width_safety_margin_mm >= 0 AND "
            "weight_safety_margin_kg >= 0",
            name="nonnegative_routing_safety_margins",
        ),
        Index("ix_vehicles_scenario_active", "scenario_id", "active"),
        Index("ix_vehicles_default_trailer_id", "default_trailer_id"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    registration_number: Mapped[str] = mapped_column(String(64), nullable=False)
    capacity: Mapped[int] = mapped_column(Integer, nullable=False, default=2)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    average_speed_city: Mapped[float] = mapped_column(Float, nullable=False, default=35)
    average_speed_region: Mapped[float] = mapped_column(Float, nullable=False, default=65)
    vehicle_type: Mapped[str | None] = mapped_column(String(64))
    manufacturer: Mapped[str | None] = mapped_column(String(100))
    model: Mapped[str | None] = mapped_column(String(100))
    is_hgv: Mapped[bool | None] = mapped_column(Boolean)
    tare_weight_kg: Mapped[int | None] = mapped_column(Integer)
    max_gross_weight_kg: Mapped[int | None] = mapped_column(Integer)
    length_mm: Mapped[int | None] = mapped_column(Integer)
    width_mm: Mapped[int | None] = mapped_column(Integer)
    height_mm: Mapped[int | None] = mapped_column(Integer)
    axle_count: Mapped[int | None] = mapped_column(Integer)
    max_axle_load_kg: Mapped[int | None] = mapped_column(Integer)
    payload_capacity_kg: Mapped[int | None] = mapped_column(Integer)
    platform_length_mm: Mapped[int | None] = mapped_column(Integer)
    platform_width_mm: Mapped[int | None] = mapped_column(Integer)
    platform_height_from_ground_mm: Mapped[int | None] = mapped_column(Integer)
    max_platform_payload_kg: Mapped[int | None] = mapped_column(Integer)
    max_cargo_length_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_width_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_height_mm: Mapped[int | None] = mapped_column(Integer)
    max_cargo_weight_kg: Mapped[int | None] = mapped_column(Integer)
    can_use_trailer: Mapped[bool | None] = mapped_column(Boolean)
    default_trailer_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("trailers.id", ondelete="SET NULL")
    )
    combined_length_with_trailer_mm: Mapped[int | None] = mapped_column(Integer)
    coupling_length_mm: Mapped[int | None] = mapped_column(Integer)
    height_safety_margin_mm: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    width_safety_margin_mm: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    weight_safety_margin_kg: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")

    scenario: Mapped[Scenario] = relationship(back_populates="vehicles")
    default_trailer: Mapped[Trailer | None] = relationship(
        back_populates="default_for_vehicles", foreign_keys=[default_trailer_id]
    )
    shifts: Mapped[list[DriverShift]] = relationship(
        back_populates="vehicle", cascade="all, delete-orphan", passive_deletes=True
    )
    load_profiles: Mapped[list[VehicleLoadProfile]] = relationship(
        back_populates="vehicle",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="VehicleLoadProfile.configuration_type",
    )


class VehicleLoadProfile(UuidPrimaryKeyMixin, Base):
    """Configured peak axle load for one vehicle and operational load state."""

    __tablename__ = "vehicle_load_profiles"
    __table_args__ = (
        UniqueConstraint(
            "vehicle_id", "configuration_type", name="uq_vehicle_load_profiles_configuration"
        ),
        CheckConstraint("max_actual_axle_load_kg > 0", name="positive_actual_axle_load"),
        Index("ix_vehicle_load_profiles_vehicle_id", "vehicle_id"),
    )

    vehicle_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("vehicles.id", ondelete="CASCADE"), nullable=False
    )
    configuration_type: Mapped[str] = mapped_column(String(64), nullable=False)
    max_actual_axle_load_kg: Mapped[int] = mapped_column(Integer, nullable=False)

    vehicle: Mapped[Vehicle] = relationship(back_populates="load_profiles")


class DriverShift(UuidPrimaryKeyMixin, Base):
    """Dated availability interval binding one active driver to one vehicle."""

    __tablename__ = "driver_shifts"
    __table_args__ = (
        CheckConstraint("end_at > start_at", name="positive_duration"),
        CheckConstraint("break_minutes >= 0", name="nonnegative_break"),
        Index("ix_driver_shifts_scenario_date", "scenario_id", "date"),
        Index("ix_driver_shifts_driver_interval", "driver_id", "start_at", "end_at"),
        Index("ix_driver_shifts_vehicle_interval", "vehicle_id", "start_at", "end_at"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    driver_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("drivers.id", ondelete="CASCADE"), nullable=False
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("vehicles.id", ondelete="CASCADE"), nullable=False
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    end_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    break_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    preferred_route_group: Mapped[str | None] = mapped_column(String(100))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    scenario: Mapped[Scenario] = relationship(back_populates="shifts")
    driver: Mapped[Driver] = relationship(back_populates="shifts")
    vehicle: Mapped[Vehicle] = relationship(back_populates="shifts")
    cycles: Mapped[list[RouteCycle]] = relationship(back_populates="driver_shift")


class LogisticsRequest(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Source delivery or pickup request classified by backend coordinates."""

    __tablename__ = "logistics_requests"
    __table_args__ = (
        UniqueConstraint(
            "scenario_id",
            "source_system",
            "external_id",
            name="uq_logistics_requests_external_source",
        ),
        CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
        CheckConstraint("quantity > 0", name="positive_quantity"),
        CheckConstraint("service_minutes >= 0", name="nonnegative_service"),
        CheckConstraint(
            "(cargo_length_mm IS NULL AND cargo_width_mm IS NULL AND "
            "cargo_height_mm IS NULL AND cargo_weight_kg IS NULL) OR "
            "(cargo_length_mm > 0 AND cargo_width_mm > 0 AND "
            "cargo_height_mm > 0 AND cargo_weight_kg > 0)",
            name="complete_positive_cargo_dimensions",
        ),
        CheckConstraint(
            "external_version IS NULL OR external_version >= 0",
            name="nonnegative_external_version",
        ),
        Index("ix_logistics_requests_scenario_status", "scenario_id", "status"),
        Index(
            "ix_logistics_requests_scenario_scheduled_date",
            "scenario_id",
            "scheduled_date",
        ),
        Index("ix_logistics_requests_zone_version", "zone_id", "zone_version"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    source_system: Mapped[str | None] = mapped_column(String(64))
    external_id: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True))
    external_version: Mapped[int | None] = mapped_column(Integer)
    external_payload: Mapped[dict[str, Any] | None] = mapped_column(MutableDict.as_mutable(JSONB))
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    address_label: Mapped[str] = mapped_column(String(500), nullable=False, default="")
    latitude: Mapped[float] = mapped_column(Float, nullable=False)
    longitude: Mapped[float] = mapped_column(Float, nullable=False)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    cargo_length_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_width_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_height_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_weight_kg: Mapped[int | None] = mapped_column(Integer)
    service_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default=RequestStatus.READY)
    scheduled_date: Mapped[date | None] = mapped_column(Date)
    zone_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("zones.id", ondelete="SET NULL")
    )
    zone_version: Mapped[int | None] = mapped_column(Integer)
    zone_classification_status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=ZoneClassificationStatus.OUTSIDE_ZONES
    )
    split_allowed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    trailer_access_allowed: Mapped[bool | None] = mapped_column(Boolean)
    include_driver_passport_in_notification: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False
    )
    contact_name: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    contact_phone: Mapped[str] = mapped_column(String(64), nullable=False, default="")
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")

    scenario: Mapped[Scenario] = relationship(back_populates="requests")
    zone: Mapped[Zone | None] = relationship(back_populates="requests")
    date_options: Mapped[list[RequestDateOption]] = relationship(
        back_populates="request",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="RequestDateOption.priority, RequestDateOption.date",
    )
    tasks: Mapped[list[PlanningTask]] = relationship(
        back_populates="request",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="PlanningTask.part_number",
    )
    notification_logs: Mapped[list[PlanNotificationLog]] = relationship(
        back_populates="request", passive_deletes=True
    )


class RequestDateOption(UuidPrimaryKeyMixin, Base):
    """One acceptable local scenario date and optional service window."""

    __tablename__ = "request_date_options"
    __table_args__ = (
        UniqueConstraint("request_id", "date", name="uq_request_date_options_request_date"),
        CheckConstraint(
            "window_start IS NULL OR window_end IS NULL OR window_end > window_start",
            name="valid_window",
        ),
        CheckConstraint(
            "travel_zone_hours IS NULL OR travel_zone_hours BETWEEN 1 AND 4",
            name="valid_travel_zone_hours",
        ),
        Index("ix_request_date_options_date", "date"),
    )

    request_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("logistics_requests.id", ondelete="CASCADE"),
        nullable=False,
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    window_start: Mapped[time | None] = mapped_column(Time)
    window_end: Mapped[time | None] = mapped_column(Time)
    is_hard: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    travel_zone_hours: Mapped[int | None] = mapped_column(Integer)

    request: Mapped[LogisticsRequest] = relationship(back_populates="date_options")


class PlanningTask(UuidPrimaryKeyMixin, Base):
    """Immutable vehicle-sized part generated from one source request."""

    __tablename__ = "planning_tasks"
    __table_args__ = (
        UniqueConstraint("request_id", "part_number", name="uq_planning_tasks_request_part"),
        CheckConstraint("quantity BETWEEN 1 AND 2", name="valid_quantity"),
        CheckConstraint("service_minutes >= 0", name="nonnegative_service"),
        CheckConstraint(
            "(cargo_length_mm IS NULL AND cargo_width_mm IS NULL AND "
            "cargo_height_mm IS NULL AND cargo_weight_kg IS NULL) OR "
            "(cargo_length_mm > 0 AND cargo_width_mm > 0 AND "
            "cargo_height_mm > 0 AND cargo_weight_kg > 0)",
            name="complete_positive_cargo_dimensions",
        ),
        Index("ix_planning_tasks_zone_status", "zone_id", "status"),
    )

    request_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("logistics_requests.id", ondelete="CASCADE"),
        nullable=False,
    )
    part_number: Mapped[int] = mapped_column(Integer, nullable=False)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    cargo_length_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_width_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_height_mm: Mapped[int | None] = mapped_column(Integer)
    cargo_weight_kg: Mapped[int | None] = mapped_column(Integer)
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    latitude: Mapped[float] = mapped_column(Float, nullable=False)
    longitude: Mapped[float] = mapped_column(Float, nullable=False)
    zone_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("zones.id", ondelete="SET NULL")
    )
    zone_version: Mapped[int | None] = mapped_column(Integer)
    service_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    priority: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default=TaskStatus.READY)
    locked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    request: Mapped[LogisticsRequest] = relationship(back_populates="tasks")
    zone: Mapped[Zone | None] = relationship(back_populates="tasks")
    route_stops: Mapped[list[RouteStop]] = relationship(back_populates="task")


class RoutePlan(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Versioned, independently saved route plan for one warehouse and date."""

    __tablename__ = "route_plans"
    __table_args__ = (
        CheckConstraint("version >= 1", name="positive_version"),
        Index("ix_route_plans_scenario_date", "scenario_id", "date"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="RESTRICT"), nullable=False
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False, default="Автоплан")
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default=PlanStatus.DRAFT)
    score: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    metrics: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    validation_errors: Mapped[list[dict[str, Any]]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    validation_warnings: Mapped[list[dict[str, Any]]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    manually_changed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    scenario: Mapped[Scenario] = relationship(back_populates="plans")
    warehouse: Mapped[Warehouse] = relationship(back_populates="plans")
    cycles: Mapped[list[RouteCycle]] = relationship(
        back_populates="route_plan",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="RouteCycle.sequence",
    )
    unassigned_tasks: Mapped[list[UnassignedTask]] = relationship(
        back_populates="route_plan", cascade="all, delete-orphan", passive_deletes=True
    )
    optimization_runs: Mapped[list[OptimizationRun]] = relationship(back_populates="plan")
    manual_changes: Mapped[list[ManualChangeAudit]] = relationship(
        back_populates="route_plan", cascade="all, delete-orphan", passive_deletes=True
    )
    notification_logs: Mapped[list[PlanNotificationLog]] = relationship(
        back_populates="route_plan",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="PlanNotificationLog.created_at, PlanNotificationLog.id",
    )


class RouteCycle(UuidPrimaryKeyMixin, Base):
    """One depot-to-depot trip assigned to a driver shift."""

    __tablename__ = "route_cycles"
    __table_args__ = (
        UniqueConstraint(
            "route_plan_id",
            "driver_shift_id",
            "sequence",
            name="uq_route_cycles_plan_shift_sequence",
        ),
        CheckConstraint("planned_finish >= planned_start", name="valid_interval"),
        CheckConstraint("total_distance_meters >= 0", name="nonnegative_distance"),
        CheckConstraint("total_travel_seconds >= 0", name="nonnegative_travel"),
        CheckConstraint("total_service_seconds >= 0", name="nonnegative_service"),
        CheckConstraint("empty_distance_meters >= 0", name="nonnegative_empty_distance"),
        CheckConstraint("detour_seconds >= 0", name="nonnegative_detour"),
        Index(
            "ix_route_cycles_shift_interval",
            "driver_shift_id",
            "planned_start",
            "planned_finish",
        ),
        Index(
            "ix_route_cycles_plan_shift_sequence",
            "route_plan_id",
            "driver_shift_id",
            "sequence",
        ),
    )

    route_plan_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="CASCADE"), nullable=False
    )
    driver_shift_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("driver_shifts.id", ondelete="RESTRICT"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    planned_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    planned_finish: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    total_distance_meters: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    total_travel_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total_service_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    empty_distance_meters: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    detour_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    score: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    locked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    manually_changed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    metrics: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )

    route_plan: Mapped[RoutePlan] = relationship(back_populates="cycles")
    driver_shift: Mapped[DriverShift] = relationship(back_populates="cycles")
    stops: Mapped[list[RouteStop]] = relationship(
        back_populates="route_cycle",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="RouteStop.sequence",
    )
    segments: Mapped[list[RouteSegment]] = relationship(
        back_populates="route_cycle",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="RouteSegment.sequence",
    )
    explanations: Mapped[list[RouteExplanation]] = relationship(
        back_populates="route_cycle", cascade="all, delete-orphan", passive_deletes=True
    )


class RouteStop(UuidPrimaryKeyMixin, Base):
    """Scheduled depot or customer action with explicit load transition."""

    __tablename__ = "route_stops"
    __table_args__ = (
        UniqueConstraint("route_cycle_id", "sequence", name="uq_route_stops_cycle_sequence"),
        CheckConstraint("planned_departure >= planned_arrival", name="valid_interval"),
        CheckConstraint("service_seconds >= 0", name="nonnegative_service"),
        CheckConstraint("load_before >= 0", name="nonnegative_load_before"),
        CheckConstraint("load_after >= 0", name="nonnegative_load_after"),
        CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
    )

    route_cycle_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_cycles.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    task_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("planning_tasks.id", ondelete="RESTRICT")
    )
    stop_type: Mapped[str] = mapped_column(String(32), nullable=False)
    planned_arrival: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    planned_departure: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    service_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    quantity_delta: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    load_before: Mapped[int] = mapped_column(Integer, nullable=False)
    load_after: Mapped[int] = mapped_column(Integer, nullable=False)
    latitude: Mapped[float] = mapped_column(Float, nullable=False)
    longitude: Mapped[float] = mapped_column(Float, nullable=False)
    warnings: Mapped[list[dict[str, Any]]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    locked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    route_cycle: Mapped[RouteCycle] = relationship(back_populates="stops")
    task: Mapped[PlanningTask | None] = relationship(back_populates="route_stops")


class RouteSegment(UuidPrimaryKeyMixin, Base):
    """Timed route geometry between two consecutive stops for map simulation."""

    __tablename__ = "route_segments"
    __table_args__ = (
        UniqueConstraint("route_cycle_id", "sequence", name="uq_route_segments_cycle_sequence"),
        CheckConstraint("arrival_at >= departure_at", name="valid_interval"),
        CheckConstraint("distance_meters >= 0", name="nonnegative_distance"),
        CheckConstraint("travel_seconds >= 0", name="nonnegative_travel"),
    )

    route_cycle_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_cycles.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    from_stop_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_stops.id", ondelete="CASCADE"), nullable=False
    )
    to_stop_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_stops.id", ondelete="CASCADE"), nullable=False
    )
    departure_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    arrival_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    distance_meters: Mapped[float] = mapped_column(Float, nullable=False)
    travel_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    geometry: Mapped[Any] = mapped_column(
        Geometry("LINESTRING", srid=4326, spatial_index=False), nullable=False
    )
    routing_profile_snapshot: Mapped[dict[str, Any] | None] = mapped_column(
        MutableDict.as_mutable(JSONB)
    )
    routing_provider: Mapped[str | None] = mapped_column(String(64))
    osm_data_version: Mapped[str | None] = mapped_column(String(100))
    routed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    route_cycle: Mapped[RouteCycle] = relationship(back_populates="segments")
    from_stop: Mapped[RouteStop] = relationship(foreign_keys=[from_stop_id])
    to_stop: Mapped[RouteStop] = relationship(foreign_keys=[to_stop_id])


class RouteExplanation(UuidPrimaryKeyMixin, Base):
    """Persisted planner explanation shown without recomputing rationale in UI."""

    __tablename__ = "route_explanations"

    route_cycle_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_cycles.id", ondelete="CASCADE"), nullable=False
    )
    explanation_type: Mapped[str] = mapped_column(String(64), nullable=False)
    summary_ru: Mapped[str] = mapped_column(Text, nullable=False)
    facts: Mapped[list[dict[str, Any]]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )

    route_cycle: Mapped[RouteCycle] = relationship(back_populates="explanations")


class UnassignedTask(UuidPrimaryKeyMixin, Base):
    """Structured failure reasons and recommendation for an unplanned task."""

    __tablename__ = "unassigned_tasks"
    __table_args__ = (
        UniqueConstraint("route_plan_id", "task_id", name="uq_unassigned_tasks_plan_task"),
    )

    route_plan_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="CASCADE"), nullable=False
    )
    task_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("planning_tasks.id", ondelete="RESTRICT"), nullable=False
    )
    reason_codes: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    descriptions_ru: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    nearest_option: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    recommendation_ru: Mapped[str | None] = mapped_column(Text)

    route_plan: Mapped[RoutePlan] = relationship(back_populates="unassigned_tasks")
    task: Mapped[PlanningTask] = relationship()


class PlanNotificationLog(UuidPrimaryKeyMixin, Base):
    """Simulated contact notification produced once per assigned source request."""

    __tablename__ = "plan_notification_logs"
    __table_args__ = (
        UniqueConstraint("plan_id", "request_id", name="uq_plan_notification_logs_plan_request"),
        CheckConstraint("status = 'SIMULATED_DELIVERED'", name="simulated_status"),
        Index("ix_plan_notification_logs_request_id", "request_id"),
    )

    plan_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="CASCADE"), nullable=False
    )
    request_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("logistics_requests.id", ondelete="CASCADE"),
        nullable=False,
    )
    recipient_name: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    recipient_contact: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    message: Mapped[str] = mapped_column(Text, nullable=False)
    includes_driver_passport: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="SIMULATED_DELIVERED")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    route_plan: Mapped[RoutePlan] = relationship(back_populates="notification_logs")
    request: Mapped[LogisticsRequest] = relationship(back_populates="notification_logs")


class OptimizationRun(UuidPrimaryKeyMixin, Base):
    """Auditable asynchronous optimizer execution independent of a saved plan."""

    __tablename__ = "optimization_runs"
    __table_args__ = (Index("ix_optimization_runs_scenario_started", "scenario_id", "started_at"),)

    scenario_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False
    )
    plan_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="SET NULL")
    )
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=OptimizationStatus.PENDING
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    seed: Mapped[int] = mapped_column(Integer, nullable=False)
    settings_snapshot: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    initial_score: Mapped[float | None] = mapped_column(Float)
    final_score: Mapped[float | None] = mapped_column(Float)
    error_message: Mapped[str | None] = mapped_column(Text)
    stopped_by_limit: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    cancel_requested: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    scenario: Mapped[Scenario] = relationship(back_populates="optimization_runs")
    plan: Mapped[RoutePlan | None] = relationship(back_populates="optimization_runs")
    trace_events: Mapped[list[OptimizationTraceEvent]] = relationship(
        back_populates="optimization_run",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="OptimizationTraceEvent.sequence",
    )


class OptimizationTraceEvent(UuidPrimaryKeyMixin, Base):
    """Bounded progress event that mirrors a real optimizer decision or phase."""

    __tablename__ = "optimization_trace_events"
    __table_args__ = (
        UniqueConstraint("optimization_run_id", "sequence", name="uq_trace_run_sequence"),
        Index("ix_trace_run_sequence", "optimization_run_id", "sequence"),
    )

    optimization_run_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("optimization_runs.id", ondelete="CASCADE"),
        nullable=False,
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    optimization_run: Mapped[OptimizationRun] = relationship(back_populates="trace_events")


class ManualChangeAudit(UuidPrimaryKeyMixin, Base):
    """Immutable record of an explicitly requested manual plan mutation."""

    __tablename__ = "manual_change_audits"
    __table_args__ = (Index("ix_manual_changes_plan_changed", "route_plan_id", "changed_at"),)

    route_plan_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="CASCADE"), nullable=False
    )
    changed_by: Mapped[str] = mapped_column(String(200), nullable=False, default="local-admin")
    changed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    change_type: Mapped[str] = mapped_column(String(64), nullable=False)
    previous_value: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    new_value: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    plan_version_before: Mapped[int] = mapped_column(Integer, nullable=False)
    plan_version_after: Mapped[int] = mapped_column(Integer, nullable=False)

    route_plan: Mapped[RoutePlan] = relationship(back_populates="manual_changes")
