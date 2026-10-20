"""Canonical physical vehicle model expressed in tonnes and metres.

The simulator carries one authoritative description of a vehicle:

* mass in tonnes: ``tare_weight_t`` (kerb/actual vehicle mass),
  ``max_gross_weight_t`` (permitted gross mass), ``axle_load_t`` (permitted
  axle load) and ``payload_capacity_t`` (payload capacity);
* dimensions in metres: ``length_m``, ``width_m`` and ``height_m``;
* trailer availability as one boolean: ``has_trailer``.

The legacy kilogram/millimetre columns and the legacy routing-extension fields
(platform sizes, coupled length, safety margins) remain in the database only for
backward compatibility with already routed plans and external consumers.  This
module is the single place where canonical units are converted, validated and
projected back to the legacy columns, so no calculation may mix units.
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
)
CANONICAL_DIMENSION_FIELDS: tuple[str, ...] = ("length_m", "width_m", "height_m")
CANONICAL_TO_LEGACY: dict[str, str] = {
    "tare_weight_t": "tare_weight_kg",
    "max_gross_weight_t": "max_gross_weight_kg",
    "axle_load_t": "max_axle_load_kg",
    "payload_capacity_t": "payload_capacity_kg",
    "length_m": "length_mm",
    "width_m": "width_mm",
    "height_m": "height_mm",
}
LEGACY_TO_CANONICAL: dict[str, str] = {
    legacy: canonical for canonical, legacy in CANONICAL_TO_LEGACY.items()
}


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
    """Project one canonical value to the legacy integer column."""

    if _is_mass_field(field):
        return tonnes_to_kilograms(value)
    return metres_to_millimetres(value)


def legacy_to_canonical_value(field: str, value: int | None) -> Decimal | None:
    """Convert one legacy integer column to the canonical decimal value."""

    if _is_mass_field(field):
        return kilograms_to_tonnes(value)
    return millimetres_to_metres(value)


def _coerce_canonical(field: str, value: object) -> Decimal | None:
    """Validate one canonical decimal supplied by a request or an entity."""

    if value is None:
        return None
    return _decimal(value, field)


def _coerce_legacy(field: str, value: object) -> int | None:
    """Validate one legacy integer supplied by a backward-compatible request."""

    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise VehicleSpecificationError(
            f"{field} must be a positive integer", fields=(field,)
        )
    if value <= 0:
        raise VehicleSpecificationError(f"{field} must be positive", fields=(field,))
    return value


def _entity_value(current: object | None, field: str) -> Any:
    """Read a field from an ORM entity or a plain mapping without importing models."""

    if current is None:
        return None
    if isinstance(current, Mapping):
        return current.get(field)
    return getattr(current, field, None)


@dataclass(frozen=True, slots=True)
class VehicleSpecification:
    """Validated canonical physical vehicle specification.

    ``None`` keeps the explicit "physical fact is unknown" semantics used by the
    existing equipment policy; a value is always a positive tonne/metre decimal
    with at most millimetre/kilogram precision.
    """

    tare_weight_t: Decimal | None = None
    max_gross_weight_t: Decimal | None = None
    axle_load_t: Decimal | None = None
    payload_capacity_t: Decimal | None = None
    length_m: Decimal | None = None
    width_m: Decimal | None = None
    height_m: Decimal | None = None
    has_trailer: bool = False

    def __post_init__(self) -> None:
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS):
            normalized = _coerce_canonical(field, getattr(self, field))
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

    def legacy_projection(self) -> dict[str, int | None]:
        """Return the legacy kilogram/millimetre columns for this specification."""

        return {
            CANONICAL_TO_LEGACY[field]: canonical_to_legacy_value(
                field, getattr(self, field)
            )
            for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS)
        }

    def routing_values(self) -> dict[str, int | None]:
        """Return exact integer units consumed by the routing adapter."""

        return self.legacy_projection()


def _canonical_current_value(current: object | None, field: str) -> Decimal | None:
    """Read the effective canonical value, falling back to the legacy column."""

    value = _entity_value(current, field)
    if value is not None:
        return _coerce_canonical(field, value)
    return legacy_to_canonical_value(
        field, _entity_value(current, CANONICAL_TO_LEGACY[field])
    )


def normalize_vehicle_fields(
    values: Mapping[str, Any],
    *,
    current: object | None = None,
    allow_clear: bool = False,
    create: bool = False,
) -> dict[str, Any]:
    """Normalize a partial vehicle command into canonical and legacy columns.

    The returned mapping always contains the canonical field and its legacy
    projection for every mass/dimension that is explicitly supplied (or derived
    from an explicitly supplied legacy value).  Conflicting values for the same
    physical fact are rejected instead of silently choosing one unit.

    ``current`` is the persisted entity (or a mapping) for partial updates.  When
    ``allow_clear`` is false, explicit ``None`` values are treated as absent
    (used by response models); request updates keep them so a fact can be cleared.
    """

    merged = {
        field: _canonical_current_value(current, field)
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS)
    }
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
        if canonical_provided and legacy_provided:
            normalized_canonical = _coerce_canonical(canonical, canonical_value)
            normalized_legacy = _coerce_legacy(legacy, legacy_value)
            expected_legacy = canonical_to_legacy_value(canonical, normalized_canonical)
            if normalized_legacy != expected_legacy:
                raise VehicleSpecificationError(
                    f"{canonical} and {legacy} describe different values",
                    fields=(canonical, legacy),
                )
            updates[canonical] = normalized_canonical
            updates[legacy] = normalized_legacy
        elif canonical_provided:
            normalized_canonical = _coerce_canonical(canonical, canonical_value)
            updates[canonical] = normalized_canonical
            updates[legacy] = canonical_to_legacy_value(canonical, normalized_canonical)
        else:
            normalized_legacy = _coerce_legacy(legacy, legacy_value)
            updates[legacy] = normalized_legacy
            updates[canonical] = legacy_to_canonical_value(canonical, normalized_legacy)
        merged[canonical] = updates[canonical]

    trailer_updates = _normalize_trailer_fields(
        values,
        current=current,
        allow_clear=allow_clear,
        create=create,
    )
    updates.update(trailer_updates)

    try:
        VehicleSpecification(
            **merged,
            has_trailer=bool(trailer_updates.get("has_trailer", False)),
        )
    except VehicleSpecificationError:
        raise
    return updates


def _normalize_trailer_fields(
    values: Mapping[str, Any],
    *,
    current: object | None,
    allow_clear: bool,
    create: bool,
) -> dict[str, Any]:
    """Reconcile the canonical trailer boolean with its legacy tri-state column."""

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

    current_has_trailer = _entity_value(current, "has_trailer")
    if current_has_trailer is None:
        current_has_trailer = _entity_value(current, "can_use_trailer") is True
    final_has_trailer = bool(current_has_trailer)

    def _as_bool(field: str, value: object) -> bool:
        if not isinstance(value, bool):
            raise VehicleSpecificationError(
                f"{field} must be boolean", fields=(field,)
            )
        return value

    if has_trailer_provided:
        if values.get("has_trailer") is None:
            final_has_trailer = False
        else:
            final_has_trailer = _as_bool("has_trailer", values["has_trailer"])
    if legacy_trailer_provided:
        legacy_value = values.get("can_use_trailer")
        if legacy_value is not None:
            normalized_legacy = _as_bool("can_use_trailer", legacy_value)
            if has_trailer_provided and normalized_legacy != final_has_trailer:
                raise VehicleSpecificationError(
                    "has_trailer and can_use_trailer describe different values",
                    fields=("has_trailer", "can_use_trailer"),
                )
            final_has_trailer = normalized_legacy
    if create and not has_trailer_provided and not legacy_trailer_provided:
        final_has_trailer = False

    default_trailer_value = values.get("default_trailer_id")
    if not default_trailer_provided or current is not None:
        current_default = _entity_value(current, "default_trailer_id")
        effective_default = (
            default_trailer_value
            if default_trailer_provided
            else current_default
        )
    else:
        effective_default = default_trailer_value
    if effective_default is not None and not final_has_trailer:
        raise VehicleSpecificationError(
            "default_trailer_id requires has_trailer=true",
            fields=("default_trailer_id", "has_trailer"),
        )

    updates: dict[str, Any] = {}
    if create:
        updates["has_trailer"] = final_has_trailer
        updates["can_use_trailer"] = (
            values.get("can_use_trailer")
            if legacy_trailer_provided
            else final_has_trailer
        )
        return updates
    if has_trailer_provided or legacy_trailer_provided:
        updates["has_trailer"] = final_has_trailer
        # Keep the legacy tri-state column in sync so old consumers and the
        # persisted routing snapshot observe the same trailer capability.
        updates["can_use_trailer"] = final_has_trailer
    return updates


def effective_trailer_capability(
    has_trailer: bool | None,
    can_use_trailer: bool | None,
) -> bool | None:
    """Resolve canonical and legacy trailer facts without inventing capability.

    ``True`` wins for backward compatibility with legacy rows and in-memory
    projections that only set ``can_use_trailer``.  ``None`` is reserved for a
    genuinely unknown profile so routing still fails with an explicit
    ``ROUTING_PROFILE_INCOMPLETE`` error instead of silently attaching a trailer.
    """

    if has_trailer is True or can_use_trailer is True:
        return True
    if has_trailer is None and can_use_trailer is None:
        return None
    return False


def vehicle_specification(entity: object) -> VehicleSpecification:
    """Build the canonical specification from a persisted or ad-hoc vehicle."""

    values = {
        field: _canonical_current_value(entity, field)
        for field in (*CANONICAL_MASS_FIELDS, *CANONICAL_DIMENSION_FIELDS)
    }
    has_trailer = effective_trailer_capability(
        _entity_value(entity, "has_trailer"),
        _entity_value(entity, "can_use_trailer"),
    )
    return VehicleSpecification(**values, has_trailer=has_trailer is True)


def load_profile_axle_load_kg(profile: object) -> int:
    """Return one measured axle load in kilograms using canonical data first."""

    canonical = _entity_value(profile, "max_actual_axle_load_t")
    if canonical is not None:
        kilograms = tonnes_to_kilograms(_coerce_canonical("axle_load_t", canonical))
        assert kilograms is not None
        return kilograms
    legacy = _entity_value(profile, "max_actual_axle_load_kg")
    if isinstance(legacy, bool) or not isinstance(legacy, int) or legacy <= 0:
        raise VehicleSpecificationError(
            "max_actual_axle_load_kg must be a positive integer",
            fields=("max_actual_axle_load_kg",),
        )
    return int(legacy)
