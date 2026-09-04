"""Warehouse policy-zone commands and exact geometry serialization."""

from __future__ import annotations

from datetime import date
from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError, not_found
from app.geo import geometry_to_geojson
from app.geo.policy_classification import geometry_from_geojson
from app.models import (
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.models.policy_zone import PolicyZoneKind, WarehousePolicyZone
from app.schemas.policy_zones import (
    GeoJsonMultiPolygon,
    PolicyZoneCreate,
    PolicyZoneRead,
    PolicyZoneUpdate,
)
from app.services.plans import MUTABLE_PLAN_STATUSES, archive_mutable_plans_for_dates

_DEFAULT_COLORS = {
    PolicyZoneKind.SPECIAL_PRICE: "#3B82F6",
    PolicyZoneKind.FORBIDDEN: "#EF4444",
    PolicyZoneKind.NO_TRAILER: "#F59E0B",
}
_POLICY_ZONE_CATEGORY_LIMIT = 500


async def list_policy_zones(
    session: AsyncSession,
    warehouse_id: UUID,
) -> list[WarehousePolicyZone]:
    """List one warehouse's exceptional policies in stable dispatcher order."""

    return list(
        await session.scalars(
            select(WarehousePolicyZone)
            .where(WarehousePolicyZone.warehouse_id == warehouse_id)
            .order_by(
                WarehousePolicyZone.kind,
                WarehousePolicyZone.name,
                WarehousePolicyZone.id,
            )
        )
    )


async def require_policy_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    *,
    for_update: bool = False,
) -> WarehousePolicyZone:
    """Resolve a zone only through its owning warehouse, optionally locking it."""

    statement = select(WarehousePolicyZone).where(
        WarehousePolicyZone.id == zone_id,
        WarehousePolicyZone.warehouse_id == warehouse_id,
    )
    if for_update:
        statement = statement.with_for_update()
    zone = await session.scalar(statement)
    if zone is None:
        raise not_found("warehouse_policy_zone", zone_id)
    return zone


async def create_policy_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: PolicyZoneCreate,
) -> WarehousePolicyZone:
    """Persist a version-one policy under the locked warehouse command root."""

    await _lock_warehouse(session, warehouse_id)
    await _require_category_capacity(session, warehouse_id, payload.kind)
    _validate_kind_values(
        payload.kind,
        payload.delivery_price_rubles,
        payload.pickup_price_rubles,
    )
    zone = WarehousePolicyZone(
        warehouse_id=warehouse_id,
        name=payload.name,
        kind=payload.kind,
        color=payload.color or _DEFAULT_COLORS[payload.kind],
        geometry=geometry_from_geojson(payload.geometry),
        version=1,
        delivery_price_rubles=payload.delivery_price_rubles,
        pickup_price_rubles=payload.pickup_price_rubles,
    )
    session.add(zone)
    await session.flush()
    return zone


async def update_policy_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    payload: PolicyZoneUpdate,
) -> WarehousePolicyZone:
    """Apply one exact version-fenced policy update and advance its source revision."""

    await _lock_warehouse(session, warehouse_id)
    zone = await require_policy_zone(
        session,
        warehouse_id,
        zone_id,
        for_update=True,
    )
    _require_version(zone, payload.expected_version)
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version", "geometry"})
    effective_kind = PolicyZoneKind(values.get("kind", zone.kind))
    effective_delivery_price = values.get(
        "delivery_price_rubles",
        zone.delivery_price_rubles,
    )
    effective_pickup_price = values.get(
        "pickup_price_rubles",
        zone.pickup_price_rubles,
    )
    await _require_category_capacity(
        session,
        warehouse_id,
        effective_kind,
        exclude_zone_id=zone.id,
    )
    _validate_kind_values(
        effective_kind,
        effective_delivery_price,
        effective_pickup_price,
    )
    for field, value in values.items():
        setattr(zone, field, value)
    if payload.geometry is not None:
        zone.geometry = geometry_from_geojson(payload.geometry)
    zone.version += 1
    await session.flush()
    await session.refresh(zone)
    return zone


async def delete_policy_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    expected_version: int,
) -> None:
    """Delete one exact owner policy only after its optimistic fence matches."""

    await _lock_warehouse(session, warehouse_id)
    zone = await require_policy_zone(
        session,
        warehouse_id,
        zone_id,
        for_update=True,
    )
    _require_version(zone, expected_version)
    await session.delete(zone)
    await session.flush()


