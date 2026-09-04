"""PostgreSQL concurrency regressions for catalog command fences."""

from __future__ import annotations

import asyncio
import os
from datetime import date, time
from uuid import uuid4

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.errors import ApiError
from app.models import Driver, Warehouse
from app.models.domain import CatalogCommandReceipt
from app.schemas.domain import DriverCreate, ShiftCreate
from app.services import catalog
from app.services.catalog_command_idempotency import execute_idempotent_create
from tests.factories import make_driver, make_vehicle, make_warehouse

pytestmark = pytest.mark.integration


def _session_factory() -> tuple[
    async_sessionmaker[AsyncSession],
    AsyncEngine,
]:
    """Create independent sessions against the explicitly selected integration database."""

    database_url = os.getenv("TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL is not configured")
    engine = create_async_engine(database_url, pool_pre_ping=True)
    return async_sessionmaker(engine, expire_on_commit=False), engine


@pytest.mark.asyncio
async def test_concurrent_shift_overlap_is_serialized() -> None:
    """Two transactions cannot both create overlapping assignments for one resource."""

    sessions, database_engine = _session_factory()
    async with sessions() as setup:
        warehouse = await make_warehouse(setup, name=f"Shift fence {uuid4()}")
        driver = await make_driver(setup, warehouse)
        vehicle = await make_vehicle(setup, warehouse)
        await setup.commit()
        warehouse_id = warehouse.id
        driver_id = driver.id
        vehicle_id = vehicle.id

    async def attempt(start_time: time, end_time: time) -> str:
        """Commit one contender or return its sanitized overlap code."""

        async with sessions() as session:
            try:
                await catalog.create_shift(
                    session,
                    warehouse_id,
                    ShiftCreate(
                        driver_id=driver_id,
                        vehicle_id=vehicle_id,
                        date_from=date(2026, 8, 31),
                        date_to=date(2026, 9, 1),
                        start_time=start_time,
                        end_time=end_time,
                    ),
                )
                await session.commit()
                return "CREATED"
            except ApiError as exc:
                await session.rollback()
                return exc.code

    try:
        outcomes = await asyncio.gather(
            attempt(time(22), time(6)),
            attempt(time(23), time(7)),
        )
        assert outcomes.count("CREATED") == 1
        assert len(outcomes) == 2
        assert set(outcomes).difference({"CREATED"}) <= {
            "DRIVER_SHIFT_OVERLAP",
            "VEHICLE_SHIFT_OVERLAP",
        }
    finally:
        async with sessions() as cleanup:
            await cleanup.execute(delete(Warehouse).where(Warehouse.id == warehouse_id))
            await cleanup.commit()
        await database_engine.dispose()


@pytest.mark.asyncio
async def test_different_idempotency_keys_cannot_create_two_driver_pools() -> None:
    """The warehouse advisory fence closes the check/insert race across command receipts."""

    sessions, database_engine = _session_factory()
    actor_id = uuid4()
    async with sessions() as setup:
        warehouse = await make_warehouse(setup, name=f"Driver pool fence {uuid4()}")
        await setup.commit()
        warehouse_id = warehouse.id

    async def attempt(idempotency_key: str) -> str:
        """Commit one actor-scoped command or return its pool conflict code."""

        async with sessions() as session:
            try:
                await execute_idempotent_create(
                    session,
                    operation="create_driver",
                    actor_id=actor_id,
                    idempotency_key=idempotency_key,
                    payload={
                        "warehouse_id": str(warehouse_id),
                        "rwms_assignment_mode": "WAREHOUSE_DRIVERS",
                    },
                    resource_type="driver",
                    create=lambda: catalog.create_driver(
                        session,
                        warehouse_id,
                        DriverCreate(rwms_assignment_mode="WAREHOUSE_DRIVERS"),
                        None,
                    ),
                    load=lambda resource_id: session.get(Driver, resource_id),
                )
                await session.commit()
                return "CREATED"
            except ApiError as exc:
                await session.rollback()
                return exc.code

    try:
        outcomes = await asyncio.gather(attempt("pool-a"), attempt("pool-b"))
        assert sorted(outcomes) == ["CREATED", "WAREHOUSE_DRIVER_POOL_EXISTS"]
        async with sessions() as verification:
            assert (
                await verification.scalar(
                    select(func.count(Driver.id)).where(
                        Driver.warehouse_id == warehouse_id,
                        Driver.rwms_assignment_mode == "WAREHOUSE_DRIVERS",
                    )
                )
                == 1
            )
    finally:
        async with sessions() as cleanup:
            await cleanup.execute(
                delete(CatalogCommandReceipt).where(
                    CatalogCommandReceipt.actor_id == actor_id
                )
            )
            await cleanup.execute(delete(Warehouse).where(Warehouse.id == warehouse_id))
            await cleanup.commit()
        await database_engine.dispose()
