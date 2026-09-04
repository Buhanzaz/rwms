"""Durable actor-scoped idempotency receipts for retryable catalog creates."""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from hashlib import sha256
from typing import Protocol
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models.domain import CatalogCommandReceipt


class CatalogCreatedResource(Protocol):
    """Structural result contract shared by UUID-backed catalog aggregates."""

    id: UUID


@dataclass(frozen=True, slots=True)
class IdempotentCreateResult[ResourceT: CatalogCreatedResource]:
    """Return the created/replayed resource and whether side effects must be skipped."""

    resource: ResourceT
    replayed: bool


def command_request_hash(payload: Mapping[str, object]) -> str:
    """Hash one JSON-compatible command without depending on field insertion order."""

    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
    return sha256(canonical.encode()).hexdigest()


async def execute_idempotent_create[ResourceT: CatalogCreatedResource](
    session: AsyncSession,
    *,
    operation: str,
    actor_id: UUID,
    idempotency_key: str,
    payload: Mapping[str, object],
    resource_type: str,
    create: Callable[[], Awaitable[ResourceT]],
    load: Callable[[UUID], Awaitable[ResourceT | None]],
) -> IdempotentCreateResult[ResourceT]:
    """Claim a unique command, or replay its committed resource after payload verification."""

    normalized_key = idempotency_key.strip()
    if not normalized_key or len(normalized_key) > 200:
        raise ApiError(
            422,
            "INVALID_IDEMPOTENCY_KEY",
            "Idempotency-Key must contain between 1 and 200 visible characters",
        )
    request_hash = command_request_hash(payload)
    receipt_id = await session.scalar(
        insert(CatalogCommandReceipt)
        .values(
            operation=operation,
            actor_id=actor_id,
            idempotency_key=normalized_key,
            request_hash=request_hash,
        )
        .on_conflict_do_nothing(
            constraint="uq_catalog_command_receipts_operation_actor_key"
        )
        .returning(CatalogCommandReceipt.id)
    )
    if receipt_id is None:
        receipt = await session.scalar(
            select(CatalogCommandReceipt)
            .where(
                CatalogCommandReceipt.operation == operation,
                CatalogCommandReceipt.actor_id == actor_id,
                CatalogCommandReceipt.idempotency_key == normalized_key,
            )
            .with_for_update()
        )
        if receipt is None:
            raise RuntimeError("idempotency receipt disappeared after a unique conflict")
        if receipt.request_hash != request_hash:
            raise ApiError(
                409,
                "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency-Key was already used with a different command payload",
            )
        if receipt.resource_id is None or receipt.resource_type != resource_type:
            raise ApiError(
                409,
                "IDEMPOTENCY_RESULT_UNAVAILABLE",
                "The previous catalog command did not leave a replayable resource",
            )
        resource = await load(receipt.resource_id)
        if resource is None:
            raise ApiError(
                409,
                "IDEMPOTENCY_RESULT_UNAVAILABLE",
                "The resource created by the previous catalog command no longer exists",
            )
        return IdempotentCreateResult(resource=resource, replayed=True)

    receipt = await session.get(CatalogCommandReceipt, receipt_id)
    if receipt is None:
        raise RuntimeError("claimed idempotency receipt was not persisted")
    resource = await create()
    receipt.resource_type = resource_type
    receipt.resource_id = resource.id
    await session.flush()
    return IdempotentCreateResult(resource=resource, replayed=False)
