"""One equipment-availability policy across slots, day routes, and publication."""

from uuid import uuid4

import pytest

from app.models import Trailer, Vehicle
from app.services.planner_runtime import RuntimePlannerFacade
from app.slot_planning.configuration import (
    effective_vehicle_cabin_capacity,
    vehicle_equipment_snapshot,
    vehicle_has_available_trailer,
)


@pytest.mark.parametrize("active", (True, False))
@pytest.mark.parametrize("can_use_trailer", (True, False, None))
def test_every_equipment_projection_excludes_unavailable_trailer(
    active: bool,
    can_use_trailer: bool | None,
) -> None:
    """An inactive trailer cannot reach exact routing even through an existing assignment."""

    trailer = Trailer(id=uuid4(), active=active)
    vehicle = Vehicle(
        id=uuid4(),
        capacity=2,
        can_use_trailer=can_use_trailer,
        default_trailer=trailer,
        load_profiles=[],
    )
    available = active and can_use_trailer is True

    assert vehicle_has_available_trailer(vehicle) is available
    assert effective_vehicle_cabin_capacity(vehicle) == (2 if available else 1)
    assert (vehicle_equipment_snapshot(vehicle, {}).trailer is not None) is available
    assert (RuntimePlannerFacade._trailer_spec(trailer) is not None) is active
