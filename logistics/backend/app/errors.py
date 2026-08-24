"""Domain-aware API errors represented as RFC 9457-style Problem Details."""

from collections.abc import Mapping
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    """Expected request failure carrying a stable machine-readable code."""

    def __init__(
        self,
        status_code: int,
        code: str,
        detail: str,
        *,
        extra: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.code = code
        self.detail = detail
        self.extra = dict(extra or {})


async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
    """Render predictable errors without leaking implementation details."""

    body: dict[str, Any] = {
        "type": f"urn:rwms:logistics:error:{exc.code.lower()}",
        "title": exc.code,
        "status": exc.status_code,
        "detail": exc.detail,
        "instance": str(request.url.path),
        "code": exc.code,
    }
    body.update(exc.extra)
    return JSONResponse(body, status_code=exc.status_code, media_type="application/problem+json")


def not_found(resource: str, resource_id: object) -> ApiError:
    """Create a consistent missing-resource response."""

    return ApiError(404, f"{resource.upper()}_NOT_FOUND", f"{resource} {resource_id} not found")
