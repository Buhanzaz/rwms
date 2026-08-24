"""Regression tests for zero-based route-stop references in JSON exports."""

from datetime import UTC, datetime

from app.models.domain import StopType
from app.schemas.domain import ExportRouteSegment, ExportRouteStop


def test_export_accepts_zero_based_depot_stop_and_segment_reference() -> None:
    """The initial depot stop is sequence zero in persisted planner output."""

    moment = datetime(2026, 8, 22, 8, tzinfo=UTC)
    stop = ExportRouteStop(
        sequence=0,
        task=None,
        stop_type=StopType.DEPOT_LOAD,
        planned_arrival=moment,
        planned_departure=moment,
        service_seconds=0,
        quantity_delta=0,
        load_before=0,
        load_after=0,
        latitude=55.75,
        longitude=37.62,
    )
    segment = ExportRouteSegment(
        sequence=1,
        from_stop_sequence=0,
        to_stop_sequence=1,
        departure_at=moment,
        arrival_at=moment,
        distance_meters=0,
        travel_seconds=0,
        geometry={"type": "LineString", "coordinates": []},
    )

    assert stop.sequence == 0
    assert segment.from_stop_sequence == 0
