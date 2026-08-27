"""Real PostGIS-backed HTTP tests for the catalog transport boundary."""

from __future__ import annotations

import os
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from httpx import ASGITransport, AsyncClient

from app.api.dependencies import get_road_snapper
from app.main import create_app
from app.routing import MockRoutingProvider
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


def _mock_snapper() -> MockRoutingProvider:
    """Return the deterministic road-snap boundary for HTTP integration tests."""

    return MockRoutingProvider()


def _cutout_payload(
    geometry: dict[str, object],
    *,
    code: str,
    name: str = "Внутренняя зона",
    route_group: str = "CITY",
    priority: int = 50,
    delivery_price: int = 0,
    pickup_price: int = 0,
    locked: bool = False,
) -> dict[str, object]:
    """Build the atomic source-cutout and inner-zone command body."""

    return {
        "geometry": geometry,
        "inner_zone": {
            "name": name,
            "code": code,
            "route_group": route_group,
            "priority": priority,
            "delivery_price": delivery_price,
            "pickup_price": pickup_price,
            "locked": locked,
        },
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
                    "delivery_price": 12_500,
                    "pickup_price": 9_000,
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
            assert first_response.json()["delivery_price"] == 12_500
            assert first_response.json()["pickup_price"] == 9_000
            first_id = first_response.json()["id"]
            second_id = second_response.json()["id"]

            repriced = await client.patch(
                f"/api/zones/{first_id}",
                json={"delivery_price": 13_000, "pickup_price": 9_500},
            )
            assert repriced.status_code == 200
            assert repriced.json()["delivery_price"] == 13_000
            assert repriced.json()["pickup_price"] == 9_500
            assert repriced.json()["version"] == 1

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
async def test_http_zone_cutout_versions_geometry_and_preserves_request_snapshot() -> None:
    """A PostGIS cutout atomically creates an inner zone and preserves old snapshots."""

    transport = ASGITransport(app=create_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        scenario_response = await client.post("/api/scenarios", json={"name": "Cutout API"})
        assert scenario_response.status_code == 201
        scenario_id = scenario_response.json()["id"]
        try:
            zone_response = await client.post(
                f"/api/scenarios/{scenario_id}/zones",
                json={
                    "name": "Большая зона",
                    "code": "BIG",
                    "route_group": "CUSTOM",
                    "geometry": _geometry(37.0, 55.0, 38.0, 56.0),
                },
            )
            assert zone_response.status_code == 201
            zone_id = zone_response.json()["id"]
            request_response = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "Точка будущего выреза",
                    "latitude": 55.5,
                    "longitude": 37.5,
                    "quantity": 1,
                    "date_options": [{"date": "2026-08-25"}],
                },
            )
            assert request_response.status_code == 201
            request_id = request_response.json()["id"]
            assert request_response.json()["zone_version"] == 1

            duplicate_code = await client.post(
                f"/api/zones/{zone_id}/cutouts",
                json=_cutout_payload(
                    _geometry(37.4, 55.4, 37.6, 55.6),
                    code="BIG",
                ),
            )
            assert duplicate_code.status_code == 409
            assert duplicate_code.json()["code"] == "ZONE_CODE_CONFLICT"
            unchanged_after_conflict = await client.get(f"/api/zones/{zone_id}")
            assert unchanged_after_conflict.json()["version"] == 1
            assert len(unchanged_after_conflict.json()["geometry"]["coordinates"][0]) == 1

            outside = await client.post(
                f"/api/zones/{zone_id}/cutouts",
                json=_cutout_payload(
                    _geometry(37.9, 55.9, 38.1, 56.1),
                    code="OUTSIDE",
                ),
            )
            assert outside.status_code == 422
            assert outside.json()["code"] == "ZONE_CUTOUT_OUTSIDE"
            unchanged_after_invalid = await client.get(f"/api/zones/{zone_id}")
            assert unchanged_after_invalid.json()["version"] == 1
            assert len(unchanged_after_invalid.json()["geometry"]["coordinates"][0]) == 1
            zones_after_failures = await client.get(f"/api/scenarios/{scenario_id}/zones")
            assert [zone["code"] for zone in zones_after_failures.json()] == ["BIG"]

            cutout = await client.post(
                f"/api/zones/{zone_id}/cutouts",
                json=_cutout_payload(
                    _geometry(37.4, 55.4, 37.6, 55.6),
                    code="INNER",
                    name="Центральная внутренняя зона",
                    route_group="CENTER",
                    priority=75,
                    delivery_price=15_000,
                    pickup_price=11_000,
                    locked=False,
                ),
            )
            assert cutout.status_code == 200, cutout.text
            body = cutout.json()
            source_zone = body["source_zone"]
            inner_zone = body["inner_zone"]
            assert source_zone["id"] == zone_id
            assert source_zone["version"] == 2
            assert source_zone["stale_request_count"] == 1
            assert source_zone["geometry"]["type"] == "MultiPolygon"
            assert len(source_zone["geometry"]["coordinates"][0]) == 2
            assert inner_zone["scenario_id"] == scenario_id
            assert inner_zone["code"] == "INNER"
            assert inner_zone["name"] == "Центральная внутренняя зона"
            assert inner_zone["route_group"] == "CENTER"
            assert inner_zone["priority"] == 75
            assert inner_zone["delivery_price"] == 15_000
            assert inner_zone["pickup_price"] == 11_000
            assert inner_zone["version"] == 1
            assert inner_zone["locked"] is False
            assert len(inner_zone["geometry"]["coordinates"][0]) == 1

            preserved = await client.get(f"/api/requests/{request_id}")
            assert preserved.status_code == 200
            assert preserved.json()["zone_id"] == zone_id
            assert preserved.json()["zone_version"] == 1
            assert preserved.json()["zone_is_stale"] is True

            reclassified = await client.post(f"/api/scenarios/{scenario_id}/reclassify-requests")
            assert reclassified.status_code == 200
            assert reclassified.json() == {"updated": 1, "outside_zones": 0, "unchanged": 0}
            moved = await client.get(f"/api/requests/{request_id}")
            assert moved.json()["zone_id"] == inner_zone["id"]
            assert moved.json()["zone_version"] == 1
            assert moved.json()["zone_is_stale"] is False
            refreshed_parent = await client.get(f"/api/zones/{zone_id}")
            assert refreshed_parent.json()["stale_request_count"] == 0

            locked = await client.post(
                f"/api/zones/{zone_id}/lock",
                json={"locked": True},
            )
            assert locked.status_code == 200
            locked_cutout = await client.post(
                f"/api/zones/{zone_id}/cutouts",
                json=_cutout_payload(
                    _geometry(37.1, 55.1, 37.2, 55.2),
                    code="LOCKED_INNER",
                ),
            )
            assert locked_cutout.status_code == 409
            assert locked_cutout.json()["code"] == "ZONE_LOCKED"
            parent_after_locked = await client.get(f"/api/zones/{zone_id}")
            assert parent_after_locked.json()["version"] == 2
            all_zones = await client.get(f"/api/scenarios/{scenario_id}/zones")
            assert {zone["code"] for zone in all_zones.json()} == {"BIG", "INNER"}
        finally:
            deleted = await client.delete(f"/api/scenarios/{scenario_id}")
            assert deleted.status_code == 204


