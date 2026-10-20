"""Pure domain value objects shared by persistence, API and routing layers."""

from .vehicle import (
    CANONICAL_DIMENSION_FIELDS,
    CANONICAL_MARGIN_FIELDS,
    CANONICAL_MASS_FIELDS,
    TrailerSpecification,
    VehicleSpecification,
    VehicleSpecificationError,
    kilograms_to_tonnes,
    load_profile_axle_load_t,
    metres_to_millimetres,
    millimetres_to_metres,
    normalize_trailer_fields,
    normalize_vehicle_fields,
    tonnes_to_kilograms,
    trailer_specification,
    vehicle_specification,
)

__all__ = [
    "CANONICAL_DIMENSION_FIELDS",
    "CANONICAL_MARGIN_FIELDS",
    "CANONICAL_MASS_FIELDS",
    "TrailerSpecification",
    "VehicleSpecification",
    "VehicleSpecificationError",
    "kilograms_to_tonnes",
    "load_profile_axle_load_t",
    "metres_to_millimetres",
    "millimetres_to_metres",
    "normalize_trailer_fields",
    "normalize_vehicle_fields",
    "tonnes_to_kilograms",
    "trailer_specification",
    "vehicle_specification",
]
