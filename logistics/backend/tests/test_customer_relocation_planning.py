"""Fail-closed coverage for customer-relocation demand in the route planner."""

from datetime import date, time
from uuid import uuid4

import pytest

from app.errors import ApiError
from app.models import CustomerDeliveryPurpose, LogisticsRequest, RequestDateOption
from app.planner import RequestStatus
from app.services.auto_planning import _planning_facts_complete
from app.services.planner_runtime import RuntimePlannerFacade


def _customer_relocation_request(planning_date: date) -> LogisticsRequest:
    """Build the persisted shape available to the planner without requiring a database."""

    request = LogisticsRequest(
        id=uuid4(),
        warehouse_id=uuid4(),
        type="DELIVERY",
        customer_delivery_purpose=CustomerDeliveryPurpose.CUSTOMER_RELOCATION,
        name="Customer relocation",
        address_label="Destination only",
        latitude=59.94,
        longitude=30.33,
        quantity=1,
        cargo_length_mm=6_000,
        cargo_width_mm=2_400,
        cargo_height_mm=2_400,
        cargo_weight_kg=1_200,
        service_minutes=30,
        priority=0,
        status=RequestStatus.READY,
        trailer_access_allowed=True,
    )
    request.date_options = [
        RequestDateOption(
            request_id=request.id,
            date=planning_date,
            priority=0,
            window_start=time(10),
            window_end=time(14),
            is_hard=True,
        )
    ]
    return request


def test_runtime_planner_rejects_relocation_without_coupled_route_data() -> None:
    """A one-point relocation cannot enter the ordinary delivery planner."""

    planning_date = date(2026, 9, 2)
    request = _customer_relocation_request(planning_date)

    with pytest.raises(ApiError) as rejected:
        RuntimePlannerFacade._assert_planning_details_complete(
            (request,),
            planning_date,
        )

    assert rejected.value.code == "PLANNING_INPUT_INCOMPLETE"
    assert rejected.value.extra["requests"] == [
        {
            "request_id": str(request.id),
            "name": request.name,
            "missing_fields": ["coupled_pickup_destination_empty_arrival"],
        }
    ]


def test_automatic_planning_marks_relocation_input_incomplete() -> None:
    """The automatic coordinator's readiness gate excludes one-point relocation demand."""

    planning_date = date(2026, 9, 2)
    request = _customer_relocation_request(planning_date)

    assert not _planning_facts_complete([request], planning_date)