@pytest.mark.asyncio
async def test_http_request_schedule_supports_options_agreement_and_unscheduling() -> None:
    """Scheduling is explicit, validated, reversible, and cleared by authoritative options."""

    transport = ASGITransport(app=create_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        scenario_response = await client.post("/api/scenarios", json={"name": "Schedule API"})
        assert scenario_response.status_code == 201
        scenario_id = scenario_response.json()["id"]
        try:
            created = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "Клиент: две даты",
                    "latitude": 55.75,
                    "longitude": 37.61,
                    "quantity": 1,
                    "date_options": [
                        {"date": "2026-08-25"},
                        {"date": "2026-08-26"},
                    ],
                },
            )
            assert created.status_code == 201
            request_id = created.json()["id"]
            assert created.json()["scheduled_date"] is None

            allowed = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": "2026-08-25"},
            )
            assert allowed.status_code == 200
            assert allowed.json()["scheduled_date"] == "2026-08-25"

            forbidden = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": "2026-08-27"},
            )
            assert forbidden.status_code == 422
            assert forbidden.json()["code"] == "REQUEST_DATE_NOT_ALLOWED"

            agreed = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": "2026-08-27", "add_if_missing": True},
            )
            assert agreed.status_code == 200
            assert agreed.json()["scheduled_date"] == "2026-08-27"
            custom_option = next(
                option for option in agreed.json()["date_options"] if option["date"] == "2026-08-27"
            )
            assert custom_option == {
                "id": custom_option["id"],
                "request_id": request_id,
                "date": "2026-08-27",
                "priority": 1000,
                "window_start": None,
                "window_end": None,
                "is_hard": False,
                "travel_zone_hours": None,
            }

            replayed_agreement = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": "2026-08-27", "add_if_missing": True},
            )
            assert replayed_agreement.status_code == 200
            assert replayed_agreement.json()["scheduled_date"] == "2026-08-27"
            assert (
                sum(
                    option["date"] == "2026-08-27"
                    for option in replayed_agreement.json()["date_options"]
                )
                == 1
            )

            options_replaced = await client.patch(
                f"/api/requests/{request_id}",
                json={"date_options": [{"date": "2026-08-25"}]},
            )
            assert options_replaced.status_code == 200, options_replaced.text
            assert options_replaced.json()["scheduled_date"] is None

            rescheduled = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": "2026-08-25"},
            )
            assert rescheduled.status_code == 200
            unscheduled = await client.post(
                f"/api/requests/{request_id}/schedule",
                json={"date": None},
            )
            assert unscheduled.status_code == 200
            assert unscheduled.json()["scheduled_date"] is None
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
            assert demo.json()["settings"]["max_detour_minutes"] == 60
            assert demo.json()["settings"]["max_detour_ratio"] == 3.0

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
            assert all(
                relation["max_detour_minutes"] == 60 and relation["max_detour_ratio"] == 3.0
                for relation in resources["zone-relations"].json()
            )

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
async def test_planning_details_gate_generation_and_drive_explicit_subtasks() -> None:
    """Planning waits for a real window/access decision and persists operator subtasks."""

    transport = ASGITransport(app=create_app(RuntimePlannerFacade("mock")))
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        created = await client.post(
            "/api/scenarios",
            json={"name": "Planning details", "timezone": "Europe/Moscow", "seed": 731},
        )
        assert created.status_code == 201
        scenario_id = created.json()["id"]
        try:
            demo = await client.post(f"/api/scenarios/{scenario_id}/generate-demo")
            assert demo.status_code == 200
            planning_date = demo.json()["default_planning_date"]
            request_response = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "Нужно согласование",
                    "address_label": "Тестовый адрес",
                    "latitude": 55.74,
                    "longitude": 37.52,
                    "quantity": 2,
                    "date_options": [{"date": planning_date, "priority": 100}],
                },
            )
            assert request_response.status_code == 201, request_response.text
            request_id = request_response.json()["id"]

            rejected_plan = await client.post(
                f"/api/scenarios/{scenario_id}/plans/generate",
                json={"date": planning_date, "seed": 731},
            )
            assert rejected_plan.status_code == 422
            assert rejected_plan.json()["code"] == "PLANNING_INPUT_INCOMPLETE"
            assert rejected_plan.json()["requests"] == [
                {
                    "request_id": request_id,
                    "name": "Нужно согласование",
                    "missing_fields": ["time_window", "trailer_access_allowed"],
                }
            ]

            wrong_date = await client.post(
                f"/api/requests/{request_id}/planning-details",
                json={
                    "date": "2099-01-01",
                    "window_start": "09:00:00",
                    "window_end": "15:00:00",
                    "is_hard": True,
                    "trailer_access_allowed": False,
                    "include_driver_passport_in_notification": False,
                    "contact_name": "Контакт",
                    "contact_phone": "+7 900 100-20-30",
                },
            )
            assert wrong_date.status_code == 422
            assert wrong_date.json()["code"] == "REQUEST_DATE_NOT_ALLOWED"
            unchanged = await client.get(f"/api/requests/{request_id}")
            assert unchanged.json()["scheduled_date"] is None
            assert unchanged.json()["trailer_access_allowed"] is None

            planned = await client.post(
                f"/api/requests/{request_id}/planning-details",
                json={
                    "date": planning_date,
                    "window_start": "09:00:00",
                    "window_end": "15:00:00",
                    "is_hard": True,
                    "trailer_access_allowed": False,
                    "include_driver_passport_in_notification": False,
                    "contact_name": "Контакт",
                    "contact_phone": "+7 900 100-20-30",
                },
            )
            assert planned.status_code == 200, planned.text
            assert planned.json()["scheduled_date"] == planning_date
            assert planned.json()["trailer_access_allowed"] is False
            assert [task["quantity"] for task in planned.json()["tasks"]] == [1, 1]
            option = next(
                item for item in planned.json()["date_options"] if item["date"] == planning_date
            )
            assert (option["window_start"], option["window_end"], option["is_hard"]) == (
                "09:00:00",
                "15:00:00",
                True,
            )

            forbidden_split = await client.post(
                f"/api/requests/{request_id}/split",
                json={"part_quantities": [2]},
            )
            assert forbidden_split.status_code == 422
            assert forbidden_split.json()["code"] == "TRAILER_ACCESS_NOT_ALLOWED"
            explicit_split = await client.post(
                f"/api/requests/{request_id}/split",
                json={"part_quantities": [1, 1]},
            )
            assert explicit_split.status_code == 200
            assert [task["quantity"] for task in explicit_split.json()["tasks"]] == [1, 1]

            generated = await client.post(
                f"/api/scenarios/{scenario_id}/plans/generate",
                json={"date": planning_date, "seed": 731},
            )
            assert generated.status_code == 202, generated.text
            assert generated.json()["status"] == "COMPLETED"
            saved_plan = await client.get(f"/api/plans/{generated.json()['plan_id']}")
            assigned_task_ids = {
                stop["task_id"]
                for cycle in saved_plan.json()["cycles"]
                for stop in cycle["stops"]
                if stop["task_id"] is not None
            }
            current_requests = await client.get(f"/api/scenarios/{scenario_id}/requests")
            referenced = next(
                request
                for request in current_requests.json()
                if request["trailer_access_allowed"] is True
                and any(task["id"] in assigned_task_ids for task in request["tasks"])
            )
            referenced_option = next(
                item for item in referenced["date_options"] if item["date"] == planning_date
            )
            blocked_change = await client.post(
                f"/api/requests/{referenced['id']}/planning-details",
                json={
                    "date": planning_date,
                    "window_start": referenced_option["window_start"],
                    "window_end": referenced_option["window_end"],
                    "is_hard": referenced_option["is_hard"],
                    "trailer_access_allowed": False,
                    "include_driver_passport_in_notification": False,
                    "contact_name": referenced["contact_name"],
                    "contact_phone": referenced["contact_phone"],
                },
            )
            assert blocked_change.status_code == 409
            assert blocked_change.json()["code"] == "REQUEST_TASKS_ALREADY_PLANNED"
            unchanged_referenced = await client.get(f"/api/requests/{referenced['id']}")
            assert unchanged_referenced.json()["trailer_access_allowed"] is True
        finally:
            deleted = await client.delete(f"/api/scenarios/{scenario_id}")
            assert deleted.status_code == 204, deleted.text


