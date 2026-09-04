"""Focused contract and safety tests for the DriverApp fixture bridge."""

from __future__ import annotations

import copy
import io
import importlib.util
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from typing import Any, Mapping
from uuid import UUID


_MODULE_PATH = Path(__file__).with_name("driver_fixture_bridge.py")
_SPEC = importlib.util.spec_from_file_location("driver_fixture_bridge", _MODULE_PATH)
assert _SPEC is not None and _SPEC.loader is not None
bridge = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = bridge
_SPEC.loader.exec_module(bridge)


class RecordingTransport:
    """Deterministic in-memory transport for request-shape assertions."""

    def __init__(self, responses: list[Mapping[str, Any]] | None = None) -> None:
        self.calls: list[tuple[str, str, Mapping[str, Any] | None]] = []
        self.responses = list(responses or [])

    def send(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
    ) -> Mapping[str, Any]:
        """Record one call and return the next configured response."""

        self.calls.append((method, path, payload))
        return self.responses.pop(0) if self.responses else {}


class DriverFixtureBridgeTest(unittest.TestCase):
    """Safety, determinism, route ordering, and cleanup contract tests."""

    def test_rejects_production_environment_and_url(self) -> None:
        with self.assertRaises(bridge.RuntimeSafetyError):
            bridge.validate_runtime("production", "https://task-board.prod.example", True)
        with self.assertRaises(bridge.RuntimeSafetyError):
            bridge.validate_runtime("dev", "https://task-board.prod.example", True)
        with self.assertRaises(bridge.RuntimeSafetyError):
            bridge.validate_runtime("test", "http://localhost:8080", False)
        self.assertEqual(
            "https://task-board.dev.example",
            bridge.validate_runtime("dev", "https://task-board.dev.example/", True),
        )

    def test_rejects_any_non_generated_request_before_http(self) -> None:
        fixture = _fixture()
        fixture["requests"].append(
            {
                **copy.deepcopy(fixture["requests"][0]),
                "id": "30000000-0000-0000-0000-000000000099",
                "source_system": "RWMS",
                "tasks": [
                    {
                        **copy.deepcopy(fixture["requests"][0]["tasks"][0]),
                        "id": "40000000-0000-0000-0000-000000000099",
                        "request_id": "30000000-0000-0000-0000-000000000099",
                    }
                ],
            }
        )
        transport = RecordingTransport()
        with self.assertRaisesRegex(bridge.FixtureValidationError, "смешанный"):
            tasks = bridge.prepare_tasks(fixture)
            bridge.publish_tasks(tasks, transport)
        self.assertEqual([], transport.calls)

    def test_deterministic_replay_builds_identical_external_id_and_payload(self) -> None:
        first = bridge.prepare_tasks(_fixture())
        second = bridge.prepare_tasks(copy.deepcopy(_fixture()))
        self.assertEqual(first, second)
        self.assertEqual(
            UUID("4cc4975d-42cc-5850-a5b4-a45c96f929bc"),
            first[0].external_task_id,
        )
        transport = RecordingTransport(
            [
                {"externalTaskId": str(first[0].external_task_id)},
                {"externalTaskId": str(second[0].external_task_id)},
            ]
        )
        bridge.publish_tasks(first, transport)
        bridge.publish_tasks(second, transport)
        self.assertEqual(transport.calls[0][2], transport.calls[1][2])

    def test_route_is_sorted_by_authoritative_stop_sequence(self) -> None:
        fixture = _fixture()
        fixture["plan"]["cycles"][0]["stops"] = list(
            reversed(fixture["plan"]["cycles"][0]["stops"])
        )
        prepared = bridge.prepare_tasks(fixture)[0]
        route = prepared.payload["route"]
        self.assertEqual(3, len(route))
        self.assertTrue(route[0]["taskText"].startswith("1. Загрузка на складе"))
        self.assertTrue(route[1]["taskText"].startswith("2. Доставка"))
        self.assertTrue(route[2]["taskText"].startswith("3. Возврат на склад"))
        self.assertIn("Тестовая улица, 7", route[1]["taskText"])
        self.assertIn("6000×2400×2500 мм, 3100 кг", route[1]["taskText"])

    def test_registration_payload_matches_existing_task_board_contract_shape(self) -> None:
        payload = bridge.prepare_tasks(_fixture())[0].payload
        self.assertEqual(
            {
                "warehouseId",
                "externalTaskId",
                "source",
                "title",
                "unitNumber",
                "description",
                "plannedDurationMinutes",
                "deadlineAt",
                "scheduledDate",
                "lane",
                "driverAudience",
                "priority",
                "route",
            },
            set(payload),
        )
        self.assertEqual(
            {"type", "sourceId"},
            set(payload["source"]),
        )
        self.assertEqual("LOGISTICS_DRIVER_TASK", payload["source"]["type"])
        self.assertEqual("ASSIGNED_DRIVER", payload["driverAudience"]["mode"])
        self.assertEqual("SCHEDULED", payload["lane"])
        for step in payload["route"]:
            self.assertEqual(
                {
                    "queueDefinitionId",
                    "taskText",
                    "plannedDurationMinutes",
                    "works",
                    "materials",
                    "comments",
                    "sourceMedia",
                },
                set(step),
            )
        self.assertEqual(
            {
                "id",
                "name",
                "quantity",
                "unit",
                "durationMinutes",
                "comment",
                "sourceMediaIds",
            },
            set(payload["route"][1]["works"][0]),
        )

    def test_cleanup_reads_version_and_uses_pre_start_source_owned_cancel(self) -> None:
        task = bridge.prepare_tasks(_fixture())[0]
        transport = RecordingTransport(
            [
                {"taskVersion": 7, "status": "ACTIVE"},
                {
                    "externalTaskId": str(task.external_task_id),
                    "taskVersion": 8,
                    "status": "CANCELLED",
                    "outcome": "CANCELLED",
                    "cancelledAt": "2026-08-31T09:00:00Z",
                },
            ]
        )
        result = bridge.cleanup_tasks((task,), transport)
        base_path = f"/api/internal/task-board/v1/tasks/{task.external_task_id}"
        self.assertEqual(("GET", base_path, None), transport.calls[0])
        self.assertEqual("POST", transport.calls[1][0])
        self.assertEqual(f"{base_path}/cancel-if-pre-start", transport.calls[1][1])
        self.assertEqual(
            {
                "expectedTaskVersion": 7,
                "reason": "Удаление тестового маршрута driver fixture bridge",
            },
            transport.calls[1][2],
        )
        self.assertEqual("CANCELLED", result[0]["outcome"])

    def test_cli_is_dry_run_without_execute_and_does_not_require_token(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture_path = Path(temporary_directory) / "fixture.json"
            fixture_path.write_text(json.dumps(_fixture()), encoding="utf-8")
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = bridge.main(
                    [
                        "--environment",
                        "test",
                        "--base-url",
                        "http://localhost:8080",
                        "--fixture-mode",
                        "publish",
                        "--fixture",
                        str(fixture_path),
                    ]
                )
        self.assertEqual(0, exit_code)
        self.assertEqual("DRY_RUN", json.loads(output.getvalue())["mode"])


def _fixture() -> dict[str, Any]:
    """Return an actual-shape confirmed plan plus its workspace source rows."""

    warehouse_id = "10000000-0000-0000-0000-000000000001"
    driver_id = "50000000-0000-0000-0000-000000000001"
    shift_id = "60000000-0000-0000-0000-000000000001"
    task_id = "40000000-0000-0000-0000-000000000001"
    request_id = "30000000-0000-0000-0000-000000000001"
    return {
        "schema_version": 1,
        "source_system": "WAREHOUSE_WORKLOAD_GENERATOR",
        "driver_queue_definition_id": "70000000-0000-0000-0000-000000000001",
        "plan": {
            "id": "20000000-0000-0000-0000-000000000001",
            "warehouse_id": warehouse_id,
            "date": "2026-08-31",
            "status": "CONFIRMED",
            "cycles": [
                {
                    "id": "80000000-0000-0000-0000-000000000001",
                    "driver_shift_id": shift_id,
                    "sequence": 0,
                    "planned_start": "2026-08-31T06:00:00Z",
                    "planned_finish": "2026-08-31T09:30:00Z",
                    "stops": [
                        {
                            "sequence": 0,
                            "task_id": None,
                            "stop_type": "DEPOT_LOAD",
                            "planned_arrival": "2026-08-31T06:00:00Z",
                            "planned_departure": "2026-08-31T06:30:00Z",
                            "service_seconds": 1800,
                            "latitude": 59.9343,
                            "longitude": 30.3351,
                        },
                        {
                            "sequence": 1,
                            "task_id": task_id,
                            "stop_type": "DELIVERY",
                            "planned_arrival": "2026-08-31T07:40:00Z",
                            "planned_departure": "2026-08-31T08:10:00Z",
                            "service_seconds": 1800,
                            "latitude": 59.8,
                            "longitude": 30.1,
                        },
                        {
                            "sequence": 2,
                            "task_id": None,
                            "stop_type": "DEPOT_RETURN",
                            "planned_arrival": "2026-08-31T09:30:00Z",
                            "planned_departure": "2026-08-31T09:30:00Z",
                            "service_seconds": 0,
                            "latitude": 59.9343,
                            "longitude": 30.3351,
                        },
                    ],
                }
            ],
        },
        "drivers": [
            {
                "id": driver_id,
                "warehouse_id": warehouse_id,
                "name": "Тестовый водитель",
                "active": True,
                "rwms_assignment_mode": "ASSIGNED_DRIVER",
                "external_worker_id": "90000000-0000-0000-0000-000000000001",
            }
        ],
        "shifts": [
            {
                "id": shift_id,
                "warehouse_id": warehouse_id,
                "driver_id": driver_id,
                "date_from": "2026-08-31",
                "date_to": "2026-08-31",
                "active": True,
            }
        ],
        "requests": [
            {
                "id": request_id,
                "warehouse_id": warehouse_id,
                "source_system": "WAREHOUSE_WORKLOAD_GENERATOR",
                "type": "DELIVERY",
                "name": "№1",
                "address_label": "Тестовая улица, 7",
                "latitude": 59.8,
                "longitude": 30.1,
                "quantity": 2,
                "service_minutes": 30,
                "cargo_length_mm": 6000,
                "cargo_width_mm": 2400,
                "cargo_height_mm": 2500,
                "cargo_weight_kg": 3100,
                "tasks": [
                    {
                        "id": task_id,
                        "request_id": request_id,
                        "quantity": 2,
                        "service_minutes": 30,
                    }
                ],
            }
        ],
    }


if __name__ == "__main__":
    unittest.main()
