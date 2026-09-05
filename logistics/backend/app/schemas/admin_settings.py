"""Administrative planner configuration under canonical warehouse identities."""

from typing import Literal
from uuid import UUID

from pydantic import Field, field_validator

from app.schemas.domain import (
    ApiModel,
    IsochroneTariff,
    PlanningSettings,
    _validate_isochrone_tariffs,
)
from app.schemas.policy_zones import PolicyZoneRead


class AdminPlanningSettingsRead(ApiModel):
    """Current configuration and map context, without private warehouse identities."""

    warehouse_id: UUID
    version: int
    name: str
    timezone: str
    latitude: float
    longitude: float
    settings: PlanningSettings
    isochrone_tariffs: list[IsochroneTariff]
    capacity_publish_status: Literal["NOT_REQUESTED", "PENDING", "PUBLISHED", "FAILED"]


class AdminPlanningSettingsUpdate(ApiModel):
    """Replace observed settings and the complete contiguous tariff ladder together."""

    expected_version: int = Field(ge=1)
    settings: PlanningSettings
    isochrone_tariffs: list[IsochroneTariff] = Field(min_length=1, max_length=12)

    _validate_tariffs = field_validator("isochrone_tariffs")(_validate_isochrone_tariffs)


class AdminPolicyZoneRead(PolicyZoneRead):
    """Policy evidence whose warehouse_id is the canonical warehouse-service UUID."""