@pytest.mark.asyncio
async def test_confirmation_logs_messages_once_and_requires_requested_passports() -> None:
    """Confirmation simulates one aggregate contact message and fences passport data."""

    transport = ASGITransport(app=create_app(RuntimePlannerFacade("mock")))
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        created = await client.post(
            "/api/scenarios",
            json={"name": "Notification log", "timezone": "Europe/Moscow", "seed": 902},
        )
        assert created.status_code == 201
        scenario_id = created.json()["id"]
        try:
            demo = await client.post(f"/api/scenarios/{scenario_id}/generate-demo")
            planning_date = demo.json()["default_planning_date"]
            requests = await client.get(f"/api/scenarios/{scenario_id}/requests")
            assert requests.status_code == 200
            split_source = next(request for request in requests.json() if request["quantity"] == 2)
            split_response = await client.post(
                f"/api/requests/{split_source['id']}/split",
                json={"part_quantities": [1, 1]},
            )
            assert split_response.status_code == 200, split_response.text
            split_task_ids = {task["id"] for task in split_response.json()["tasks"]}
            for request in requests.json():
                updated = await client.patch(
                    f"/api/requests/{request['id']}",
                    json={"include_driver_passport_in_notification": True},
                )
                assert updated.status_code == 200, updated.text

            generated = await client.post(
                f"/api/scenarios/{scenario_id}/plans/generate",
                json={"date": planning_date, "seed": 902},
            )
            assert generated.status_code == 202, generated.text
            plan_id = generated.json()["plan_id"]
            plan = await client.get(f"/api/plans/{plan_id}")
            assigned_task_ids = {
                stop["task_id"]
                for cycle in plan.json()["cycles"]
                for stop in cycle["stops"]
                if stop["task_id"] is not None
            }
            assert split_task_ids <= assigned_task_ids
            confirmed = await client.post(
                f"/api/plans/{plan_id}/confirm",
                json={
                    "expected_version": plan.json()["version"],
                    "accept_warnings": True,
                },
            )
            assert confirmed.status_code == 200, confirmed.text
            body = confirmed.json()
            assert body["status"] == "CONFIRMED"
            assert body["notification_logs"]
            split_notifications = [
                item
                for item in body["notification_logs"]
                if item["request_id"] == split_source["id"]
            ]
            assert len(split_notifications) == 1
            assert "2 БК" in split_notifications[0]["message"]
            assert all(
                item["status"] == "SIMULATED_DELIVERED"
                and item["includes_driver_passport"]
                and "водитель" in item["message"]
                and "госномер" in item["message"]
                and "Паспортные данные" in item["message"]
                for item in body["notification_logs"]
            )
            current_requests = await client.get(f"/api/scenarios/{scenario_id}/requests")
            assert current_requests.status_code == 200
            request_by_task_id = {
                task["id"]: request["id"]
                for request in current_requests.json()
                for task in request["tasks"]
            }
            for notification in body["notification_logs"]:
                local_arrivals = {
                    datetime.fromisoformat(stop["planned_arrival"])
                    .astimezone(ZoneInfo("Europe/Moscow"))
                    .strftime("%H:%M")
                    for cycle in body["cycles"]
                    for stop in cycle["stops"]
                    if stop["task_id"] is not None
                    and request_by_task_id[stop["task_id"]] == notification["request_id"]
                }
                assert local_arrivals
                assert all(
                    f"прибытие ориентировочно {arrival}" in notification["message"]
                    for arrival in local_arrivals
                )
            notification_ids = [item["id"] for item in body["notification_logs"]]

            repeated = await client.post(
                f"/api/plans/{plan_id}/confirm",
                json={
                    "expected_version": body["version"],
                    "accept_warnings": True,
                },
            )
            assert repeated.status_code == 200, repeated.text
            assert repeated.json()["version"] == body["version"]
            assert [item["id"] for item in repeated.json()["notification_logs"]] == (
                notification_ids
            )

            clone = await client.post(
                f"/api/plans/{plan_id}/clone",
                json={"name": "Без паспортов"},
            )
            assert clone.status_code == 201, clone.text
            drivers = await client.get(f"/api/scenarios/{scenario_id}/drivers")
            for driver in drivers.json():
                cleared = await client.patch(
                    f"/api/drivers/{driver['id']}",
                    json={"passport_details": ""},
                )
                assert cleared.status_code == 200, cleared.text
            passport_rejected = await client.post(
                f"/api/plans/{clone.json()['id']}/confirm",
                json={
                    "expected_version": clone.json()["version"],
                    "accept_warnings": True,
                },
            )
            assert passport_rejected.status_code == 422
            assert passport_rejected.json()["code"] == "DRIVER_PASSPORT_REQUIRED"
            unchanged_clone = await client.get(f"/api/plans/{clone.json()['id']}")
            assert unchanged_clone.json()["status"] == "DRAFT"
            assert unchanged_clone.json()["notification_logs"] == []
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


