"""Regression tests for the canonical tonne/metre vehicle model."""

from __future__ import annotations

from decimal import Decimal
from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.domain.vehicle import (
    VehicleSpecification,
    VehicleSpecificationError,
    kilograms_to_tonnes,
    load_profile_axle_load_t,
    metres_to_millimetres,
    millimetres_to_metres,
    normalize_trailer_fields,
    normalize_vehicle_fields,
    tonnes_to_kilograms,
    vehicle_specification,
)
from app.schemas.domain import (
    TrailerCreate,
    VehicleCreate,
    VehicleLoadProfileCreate,
    VehicleRead,
    VehicleUpdate,
)


def _legacy_vehicle_payload() -> dict[str, object]:
    """Build one complete backward-compatible kilogram/millimetre payload."""

    return {
        "name": "Canonical truck",
        "registration_number": "A123BC77",
        "capacity": 2,
        "is_hgv": True,
        "tare_weight_kg": 12_000,
        "max_gross_weight_kg": 22_000,
        "length_mm": 9_200,
        "width_mm": 2_500,
        "height_mm": 3_200,
        "max_axle_load_kg": 10_000,
        "payload_capacity_kg": 8_000,
        "can_use_trailer": True,
    }


def test_legacy_kilogram_fields_are_converted_to_exact_tonnes() -> None:
    """A legacy payload is converted once into the canonical model."""

    vehicle = VehicleCreate.model_validate(_legacy_vehicle_payload())

    assert vehicle.tare_weight_t == Decimal("12.000")
    assert vehicle.max_gross_weight_t == Decimal("22.000")
    assert vehicle.axle_load_t == Decimal("10.000")
    assert vehicle.payload_capacity_t == Decimal("8.000")
    assert vehicle.length_m == Decimal("9.200")
    assert vehicle.width_m == Decimal("2.500")
    assert vehicle.height_m == Decimal("3.200")
    assert vehicle.has_trailer is True


def test_canonical_input_does_not_mutate_legacy_projection() -> None:
    """The deprecated kilogram/millimetre fields are input-only, never derived."""

    vehicle = VehicleCreate(
        name="Canonical truck",
        registration_number="B456BC77",
        tare_weight_t=Decimal("12.345"),
        max_gross_weight_t=Decimal("22.5"),
        axle_load_t=Decimal("10.1"),
        payload_capacity_t=Decimal("8.005"),
        length_m=Decimal("9.215"),
        width_m=Decimal("2.55"),
        height_m=Decimal("3.2"),
        has_trailer=False,
    )

    assert vehicle.tare_weight_kg is None
    assert vehicle.length_mm is None
    assert vehicle.can_use_trailer is None


def test_new_vehicle_without_trailer_facts_is_explicitly_false() -> None:
    """The canonical boolean is definite even when a client omits it."""

    vehicle = VehicleCreate(name="Solo truck", registration_number="C789BC77")

    assert vehicle.has_trailer is False


def test_conflicting_canonical_and_legacy_units_are_rejected() -> None:
    """Two different values for the same physical fact cannot be silently merged."""

    with pytest.raises(ValidationError, match="different values"):
        VehicleCreate.model_validate(
            {
                "name": "Conflicting truck",
                "registration_number": "D100BC77",
                "tare_weight_t": 12.5,
                "tare_weight_kg": 12_000,
            }
        )


def test_conflicting_trailer_aliases_are_rejected() -> None:
    """The deprecated capability alias must agree with the canonical boolean."""

    with pytest.raises(ValidationError, match="different values"):
        VehicleCreate.model_validate(
            {
                "name": "Conflicting trailer",
                "registration_number": "D101BC77",
                "has_trailer": False,
                "can_use_trailer": True,
            }
        )


def test_default_trailer_requires_canonical_trailer_capability() -> None:
    """A default trailer cannot contradict the authoritative has_trailer boolean."""

    with pytest.raises(ValidationError, match="default_trailer_id requires"):
        VehicleCreate(
            name="Trailer without capability",
            registration_number="E200BC77",
            has_trailer=False,
            default_trailer_id=uuid4(),
        )


