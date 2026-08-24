"""Regression tests for zero-based route-stop references in JSON exports."""

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.models.domain import StopType
from app.schemas.domain import ExportRequest, ExportRouteSegment, ExportRouteStop


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


def test_old_request_export_without_scheduled_date_remains_valid() -> None:
    """Schema-version-one files created before explicit scheduling still import."""

    payload = {
        "id": str(uuid4()),
        "data": {
            "type": "DELIVERY",
            "name": "Старая заявка",
            "latitude": 55.75,
            "longitude": 37.61,
            "quantity": 1,
            "date_options": [{"date": "2026-08-25"}],
        },
        "zone_id": None,
        "zone_version": None,
        "zone_classification_status": "OUTSIDE_ZONES",
    }

    assert ExportRequest.model_validate(payload).scheduled_date is None


def test_export_rejects_scheduled_date_outside_accepted_options() -> None:
    """A malformed import cannot create an assignment outside customer options."""

    with pytest.raises(ValidationError, match="scheduled_date"):
        ExportRequest.model_validate(
            {
                "id": str(uuid4()),
                "data": {
                    "type": "PICKUP",
                    "name": "Некорректная заявка",
                    "latitude": 55.75,
                    "longitude": 37.61,
                    "quantity": 1,
                    "date_options": [{"date": "2026-08-25"}],
                },
                "scheduled_date": "2026-08-26",
                "zone_id": None,
                "zone_version": None,
                "zone_classification_status": "OUTSIDE_ZONES",
            }
        )