@pytest.mark.asyncio
async def test_http_workload_generator_can_regenerate_one_day() -> None:
    """The public command defaults to one day and replaces its prior generated workload."""

    application = create_app()
    application.dependency_overrides[get_road_snapper] = _mock_snapper
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        scenario = await client.post("/api/scenarios", json={"name": "Generator API"})
        assert scenario.status_code == 201
        scenario_id = scenario.json()["id"]
        try:
            zone = await client.post(
                f"/api/scenarios/{scenario_id}/zones",
                json={
                    "name": "Generator zone",
                    "code": "GEN",
                    "route_group": "CUSTOM",
                    "geometry": _geometry(37.0, 55.0, 38.0, 56.0),
                },
            )
            assert zone.status_code == 201
            generated = await client.post(
                f"/api/scenarios/{scenario_id}/generate-workload",
                json={
                    "start_date": "2026-08-24",
                    "deliveries_per_day": 1,
                    "pickups_per_day": 1,
                    "seed": 24,
                },
            )
            assert generated.status_code == 201, generated.text
            assert generated.json()["created_requests"] == 2
            assert generated.json()["replaced_requests"] == 0
            assert generated.json()["deleted_plans"] == 0

            regenerated = await client.post(
                f"/api/scenarios/{scenario_id}/generate-workload",
                json={
                    "start_date": "2026-08-24",
                    "days": 1,
                    "deliveries_per_day": 2,
                    "pickups_per_day": 1,
                    "alternative_dates_count": 0,
                    "seed": 25,
                },
            )
            assert regenerated.status_code == 201, regenerated.text
            assert regenerated.json()["created_requests"] == 3
            assert regenerated.json()["created_deliveries"] == 2
            assert regenerated.json()["created_pickups"] == 1
            assert regenerated.json()["replaced_requests"] == 2
            assert regenerated.json()["deleted_plans"] == 0

            requests = await client.get(f"/api/scenarios/{scenario_id}/requests")
            assert requests.status_code == 200
            assert len(requests.json()) == 3
            assert all(request["zone_id"] == zone.json()["id"] for request in requests.json())
            assert all(len(request["date_options"]) == 1 for request in requests.json())
            assert all(request["scheduled_date"] == "2026-08-24" for request in requests.json())

            next_day = await client.post(
                f"/api/scenarios/{scenario_id}/generate-workload",
                json={
                    "start_date": "2026-08-25",
                    "deliveries_per_day": 1,
                    "pickups_per_day": 1,
                    "seed": 26,
                },
            )
            assert next_day.status_code == 201, next_day.text
            assert next_day.json()["replaced_requests"] == 0

            deleted = await client.delete(
                f"/api/scenarios/{scenario_id}/generated-workload",
                params={"date": "2026-08-24"},
            )
            assert deleted.status_code == 200, deleted.text
            assert deleted.json() == {
                "scenario_id": scenario_id,
                "date": "2026-08-24",
                "deleted_requests": 3,
                "deleted_plans": 0,
            }
            repeated_delete = await client.delete(
                f"/api/scenarios/{scenario_id}/generated-workload",
                params={"date": "2026-08-24"},
            )
            assert repeated_delete.status_code == 200, repeated_delete.text
            assert repeated_delete.json()["deleted_requests"] == 0
            assert repeated_delete.json()["deleted_plans"] == 0
            remaining = await client.get(f"/api/scenarios/{scenario_id}/requests")
            assert len(remaining.json()) == 2
            assert all(request["scheduled_date"] == "2026-08-25" for request in remaining.json())
        finally:
            deleted = await client.delete(f"/api/scenarios/{scenario_id}")
            assert deleted.status_code == 204, deleted.text
