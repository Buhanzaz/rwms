"""Pure domain value objects shared by persistence, API and routing layers."""

from .vehicle import (
    CANONICAL_DIMENSION_FIELDS,
    CANONICAL_MASS_FIELDS,
    CANONICAL_TO_LEGACY,
    LEGACY_TO_CANONICAL,
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

__all__ = [
    "CANONICAL_DIMENSION_FIELDS",
    "CANONICAL_MASS_FIELDS",
    "CANONICAL_TO_LEGACY",
    "LEGACY_TO_CANONICAL",
    "VehicleSpecification",
    "VehicleSpecificationError",
    "effective_trailer_capability",
    "kilograms_to_tonnes",
    "load_profile_axle_load_kg",
    "metres_to_millimetres",
    "millimetres_to_metres",
    "normalize_vehicle_fields",
    "tonnes_to_kilograms",
    "vehicle_specification",
]
