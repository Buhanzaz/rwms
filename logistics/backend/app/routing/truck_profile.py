"""Pure calculation of the effective road vehicle for one routed leg."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Never
from uuid import UUID

type EntityId = UUID | str


class CargoPosition(StrEnum):
    """Physical platform on which one cargo unit is carried."""

    TRUCK_PLATFORM = "TRUCK_PLATFORM"
    TRAILER_PLATFORM = "TRAILER_PLATFORM"


class TruckConfigurationType(StrEnum):
    """Operational load state used to select an observed axle-load profile."""

    EMPTY_TRUCK = "EMPTY_TRUCK"
    CARGO_ON_TRUCK = "CARGO_ON_TRUCK"
    EMPTY_COMBINATION = "EMPTY_COMBINATION"
    CARGO_ON_TRUCK_WITH_TRAILER = "CARGO_ON_TRUCK_WITH_TRAILER"
    CARGO_ON_TRAILER_WITH_TRAILER = "CARGO_ON_TRAILER_WITH_TRAILER"
    TWO_CARGO_SPLIT = "TWO_CARGO_SPLIT"


class TruckProfileErrorCode(StrEnum):
    """Stable domain failures returned before any routing request is attempted."""

    CARGO_TOO_HEAVY = "CARGO_TOO_HEAVY"
    CARGO_TOO_LONG = "CARGO_TOO_LONG"
    CARGO_TOO_WIDE = "CARGO_TOO_WIDE"
    CARGO_TOO_HIGH = "CARGO_TOO_HIGH"
    TRAILER_REQUIRED = "TRAILER_REQUIRED"
    NO_COMPATIBLE_TRAILER = "NO_COMPATIBLE_TRAILER"
    AXLE_LOAD_EXCEEDED = "AXLE_LOAD_EXCEEDED"
    ROUTING_PROFILE_INCOMPLETE = "ROUTING_PROFILE_INCOMPLETE"


class TruckProfileError(ValueError):
    """Domain error carrying a stable code and precise incomplete-field detail."""

    def __init__(
        self,
        code: TruckProfileErrorCode,
        detail: str,
        *,
        missing_fields: Iterable[str] = (),
    ) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.missing_fields = tuple(sorted(set(missing_fields)))


@dataclass(frozen=True, slots=True)
class CargoDimensions:
    """Physical measurements of one cargo unit in metres and tonnes."""

    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    weight_t: Decimal | None = None


@dataclass(frozen=True, slots=True)
class CargoPlacement:
    """One identified cargo unit and the platform carrying it on this leg."""

    cargo_id: EntityId
    position: CargoPosition
    dimensions: CargoDimensions


@dataclass(frozen=True, slots=True)
class VehicleRoutingSpec:
    """Routing-relevant canonical physical specification of the powered vehicle."""

    vehicle_id: EntityId
    is_hgv: bool | None = None
    tare_weight_t: Decimal | None = None
    max_gross_weight_t: Decimal | None = None
    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    axle_count: int | None = None
    axle_load_t: Decimal | None = None
    payload_capacity_t: Decimal | None = None
    platform_length_m: Decimal | None = None
    platform_height_from_ground_m: Decimal | None = None
    max_platform_payload_t: Decimal | None = None
    max_cargo_length_m: Decimal | None = None
    max_cargo_width_m: Decimal | None = None
    max_cargo_height_m: Decimal | None = None
    max_cargo_weight_t: Decimal | None = None
    has_trailer: bool | None = None
    coupling_length_m: Decimal | None = None
    height_safety_margin_m: Decimal = Decimal(0)
    width_safety_margin_m: Decimal = Decimal(0)
    weight_safety_margin_t: Decimal = Decimal(0)


@dataclass(frozen=True, slots=True)
class TrailerSpec:
    """Routing-relevant canonical physical specification of an attachable trailer."""

    trailer_id: EntityId
    tare_weight_t: Decimal | None = None
    max_gross_weight_t: Decimal | None = None
    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    platform_length_m: Decimal | None = None
    platform_height_from_ground_m: Decimal | None = None
    max_platform_payload_t: Decimal | None = None
    payload_capacity_t: Decimal | None = None
    axle_count: int | None = None
    axle_load_t: Decimal | None = None
    max_cargo_length_m: Decimal | None = None
    max_cargo_width_m: Decimal | None = None
    max_cargo_height_m: Decimal | None = None
    max_cargo_weight_t: Decimal | None = None


@dataclass(frozen=True, slots=True)
class OperationalAxleLoadProfile:
    """Measured or configured peak axle load for one whole-combination state."""

    configuration_type: TruckConfigurationType
    max_actual_axle_load_t: Decimal


@dataclass(frozen=True, slots=True)
class LoadConfiguration:
    """Attachment and cargo-placement state at the start of a single route leg."""

    vehicle_id: EntityId
    trailer_attached: bool = False
    trailer_id: EntityId | None = None
    cargo_placements: tuple[CargoPlacement, ...] = ()


@dataclass(frozen=True, slots=True)
class EffectiveTruckProfile:
    """Exact SI profile passed to truck routing and persisted for auditability."""

    vehicle_id: EntityId
    trailer_id: EntityId | None
    trailer_attached: bool
    is_hgv: bool
    height_meters: float
    width_meters: float
    length_meters: float
    actual_weight_tons: float
    max_axle_load_tons: float
    axle_count: int
    cargo_count: int
    cargo_placements: tuple[CargoPlacement, ...]
    configuration_type: TruckConfigurationType

    @property
    def has_trailer(self) -> bool:
        """Expose an explicit semantic alias expected by routing diagnostics."""

        return self.trailer_attached

    def cache_key_data(self) -> tuple[object, ...]:
        """Return stable hashable routing inputs excluding provider and endpoints."""

        placements = tuple(
            (
                str(placement.cargo_id),
                placement.position.value,
                placement.dimensions.length_m,
                placement.dimensions.width_m,
                placement.dimensions.height_m,
                placement.dimensions.weight_t,
            )
            for placement in self.cargo_placements
        )
        return (
            str(self.vehicle_id),
            str(self.trailer_id) if self.trailer_id is not None else None,
            self.trailer_attached,
            self.is_hgv,
            self.height_meters,
            self.width_meters,
            self.length_meters,
            self.actual_weight_tons,
            self.max_axle_load_tons,
            self.axle_count,
            self.configuration_type.value,
            placements,
        )

    def to_snapshot(
        self,
        *,
        provider: str | None = None,
        osm_data_version: str | None = None,
        calculated_at: datetime | None = None,
    ) -> dict[str, object]:
        """Build a JSON-safe audit snapshot of the exact routed configuration."""

        snapshot: dict[str, object] = {
            "vehicleId": str(self.vehicle_id),
            "trailerId": str(self.trailer_id) if self.trailer_id is not None else None,
            "trailerAttached": self.trailer_attached,
            "isHgv": self.is_hgv,
            "cargoCount": self.cargo_count,
            "cargoPlacements": [
                {
                    "cargoId": str(placement.cargo_id),
                    "position": placement.position.value,
                    "lengthM": float(placement.dimensions.length_m)
                    if placement.dimensions.length_m is not None
                    else None,
                    "widthM": float(placement.dimensions.width_m)
                    if placement.dimensions.width_m is not None
                    else None,
                    "heightM": float(placement.dimensions.height_m)
                    if placement.dimensions.height_m is not None
                    else None,
                    "weightT": float(placement.dimensions.weight_t)
                    if placement.dimensions.weight_t is not None
                    else None,
                }
                for placement in self.cargo_placements
            ],
            "configurationType": self.configuration_type.value,
            "effectiveHeightMeters": self.height_meters,
            "effectiveWidthMeters": self.width_meters,
            "effectiveLengthMeters": self.length_meters,
            "actualWeightTons": self.actual_weight_tons,
            "maxAxleLoadTons": self.max_axle_load_tons,
            "axleCount": self.axle_count,
        }
        if provider is not None:
            snapshot["routingProvider"] = provider
        if osm_data_version is not None:
            snapshot["osmDataVersion"] = osm_data_version
        if calculated_at is not None:
            snapshot["calculatedAt"] = calculated_at.isoformat()
        return snapshot


class EffectiveTruckProfileCalculator:
    """Validate a physical load state and derive its effective routing profile."""

    def calculate(
        self,
        *,
        vehicle: VehicleRoutingSpec,
        load: LoadConfiguration,
        axle_profiles: Iterable[OperationalAxleLoadProfile],
        trailer: TrailerSpec | None = None,
    ) -> EffectiveTruckProfile:
        """Calculate one leg profile without inventing missing physical inputs."""

        if str(load.vehicle_id) != str(vehicle.vehicle_id):
            self._incomplete("load.vehicle_id does not match vehicle.vehicle_id")
        placements = load.cargo_placements
        self._validate_placement_shape(load)
        attached_trailer = self._resolve_trailer(vehicle, load, trailer)
        self._require_nonnegative_margins(vehicle)

        required_vehicle: dict[str, object | None] = {
            "vehicle.is_hgv": vehicle.is_hgv,
            "vehicle.tare_weight_t": vehicle.tare_weight_t,
            "vehicle.max_gross_weight_t": vehicle.max_gross_weight_t,
            "vehicle.length_m": vehicle.length_m,
            "vehicle.width_m": vehicle.width_m,
            "vehicle.height_m": vehicle.height_m,
            "vehicle.axle_count": vehicle.axle_count,
            "vehicle.axle_load_t": vehicle.axle_load_t,
        }
        truck_cargo = tuple(
            item for item in placements if item.position is CargoPosition.TRUCK_PLATFORM
        )
        trailer_cargo = tuple(
            item for item in placements if item.position is CargoPosition.TRAILER_PLATFORM
        )
        if truck_cargo:
            required_vehicle.update(
                {
                    "vehicle.payload_capacity_t": vehicle.payload_capacity_t,
                    "vehicle.platform_length_m": vehicle.platform_length_m,
                    "vehicle.platform_height_from_ground_m": (
                        vehicle.platform_height_from_ground_m
                    ),
                    "vehicle.max_platform_payload_t": vehicle.max_platform_payload_t,
                    "vehicle.max_cargo_length_m": vehicle.max_cargo_length_m,
                    "vehicle.max_cargo_width_m": vehicle.max_cargo_width_m,
                    "vehicle.max_cargo_height_m": vehicle.max_cargo_height_m,
                    "vehicle.max_cargo_weight_t": vehicle.max_cargo_weight_t,
                }
            )
        self._require_values(required_vehicle)
        self._require_cargo_dimensions(placements)

        if attached_trailer is not None:
            required_trailer: dict[str, object | None] = {
                "trailer.tare_weight_t": attached_trailer.tare_weight_t,
                "trailer.max_gross_weight_t": attached_trailer.max_gross_weight_t,
                "trailer.length_m": attached_trailer.length_m,
                "trailer.width_m": attached_trailer.width_m,
                "trailer.height_m": attached_trailer.height_m,
                "trailer.axle_count": attached_trailer.axle_count,
                "trailer.axle_load_t": attached_trailer.axle_load_t,
                "vehicle.coupling_length_m": vehicle.coupling_length_m,
            }
            if trailer_cargo:
                required_trailer.update(
                    {
                        "trailer.payload_capacity_t": attached_trailer.payload_capacity_t,
                        "trailer.platform_length_m": attached_trailer.platform_length_m,
                        "trailer.platform_height_from_ground_m": (
                            attached_trailer.platform_height_from_ground_m
                        ),
                        "trailer.max_platform_payload_t": (
                            attached_trailer.max_platform_payload_t
                        ),
                        "trailer.max_cargo_length_m": attached_trailer.max_cargo_length_m,
                        "trailer.max_cargo_width_m": attached_trailer.max_cargo_width_m,
                        "trailer.max_cargo_height_m": attached_trailer.max_cargo_height_m,
                        "trailer.max_cargo_weight_t": attached_trailer.max_cargo_weight_t,
                    }
                )
            self._require_values(required_trailer)

        configuration_type = self._configuration_type(load)
        axle_profile = self._select_axle_profile(configuration_type, axle_profiles)
        self._validate_cargo_compatibility(vehicle, truck_cargo, prefix="vehicle")
        if attached_trailer is not None:
            self._validate_cargo_compatibility(attached_trailer, trailer_cargo, prefix="trailer")

        truck_cargo_weight = sum(self._weight(item) for item in truck_cargo)
        trailer_cargo_weight = sum(self._weight(item) for item in trailer_cargo)
        assert vehicle.tare_weight_t is not None
        assert vehicle.max_gross_weight_t is not None
        truck_gross = vehicle.tare_weight_t + truck_cargo_weight
        if truck_gross > vehicle.max_gross_weight_t:
            self._raise(TruckProfileErrorCode.CARGO_TOO_HEAVY, "Vehicle gross weight exceeded")

        trailer_tare = Decimal(0)
        if attached_trailer is not None:
            assert attached_trailer.tare_weight_t is not None
            assert attached_trailer.max_gross_weight_t is not None
            trailer_tare = attached_trailer.tare_weight_t
            if trailer_tare + trailer_cargo_weight > attached_trailer.max_gross_weight_t:
                self._raise(
                    TruckProfileErrorCode.CARGO_TOO_HEAVY,
                    "Trailer gross weight exceeded",
                )

        max_allowed_axle_loads = [vehicle.axle_load_t]
        if attached_trailer is not None:
            max_allowed_axle_loads.append(attached_trailer.axle_load_t)
        # The operational profile is the measured peak across the complete
        # combination, not the load of every axle. A trailer axle may have a
        # lower rating while the recorded peak remains on the powered vehicle.
        allowed_axle_load = max(value for value in max_allowed_axle_loads if value is not None)
        if axle_profile.max_actual_axle_load_t > allowed_axle_load:
            self._raise(
                TruckProfileErrorCode.AXLE_LOAD_EXCEEDED,
                "Configured operational axle load exceeds equipment capability",
            )

        effective_height = self._effective_height(
            vehicle, attached_trailer, truck_cargo, trailer_cargo
        )
        effective_width = self._effective_width(
            vehicle, attached_trailer, truck_cargo, trailer_cargo
        )
        effective_length = self._effective_length(
            vehicle, attached_trailer, truck_cargo, trailer_cargo
        )
        actual_weight_t = (
            vehicle.tare_weight_t
            + trailer_tare
            + truck_cargo_weight
            + trailer_cargo_weight
            + vehicle.weight_safety_margin_t
        )
        assert vehicle.axle_count is not None
        trailer_axles = attached_trailer.axle_count if attached_trailer is not None else 0
        assert trailer_axles is not None
        return EffectiveTruckProfile(
            vehicle_id=vehicle.vehicle_id,
            trailer_id=load.trailer_id,
            trailer_attached=load.trailer_attached,
            is_hgv=bool(vehicle.is_hgv),
            height_meters=float(effective_height),
            width_meters=float(effective_width),
            length_meters=float(effective_length),
            actual_weight_tons=float(actual_weight_t),
            max_axle_load_tons=float(axle_profile.max_actual_axle_load_t),
            axle_count=vehicle.axle_count + trailer_axles,
            cargo_count=len(placements),
            cargo_placements=placements,
            configuration_type=configuration_type,
        )

    def _resolve_trailer(
        self,
        vehicle: VehicleRoutingSpec,
        load: LoadConfiguration,
        trailer: TrailerSpec | None,
    ) -> TrailerSpec | None:
        """Resolve an attached trailer while distinguishing missing from incompatible data."""

        if not load.trailer_attached:
            return None
        if vehicle.has_trailer is None:
            self._incomplete("vehicle.has_trailer")
        if not vehicle.has_trailer or load.trailer_id is None or trailer is None:
            self._raise(
                TruckProfileErrorCode.NO_COMPATIBLE_TRAILER,
                "The attached load state has no compatible trailer",
            )
        if str(load.trailer_id) != str(trailer.trailer_id):
            self._raise(
                TruckProfileErrorCode.NO_COMPATIBLE_TRAILER,
                "load.trailer_id does not match the supplied trailer",
            )
        return trailer

    def _validate_placement_shape(self, load: LoadConfiguration) -> None:
        """Enforce the supported one-unit-per-platform two-cargo arrangement."""

        placements = load.cargo_placements
        if (
            any(item.position is CargoPosition.TRAILER_PLATFORM for item in placements)
            and not load.trailer_attached
        ):
            self._raise(
                TruckProfileErrorCode.TRAILER_REQUIRED,
                "Cargo placed on a trailer requires an attached trailer",
            )
        if len(placements) >= 2 and not load.trailer_attached:
            self._raise(
                TruckProfileErrorCode.TRAILER_REQUIRED,
                "Two cargo units require an attached compatible trailer",
            )
        truck_count = sum(item.position is CargoPosition.TRUCK_PLATFORM for item in placements)
        trailer_count = len(placements) - truck_count
        if len(placements) > 2 or truck_count > 1 or trailer_count > 1:
            self._raise(
                TruckProfileErrorCode.NO_COMPATIBLE_TRAILER,
                "At most one cargo unit may occupy each supported platform",
            )
        if len({str(item.cargo_id) for item in placements}) != len(placements):
            self._incomplete("load.cargo_placements contains duplicate cargo_id")

    def _configuration_type(self, load: LoadConfiguration) -> TruckConfigurationType:
        """Classify the exact attachment and placement state for axle lookup."""

        truck_count = sum(
            item.position is CargoPosition.TRUCK_PLATFORM for item in load.cargo_placements
        )
        trailer_count = len(load.cargo_placements) - truck_count
        if not load.trailer_attached:
            return (
                TruckConfigurationType.CARGO_ON_TRUCK
                if truck_count
                else TruckConfigurationType.EMPTY_TRUCK
            )
        if truck_count and trailer_count:
            return TruckConfigurationType.TWO_CARGO_SPLIT
        if truck_count:
            return TruckConfigurationType.CARGO_ON_TRUCK_WITH_TRAILER
        if trailer_count:
            return TruckConfigurationType.CARGO_ON_TRAILER_WITH_TRAILER
        return TruckConfigurationType.EMPTY_COMBINATION

    def _select_axle_profile(
        self,
        configuration_type: TruckConfigurationType,
        axle_profiles: Iterable[OperationalAxleLoadProfile],
    ) -> OperationalAxleLoadProfile:
        """Select an exact operational value without deriving it from total weight."""

        matches = [
            item for item in axle_profiles if item.configuration_type is configuration_type
        ]
        if len(matches) != 1 or matches[0].max_actual_axle_load_t <= 0:
            self._incomplete(
                f"axle_profiles.{configuration_type.value}.max_actual_axle_load_t"
            )
        return matches[0]

    def _validate_cargo_compatibility(
        self,
        carrier: VehicleRoutingSpec | TrailerSpec,
        placements: tuple[CargoPlacement, ...],
        *,
        prefix: str,
    ) -> None:
        """Apply explicit carrier, platform, and per-unit limits to its cargo."""

        if not placements:
            return
        cargo_weight = sum(self._weight(item) for item in placements)
        payload_capacity = carrier.payload_capacity_t
        platform_payload = carrier.max_platform_payload_t
        assert payload_capacity is not None
        assert platform_payload is not None
        if cargo_weight > payload_capacity or cargo_weight > platform_payload:
            self._raise(
                TruckProfileErrorCode.CARGO_TOO_HEAVY,
                f"Cargo exceeds {prefix} payload capability",
            )
        for placement in placements:
            dimensions = placement.dimensions
            assert dimensions.length_m is not None
            assert dimensions.width_m is not None
            assert dimensions.height_m is not None
            assert dimensions.weight_t is not None
            assert carrier.max_cargo_length_m is not None
            assert carrier.max_cargo_width_m is not None
            assert carrier.max_cargo_height_m is not None
            assert carrier.max_cargo_weight_t is not None
            if dimensions.length_m > carrier.max_cargo_length_m:
                self._raise(
                    TruckProfileErrorCode.CARGO_TOO_LONG,
                    f"Cargo exceeds {prefix} limit",
                )
            if dimensions.width_m > carrier.max_cargo_width_m:
                self._raise(
                    TruckProfileErrorCode.CARGO_TOO_WIDE,
                    f"Cargo exceeds {prefix} limit",
                )
            if dimensions.height_m > carrier.max_cargo_height_m:
                self._raise(
                    TruckProfileErrorCode.CARGO_TOO_HIGH,
                    f"Cargo exceeds {prefix} limit",
                )
            if dimensions.weight_t > carrier.max_cargo_weight_t:
                self._raise(
                    TruckProfileErrorCode.CARGO_TOO_HEAVY,
                    f"Cargo exceeds {prefix} per-unit limit",
                )

    def _effective_height(
        self,
        vehicle: VehicleRoutingSpec,
        trailer: TrailerSpec | None,
        truck_cargo: tuple[CargoPlacement, ...],
        trailer_cargo: tuple[CargoPlacement, ...],
    ) -> Decimal:
        """Calculate maximum road height including platform offsets and safety margin."""

        assert vehicle.height_m is not None
        values = [vehicle.height_m]
        if trailer is not None:
            assert trailer.height_m is not None
            values.append(trailer.height_m)
        if truck_cargo:
            assert vehicle.platform_height_from_ground_m is not None
            assert truck_cargo[0].dimensions.height_m is not None
            values.append(
                vehicle.platform_height_from_ground_m + truck_cargo[0].dimensions.height_m
            )
        if trailer_cargo:
            assert trailer is not None
            assert trailer.platform_height_from_ground_m is not None
            assert trailer_cargo[0].dimensions.height_m is not None
            values.append(
                trailer.platform_height_from_ground_m + trailer_cargo[0].dimensions.height_m
            )
        return max(values) + vehicle.height_safety_margin_m

    def _effective_width(
        self,
        vehicle: VehicleRoutingSpec,
        trailer: TrailerSpec | None,
        truck_cargo: tuple[CargoPlacement, ...],
        trailer_cargo: tuple[CargoPlacement, ...],
    ) -> Decimal:
        """Calculate the widest equipment or cargo envelope plus safety margin."""

        assert vehicle.width_m is not None
        values = [vehicle.width_m]
        if trailer is not None:
            assert trailer.width_m is not None
            values.append(trailer.width_m)
        for placement in (*truck_cargo, *trailer_cargo):
            assert placement.dimensions.width_m is not None
            values.append(placement.dimensions.width_m)
        return max(values) + vehicle.width_safety_margin_m

    def _effective_length(
        self,
        vehicle: VehicleRoutingSpec,
        trailer: TrailerSpec | None,
        truck_cargo: tuple[CargoPlacement, ...],
        trailer_cargo: tuple[CargoPlacement, ...],
    ) -> Decimal:
        """Calculate combination length and conservatively include cargo overhangs."""

        assert vehicle.length_m is not None
        if trailer is None:
            base_length = vehicle.length_m
        else:
            assert vehicle.coupling_length_m is not None
            assert trailer.length_m is not None
            base_length = vehicle.length_m + vehicle.coupling_length_m + trailer.length_m
        truck_overhang = Decimal(0)
        if truck_cargo:
            assert vehicle.platform_length_m is not None
            assert truck_cargo[0].dimensions.length_m is not None
            truck_overhang = max(
                Decimal(0), truck_cargo[0].dimensions.length_m - vehicle.platform_length_m
            )
        trailer_overhang = Decimal(0)
        if trailer_cargo:
            assert trailer is not None
            assert trailer.platform_length_m is not None
            assert trailer_cargo[0].dimensions.length_m is not None
            trailer_overhang = max(
                Decimal(0), trailer_cargo[0].dimensions.length_m - trailer.platform_length_m
            )
        return base_length + truck_overhang + trailer_overhang

    def _require_cargo_dimensions(self, placements: tuple[CargoPlacement, ...]) -> None:
        """Report every missing or non-positive cargo measurement by cargo identifier."""

        missing: list[str] = []
        for placement in placements:
            prefix = f"cargo[{placement.cargo_id}]"
            for name, value in (
                ("length_m", placement.dimensions.length_m),
                ("width_m", placement.dimensions.width_m),
                ("height_m", placement.dimensions.height_m),
                ("weight_t", placement.dimensions.weight_t),
            ):
                if value is None or value <= 0:
                    missing.append(f"{prefix}.{name}")
        if missing:
            self._incomplete(*missing)

    def _require_values(self, values: Mapping[str, object | None]) -> None:
        """Reject missing or non-positive physical values with exact field names."""

        missing = [
            name
            for name, value in values.items()
            if value is None or (not isinstance(value, bool) and value <= 0)  # type: ignore[operator]
        ]
        if missing:
            self._incomplete(*missing)

    def _require_nonnegative_margins(self, vehicle: VehicleRoutingSpec) -> None:
        """Reject invalid negative safety margins rather than silently normalising them."""

        invalid = [
            name
            for name, value in (
                ("vehicle.height_safety_margin_m", vehicle.height_safety_margin_m),
                ("vehicle.width_safety_margin_m", vehicle.width_safety_margin_m),
                ("vehicle.weight_safety_margin_t", vehicle.weight_safety_margin_t),
            )
            if value < 0
        ]
        if invalid:
            self._incomplete(*invalid)

    @staticmethod
    def _weight(placement: CargoPlacement) -> Decimal:
        """Return a validated cargo weight for total-mass arithmetic."""

        assert placement.dimensions.weight_t is not None
        return placement.dimensions.weight_t

    def _incomplete(self, *missing_fields: str) -> Never:
        """Raise an incomplete-profile error with deterministic field detail."""

        raise TruckProfileError(
            TruckProfileErrorCode.ROUTING_PROFILE_INCOMPLETE,
            "Critical routing profile fields are missing or invalid",
            missing_fields=missing_fields,
        )

    @staticmethod
    def _raise(code: TruckProfileErrorCode, detail: str) -> Never:
        """Raise a stable compatibility error."""

        raise TruckProfileError(code, detail)
