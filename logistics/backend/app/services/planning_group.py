"""Resolve one main warehouse and its directly served representative planning group."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.integrations.rwms import RwmsPlanningClient
from app.models import Warehouse
from app.schemas.domain import RwmsWarehouseSupportLink


@dataclass(frozen=True, slots=True)
class PlanningWarehouseGroup:
    """One non-transitive planning root, local members, and admitted directed links."""

    root: Warehouse
    members: tuple[Warehouse, ...]
    links: tuple[RwmsWarehouseSupportLink, ...]


def link_allows_planning_date(
    link: RwmsWarehouseSupportLink,
    planning_date: date,
) -> bool:
    """Apply the owner-defined recurring calendar and exact date exceptions."""

    if planning_date in link.excluded_dates:
        return False
    weekday = planning_date.strftime("%A").upper()
    return (
        planning_date in link.allowed_dates
        or not link.allowed_weekdays
        or weekday in link.allowed_weekdays
    )


def link_allows_group_planning(link: RwmsWarehouseSupportLink) -> bool:
    """Require a capability that can carry a representative order in the root plan."""

    return (
        link.allow_drivers
        or link.allow_direct_fulfillment
        or link.allow_contractor_fallback
    )


async def resolve_planning_warehouse_group(
    session: AsyncSession,
    client: RwmsPlanningClient | None,
    selected: Warehouse,
    *,
    planning_date: date | None = None,
) -> PlanningWarehouseGroup:
    """Resolve a root and direct representatives without inventing a parent hierarchy."""

    if client is None:
        return PlanningWarehouseGroup(root=selected, members=(selected,), links=())

    adjacent = await client.list_support_network(selected.external_warehouse_id)
    root = selected
    if selected.representative:
        incoming = sorted(
            (
                link
                for link in adjacent
                if link.served_warehouse.warehouse_id == selected.external_warehouse_id
            ),
            key=lambda link: (link.priority, str(link.support_link_id)),
        )
        if incoming:
            support_external_id = incoming[0].support_warehouse.warehouse_id
            local_support = await session.scalar(
                select(Warehouse).where(
                    Warehouse.external_warehouse_id == support_external_id,
                    Warehouse.routing_ready.is_(True),
                )
            )
            if local_support is not None:
                root = local_support

    network = (
        adjacent
        if root.external_warehouse_id == selected.external_warehouse_id
        else await client.list_support_network(root.external_warehouse_id)
    )
    admitted_links = tuple(
        sorted(
            (
                link
                for link in network
                if link.support_warehouse.warehouse_id == root.external_warehouse_id
                and link.served_warehouse.representative
                and (
                    planning_date is None
                    or (
                        link_allows_group_planning(link)
                        and link_allows_planning_date(link, planning_date)
                    )
                )
            ),
            key=lambda link: (link.priority, str(link.support_link_id)),
        )
    )
    external_ids = {
        root.external_warehouse_id,
        *(link.served_warehouse.warehouse_id for link in admitted_links),
    }
    members_by_external_id = {
        warehouse.external_warehouse_id: warehouse
        for warehouse in await session.scalars(
            select(Warehouse)
            .where(
                Warehouse.external_warehouse_id.in_(external_ids),
                Warehouse.routing_ready.is_(True),
            )
            .order_by(Warehouse.name, Warehouse.id)
        )
    }
    members = [root]
    members.extend(
        members_by_external_id[link.served_warehouse.warehouse_id]
        for link in admitted_links
        if link.served_warehouse.warehouse_id in members_by_external_id
        and link.served_warehouse.warehouse_id != root.external_warehouse_id
    )
    unique_members = tuple({member.id: member for member in members}.values())
    return PlanningWarehouseGroup(
        root=root,
        members=unique_members,
        links=admitted_links,
    )
