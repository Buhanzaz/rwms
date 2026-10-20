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
    effective_trailer_capability,
    kilograms_to_tonnes,
    load_profile_axle_load_kg,
    metres_to_millimetres,
    millimetres_to_metres,
    normalize_vehicle_fields,
    tonnes_to_kilograms,
    vehicle_specification,
)
from app.schemas.domain import (
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
    """A legacy payload receives an exact canonical projection without data loss."""

    vehicle = VehicleCreate.model_validate(_legacy_vehicle_payload())

    assert vehicle.tare_weight_t == Decimal("12.000")
    assert vehicle.max_gross_weight_t == Decimal("22.000")
    assert vehicle.axle_load_t == Decimal("10.000")
    assert vehicle.payload_capacity_t == Decimal("8.000")
    assert vehicle.length_m == Decimal("9.200")
    assert vehicle.width_m == Decimal("2.500")
    assert vehicle.height_m == Decimal("3.200")
    assert vehicle.has_trailer is True
    assert vehicle.can_use_trailer is True
    # The legacy projection remains available for backward-compatible consumers.
    assert vehicle.tare_weight_kg == 12_000
    assert vehicle.length_mm == 9_200


def test_canonical_tonne_metre_input_derives_legacy_projection() -> None:
    """A canonical-only command keeps the deprecated columns in sync."""

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

    assert vehicle.tare_weight_kg == 12_345
    assert vehicle.max_gross_weight_kg == 22_500
    assert vehicle.max_axle_load_kg == 10_100
    assert vehicle.payload_capacity_kg == 8_005
    assert vehicle.length_mm == 9_215
    assert vehicle.width_mm == 2_550
    assert vehicle.height_mm == 3_200
    assert vehicle.can_use_trailer is False


def test_new_vehicle_without_trailer_facts_is_explicitly_false() -> None:
    """The canonical boolean is definite even when a legacy client omits it."""

    vehicle = VehicleCreate(name="Solo truck", registration_number="C789BC77")

    assert vehicle.has_trailer is False
    assert vehicle.can_use_trailer is False


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


def test_partial_update_normalizes_both_stored_unit_sets() -> None:
    """A PATCH that changes one unit also updates its canonical/legacy counterpart."""

    current = SimpleNamespace(
        tare_weight_t=Decimal("12.000"),
        max_gross_weight_t=Decimal("22.000"),
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=None,
        width_m=None,
        height_m=None,
        has_trailer=True,
        can_use_trailer=True,
        default_trailer_id=None,
    )

    updates = normalize_vehicle_fields(
        {"tare_weight_t": Decimal("12.5")},
        current=current,
        allow_clear=True,
    )

    assert updates["tare_weight_t"] == Decimal("12.500")
    assert updates["tare_weight_kg"] == 12_500
    assert "max_gross_weight_t" not in updates


def test_partial_update_can_clear_a_physical_fact() -> None:
    """An explicit null clears both projections instead of leaving stale legacy data."""

    current = SimpleNamespace(
        tare_weight_t=Decimal("12.000"),
        max_gross_weight_t=None,
        axle_load_t=None,
        payload_capacity_t=None,
        length_m=Decimal("9.200"),
        width_m=None,
        height_m=None,
        has_trailer=False,
        can_use_trailer=False,
        default_trailer_id=None,
    )

    updates = normalize_vehicle_fields(
        {"length_m": None},
        current=current,
        allow_clear=True,
    )

    assert updates["length_m"] is None
    assert updates["length_mm"] is None


def test_canonical_specification_preserves_exact_routing_projection() -> None:
    """Tonnes/metres round-trip to the exact millimetres/kilograms used by routing."""

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

    assert specification.legacy_projection() == {
        "tare_weight_kg": 12_345,
        "max_gross_weight_kg": 22_000,
        "max_axle_load_kg": 10_000,
        "payload_capacity_kg": 8_000,
        "length_mm": 9_215,
        "width_mm": 2_500,
        "height_mm": 3_200,
    }


def test_vehicle_specification_falls_back_to_legacy_columns() -> None:
    """Rows created before the canonical migration remain readable and routable."""

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
        has_trailer=None,
        can_use_trailer=True,
    )

    specification = vehicle_specification(legacy_row)

    assert specification.tare_weight_t == Decimal("12.000")
    assert specification.length_m == Decimal("9.200")
    assert specification.has_trailer is True


def test_effective_trailer_capability_keeps_legacy_tri_state_semantics() -> None:
    """Unknown stays unknown, while any explicit positive fact wins."""

    assert effective_trailer_capability(None, None) is None
    assert effective_trailer_capability(False, None) is False
    assert effective_trailer_capability(None, True) is True
    assert effective_trailer_capability(True, False) is True


def test_unit_conversion_helpers_are_exact_and_reject_negative_values() -> None:
    """One conversion layer is shared by persistence, API and routing."""

    assert kilograms_to_tonnes(12_345) == Decimal("12.345")
    assert tonnes_to_kilograms(Decimal("12.345")) == 12_345
    assert millimetres_to_metres(9_215) == Decimal("9.215")
    assert metres_to_millimetres(Decimal("9.215")) == 9_215
    with pytest.raises(VehicleSpecificationError):
        kilograms_to_tonnes(-1)


def test_axle_load_profile_keeps_tonne_and_kilogram_views_in_sync() -> None:
    """Measured axle loads use the same conversion layer as the vehicle model."""

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

    assert canonical.max_actual_axle_load_kg == 7_800
    assert legacy.max_actual_axle_load_t == Decimal("7.800")
    assert load_profile_axle_load_kg(canonical) == 7_800
    assert load_profile_axle_load_kg(
        SimpleNamespace(max_actual_axle_load_t=None, max_actual_axle_load_kg=7_900)
    ) == 7_900


def test_vehicle_read_serializes_canonical_values_as_json_numbers() -> None:
    """The transport boundary exposes tonnes/metres as numbers, not unit-less strings."""

    values = _legacy_vehicle_payload()
    values.update(
        {
            "id": uuid4(),
            "version": 3,
            "warehouse_id": uuid4(),
            "load_profiles": [],
            "has_trailer": True,
            "tare_weight_kg": 12_345,
            "length_mm": 9_215,
        }
    )
    read = VehicleRead.model_validate(SimpleNamespace(**values))
    payload = read.model_dump(mode="json")

    assert payload["tare_weight_t"] == 12.345
    assert payload["length_m"] == 9.215
    assert payload["has_trailer"] is True
    assert payload["tare_weight_kg"] == 12_345


def test_vehicle_update_schema_no_longer_invents_a_trailer_flag() -> None:
    """A label-only PATCH keeps the existing trailer capability untouched."""

    update = VehicleUpdate(expected_version=4, notes="Только заметка")

    assert update.model_fields_set == {"expected_version", "notes"}
    assert normalize_vehicle_fields(update.model_dump(exclude_unset=True), current=None) == {}
