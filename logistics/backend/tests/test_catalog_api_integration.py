"""Real PostGIS-backed HTTP tests for the catalog transport boundary."""

from __future__ import annotations

import os

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app
from app.services.planner_runtime import RuntimePlannerFacade

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is not configured"
    ),
]


def _geometry(west: float, south: float, east: float, north: float) -> dict[str, object]:
    """Build JSON-compatible rectangular zone geometry."""

    return {
        "type": "Polygon",
        "coordinates": [
            [[west, south], [east, south], [east, north], [west, north], [west, south]]
        ],
    }


@pytest.mark.asyncio
async def test_http_relation_uuid_bind_and_backend_request_classification() -> None:
    """HTTP creates real UUID relations and ignores no client-selected zone escape hatch."""

    transport = ASGITransport(app=create_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        health = await client.get("/api/health")
        assert health.status_code == 200
        assert health.json()["database"] == "ready"

        scenario_response = await client.post("/api/scenarios", json={"name": "API integration"})
        assert scenario_response.status_code == 201
        scenario_id = scenario_response.json()["id"]
        try:
            first_response = await client.post(
                f"/api/scenarios/{scenario_id}/zones",
                json={
                    "name": "Outer",
                    "code": "OUTER",
                    "route_group": "CUSTOM",
                    "geometry": _geometry(37.0, 55.0, 38.0, 56.0),
                    "priority": 1,
                },
            )
            second_response = await client.post(
                f"/api/scenarios/{scenario_id}/zones",
                json={
                    "name": "Inner",
                    "code": "INNER",
                    "route_group": "CITY",
                    "geometry": _geometry(37.5, 55.5, 37.7, 55.8),
                    "priority": 1,
                },
            )
            assert first_response.status_code == second_response.status_code == 201
            first_id = first_response.json()["id"]
            second_id = second_response.json()["id"]

            relation = await client.post(
                f"/api/scenarios/{scenario_id}/zone-relations",
                json={
                    "from_zone_id": first_id,
                    "to_zone_id": second_id,
                    "relation_type": "ADJACENT",
                },
            )
            assert relation.status_code == 201
            assert relation.json()["from_zone_id"] == first_id

            request_response = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "API request",
                    "latitude": 55.6,
                    "longitude": 37.6,
                    "quantity": 5,
                    "date_options": [{"date": "2026-08-25"}],
                },
            )
            assert request_response.status_code == 201
            body = request_response.json()
            assert body["zone_id"] == second_id
            assert [task["quantity"] for task in body["tasks"]] == [2, 2, 1]

            override = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "Forbidden override",
                    "latitude": 55.6,
                    "longitude": 37.6,
                    "quantity": 1,
                    "zone_id": first_id,
                },
            )
            assert override.status_code == 422
        finally:
            deleted = await client.delete(f"/api/scenarios/{scenario_id}")
            assert deleted.status_code == 204


@pytest.mark.asyncio
async def test_demo_plan_is_immediately_visible_editable_exportable_and_deletable() -> None:
    """A committed demo supports its canonical mixed cycle through the real runtime facade."""

    transport = ASGITransport(app=create_app(RuntimePlannerFacade("mock")))
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        created = await client.post(
            "/api/scenarios",
            json={"name": "Runtime integration", "timezone": "Europe/Moscow", "seed": 4242},
        )
        assert created.status_code == 201
        scenario_id = created.json()["id"]
        try:
            demo = await client.post(f"/api/scenarios/{scenario_id}/generate-demo")
            assert demo.status_code == 200
            planning_date = demo.json()["default_planning_date"]

            resources = {
                resource: await client.get(f"/api/scenarios/{scenario_id}/{resource}")
                for resource in (
                    "warehouses",
                    "zones",
                    "zone-relations",
                    "drivers",
                    "vehicles",
                    "shifts",
                    "requests",
                )
            }
            assert {name: len(response.json()) for name, response in resources.items()} == {
                "warehouses": 1,
                "zones": 4,
                "zone-relations": 4,
                "drivers": 3,
                "vehicles": 3,
                "shifts": 3,
                "requests": 6,
            }

            generated = await client.post(
                f"/api/scenarios/{scenario_id}/plans/generate",
                json={"date": planning_date, "seed": 4242, "show_trace": True},
            )
            assert generated.status_code == 202
            assert generated.json()["status"] == "COMPLETED"
            plan_id = generated.json()["plan_id"]
            plan_response = await client.get(f"/api/plans/{plan_id}")
            assert plan_response.status_code == 200
            plan = plan_response.json()
            mixed = next(
                cycle
                for cycle in plan["cycles"]
                if [stop["load_after"] for stop in cycle["stops"]] == [2, 1, 0, 1, 2, 0]
            )
            second_delivery = next(
                stop
                for stop in mixed["stops"]
                if stop["sequence"] == 2 and stop["stop_type"] == "DELIVERY"
            )
            manual = await client.post(
                f"/api/plans/{plan_id}/manual-change",
                json={
                    "expected_version": plan["version"],
                    "change_type": "REORDER_TASK",
                    "payload": {
                        "task_id": second_delivery["task_id"],
                        "source_cycle_id": mixed["id"],
                        "target_cycle_id": mixed["id"],
                        "target_sequence": 2,
                    },
                    "reason": "Integration no-op reorder",
                    "changed_by": "local-admin",
                },
            )
            assert manual.status_code == 200, manual.text
            assert manual.json()["version"] == plan["version"] + 1

            exported = await client.post(
                f"/api/scenarios/{scenario_id}/export", params={"include_plans": "true"}
            )
            assert exported.status_code == 200, exported.text
            assert len(exported.json()["plans"]) == 1
        finally:
            deleted = await client.delete(f"/api/scenarios/{scenario_id}")
            assert deleted.status_code == 204, deleted.text


@pytest.mark.asyncio
async def test_multi_day_demo_is_created_separately_with_parallel_resources() -> None:
    """The workload fixture must not reset another scenario and exposes three planning dates."""

    transport = ASGITransport(app=create_app(RuntimePlannerFacade("mock")))
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        original = await client.post("/api/scenarios", json={"name": "Keep this scenario"})
        assert original.status_code == 201
        created = await client.post("/api/scenarios/generate-multi-day-demo")
        assert created.status_code == 201, created.text
        scenario_id = created.json()["id"]
        try:
            assert scenario_id != original.json()["id"]
            resources = {
                resource: await client.get(f"/api/scenarios/{scenario_id}/{resource}")
                for resource in ("zones", "drivers", "vehicles", "shifts", "requests")
            }
            assert {name: len(response.json()) for name, response in resources.items()} == {
                "zones": 6,
                "drivers": 3,
                "vehicles": 3,
                "shifts": 9,
                "requests": 23,
            }
            request_count_by_date: dict[str, int] = {}
            for request in resources["requests"].json():
                for option in request["date_options"]:
                    option_date = option["date"]
                    request_count_by_date[option_date] = (
                        request_count_by_date.get(option_date, 0) + 1
                    )
            assert len(request_count_by_date) == 3
            assert sorted(request_count_by_date.values()) == [10, 10, 10]
            scenario_list = await client.get("/api/scenarios")
            assert any(item["id"] == original.json()["id"] for item in scenario_list.json())
        finally:
            for target_id in (scenario_id, original.json()["id"]):
                deleted = await client.delete(f"/api/scenarios/{target_id}")
                assert deleted.status_code == 204, deleted.text
