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

_LEGACY_VEHICLE_FIELDS = {
    "tare_weight_kg": 12_000,
    "max_gross_weight_kg": 22_000,
    "length_mm": 9_200,
    "width_mm": 2_500,
    "height_mm": 3_200,
    "max_axle_load_kg": 10_000,
    "payload_capacity_kg": 8_000,
    "platform_length_mm": 6_000,
    "platform_width_mm": 2_500,
    "platform_height_from_ground_mm": 1_300,
    "max_platform_payload_kg": 7_000,
    "max_cargo_length_mm": 7_000,
    "max_cargo_width_mm": 2_600,
    "max_cargo_height_mm": 3_000,
    "max_cargo_weight_kg": 6_000,
    "can_use_trailer": True,
    "combined_length_with_trailer_mm": 18_500,
    "coupling_length_mm": 1_300,
    "height_safety_margin_mm": 100,
    "width_safety_margin_mm": 50,
    "weight_safety_margin_kg": 200,
    "is_hgv": True,
}


def test_canonical_vehicle_input_is_stored_without_a_second_projection() -> None:
    """Canonical values pass through unchanged; no legacy field is synthesized."""

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

    assert vehicle.tare_weight_t == Decimal("12.345")
    assert vehicle.length_m == Decimal("9.215")
    assert vehicle.has_trailer is False


@pytest.mark.parametrize("field", sorted(_LEGACY_VEHICLE_FIELDS))
def test_legacy_vehicle_fields_are_rejected(field: str) -> None:
    """Every removed kilogram/millimetre, alias and dead field is forbidden."""

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        VehicleCreate.model_validate(
            {
                "name": "Legacy field truck",
                "registration_number": "L100BC77",
                field: _LEGACY_VEHICLE_FIELDS[field],
            }
        )


@pytest.mark.parametrize(
    "field,value",
    (
        ("tare_weight_kg", 3_000),
        ("max_gross_weight_kg", 10_000),
        ("length_mm", 8_000),
        ("width_mm", 2_500),
        ("height_mm", 1_800),
        ("platform_length_mm", 6_000),
        ("platform_width_mm", 2_500),
        ("platform_height_from_ground_mm", 900),
        ("max_platform_payload_kg", 6_000),
        ("payload_capacity_kg", 7_000),
        ("max_axle_load_kg", 10_000),
        ("max_cargo_length_mm", 7_000),
        ("max_cargo_width_mm", 2_600),
        ("max_cargo_height_mm", 3_000),
        ("max_cargo_weight_kg", 6_000),
    ),
)
def test_legacy_trailer_fields_are_rejected(field: str, value: int) -> None:
    """A trailer accepts canonical tonnes/metres only."""

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        TrailerCreate.model_validate(
            {
                "name": "Legacy field trailer",
                "registration_number": "L200BC77",
                field: value,
            }
        )


def test_new_vehicle_without_trailer_facts_is_explicitly_false() -> None:
    """The canonical boolean is definite even when a client omits it."""

    vehicle = VehicleCreate(name="Solo truck", registration_number="C789BC77")

    assert vehicle.has_trailer is False


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
    """A PATCH validates canonical fields and never produces a legacy projection."""

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
        {"tare_weight_t": Decimal("12.5")},
        current=current,
        allow_clear=True,
    )

    assert updates == {"tare_weight_t": Decimal("12.500")}


def test_partial_update_can_clear_a_physical_fact() -> None:
    """An explicit null clears the canonical value."""

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
    """The routing specification exposes canonical values unchanged."""

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


def test_vehicle_specification_reads_canonical_columns_only() -> None:
    """A row without canonical values stays explicitly unknown."""

    entity = SimpleNamespace(
        tare_weight_t=None,
        max_gross_weight_t=None,
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=None,
        width_m=None,
        height_m=None,
        has_trailer=False,
    )

    specification = vehicle_specification(entity)

    assert specification.tare_weight_t is None
    assert specification.length_m is None
    assert specification.has_trailer is False


def test_unit_conversion_helpers_are_exact_and_reject_negative_values() -> None:
    """One conversion layer is shared by request cargo and persistence boundaries."""

    assert kilograms_to_tonnes(12_345) == Decimal("12.345")
    assert tonnes_to_kilograms(Decimal("12.345")) == 12_345
    assert millimetres_to_metres(9_215) == Decimal("9.215")
    assert metres_to_millimetres(Decimal("9.215")) == 9_215
    with pytest.raises(VehicleSpecificationError):
        kilograms_to_tonnes(-1)


def test_axle_load_profile_requires_canonical_tonnes() -> None:
    """Measured axle loads are persisted and read in tonnes only."""

    canonical = VehicleLoadProfileCreate(
        configuration_type="CARGO_ON_TRUCK",
        max_actual_axle_load_t=Decimal("7.8"),
    )
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        VehicleLoadProfileCreate.model_validate(
            {
                "configuration_type": "CARGO_ON_TRUCK",
                "max_actual_axle_load_kg": 7_800,
            }
        )

    assert load_profile_axle_load_t(canonical) == Decimal("7.800")


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
    assert "is_hgv" not in payload


def test_trailer_update_accepts_canonical_values_and_clears_them() -> None:
    """Trailer normalization handles canonical updates and explicit clearing."""

    current = SimpleNamespace(
        tare_weight_t=Decimal("3.000"),
        max_gross_weight_t=None,
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=Decimal("8.000"),
        width_m=None,
        height_m=None,
        platform_length_m=None,
        platform_height_from_ground_m=None,
        max_platform_payload_t=None,
        max_cargo_length_m=None,
        max_cargo_width_m=None,
        max_cargo_height_m=None,
        max_cargo_weight_t=None,
    )

    assert normalize_trailer_fields(
        {"length_m": Decimal("8.5")}, current=current, allow_clear=True
    ) == {"length_m": Decimal("8.500")}
    assert normalize_trailer_fields(
        {"width_m": None}, current=current, allow_clear=True
    ) == {"width_m": None}


def test_vehicle_update_schema_no_longer_invents_a_trailer_flag() -> None:
    """A label-only PATCH keeps the existing trailer capability untouched."""

    update = VehicleUpdate(expected_version=4, notes="Only a note")

    assert update.model_fields_set == {"expected_version", "notes"}
    assert normalize_vehicle_fields(update.model_dump(exclude_unset=True), current=None) == {}