def test_gross_mass_below_tare_mass_is_rejected() -> None:
    """Physical mass invariants use the canonical tonne values."""

    with pytest.raises(ValidationError, match="max_gross_weight_t"):
        VehicleCreate(
            name="Impossible truck",
            registration_number="F300BC77",
            tare_weight_t=22,
            max_gross_weight_t=12,
        )
    with pytest.raises(VehicleSpecificationError, match="max_gross_weight_t"):
        VehicleSpecification(tare_weight_t=Decimal("22"), max_gross_weight_t=Decimal("12"))


def test_partial_update_normalizes_only_canonical_columns() -> None:
    """A PATCH converts legacy input but never returns or writes a second projection."""

    current = SimpleNamespace(
        tare_weight_t=Decimal("12.000"),
        max_gross_weight_t=Decimal("22.000"),
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=None,
        width_m=None,
        height_m=None,
        has_trailer=True,
        default_trailer_id=None,
    )

    updates = normalize_vehicle_fields(
        {"tare_weight_kg": 12_500},
        current=current,
        allow_clear=True,
    )

    assert updates == {"tare_weight_t": Decimal("12.500")}


def test_partial_update_can_clear_a_physical_fact() -> None:
    """An explicit null clears the canonical value without a legacy counterpart."""

    current = SimpleNamespace(
        tare_weight_t=Decimal("12.000"),
        max_gross_weight_t=None,
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=Decimal("9.200"),
        width_m=None,
        height_m=None,
        has_trailer=False,
        default_trailer_id=None,
    )

    updates = normalize_vehicle_fields(
        {"length_m": None},
        current=current,
        allow_clear=True,
    )

    assert updates == {"length_m": None}


def test_canonical_specification_keeps_tonne_metre_values() -> None:
    """The routing specification no longer projects integer units."""

    specification = VehicleSpecification(
        tare_weight_t=Decimal("12.345"),
        max_gross_weight_t=Decimal("22.000"),
        axle_load_t=Decimal("10.000"),
        payload_capacity_t=Decimal("8.000"),
        length_m=Decimal("9.215"),
        width_m=Decimal("2.500"),
        height_m=Decimal("3.200"),
        has_trailer=True,
    )

    assert specification.tare_weight_t == Decimal("12.345")
    assert specification.length_m == Decimal("9.215")
    assert specification.has_trailer is True


def test_vehicle_specification_does_not_read_legacy_columns() -> None:
    """Legacy columns are a rollback projection, not an active fallback."""

    legacy_row = SimpleNamespace(
        tare_weight_t=None,
        max_gross_weight_t=None,
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=None,
        width_m=None,
        height_m=None,
        tare_weight_kg=12_000,
        max_gross_weight_kg=22_000,
        max_axle_load_kg=10_000,
        payload_capacity_kg=8_000,
        length_mm=9_200,
        width_mm=2_500,
        height_mm=3_200,
        has_trailer=False,
    )

    specification = vehicle_specification(legacy_row)

    assert specification.tare_weight_t is None
    assert specification.length_m is None
    assert specification.has_trailer is False


def test_unit_conversion_helpers_are_exact_and_reject_negative_values() -> None:
    """One conversion layer is shared by persistence, API and routing."""

    assert kilograms_to_tonnes(12_345) == Decimal("12.345")
    assert tonnes_to_kilograms(Decimal("12.345")) == 12_345
    assert millimetres_to_metres(9_215) == Decimal("9.215")
    assert metres_to_millimetres(Decimal("9.215")) == 9_215
    with pytest.raises(VehicleSpecificationError):
        kilograms_to_tonnes(-1)