async def archive_policy_stale_plan_heads(
    session: AsyncSession,
    warehouse_id: UUID,
) -> tuple[UUID, ...]:
    """Archive mutable local or group-root heads containing this warehouse's demand.

    Confirmed plans are historical operational decisions and deliberately remain unchanged.
    A later ensure or demand-ingestion pass rebuilds a fresh head from the new policy revision.
    """

    assigned_plan_ids = (
        select(RouteCycle.route_plan_id)
        .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
        .join(PlanningTask, PlanningTask.id == RouteStop.task_id)
        .join(LogisticsRequest, LogisticsRequest.id == PlanningTask.request_id)
        .where(LogisticsRequest.warehouse_id == warehouse_id)
    )
    unassigned_plan_ids = (
        select(UnassignedTask.route_plan_id)
        .join(PlanningTask, PlanningTask.id == UnassignedTask.task_id)
        .join(LogisticsRequest, LogisticsRequest.id == PlanningTask.request_id)
        .where(LogisticsRequest.warehouse_id == warehouse_id)
    )
    affected_rows = await session.execute(
        select(RoutePlan.warehouse_id, RoutePlan.date)
        .where(
            RoutePlan.status.in_(MUTABLE_PLAN_STATUSES),
            or_(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.id.in_(assigned_plan_ids),
                RoutePlan.id.in_(unassigned_plan_ids),
            ),
        )
        .distinct()
    )
    dates_by_root: dict[UUID, set[date]] = {}
    for root_warehouse_id, planning_date in affected_rows:
        dates_by_root.setdefault(root_warehouse_id, set()).add(planning_date)
    archived: list[UUID] = []
    for root_warehouse_id, dates in sorted(
        dates_by_root.items(),
        key=lambda item: str(item[0]),
    ):
        archived.extend(
            await archive_mutable_plans_for_dates(
                session,
                root_warehouse_id,
                dates,
            )
        )
    return tuple(archived)


def policy_zone_read(zone: WarehousePolicyZone) -> PolicyZoneRead:
    """Serialize the persisted MultiPolygon without client-owned reconstruction."""

    geometry = GeoJsonMultiPolygon.model_validate(geometry_to_geojson(zone.geometry))
    return PolicyZoneRead(
        id=zone.id,
        warehouse_id=zone.warehouse_id,
        name=zone.name,
        kind=PolicyZoneKind(zone.kind),
        color=zone.color,
        geometry=geometry,
        version=zone.version,
        delivery_price_rubles=zone.delivery_price_rubles,
        pickup_price_rubles=zone.pickup_price_rubles,
        created_at=zone.created_at,
        updated_at=zone.updated_at,
    )


async def _lock_warehouse(session: AsyncSession, warehouse_id: UUID) -> None:
    """Serialize policy changes with every other warehouse-capacity mutation."""

    found = await session.scalar(
        select(Warehouse.id).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if found is None:
        raise not_found("warehouse", warehouse_id)


async def _require_category_capacity(
    session: AsyncSession,
    warehouse_id: UUID,
    kind: PolicyZoneKind,
    *,
    exclude_zone_id: UUID | None = None,
) -> None:
    """Reject a local state that the canonical capacity replacement cannot publish."""

    category_filter = (
        WarehousePolicyZone.kind == PolicyZoneKind.SPECIAL_PRICE
        if kind is PolicyZoneKind.SPECIAL_PRICE
        else WarehousePolicyZone.kind.in_(
            (PolicyZoneKind.FORBIDDEN, PolicyZoneKind.NO_TRAILER)
        )
    )
    statement = select(func.count(WarehousePolicyZone.id)).where(
        WarehousePolicyZone.warehouse_id == warehouse_id,
        category_filter,
    )
    if exclude_zone_id is not None:
        statement = statement.where(WarehousePolicyZone.id != exclude_zone_id)
    count = int(await session.scalar(statement) or 0)
    if count >= _POLICY_ZONE_CATEGORY_LIMIT:
        family = "price" if kind is PolicyZoneKind.SPECIAL_PRICE else "restriction"
        raise ApiError(
            422,
            "POLICY_ZONE_LIMIT_EXCEEDED",
            f"A warehouse cannot contain more than {_POLICY_ZONE_CATEGORY_LIMIT} {family} zones",
            extra={"family": family, "limit": _POLICY_ZONE_CATEGORY_LIMIT},
        )


def _require_version(zone: WarehousePolicyZone, expected_version: int) -> None:
    """Reject stale mutations with the current source revision for refetch."""

    if zone.version != expected_version:
        raise ApiError(
            409,
            "POLICY_ZONE_VERSION_CONFLICT",
            "The policy zone changed; reload it before retrying the command",
            extra={
                "zone_id": str(zone.id),
                "expected_version": expected_version,
                "current_version": zone.version,
            },
        )


def _validate_kind_values(
    kind: PolicyZoneKind,
    delivery_price_rubles: object,
    pickup_price_rubles: object,
) -> None:
    """Keep special money and physical restrictions as disjoint policy families."""

    prices_present = (
        isinstance(delivery_price_rubles, int)
        and not isinstance(delivery_price_rubles, bool)
        and delivery_price_rubles >= 0
        and isinstance(pickup_price_rubles, int)
        and not isinstance(pickup_price_rubles, bool)
        and pickup_price_rubles >= 0
    )
    restriction_values_absent = (
        delivery_price_rubles is None and pickup_price_rubles is None
    )
    if (
        (kind is PolicyZoneKind.SPECIAL_PRICE and not prices_present)
        or (
            kind is not PolicyZoneKind.SPECIAL_PRICE
            and not restriction_values_absent
        )
    ):
        raise ApiError(
            422,
            "POLICY_ZONE_VALUES_INVALID",
            "SPECIAL_PRICE requires both prices; restrictions cannot define money",
        )
