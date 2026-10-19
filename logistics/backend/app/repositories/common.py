"""Type-safe generic SQLAlchemy lookup helpers."""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Base
from app.errors import not_found


async def get_required[ModelT: Base](
    session: AsyncSession, model: type[ModelT], entity_id: UUID, resource_name: str
) -> ModelT:
    """Load a model by UUID or raise the standard API not-found error."""

    entity = await session.get(model, entity_id)
    if entity is None:
        raise not_found(resource_name, entity_id)
    return entity


async def list_for_warehouse[ModelT: Base](
    session: AsyncSession, model: type[ModelT], warehouse_id: UUID
) -> list[ModelT]:
    """List warehouse-owned rows in stable UUID order."""

    warehouse_column = model.warehouse_id  # type: ignore[attr-defined]
    id_column = model.id  # type: ignore[attr-defined]
    return list(
        (
            await session.scalars(
                select(model).where(warehouse_column == warehouse_id).order_by(id_column)
            )
        )
        .unique()
        .all()
    )
