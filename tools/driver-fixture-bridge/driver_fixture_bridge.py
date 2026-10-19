#!/usr/bin/env python3
"""Publish generated route fixtures to DriverApp through task-board's private API.

This module is deliberately a development/test harness.  It does not import
logistics-service code, mutate its database, or introduce a second task-board
domain implementation.  It validates an exported planner snapshot, translates
each confirmed cycle into the existing ``RegisterExternalTaskRequest`` shape,
and calls only source-owned task-board endpoints.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Protocol, Sequence
from urllib import error, parse, request
from uuid import UUID, uuid5


GENERATOR_SOURCE_SYSTEM = "WAREHOUSE_WORKLOAD_GENERATOR"
LOGISTICS_DRIVER_SOURCE_TYPE = "LOGISTICS_DRIVER_TASK"
EXTERNAL_TASK_NAMESPACE = UUID("de87c866-6be1-5d60-9b1e-420c04fe7295")
SUPPORTED_ENVIRONMENTS = frozenset({"dev", "test"})
_CUSTOMER_STOP_TYPES = frozenset({"DELIVERY", "PICKUP"})
_STOP_LABELS = {
    "DEPOT_LOAD": "Загрузка на складе",
    "DELIVERY": "Доставка",
    "PICKUP": "Вывоз",
    "DEPOT_UNLOAD": "Выгрузка на складе",
    "DEPOT_RETURN": "Возврат на склад",
}


class BridgeError(RuntimeError):
    """Base error rendered by the fixture bridge without secret material."""


class FixtureValidationError(BridgeError):
    """Raised before any HTTP request when an exported fixture is unsafe."""


class RuntimeSafetyError(BridgeError):
    """Raised when the requested runtime is not unmistakably dev or test."""


class BridgeHttpError(BridgeError):
    """Sanitized HTTP error returned by the configured task-board target."""

    def __init__(self, status: int | None, message: str) -> None:
        self.status = status
        super().__init__(message)


class _NoRedirectHandler(request.HTTPRedirectHandler):
    """Prevent bearer credentials from following redirects to another target."""

    def redirect_request(
        self,
        req: request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> None:
        """Reject every redirect; the operator must name the final dev/test URL."""

        del req, fp, code, msg, headers, newurl
        return None


class JsonTransport(Protocol):
    """Minimal JSON transport used by the bridge and its unit tests."""

    def send(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
    ) -> Mapping[str, Any]:
        """Send one authenticated JSON request and return an object response."""

        ...


@dataclass(frozen=True)
class PreparedTask:
    """One deterministic canonical task-board registration for a route cycle."""

    external_task_id: UUID
    cycle_id: UUID
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class _RequestTask:
    """Generated request and cargo metadata referenced by one planner task."""

    task_id: UUID
    request_id: UUID
    request_type: str
    name: str
    address_label: str
    latitude: float
    longitude: float
    quantity: int
    service_minutes: int
    cargo_length_mm: int | None
    cargo_width_mm: int | None
    cargo_height_mm: int | None
    cargo_weight_kg: int | None


class UrllibJsonTransport:
    """Small bearer-auth JSON client that never logs or returns its token."""

    def __init__(self, base_url: str, token: str, timeout_seconds: float = 15.0) -> None:
        if not token.strip():
            raise RuntimeSafetyError("Не задан service token для task-board.")
        self._base_url = base_url.rstrip("/")
        self._token = token.strip()
        self._timeout_seconds = timeout_seconds
        self._opener = request.build_opener(request.ProxyHandler({}), _NoRedirectHandler())

    def send(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
    ) -> Mapping[str, Any]:
        """Call task-board and expose only a sanitized domain failure."""

        body = None
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self._token}",
        }
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
                "utf-8"
            )
            headers["Content-Type"] = "application/json"
        outbound = request.Request(
            f"{self._base_url}{path}",
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with self._opener.open(outbound, timeout=self._timeout_seconds) as response:
                raw = response.read()
        except error.HTTPError as exc:
            raw = exc.read()
            detail = _safe_remote_detail(raw)
            message = f"Task-board отклонил fixture-запрос (HTTP {exc.code})"
            if detail:
                message = f"{message}: {detail}"
            raise BridgeHttpError(exc.code, message) from exc
        except (error.URLError, TimeoutError) as exc:
            raise BridgeHttpError(
                None,
                "Task-board недоступен. Повтор команды безопасен благодаря "
                "стабильным externalTaskId.",
            ) from exc
        if not raw:
            return {}
        try:
            decoded = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise BridgeHttpError(None, "Task-board вернул некорректный JSON.") from exc
        if not isinstance(decoded, dict):
            raise BridgeHttpError(None, "Task-board вернул JSON неожиданного типа.")
        return decoded


def validate_runtime(environment: str, base_url: str, fixture_mode: bool) -> str:
    """Fail closed unless an explicit fixture opt-in targets a clear dev/test host."""

    if not fixture_mode:
        raise RuntimeSafetyError(
            "Fixture mode не подтверждён: передайте --fixture-mode явно."
        )
    normalized_environment = environment.strip().lower()
    if normalized_environment not in SUPPORTED_ENVIRONMENTS:
        raise RuntimeSafetyError("Разрешены только environment=dev или environment=test.")
    parsed = parse.urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise RuntimeSafetyError("Task-board URL должен быть абсолютным HTTP(S) URL.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise RuntimeSafetyError(
            "Task-board URL не должен содержать credentials, query или fragment."
        )
    if parsed.path not in {"", "/"}:
        raise RuntimeSafetyError("Task-board URL должен указывать на корень сервиса.")
    hostname = parsed.hostname.lower().rstrip(".")
    if not _is_explicit_non_production_host(hostname):
        raise RuntimeSafetyError(
            "URL не распознан как dev/test. Используйте localhost или hostname с отдельной "
            "меткой dev/test."
        )
    return base_url.rstrip("/")


def prepare_tasks(document: Mapping[str, Any]) -> tuple[PreparedTask, ...]:
    """Validate a confirmed generated plan snapshot and build canonical requests."""

    root = _mapping(document, "fixture")
    schema_version = _integer(root.get("schema_version"), "schema_version", minimum=1)
    if schema_version != 1:
        raise FixtureValidationError("Поддерживается только schema_version=1.")
    if root.get("source_system") != GENERATOR_SOURCE_SYSTEM:
        raise FixtureValidationError(
            f"source_system должен быть {GENERATOR_SOURCE_SYSTEM}."
        )
    queue_definition_id = _uuid(
        root.get("driver_queue_definition_id"), "driver_queue_definition_id"
    )
    plan = _mapping(root.get("plan"), "plan")
    if plan.get("status") != "CONFIRMED":
        raise FixtureValidationError("Разрешён только подтверждённый plan со status=CONFIRMED.")
    plan_id = _uuid(plan.get("id"), "plan.id")
    warehouse_id = _uuid(plan.get("warehouse_id"), "plan.warehouse_id")
    planning_date = _date(plan.get("date"), "plan.date")

    drivers = _driver_index(root.get("drivers"), warehouse_id)
    shifts = _shift_index(root.get("shifts"), warehouse_id, planning_date)
    tasks = _request_task_index(root.get("requests"))
    cycles = _sequence(plan.get("cycles"), "plan.cycles")
    if not cycles:
        raise FixtureValidationError("plan.cycles не должен быть пустым.")

    prepared: list[PreparedTask] = []
    seen_cycle_ids: set[UUID] = set()
    used_task_ids: set[UUID] = set()
    for cycle_position, raw_cycle in enumerate(cycles):
        cycle = _mapping(raw_cycle, f"plan.cycles[{cycle_position}]")
        cycle_id = _uuid(cycle.get("id"), f"plan.cycles[{cycle_position}].id")
        if cycle_id in seen_cycle_ids:
            raise FixtureValidationError(f"Повторяется cycle id {cycle_id}.")
        seen_cycle_ids.add(cycle_id)
        shift_id = _uuid(
            cycle.get("driver_shift_id"),
            f"plan.cycles[{cycle_position}].driver_shift_id",
        )
        shift = shifts.get(shift_id)
        if shift is None:
            raise FixtureValidationError(
                f"Для cycle {cycle_id} отсутствует активная shift {shift_id}."
            )
        driver_id = shift["driver_id"]
        driver = drivers.get(driver_id)
        if driver is None:
            raise FixtureValidationError(
                f"Для shift {shift_id} отсутствует активный водитель {driver_id}."
            )
        if driver["active"] is not True:
            raise FixtureValidationError(f"Driver {driver_id} неактивен.")
        if driver["rwms_assignment_mode"] != "ASSIGNED_DRIVER":
            raise FixtureValidationError(
                f"Driver {driver_id} не имеет точной DriverApp audience ASSIGNED_DRIVER."
            )
        if driver["external_worker_id"] is None:
            raise FixtureValidationError(
                f"Driver {driver_id} не содержит task-board external_worker_id."
            )
        external_worker_id = driver["external_worker_id"]
        worker_name = driver["name"]
        external_task_id = uuid5(
            EXTERNAL_TASK_NAMESPACE,
            f"{GENERATOR_SOURCE_SYSTEM}:{warehouse_id}:{plan_id}:{cycle_id}",
        )
        route, route_task_ids = _route_steps(
            cycle,
            cycle_position,
            queue_definition_id,
            external_task_id,
            tasks,
        )
        duplicate_tasks = used_task_ids.intersection(route_task_ids)
        if duplicate_tasks:
            rendered = ", ".join(str(item) for item in sorted(duplicate_tasks, key=str))
            raise FixtureValidationError(
                f"Planner task встречается более чем в одном cycle: {rendered}."
            )
        used_task_ids.update(route_task_ids)
        planned_start = _aware_datetime(
            cycle.get("planned_start"), f"plan.cycles[{cycle_position}].planned_start"
        )
        planned_finish = _aware_datetime(
            cycle.get("planned_finish"), f"plan.cycles[{cycle_position}].planned_finish"
        )
        if planned_finish < planned_start:
            raise FixtureValidationError(f"cycle {cycle_id}: planned_finish раньше planned_start.")
        duration_minutes = max(
            0, math.ceil((planned_finish - planned_start).total_seconds() / 60)
        )
        sequence_number = _integer(
            cycle.get("sequence"), f"plan.cycles[{cycle_position}].sequence", minimum=0
        )
        payload: Mapping[str, Any] = {
            "warehouseId": str(warehouse_id),
            "externalTaskId": str(external_task_id),
            "source": {
                "type": LOGISTICS_DRIVER_SOURCE_TYPE,
                "sourceId": str(external_task_id),
            },
            "title": f"Тестовый маршрут {sequence_number + 1} · {planning_date.isoformat()}",
            "unitNumber": None,
            "description": (
                "Fixture-маршрут из подтверждённого плана "
                f"{plan_id}; source={GENERATOR_SOURCE_SYSTEM}."
            ),
            "plannedDurationMinutes": duration_minutes,
            "deadlineAt": _iso_utc(planned_finish),
            "scheduledDate": planning_date.isoformat(),
            "lane": "SCHEDULED",
            "driverAudience": {
                "mode": "ASSIGNED_DRIVER",
                "workerId": str(external_worker_id),
                "workerName": worker_name,
            },
            "priority": 3,
            "route": route,
        }
        prepared.append(
            PreparedTask(
                external_task_id=external_task_id,
                cycle_id=cycle_id,
                payload=payload,
            )
        )
    return tuple(prepared)


def publish_tasks(
    tasks: Sequence[PreparedTask], transport: JsonTransport
) -> tuple[Mapping[str, Any], ...]:
    """Idempotently register prepared tasks through the canonical private endpoint."""

    results: list[Mapping[str, Any]] = []
    for item in tasks:
        response = transport.send(
            "POST", "/api/internal/task-board/v1/tasks", item.payload
        )
        returned_id = response.get("externalTaskId")
        if returned_id is not None and returned_id != str(item.external_task_id):
            raise BridgeHttpError(
                None,
                "Task-board вернул другой externalTaskId; дальнейшая публикация остановлена.",
            )
        results.append(response)
    return tuple(results)


def cleanup_tasks(
    tasks: Sequence[PreparedTask], transport: JsonTransport
) -> tuple[Mapping[str, Any], ...]:
    """Cancel only fixture tasks that have not started, using source-owned CAS."""

    results: list[Mapping[str, Any]] = []
    for item in tasks:
        identifier = str(item.external_task_id)
        path = f"/api/internal/task-board/v1/tasks/{identifier}"
        try:
            current = transport.send("GET", path)
        except BridgeHttpError as exc:
            if exc.status == 404:
                results.append({"externalTaskId": identifier, "outcome": "NOT_FOUND"})
                continue
            raise
        status = current.get("status")
        if status == "CANCELLED":
            results.append({"externalTaskId": identifier, "outcome": "ALREADY_CANCELLED"})
            continue
        if status == "DONE":
            results.append({"externalTaskId": identifier, "outcome": "COMPLETED"})
            continue
        task_version = _integer(
            current.get("taskVersion"),
            f"task-board registration {identifier}.taskVersion",
            minimum=0,
        )
        result = transport.send(
            "POST",
            f"{path}/cancel-if-pre-start",
            {
                "expectedTaskVersion": task_version,
                "reason": "Удаление тестового маршрута driver fixture bridge",
            },
        )
        results.append(result)
    return tuple(results)


def load_fixture(path: Path) -> Mapping[str, Any]:
    """Load one UTF-8 fixture JSON object."""

    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise FixtureValidationError(f"Не удалось прочитать fixture {path}.") from exc
    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise FixtureValidationError(
            f"Fixture содержит некорректный JSON (строка {exc.lineno})."
        ) from exc
    return _mapping(decoded, "fixture")


def main(argv: Sequence[str] | None = None) -> int:
    """Run the fixture-only publish or cleanup command."""

    parser = _argument_parser()
    args = parser.parse_args(argv)
    try:
        base_url = validate_runtime(args.environment, args.base_url, args.fixture_mode)
        prepared = prepare_tasks(load_fixture(args.fixture))
        if not args.execute:
            preview: dict[str, Any] = {
                "mode": "DRY_RUN",
                "command": args.command,
                "taskCount": len(prepared),
            }
            if args.command == "publish":
                preview["tasks"] = [item.payload for item in prepared]
            else:
                preview["operations"] = [
                    {
                        "externalTaskId": str(item.external_task_id),
                        "read": (
                            "/api/internal/task-board/v1/tasks/"
                            f"{item.external_task_id}"
                        ),
                        "cancelIfPreStart": (
                            "/api/internal/task-board/v1/tasks/"
                            f"{item.external_task_id}/cancel-if-pre-start"
                        ),
                        "expectedTaskVersion": "resolved by GET during --execute",
                    }
                    for item in prepared
                ]
            _print_json(preview)
            return 0
        token = os.environ.get(args.token_env, "")
        if not token:
            raise RuntimeSafetyError(
                f"Для --execute задайте token в переменной {args.token_env}."
            )
        transport = UrllibJsonTransport(base_url, token, args.timeout_seconds)
        if args.command == "publish":
            responses = publish_tasks(prepared, transport)
        else:
            responses = cleanup_tasks(prepared, transport)
        _print_json(
            {
                "mode": "EXECUTED",
                "command": args.command,
                "taskCount": len(prepared),
                "externalTaskIds": [str(item.external_task_id) for item in prepared],
                "results": list(responses),
            }
        )
        return 0
    except BridgeError as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


def _argument_parser() -> argparse.ArgumentParser:
    """Build a fail-closed command-line interface."""

    parser = argparse.ArgumentParser(
        description="Dev/test-only generated route bridge for DriverApp fixtures."
    )
    parser.add_argument("--environment", required=True, help="Только dev или test.")
    parser.add_argument("--base-url", required=True, help="Корень dev/test task-board.")
    parser.add_argument(
        "--fixture-mode",
        action="store_true",
        help="Явное подтверждение работы только с test/dev fixture.",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Выполнить HTTP-команды; без флага работает dry-run.",
    )
    parser.add_argument(
        "--token-env",
        default="DRIVER_FIXTURE_BRIDGE_TOKEN",
        help="Имя environment variable с service token; token не передаётся аргументом.",
    )
    parser.add_argument("--timeout-seconds", type=_positive_float, default=15.0)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("publish", "cleanup"):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--fixture", type=Path, required=True)
    return parser


def _driver_index(value: Any, warehouse_id: UUID) -> dict[UUID, Mapping[str, Any]]:
    """Validate active exact-audience drivers in the exported workspace."""

    result: dict[UUID, Mapping[str, Any]] = {}
    for index, raw_driver in enumerate(_sequence(value, "drivers")):
        driver = _mapping(raw_driver, f"drivers[{index}]")
        driver_id = _uuid(driver.get("id"), f"drivers[{index}].id")
        if driver_id in result:
            raise FixtureValidationError(f"Повторяется driver id {driver_id}.")
        if _uuid(driver.get("warehouse_id"), f"drivers[{index}].warehouse_id") != warehouse_id:
            raise FixtureValidationError(f"Driver {driver_id} относится к другому складу.")
        raw_external_worker_id = driver.get("external_worker_id")
        result[driver_id] = {
            "active": driver.get("active") is True,
            "rwms_assignment_mode": driver.get("rwms_assignment_mode"),
            "external_worker_id": (
                _uuid(raw_external_worker_id, f"drivers[{index}].external_worker_id")
                if raw_external_worker_id is not None
                else None
            ),
            "name": _non_empty_string(
                driver.get("name"), f"drivers[{index}].name", maximum_length=512
            ),
        }
    return result


def _shift_index(
    value: Any, warehouse_id: UUID, planning_date: date
) -> dict[UUID, Mapping[str, Any]]:
    """Validate shifts that are active for the exact exported plan date."""

    result: dict[UUID, Mapping[str, Any]] = {}
    for index, raw_shift in enumerate(_sequence(value, "shifts")):
        shift = _mapping(raw_shift, f"shifts[{index}]")
        shift_id = _uuid(shift.get("id"), f"shifts[{index}].id")
        if shift_id in result:
            raise FixtureValidationError(f"Повторяется shift id {shift_id}.")
        if _uuid(shift.get("warehouse_id"), f"shifts[{index}].warehouse_id") != warehouse_id:
            raise FixtureValidationError(f"Shift {shift_id} относится к другому складу.")
        if shift.get("active") is not True:
            continue
        date_from = _date(shift.get("date_from"), f"shifts[{index}].date_from")
        date_to = _date(shift.get("date_to"), f"shifts[{index}].date_to")
        if date_from <= planning_date <= date_to:
            result[shift_id] = {
                "driver_id": _uuid(
                    shift.get("driver_id"), f"shifts[{index}].driver_id"
                )
            }
    return result


def _request_task_index(value: Any) -> dict[UUID, _RequestTask]:
    """Reject mixed sources and index every generated planning task."""

    result: dict[UUID, _RequestTask] = {}
    requests = _sequence(value, "requests")
    if not requests:
        raise FixtureValidationError("requests не должен быть пустым.")
    for request_index, raw_request in enumerate(requests):
        request_item = _mapping(raw_request, f"requests[{request_index}]")
        if request_item.get("source_system") != GENERATOR_SOURCE_SYSTEM:
            raise FixtureValidationError(
                f"requests[{request_index}] имеет не-generated source_system; "
                "смешанный RWMS/manual fixture запрещён."
            )
        request_id = _uuid(request_item.get("id"), f"requests[{request_index}].id")
        request_type = _non_empty_string(
            request_item.get("type"), f"requests[{request_index}].type"
        )
        if request_type not in _CUSTOMER_STOP_TYPES:
            raise FixtureValidationError(
                f"requests[{request_index}].type должен быть DELIVERY или PICKUP."
            )
        name = _non_empty_string(
            request_item.get("name"),
            f"requests[{request_index}].name",
            maximum_length=900,
        )
        address_label = _non_empty_string(
            request_item.get("address_label"),
            f"requests[{request_index}].address_label",
            maximum_length=1500,
        )
        latitude = _coordinate(
            request_item.get("latitude"), f"requests[{request_index}].latitude", -90, 90
        )
        longitude = _coordinate(
            request_item.get("longitude"),
            f"requests[{request_index}].longitude",
            -180,
            180,
        )
        quantity = _integer(
            request_item.get("quantity"), f"requests[{request_index}].quantity", minimum=1
        )
        service_minutes = _integer(
            request_item.get("service_minutes"),
            f"requests[{request_index}].service_minutes",
            minimum=0,
        )
        dimensions = tuple(
            _optional_positive_integer(
                request_item.get(field), f"requests[{request_index}].{field}"
            )
            for field in (
                "cargo_length_mm",
                "cargo_width_mm",
                "cargo_height_mm",
                "cargo_weight_kg",
            )
        )
        if any(item is None for item in dimensions) and any(
            item is not None for item in dimensions
        ):
            raise FixtureValidationError(
                f"requests[{request_index}] содержит неполный cargo profile."
            )
        raw_tasks = _sequence(request_item.get("tasks"), f"requests[{request_index}].tasks")
        if not raw_tasks:
            raise FixtureValidationError(f"requests[{request_index}].tasks не должен быть пустым.")
        for task_index, raw_task in enumerate(raw_tasks):
            task = _mapping(
                raw_task, f"requests[{request_index}].tasks[{task_index}]"
            )
            task_id = _uuid(
                task.get("id"), f"requests[{request_index}].tasks[{task_index}].id"
            )
            if task_id in result:
                raise FixtureValidationError(f"Повторяется planner task id {task_id}.")
            task_request_id = _uuid(
                task.get("request_id"),
                f"requests[{request_index}].tasks[{task_index}].request_id",
            )
            if task_request_id != request_id:
                raise FixtureValidationError(
                    f"Planner task {task_id} ссылается на другой request."
                )
            task_quantity = _integer(
                task.get("quantity"),
                f"requests[{request_index}].tasks[{task_index}].quantity",
                minimum=1,
            )
            task_service_minutes = _integer(
                task.get("service_minutes", service_minutes),
                f"requests[{request_index}].tasks[{task_index}].service_minutes",
                minimum=0,
            )
            result[task_id] = _RequestTask(
                task_id=task_id,
                request_id=request_id,
                request_type=request_type,
                name=name,
                address_label=address_label,
                latitude=latitude,
                longitude=longitude,
                quantity=task_quantity,
                service_minutes=task_service_minutes,
                cargo_length_mm=dimensions[0],
                cargo_width_mm=dimensions[1],
                cargo_height_mm=dimensions[2],
                cargo_weight_kg=dimensions[3],
            )
        if quantity != sum(
            item.quantity for item in result.values() if item.request_id == request_id
        ):
            raise FixtureValidationError(
                f"Сумма planning task quantity не совпадает с request {request_id}."
            )
    return result


def _route_steps(
    cycle: Mapping[str, Any],
    cycle_position: int,
    queue_definition_id: UUID,
    external_task_id: UUID,
    tasks: Mapping[UUID, _RequestTask],
) -> tuple[list[Mapping[str, Any]], set[UUID]]:
    """Translate authoritative stop sequence into ordered task-board route steps."""

    raw_stops = _sequence(cycle.get("stops"), f"plan.cycles[{cycle_position}].stops")
    if not raw_stops:
        raise FixtureValidationError(f"plan.cycles[{cycle_position}].stops не должен быть пустым.")
    indexed: list[tuple[int, int, Mapping[str, Any]]] = []
    sequences: set[int] = set()
    for input_position, raw_stop in enumerate(raw_stops):
        stop = _mapping(
            raw_stop, f"plan.cycles[{cycle_position}].stops[{input_position}]"
        )
        sequence_number = _integer(
            stop.get("sequence"),
            f"plan.cycles[{cycle_position}].stops[{input_position}].sequence",
            minimum=0,
        )
        if sequence_number in sequences:
            raise FixtureValidationError(
                f"cycle {cycle.get('id')}: повторяется stop sequence {sequence_number}."
            )
        sequences.add(sequence_number)
        indexed.append((sequence_number, input_position, stop))
    indexed.sort(key=lambda item: item[0])

    route: list[Mapping[str, Any]] = []
    used_task_ids: set[UUID] = set()
    for route_index, (sequence_number, input_position, stop) in enumerate(indexed):
        field_prefix = f"plan.cycles[{cycle_position}].stops[{input_position}]"
        stop_type = _non_empty_string(stop.get("stop_type"), f"{field_prefix}.stop_type")
        if stop_type not in _STOP_LABELS:
            raise FixtureValidationError(
                f"{field_prefix}.stop_type не поддерживается: {stop_type}."
            )
        arrival = _aware_datetime(stop.get("planned_arrival"), f"{field_prefix}.planned_arrival")
        departure = _aware_datetime(
            stop.get("planned_departure"), f"{field_prefix}.planned_departure"
        )
        if departure < arrival:
            raise FixtureValidationError(f"{field_prefix}: departure раньше arrival.")
        service_seconds = _integer(
            stop.get("service_seconds"), f"{field_prefix}.service_seconds", minimum=0
        )
        latitude = _coordinate(stop.get("latitude"), f"{field_prefix}.latitude", -90, 90)
        longitude = _coordinate(
            stop.get("longitude"), f"{field_prefix}.longitude", -180, 180
        )
        raw_task_id = stop.get("task_id")
        task: _RequestTask | None = None
        if raw_task_id is not None:
            task_id = _uuid(raw_task_id, f"{field_prefix}.task_id")
            task = tasks.get(task_id)
            if task is None:
                raise FixtureValidationError(
                    f"Stop {sequence_number} ссылается на отсутствующий generated task {task_id}."
                )
            if task_id in used_task_ids:
                raise FixtureValidationError(
                    f"Planner task {task_id} повторяется внутри одного cycle."
                )
            used_task_ids.add(task_id)
            if stop_type != task.request_type:
                raise FixtureValidationError(
                    f"Stop {sequence_number}: stop_type не совпадает с request type."
                )
            if abs(latitude - task.latitude) > 1e-7 or abs(longitude - task.longitude) > 1e-7:
                raise FixtureValidationError(
                    f"Stop {sequence_number}: координаты не совпадают с generated request."
                )
        elif stop_type in _CUSTOMER_STOP_TYPES:
            raise FixtureValidationError(
                f"Customer stop {sequence_number} не содержит task_id."
            )

        route.append(
            _route_step_payload(
                queue_definition_id,
                external_task_id,
                route_index,
                sequence_number,
                stop_type,
                arrival,
                departure,
                service_seconds,
                latitude,
                longitude,
                task,
            )
        )
    if not used_task_ids:
        raise FixtureValidationError(
            f"cycle {cycle.get('id')} не содержит generated DELIVERY/PICKUP tasks."
        )
    return route, used_task_ids


def _route_step_payload(
    queue_definition_id: UUID,
    external_task_id: UUID,
    route_index: int,
    source_sequence: int,
    stop_type: str,
    arrival: datetime,
    departure: datetime,
    service_seconds: int,
    latitude: float,
    longitude: float,
    task: _RequestTask | None,
) -> Mapping[str, Any]:
    """Build one contract-shaped route step without inventing business state."""

    label = _STOP_LABELS[stop_type]
    address = task.address_label if task is not None else "Склад маршрута"
    details = [
        f"{source_sequence + 1}. {label}",
        f"Адрес: {address}",
        f"Координаты: {latitude:.7f}, {longitude:.7f}",
        f"План: {_iso_utc(arrival)} — {_iso_utc(departure)}",
    ]
    works: list[Mapping[str, Any]] = []
    if task is not None:
        details.append(f"Груз: {task.quantity} бытовок; заявка {task.name}")
        cargo = _cargo_description(task)
        if cargo:
            details.append(f"Параметры груза: {cargo}")
        works.append(
            {
                "id": str(
                    uuid5(
                        external_task_id,
                        f"route:{route_index}:task:{task.task_id}:work",
                    )
                ),
                "name": f"{label}: {task.name}",
                "quantity": task.quantity,
                "unit": "быт.",
                "durationMinutes": task.service_minutes,
                "comment": f"{task.address_label}; {cargo}" if cargo else task.address_label,
                "sourceMediaIds": [],
            }
        )
    task_text = "\n".join(details)
    if len(task_text) > 2000:
        raise FixtureValidationError(
            f"Route step {source_sequence}: taskText превышает 2000 символов."
        )
    planned_minutes = max(service_seconds, math.ceil((departure - arrival).total_seconds()))
    return {
        "queueDefinitionId": str(queue_definition_id),
        "taskText": task_text,
        "plannedDurationMinutes": math.ceil(planned_minutes / 60),
        "works": works,
        "materials": [],
        "comments": [],
        "sourceMedia": [],
    }


def _cargo_description(task: _RequestTask) -> str:
    """Render complete generated cargo dimensions for DriverApp presentation."""

    if task.cargo_length_mm is None:
        return ""
    return (
        f"{task.cargo_length_mm}×{task.cargo_width_mm}×{task.cargo_height_mm} мм, "
        f"{task.cargo_weight_kg} кг"
    )


def _is_explicit_non_production_host(hostname: str) -> bool:
    """Accept loopback or DNS names with an explicit dev/test label only."""

    if hostname in {"localhost", "127.0.0.1", "::1"} or hostname.startswith("127."):
        return True
    labels = hostname.split(".")
    return any(
        label in {"dev", "test", "localhost"}
        or label.startswith("dev-")
        or label.startswith("test-")
        or label.endswith("-dev")
        or label.endswith("-test")
        for label in labels
    )


def _safe_remote_detail(raw: bytes) -> str | None:
    """Extract a bounded Problem Details message without echoing arbitrary bodies."""

    if not raw:
        return None
    try:
        decoded = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(decoded, dict):
        return None
    detail = decoded.get("detail") or decoded.get("message") or decoded.get("title")
    if not isinstance(detail, str):
        return None
    normalized = " ".join(detail.split())
    return normalized[:500] or None


def _mapping(value: Any, field: str) -> Mapping[str, Any]:
    """Require a JSON object."""

    if not isinstance(value, dict):
        raise FixtureValidationError(f"{field} должен быть JSON object.")
    return value


def _sequence(value: Any, field: str) -> list[Any]:
    """Require a JSON array, excluding strings."""

    if not isinstance(value, list):
        raise FixtureValidationError(f"{field} должен быть JSON array.")
    return value


def _uuid(value: Any, field: str) -> UUID:
    """Require a canonical UUID value."""

    try:
        return UUID(str(value))
    except (TypeError, ValueError, AttributeError) as exc:
        raise FixtureValidationError(f"{field} должен быть UUID.") from exc


def _date(value: Any, field: str) -> date:
    """Require an ISO local date."""

    if not isinstance(value, str):
        raise FixtureValidationError(f"{field} должен быть ISO date.")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise FixtureValidationError(f"{field} должен быть ISO date.") from exc


def _aware_datetime(value: Any, field: str) -> datetime:
    """Require an ISO timestamp with an explicit UTC offset."""

    if not isinstance(value, str):
        raise FixtureValidationError(f"{field} должен быть ISO timestamp с timezone.")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise FixtureValidationError(f"{field} должен быть ISO timestamp с timezone.") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise FixtureValidationError(f"{field} потерял timezone.")
    return parsed


def _iso_utc(value: datetime) -> str:
    """Normalize equivalent timestamp spellings for deterministic replay."""

    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _integer(value: Any, field: str, minimum: int) -> int:
    """Require an integer at or above the declared bound."""

    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise FixtureValidationError(f"{field} должен быть integer >= {minimum}.")
    return value


def _optional_positive_integer(value: Any, field: str) -> int | None:
    """Require null or a strictly positive integer."""

    if value is None:
        return None
    return _integer(value, field, minimum=1)


def _coordinate(value: Any, field: str, minimum: float, maximum: float) -> float:
    """Require a finite coordinate inside an inclusive range."""

    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise FixtureValidationError(f"{field} должен быть числом.")
    numeric = float(value)
    if not math.isfinite(numeric) or not minimum <= numeric <= maximum:
        raise FixtureValidationError(f"{field} вне диапазона {minimum}..{maximum}.")
    return numeric


def _non_empty_string(
    value: Any, field: str, maximum_length: int | None = None
) -> str:
    """Require a non-blank string."""

    if not isinstance(value, str) or not value.strip():
        raise FixtureValidationError(f"{field} должен быть непустой строкой.")
    normalized = value.strip()
    if maximum_length is not None and len(normalized) > maximum_length:
        raise FixtureValidationError(
            f"{field} превышает {maximum_length} символов."
        )
    return normalized


def _print_json(value: Mapping[str, Any]) -> None:
    """Write stable UTF-8 JSON without secret-bearing runtime configuration."""

    print(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True))


def _positive_float(value: str) -> float:
    """Parse a strictly positive finite CLI timeout."""

    try:
        parsed = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("timeout должен быть числом > 0") from exc
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError("timeout должен быть числом > 0")
    return parsed


if __name__ == "__main__":
    raise SystemExit(main())
