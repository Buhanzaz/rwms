"""Bounded server-owned RWMS demand ingestion with a cross-instance PostgreSQL fence."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from hashlib import sha256
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.db import async_session_factory, engine
from app.integrations.rwms import RwmsPlanningClient
from app.integrations.rwms_sync import (
    refresh_warehouse_directory,
    sync_warehouse_requests,
    warehouse_geocoding_query,
)
from app.models import Warehouse
from app.schemas.domain import RwmsSyncRequest, RwmsWarehouseSupportLink
from app.schemas.geocoding import ResolvedAddress
from app.services.auto_planning import (
    generate_missing_draft_plans,
    invalidate_mutable_group_root_plans,
)
from app.services.planning_group import (
    link_allows_group_planning,
    link_allows_planning_date,
)
from app.services.plans import PlannerFacade

logger = logging.getLogger(__name__)
_FENCE_KEY = int.from_bytes(
    sha256(b"rwms-logistics:demand-ingestion:v1").digest()[:8],
    byteorder="big",
    signed=True,
)


class DemandGeocoder(Protocol):
    """Minimal server-side address resolver required by automatic demand ingestion."""

    async def forward(self, address: str) -> ResolvedAddress:
        """Resolve one canonical warehouse or request address."""


@dataclass(frozen=True, slots=True)
class DemandIngestionBatchResult:
    """Bounded worker progress and the stable UUID cursor for the next interval."""

    processed: int
    next_cursor: UUID | None
    fence_acquired: bool


@dataclass(frozen=True, slots=True)
class _PlanningNetwork:
    """HTTP-fetched group topology safe to carry into a later database transaction."""

    root_external_id: UUID
    links: tuple[RwmsWarehouseSupportLink, ...]


async def _fetch_planning_network(
    client: RwmsPlanningClient,
    selected: Warehouse,
) -> _PlanningNetwork:
    """Resolve the root and its adjacent links without holding a database transaction."""

    adjacent = await client.list_support_network(selected.external_warehouse_id)
    root_external_id = selected.external_warehouse_id
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
            root_external_id = incoming[0].support_warehouse.warehouse_id
    network = (
        adjacent
        if root_external_id == selected.external_warehouse_id
        else await client.list_support_network(root_external_id)
    )
    return _PlanningNetwork(root_external_id=root_external_id, links=tuple(network))


async def _group_planning_dates(
    session: AsyncSession,
    selected: Warehouse,
    network: _PlanningNetwork,
    planning_dates: tuple[date, ...],
) -> tuple[Warehouse, dict[tuple[UUID, ...], tuple[date, ...]]]:
    """Group exact dates by their admitted local direct-member set."""

    served_external_ids = {
        link.served_warehouse.warehouse_id
        for link in network.links
        if link.support_warehouse.warehouse_id == network.root_external_id
    }
    local_by_external_id = {
        warehouse.external_warehouse_id: warehouse
        for warehouse in await session.scalars(
            select(Warehouse).where(
                Warehouse.external_warehouse_id.in_(
                    {network.root_external_id, *served_external_ids}
                ),
                Warehouse.routing_ready.is_(True),
            )
        )
    }
    root = local_by_external_id.get(network.root_external_id, selected)
    grouped: dict[tuple[UUID, ...], list[date]] = {}
    for planning_date in planning_dates:
        admitted = sorted(
            (
                link
                for link in network.links
                if link.support_warehouse.warehouse_id == root.external_warehouse_id
                and link.served_warehouse.representative
                and link_allows_group_planning(link)
                and link_allows_planning_date(link, planning_date)
            ),
            key=lambda link: (link.priority, str(link.support_link_id)),
        )
        member_ids = tuple(
            dict.fromkeys(
                (
                    root.id,
                    *(
                        local_by_external_id[link.served_warehouse.warehouse_id].id
                        for link in admitted
                        if link.served_warehouse.warehouse_id in local_by_external_id
                        and link.served_warehouse.warehouse_id
                        != root.external_warehouse_id
                    ),
                )
            )
        )
        grouped.setdefault(member_ids, []).append(planning_date)
    return root, {member_ids: tuple(dates) for member_ids, dates in grouped.items()}


@asynccontextmanager
async def demand_ingestion_fence(
    database_engine: AsyncEngine,
) -> AsyncIterator[bool]:
    """Hold one session-level advisory lock on a dedicated connection for the complete batch."""

    async with database_engine.connect() as connection:
        acquired = bool(
            await connection.scalar(
                text("SELECT pg_try_advisory_lock(:key)"),
                {"key": _FENCE_KEY},
            )
        )
        try:
            yield acquired
        finally:
            if acquired:
                await connection.execute(
                    text("SELECT pg_advisory_unlock(:key)"),
                    {"key": _FENCE_KEY},
                )


async def run_demand_ingestion_batch(
    client: RwmsPlanningClient,
    geocoder: DemandGeocoder,
    planner: PlannerFacade,
    *,
    cursor: UUID | None,
    batch_size: int,
    database_engine: AsyncEngine = engine,
    session_factory: async_sessionmaker[AsyncSession] = async_session_factory,
) -> DemandIngestionBatchResult:
    """Reconcile the directory and import at most one keyset page of 31-day feeds."""

    async with demand_ingestion_fence(database_engine) as acquired:
        if not acquired:
            return DemandIngestionBatchResult(
                processed=0,
                next_cursor=cursor,
                fence_acquired=False,
            )

        async with session_factory() as directory_session:
            await refresh_warehouse_directory(
                directory_session,
                client,
                lambda identity: geocoder.forward(warehouse_geocoding_query(identity)),
            )
            await directory_session.commit()

        async with session_factory() as selection_session:
            statement = (
                select(Warehouse.id)
                .where(Warehouse.routing_ready.is_(True))
                .order_by(Warehouse.id)
                .limit(batch_size)
            )
            if cursor is not None:
                statement = statement.where(Warehouse.id > cursor)
            warehouse_ids = tuple(await selection_session.scalars(statement))
            if not warehouse_ids and cursor is not None:
                warehouse_ids = tuple(
                    await selection_session.scalars(
                        select(Warehouse.id)
                        .where(Warehouse.routing_ready.is_(True))
                        .order_by(Warehouse.id)
                        .limit(batch_size)
                    )
                )

        processed = 0
        for warehouse_id in warehouse_ids:
            warehouse: Warehouse | None = None
            result = None
            planning_dates: tuple[date, ...] = ()
            async with session_factory() as ingestion_session:
                try:
                    warehouse = await ingestion_session.get(Warehouse, warehouse_id)
                    if warehouse is None or not warehouse.routing_ready:
                        continue
                    date_from = datetime.now(ZoneInfo(warehouse.timezone)).date()
                    planning_dates = tuple(
                        date_from + timedelta(days=offset) for offset in range(31)
                    )
                    result = await sync_warehouse_requests(
                        ingestion_session,
                        warehouse.id,
                        RwmsSyncRequest(
                            warehouse_id=warehouse.external_warehouse_id,
                            date_from=date_from,
                            date_to=date_from + timedelta(days=30),
                        ),
                        client,
                        None,
                        resolve_address=lambda source: geocoder.forward(source.address),
                    )
                    await ingestion_session.commit()
                    processed += 1
                except asyncio.CancelledError:
                    raise
                except Exception:
                    await ingestion_session.rollback()
                    logger.exception(
                        "Automatic RWMS demand ingestion failed",
                        extra={"warehouse_id": str(warehouse_id)},
                    )
            if warehouse is None or result is None:
                continue
            if result.failures:
                logger.warning(
                    "Automatic RWMS demand ingestion completed with rejected orders",
                    extra={
                        "warehouse_id": str(warehouse_id),
                        "failure_count": len(result.failures),
                    },
                )
                continue
            try:
                network = await _fetch_planning_network(client, warehouse)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception(
                    "Automatic RWMS planning-group resolution failed after demand commit",
                    extra={"warehouse_id": str(warehouse_id)},
                )
                continue
            async with session_factory() as planning_session:
                try:
                    root, grouped_dates = await _group_planning_dates(
                        planning_session,
                        warehouse,
                        network,
                        planning_dates,
                    )
                    changed = result.imported > 0 or result.updated > 0
                    for member_ids, member_dates in grouped_dates.items():
                        if changed and len(member_ids) > 1 and warehouse.id in member_ids:
                            await invalidate_mutable_group_root_plans(
                                planning_session,
                                root.id,
                                member_dates,
                            )
                        await generate_missing_draft_plans(
                            planning_session,
                            planner,
                            root.id,
                            member_dates,
                            request_warehouse_ids=member_ids,
                            resource_warehouse_ids=member_ids,
                        )
                    await planning_session.commit()
                except asyncio.CancelledError:
                    await planning_session.rollback()
                    raise
                except Exception:
                    await planning_session.rollback()
                    logger.exception(
                        "Automatic root-group planning failed after demand commit",
                        extra={"warehouse_id": str(warehouse_id)},
                    )

        next_cursor = warehouse_ids[-1] if len(warehouse_ids) == batch_size else None
        return DemandIngestionBatchResult(
            processed=processed,
            next_cursor=next_cursor,
            fence_acquired=True,
        )


async def run_demand_ingestion_worker(
    client: RwmsPlanningClient,
    geocoder: DemandGeocoder,
    planner: PlannerFacade,
    stop: asyncio.Event,
    *,
    interval_seconds: float,
    batch_size: int,
) -> None:
    """Run one bounded ingestion attempt per configured interval until graceful shutdown."""

    cursor: UUID | None = None
    while not stop.is_set():
        try:
            outcome = await run_demand_ingestion_batch(
                client,
                geocoder,
                planner,
                cursor=cursor,
                batch_size=batch_size,
            )
            if outcome.fence_acquired:
                cursor = outcome.next_cursor
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("RWMS demand ingestion batch failed")
        try:
            await asyncio.wait_for(stop.wait(), timeout=interval_seconds)
        except TimeoutError:
            pass
