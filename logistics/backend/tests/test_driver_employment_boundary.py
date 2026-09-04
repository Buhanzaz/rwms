"""Focused tests for the staff-only planner-driver employment boundary."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Literal, cast
from unittest.mock import AsyncMock, MagicMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api import catalog as catalog_api
from app.errors import ApiError
from app.schemas.domain import DriverCreate, DriverUpdate, RwmsDriverIdentity
from app.services import catalog as catalog_service
from tests.auth import admin_principal


def _driver_identity(
    warehouse_id: UUID,
    *,
    employment_type: Literal["STAFF", "CONTRACTOR"],
) -> RwmsDriverIdentity:
    """Build one canonical RWMS driver identity for boundary tests."""

    return RwmsDriverIdentity(
        workerId=uuid4(),
        displayName=(
            "Штатный водитель" if employment_type == "STAFF" else "Наёмный водитель"
        ),
        employmentType=employment_type,
        phone="+79990000000" if employment_type == "CONTRACTOR" else None,
        operationalWarehouseId=warehouse_id,
        availableFrom=None,
        availableUntil=None,
        availabilityKind="HOME",
    )


@pytest.mark.asyncio
async def test_available_driver_catalog_lists_only_staff(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Do not expose contractors in the exact planner-driver selector."""

    warehouse_id = uuid4()
    external_warehouse_id = uuid4()
    staff = _driver_identity(external_warehouse_id, employment_type="STAFF")
    contractor = _driver_identity(external_warehouse_id, employment_type="CONTRACTOR")
    client = AsyncMock()
    client.list_drivers.return_value = [contractor, staff]
    monkeypatch.setattr(
        catalog_api,
        "require_local_warehouse_access",
        AsyncMock(
            return_value=SimpleNamespace(external_warehouse_id=external_warehouse_id)
        ),
    )

    available = await catalog_api.list_available_drivers(
        warehouse_id,
        cast(AsyncSession, MagicMock()),
        client,
        admin_principal(),
    )

    assert [item.worker_id for item in available] == [staff.worker_id]
    client.list_drivers.assert_awaited_once_with(external_warehouse_id)


@pytest.mark.asyncio
async def test_direct_create_rejects_contractor_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reject a contractor even when the service is called without the API filter."""

    warehouse_id = uuid4()
    contractor = _driver_identity(warehouse_id, employment_type="CONTRACTOR")
    session = MagicMock(spec=AsyncSession)
    session.add = MagicMock()
    session.flush = AsyncMock()
    monkeypatch.setattr(
        catalog_service,
        "require_warehouse",
        AsyncMock(return_value=SimpleNamespace(id=warehouse_id)),
    )

    with pytest.raises(ApiError) as rejected:
        await catalog_service.create_driver(
            cast(AsyncSession, session),
            warehouse_id,
            DriverCreate(
                rwms_assignment_mode="ASSIGNED_DRIVER",
                external_worker_id=contractor.worker_id,
            ),
            contractor,
        )

    assert rejected.value.code == "RWMS_DRIVER_NOT_STAFF"
    assert rejected.value.status_code == 422
    session.add.assert_not_called()
    session.flush.assert_not_awaited()


@pytest.mark.asyncio
async def test_direct_update_rejects_contractor_without_mutating_driver(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Keep the persisted driver unchanged when an update supplies a contractor."""

    warehouse_id = uuid4()
    driver_id = uuid4()
    contractor = _driver_identity(warehouse_id, employment_type="CONTRACTOR")
    driver = SimpleNamespace(
        id=driver_id,
        version=1,
        warehouse_id=warehouse_id,
        rwms_assignment_mode="WAREHOUSE_DRIVERS",
        external_worker_id=None,
        name="Водители склада",
    )
    session = MagicMock(spec=AsyncSession)
    session.flush = AsyncMock()
    monkeypatch.setattr(
        catalog_service,
        "_get_versioned_catalog_entity",
        AsyncMock(return_value=driver),
    )

    with pytest.raises(ApiError) as rejected:
        await catalog_service.update_driver(
            cast(AsyncSession, session),
            driver_id,
            DriverUpdate(
                expected_version=1,
                rwms_assignment_mode="ASSIGNED_DRIVER",
                external_worker_id=contractor.worker_id,
            ),
            contractor,
        )

    assert rejected.value.code == "RWMS_DRIVER_NOT_STAFF"
    assert driver.rwms_assignment_mode == "WAREHOUSE_DRIVERS"
    assert driver.external_worker_id is None
    assert driver.name == "Водители склада"
    session.flush.assert_not_awaited()


@pytest.mark.asyncio
async def test_staff_and_warehouse_driver_pool_remain_supported(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Preserve exact staff selection and the identity-free warehouse pool."""

    warehouse_id = uuid4()
    staff = _driver_identity(warehouse_id, employment_type="STAFF")
    session = MagicMock(spec=AsyncSession)
    session.add = MagicMock()
    session.execute = AsyncMock()
    session.scalar = AsyncMock(return_value=None)
    session.flush = AsyncMock()
    monkeypatch.setattr(
        catalog_service,
        "require_warehouse",
        AsyncMock(return_value=SimpleNamespace(id=warehouse_id)),
    )

    exact = await catalog_service.create_driver(
        cast(AsyncSession, session),
        warehouse_id,
        DriverCreate(
            rwms_assignment_mode="ASSIGNED_DRIVER",
            external_worker_id=staff.worker_id,
        ),
        staff,
    )
    pool = await catalog_service.create_driver(
        cast(AsyncSession, session),
        warehouse_id,
        DriverCreate(rwms_assignment_mode="WAREHOUSE_DRIVERS"),
        None,
    )

    assert exact.name == staff.display_name
    assert exact.external_worker_id == staff.worker_id
    assert pool.name == "Водители склада"
    assert pool.external_worker_id is None
    assert session.add.call_count == 2
    assert session.flush.await_count == 2