def test_axle_load_profile_uses_canonical_tonnes_only() -> None:
    """Measured axle loads are persisted and read in tonnes only."""

    canonical = VehicleLoadProfileCreate(
        configuration_type="CARGO_ON_TRUCK",
        max_actual_axle_load_t=Decimal("7.8"),
    )
    legacy = VehicleLoadProfileCreate(
        configuration_type="CARGO_ON_TRUCK",
        max_actual_axle_load_kg=7_800,
    )
    with pytest.raises(ValidationError, match="required"):
        VehicleLoadProfileCreate(configuration_type="CARGO_ON_TRUCK")
    with pytest.raises(ValidationError, match="different values"):
        VehicleLoadProfileCreate(
            configuration_type="CARGO_ON_TRUCK",
            max_actual_axle_load_t=Decimal("7.9"),
            max_actual_axle_load_kg=7_800,
        )

    assert canonical.max_actual_axle_load_kg is None
    assert legacy.max_actual_axle_load_t == Decimal("7.800")
    assert load_profile_axle_load_t(canonical) == Decimal("7.800")
    assert load_profile_axle_load_t(legacy) == Decimal("7.800")


def test_vehicle_read_serializes_canonical_values_as_json_numbers() -> None:
    """The transport boundary exposes tonnes/metres as numbers, not unit-less strings."""

    values = {
        "id": uuid4(),
        "version": 3,
        "warehouse_id": uuid4(),
        "name": "Canonical truck",
        "registration_number": "A123BC77",
        "capacity": 2,
        "active": True,
        "average_speed_city": 35,
        "average_speed_region": 65,
        "vehicle_type": None,
        "manufacturer": None,
        "model": None,
        "is_hgv": True,
        "tare_weight_t": Decimal("12.345"),
        "max_gross_weight_t": Decimal("22.000"),
        "axle_load_t": Decimal("10.000"),
        "payload_capacity_t": Decimal("8.000"),
        "length_m": Decimal("9.215"),
        "width_m": Decimal("2.500"),
        "height_m": Decimal("3.200"),
        "has_trailer": True,
        "default_trailer_id": None,
        "axle_count": 3,
        "notes": "",
        "load_profiles": [],
    }
    read = VehicleRead.model_validate(SimpleNamespace(**values))
    payload = read.model_dump(mode="json")

    assert payload["tare_weight_t"] == 12.345
    assert payload["length_m"] == 9.215
    assert payload["has_trailer"] is True
    assert "tare_weight_kg" not in payload


def test_removed_duplicate_vehicle_fields_are_rejected() -> None:
    """Derived fields that duplicate canonical data cannot be supplied again."""

    for field, value in (
        ("platform_width_mm", 2_500),
        ("combined_length_with_trailer_mm", 18_500),
    ):
        with pytest.raises(ValidationError, match="no longer supported"):
            VehicleCreate.model_validate(
                {
                    "name": "Removed field truck",
                    "registration_number": "G400BC77",
                    field: value,
                }
            )

    accepted = VehicleCreate.model_validate(
        {
            "name": "Explicit null truck",
            "registration_number": "G401BC77",
            "platform_width_mm": None,
            "combined_length_with_trailer_mm": None,
        }
    )
    assert accepted.platform_width_mm is None
    assert accepted.combined_length_with_trailer_mm is None
    assert accepted.has_trailer is False


def test_removed_duplicate_trailer_field_is_rejected() -> None:
    """A trailer cannot reintroduce the dead platform width column."""

    with pytest.raises(ValidationError, match="no longer supported"):
        TrailerCreate.model_validate(
            {
                "name": "Removed field trailer",
                "registration_number": "G402BC77",
                "platform_width_mm": 2_500,
            }
        )
    assert normalize_trailer_fields({"platform_width_mm": None}) == {}


def test_removed_duplicate_vehicle_field_is_rejected_on_update() -> None:
    """A PATCH that supplies a removed duplicate field fails instead of ignoring it."""

    with pytest.raises(VehicleSpecificationError, match="no longer supported"):
        normalize_vehicle_fields(
            {"combined_length_with_trailer_mm": 18_500},
            current=SimpleNamespace(has_trailer=False),
            allow_clear=True,
        )


def test_vehicle_update_schema_no_longer_invents_a_trailer_flag() -> None:
    """A label-only PATCH keeps the existing trailer capability untouched."""

    update = VehicleUpdate(expected_version=4, notes="Only a note")

    assert update.model_fields_set == {"expected_version", "notes"}
    assert normalize_vehicle_fields(update.model_dump(exclude_unset=True), current=None) == {}
