"""Canonical physical equipment model expressed in tonnes and metres.

The simulator carries one authoritative description of a vehicle:

* mass in tonnes: ``tare_weight_t`` (kerb/actual vehicle mass),
  ``max_gross_weight_t`` (permitted gross mass), ``axle_load_t`` (permitted
  axle load) and ``payload_capacity_t`` (payload capacity);
* dimensions in metres: ``length_m``, ``width_m`` and ``height_m``;
* trailer availability as one boolean: ``has_trailer``.

Optional routing extensions (platform geometry, per-unit cargo limits, coupling
length and safety margins) use the same tonne/metre units.  The legacy
kilogram/millimetre columns remain in the database only as a rollback and
compatibility layer; no active calculation reads or writes them.

The module is the single place where legacy request values are converted and
canonical values are validated, so no calculation may mix units.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any

KG_PER_TONNE = Decimal(1_000)
MM_PER_METER = Decimal(1_000)
QUANTUM = Decimal("0.001")

CANONICAL_MASS_FIELDS: tuple[str, ...] = (
    "tare_weight_t",
    "max_gross_weight_t",
    "axle_load_t",
    "payload_capacity_t",
    "max_platform_payload_t",
    "max_cargo_weight_t",
)
CANONICAL_DIMENSION_FIELDS: tuple[str, ...] = (
    "length_m",
    "width_m",
    "height_m",
    "platform_length_m",
    "platform_height_from_ground_m",
    "max_cargo_length_m",
    "max_cargo_width_m",
    "max_cargo_height_m",
    "coupling_length_m",
)
CANONICAL_MARGIN_FIELDS: tuple[str, ...] = (
    "height_safety_margin_m",
    "width_safety_margin_m",
    "weight_safety_margin_t",
)
TRAILER_DIMENSION_FIELDS: tuple[str, ...] = tuple(
    field for field in CANONICAL_DIMENSION_FIELDS if field != "coupling_length_m"
)

CANONICAL_TO_LEGACY: dict[str, str] = {
    "tare_weight_t": "tare_weight_kg",
    "max_gross_weight_t": "max_gross_weight_kg",
    "axle_load_t": "max_axle_load_kg",
    "payload_capacity_t": "payload_capacity_kg",
    "length_m": "length_mm",
    "width_m": "width_mm",
    "height_m": "height_mm",
    "platform_length_m": "platform_length_mm",
    "platform_height_from_ground_m": "platform_height_from_ground_mm",
    "max_platform_payload_t": "max_platform_payload_kg",
    "max_cargo_length_m": "max_cargo_length_mm",
    "max_cargo_width_m": "max_cargo_width_mm",
    "max_cargo_height_m": "max_cargo_height_mm",
    "max_cargo_weight_t": "max_cargo_weight_kg",
    "coupling_length_m": "coupling_length_mm",
    "height_safety_margin_m": "height_safety_margin_mm",
    "width_safety_margin_m": "width_safety_margin_mm",
    "weight_safety_margin_t": "weight_safety_margin_kg",
}
LEGACY_TO_CANONICAL: dict[str, str] = {
    legacy: canonical for canonical, legacy in CANONICAL_TO_LEGACY.items()
}

LEGACY_VEHICLE_FIELDS: frozenset[str] = frozenset(
    {
        *LEGACY_TO_CANONICAL,
        "can_use_trailer",
        "combined_length_with_trailer_mm",
        "platform_width_mm",
    }
)
LEGACY_TRAILER_FIELDS: frozenset[str] = frozenset(LEGACY_TO_CANONICAL) | {
    "platform_width_mm"
}

REMOVED_VEHICLE_FIELDS: frozenset[str] = frozenset(
    {"combined_length_with_trailer_mm", "platform_width_mm"}
)
REMOVED_TRAILER_FIELDS: frozenset[str] = frozenset({"platform_width_mm"})


class VehicleSpecificationError(ValueError):
    """Invalid or contradictory canonical vehicle data with exact field names."""

    def __init__(self, detail: str, *, fields: tuple[str, ...] = ()) -> None:
        super().__init__(detail)
        self.detail = detail
        self.fields = tuple(sorted(set(fields)))


def _quantize(value: Decimal) -> Decimal:
    """Round one canonical value to millimetre/kilogram precision."""

    return value.quantize(QUANTUM, rounding=ROUND_HALF_UP)


def _decimal(value: object, field: str) -> Decimal:
    """Coerce a positive canonical decimal without accepting booleans or NaN."""

    if isinstance(value, bool):
        raise VehicleSpecificationError(f"{field} must be a decimal number", fields=(field,))
    try:
        decimal_value = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise VehicleSpecificationError(
            f"{field} must be a decimal number", fields=(field,)
        ) from exc
    if not decimal_value.is_finite():
        raise VehicleSpecificationError(f"{field} must be finite", fields=(field,))
    normalized = _quantize(decimal_value)
    if normalized <= 0:
        raise VehicleSpecificationError(f"{field} must be positive", fields=(field,))
    return normalized


def _nonnegative_decimal(value: object, field: str) -> Decimal:
    """Coerce a non-negative canonical decimal used for safety margins."""

    if isinstance(value, bool):
        raise VehicleSpecificationError(f"{field} must be a decimal number", fields=(field,))
    try:
        decimal_value = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise VehicleSpecificationError(
            f"{field} must be a decimal number", fields=(field,)
        ) from exc
    if not decimal_value.is_finite():
        raise VehicleSpecificationError(f"{field} must be finite", fields=(field,))
    normalized = _quantize(decimal_value)
    if normalized < 0:
        raise VehicleSpecificationError(f"{field} must not be negative", fields=(field,))
    return normalized


def kilograms_to_tonnes(value: int | None) -> Decimal | None:
    """Convert an optional integer kilogram value to an exact tonne decimal."""

    if value is None:
        return None
    if isinstance(value, bool) or value <= 0:
        raise VehicleSpecificationError("weight must be a positive integer")
    return _quantize(Decimal(value) / KG_PER_TONNE)


def tonnes_to_kilograms(value: Decimal | None) -> int | None:
    """Convert an optional tonne decimal back to exact whole kilograms."""

    if value is None:
        return None
    normalized = _decimal(value, "weight_t")
    kilograms = (normalized * KG_PER_TONNE).to_integral_value(rounding=ROUND_HALF_UP)
    return int(kilograms)


def millimetres_to_metres(value: int | None) -> Decimal | None:
    """Convert an optional integer millimetre value to an exact metre decimal."""

    if value is None:
        return None
    if isinstance(value, bool) or value <= 0:
        raise VehicleSpecificationError("dimension must be a positive integer")
    return _quantize(Decimal(value) / MM_PER_METER)


def metres_to_millimetres(value: Decimal | None) -> int | None:
    """Convert an optional metre decimal back to exact whole millimetres."""

    if value is None:
        return None
    normalized = _decimal(value, "dimension_m")
    millimetres = (normalized * MM_PER_METER).to_integral_value(rounding=ROUND_HALF_UP)
    return int(millimetres)


def _is_mass_field(name: str) -> bool:
    return name in CANONICAL_MASS_FIELDS


def canonical_to_legacy_value(field: str, value: Decimal | None) -> int | None:
    """Project one canonical value to its rollback compatibility column."""

    if value is None:
        return None
    if _is_mass_field(field) or (
        field in CANONICAL_MARGIN_FIELDS and field.endswith("_t")
    ):
        return tonnes_to_kilograms(value)
    return metres_to_millimetres(value)


def legacy_to_canonical_value(field: str, value: int | None) -> Decimal | None:
    """Convert one legacy integer request value to the canonical decimal value."""

    if value is None:
        return None
    if field in CANONICAL_MARGIN_FIELDS:
        if isinstance(value, bool) or value < 0:
            raise VehicleSpecificationError(f"{field} must not be negative", fields=(field,))
        divisor = KG_PER_TONNE if field.endswith("_t") else MM_PER_METER
        return _quantize(Decimal(value) / divisor)
    if _is_mass_field(field):
        return kilograms_to_tonnes(value)
    return millimetres_to_metres(value)


def _coerce_legacy(field: str, value: object) -> int | None:
    """Validate one legacy integer supplied by a backward-compatible request."""

    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise VehicleSpecificationError(
            f"{field} must be a positive integer", fields=(field,)
        )
    if value < 0:
        raise VehicleSpecificationError(f"{field} must not be negative", fields=(field,))
    if value == 0 and LEGACY_TO_CANONICAL.get(field) not in CANONICAL_MARGIN_FIELDS:
        raise VehicleSpecificationError(f"{field} must be positive", fields=(field,))
    return value


def _entity_value(current: object | None, field: str) -> Any:
    """Read a field from an ORM entity or a plain mapping without importing models."""

    if current is None:
        return None
    if isinstance(current, Mapping):
        return current.get(field)
    return getattr(current, field, None)


def _canonical_current_value(current: object | None, field: str) -> Decimal | None:
    """Read the effective canonical value from a persisted entity."""

    value = _entity_value(current, field)
    if value is None:
        return None
    if field in CANONICAL_MARGIN_FIELDS:
        return _nonnegative_decimal(value, field)
    return _decimal(value, field)


def _normalize_field_names(values: Mapping[str, Any]) -> None:
    removed = REMOVED_VEHICLE_FIELDS | REMOVED_TRAILER_FIELDS
    supplied = sorted(field for field in removed if values.get(field) is not None)
    if supplied:
        raise VehicleSpecificationError(
            f"{', '.join(supplied)} is no longer supported; use canonical metres",
            fields=tuple(supplied),
        )


def _normalize_physical_fields(
    values: Mapping[str, Any],
    *,
    current: object | None,
    allow_clear: bool,
) -> dict[str, Any]:
    """Reconcile canonical and legacy request values into canonical updates."""

    updates: dict[str, Any] = {}
    for canonical, legacy in CANONICAL_TO_LEGACY.items():
        canonical_provided = canonical in values
        legacy_provided = legacy in values
        canonical_value = values.get(canonical)
        legacy_value = values.get(legacy)
        if not allow_clear:
            canonical_provided = canonical_provided and canonical_value is not None
            legacy_provided = legacy_provided and legacy_value is not None
        if not canonical_provided and not legacy_provided:
            continue
        if allow_clear and (
            (canonical_provided and canonical_value is None)
            or (legacy_provided and legacy_value is None)
        ):
            if canonical_provided and legacy_provided and (
                canonical_value is None
            ) != (legacy_value is None):
                raise VehicleSpecificationError(
                    f"{canonical} and {legacy} describe different values",
                    fields=(canonical, legacy),
                )
            updates[canonical] = None
            continue
        if canonical_provided and legacy_provided:
            normalized_canonical = (
                _nonnegative_decimal(canonical_value, canonical)
                if canonical in CANONICAL_MARGIN_FIELDS
                else _decimal(canonical_value, canonical)
            )
            normalized_legacy = _coerce_legacy(legacy, legacy_value)
            expected_legacy = canonical_to_legacy_value(canonical, normalized_canonical)
            if normalized_legacy != expected_legacy:
                raise VehicleSpecificationError(
                    f"{canonical} and {legacy} describe different values",
                    fields=(canonical, legacy),
                )
            updates[canonical] = normalized_canonical
        elif canonical_provided:
            updates[canonical] = (
                _nonnegative_decimal(canonical_value, canonical)
                if canonical in CANONICAL_MARGIN_FIELDS
                else _decimal(canonical_value, canonical)
            )
        else:
            normalized_legacy = _coerce_legacy(legacy, legacy_value)
            updates[canonical] = legacy_to_canonical_value(canonical, normalized_legacy)
    return updates


@dataclass(frozen=True, slots=True)
class VehicleSpecification:
    """Validated canonical physical vehicle specification.

    ``None`` keeps the explicit "physical fact is unknown" semantics used by the
    existing equipment policy; a value is always a positive tonne/metre decimal
    with at most millimetre/kilogram precision.  Safety margins are non-negative.
    """

    tare_weight_t: Decimal | None = None
    max_gross_weight_t: Decimal | None = None
    axle_load_t: Decimal | None = None
    payload_capacity_t: Decimal | None = None
    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    platform_length_m: Decimal | None = None
    platform_height_from_ground_m: Decimal | None = None
    max_platform_payload_t: Decimal | None = None
    max_cargo_length_m: Decimal | None = None
    max_cargo_width_m: Decimal | None = None
    max_cargo_height_m: Decimal | None = None
    max_cargo_weight_t: Decimal | None = None
    coupling_length_m: Decimal | None = None
    height_safety_margin_m: Decimal = Decimal(0)
    width_safety_margin_m: Decimal = Decimal(0)
    weight_safety_margin_t: Decimal = Decimal(0)
    has_trailer: bool = False

    def __post_init__(self) -> None:
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS):
            normalized = _canonical_current_value(self, field)
            object.__setattr__(self, field, normalized)
        for field in CANONICAL_MARGIN_FIELDS:
            value = getattr(self, field)
            normalized = _nonnegative_decimal(0 if value is None else value, field)
            object.__setattr__(self, field, normalized)
        if not isinstance(self.has_trailer, bool):
            raise VehicleSpecificationError(
                "has_trailer must be boolean", fields=("has_trailer",)
            )
        if (
            self.tare_weight_t is not None
            and self.max_gross_weight_t is not None
            and self.max_gross_weight_t < self.tare_weight_t
        ):
            raise VehicleSpecificationError(
                "max_gross_weight_t must not be below tare_weight_t",
                fields=("max_gross_weight_t", "tare_weight_t"),
            )


@dataclass(frozen=True, slots=True)
class TrailerSpecification:
    """Validated canonical physical trailer specification."""

    tare_weight_t: Decimal | None = None
    max_gross_weight_t: Decimal | None = None
    axle_load_t: Decimal | None = None
    payload_capacity_t: Decimal | None = None
    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    platform_length_m: Decimal | None = None
    platform_height_from_ground_m: Decimal | None = None
    max_platform_payload_t: Decimal | None = None
    max_cargo_length_m: Decimal | None = None
    max_cargo_width_m: Decimal | None = None
    max_cargo_height_m: Decimal | None = None
    max_cargo_weight_t: Decimal | None = None

    def __post_init__(self) -> None:
        for field in (*CANONICAL_MASS_FIELDS, *TRAILER_DIMENSION_FIELDS):
            normalized = _canonical_current_value(self, field)
            object.__setattr__(self, field, normalized)
        if (
            self.tare_weight_t is not None
            and self.max_gross_weight_t is not None
            and self.max_gross_weight_t < self.tare_weight_t
        ):
            raise VehicleSpecificationError(
                "max_gross_weight_t must not be below tare_weight_t",
                fields=("max_gross_weight_t", "tare_weight_t"),
            )


def normalize_vehicle_fields(
    values: Mapping[str, Any],
    *,
    current: object | None = None,
    allow_clear: bool = False,
    create: bool = False,
) -> dict[str, Any]:
    """Normalize a partial vehicle command into canonical columns only.

    Legacy kilogram/millimetre values are accepted for a transition period and
    converted exactly once.  Removed duplicate fields are rejected instead of
    being silently ignored.  The returned mapping never contains legacy fields.
    """

    _normalize_field_names(values)
    updates = _normalize_physical_fields(values, current=current, allow_clear=allow_clear)

    has_trailer_provided = "has_trailer" in values
    legacy_trailer_provided = "can_use_trailer" in values
    default_trailer_provided = "default_trailer_id" in values
    if not allow_clear:
        has_trailer_provided = has_trailer_provided and values.get("has_trailer") is not None
        legacy_trailer_provided = (
            legacy_trailer_provided and values.get("can_use_trailer") is not None
        )
        default_trailer_provided = (
            default_trailer_provided and values.get("default_trailer_id") is not None
        )

    final_has_trailer = bool(_entity_value(current, "has_trailer"))
    if has_trailer_provided:
        value = values.get("has_trailer")
        if not isinstance(value, bool):
            raise VehicleSpecificationError(
                "has_trailer must be boolean", fields=("has_trailer",)
            )
        final_has_trailer = value
    if legacy_trailer_provided:
        value = values.get("can_use_trailer")
        if not isinstance(value, bool):
            raise VehicleSpecificationError(
                "can_use_trailer must be boolean", fields=("can_use_trailer",)
            )
        if has_trailer_provided and value != final_has_trailer:
            raise VehicleSpecificationError(
                "has_trailer and can_use_trailer describe different values",
                fields=("has_trailer", "can_use_trailer"),
            )
        final_has_trailer = value
    if create and not has_trailer_provided and not legacy_trailer_provided:
        final_has_trailer = False
    if create or has_trailer_provided or legacy_trailer_provided:
        updates["has_trailer"] = final_has_trailer

    effective_default = (
        values.get("default_trailer_id")
        if default_trailer_provided
        else _entity_value(current, "default_trailer_id")
    )
    if effective_default is not None and not final_has_trailer:
        raise VehicleSpecificationError(
            "default_trailer_id requires has_trailer=true",
            fields=("default_trailer_id", "has_trailer"),
        )

    merged: dict[str, Any] = {
        field: _canonical_current_value(current, field)
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS, *CANONICAL_MARGIN_FIELDS)
    }
    merged.update({field: value for field, value in updates.items() if field in merged})
    for field in CANONICAL_MARGIN_FIELDS:
        if merged[field] is None:
            merged[field] = Decimal(0)
    VehicleSpecification(**merged, has_trailer=final_has_trailer)
    return updates


def normalize_trailer_fields(
    values: Mapping[str, Any],
    *,
    current: object | None = None,
    allow_clear: bool = False,
) -> dict[str, Any]:
    """Normalize a partial trailer command into canonical columns only."""

    _normalize_field_names(values)
    updates = _normalize_physical_fields(values, current=current, allow_clear=allow_clear)
    merged = {
        field: _canonical_current_value(current, field)
        for field in (*CANONICAL_MASS_FIELDS, *TRAILER_DIMENSION_FIELDS)
    }
    merged.update(updates)
    TrailerSpecification(**merged)
    return updates


def vehicle_specification(entity: object) -> VehicleSpecification:
    """Build the canonical vehicle specification from canonical persisted fields."""

    values: dict[str, Any] = {
        field: _canonical_current_value(entity, field)
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS)
    }
    for field in CANONICAL_MARGIN_FIELDS:
        values[field] = _canonical_current_value(entity, field) or Decimal(0)
    has_trailer = _entity_value(entity, "has_trailer")
    return VehicleSpecification(**values, has_trailer=bool(has_trailer))


def trailer_specification(entity: object) -> TrailerSpecification:
    """Build the canonical trailer specification from canonical persisted fields."""

    return TrailerSpecification(
        **{
            field: _canonical_current_value(entity, field)
            for field in (*CANONICAL_MASS_FIELDS, *TRAILER_DIMENSION_FIELDS)
        }
    )


def load_profile_axle_load_t(profile: object) -> Decimal:
    """Return one measured axle load in tonnes from the canonical column."""

    canonical = _entity_value(profile, "max_actual_axle_load_t")
    if canonical is None:
        raise VehicleSpecificationError(
            "max_actual_axle_load_t is required",
            fields=("max_actual_axle_load_t",),
        )
    return _decimal(canonical, "max_actual_axle_load_t")
