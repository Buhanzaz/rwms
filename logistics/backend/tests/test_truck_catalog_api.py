"""PostGIS-backed HTTP tests for truck, trailer, cargo, and load-profile CRUD."""

from __future__ import annotations

import os

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is not configured"
    ),
]


def _trailer_payload(registration_number: str = "PR1000") -> dict[str, object]:
    """Build a complete API trailer payload with explicit physical limits."""

    return {
        "name": "Низкорамный прицеп",
        "registration_number": registration_number,
        "active": True,
        "tare_weight_kg": 3_000,
        "max_gross_weight_kg": 10_000,
        "length_mm": 8_000,
        "width_mm": 2_500,
        "height_mm": 1_800,
        "platform_length_mm": 6_000,
        "platform_width_mm": 2_500,
        "platform_height_from_ground_mm": 900,
        "max_platform_payload_kg": 6_000,
        "payload_capacity_kg": 7_000,
        "axle_count": 2,
        "max_axle_load_kg": 10_000,
        "max_cargo_length_mm": 7_000,
        "max_cargo_width_mm": 2_600,
        "max_cargo_height_mm": 3_000,
        "max_cargo_weight_kg": 6_000,
        "notes": "Испытательный прицеп",
    }


def _vehicle_payload(trailer_id: str) -> dict[str, object]:
    """Build a complete truck payload referencing one scenario-owned trailer."""

    return {
        "name": "Манипулятор 1",
        "registration_number": "A123BC77",
        "capacity": 2,
        "active": True,
        "average_speed_city": 30,
        "average_speed_region": 60,
        "vehicle_type": "FLATBED_CRANE",
        "manufacturer": "MAN",
        "model": "TGS",
        "is_hgv": True,
        "tare_weight_kg": 12_000,
        "max_gross_weight_kg": 22_000,
        "length_mm": 9_200,
        "width_mm": 2_500,
        "height_mm": 3_200,
        "axle_count": 3,
        "max_axle_load_kg": 10_000,
        "payload_capacity_kg": 8_000,
        "platform_length_mm": 6_000,
        "platform_width_mm": 2_500,
        "platform_height_from_ground_mm": 1_300,
        "max_platform_payload_kg": 7_000,
        "max_cargo_length_mm": 7_000,
        "max_cargo_width_mm": 2_600,
        "max_cargo_height_mm": 3_000,
        "max_cargo_weight_kg": 6_000,
        "can_use_trailer": True,
        "default_trailer_id": trailer_id,
        "combined_length_with_trailer_mm": 18_500,
        "coupling_length_mm": 1_300,
        "height_safety_margin_mm": 100,
        "width_safety_margin_mm": 50,
        "weight_safety_margin_kg": 200,
        "notes": "Полный профиль",
    }


@pytest.mark.asyncio
async def test_trailer_vehicle_load_profile_and_cargo_crud() -> None:
    """Truck catalog CRUD preserves ownership and complete cargo data in split tasks."""

    transport = ASGITransport(app=create_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        first_scenario = await client.post("/api/scenarios", json={"name": "Truck routing"})
        second_scenario = await client.post("/api/scenarios", json={"name": "Other scenario"})
        assert first_scenario.status_code == second_scenario.status_code == 201
        scenario_id = first_scenario.json()["id"]
        other_scenario_id = second_scenario.json()["id"]
        try:
            trailer = await client.post(
                f"/api/scenarios/{scenario_id}/trailers", json=_trailer_payload()
            )
            assert trailer.status_code == 201, trailer.text
            trailer_id = trailer.json()["id"]
            assert trailer.json()["platform_height_from_ground_mm"] == 900

            listed = await client.get(f"/api/scenarios/{scenario_id}/trailers")
            assert listed.status_code == 200
            assert [item["id"] for item in listed.json()] == [trailer_id]

            vehicle = await client.post(
                f"/api/scenarios/{scenario_id}/vehicle-configurations",
                json={
                    "vehicle": _vehicle_payload(trailer_id),
                    "load_profiles": [
                        {
                            "configuration_type": "TWO_CARGO_SPLIT",
                            "max_actual_axle_load_kg": 8_700,
                        }
                    ],
                },
            )
            assert vehicle.status_code == 201, vehicle.text
            vehicle_id = vehicle.json()["id"]
            assert vehicle.json()["default_trailer_id"] == trailer_id
            assert vehicle.json()["height_safety_margin_mm"] == 100

            profiles = await client.get(f"/api/vehicles/{vehicle_id}/load-profiles")
            assert profiles.status_code == 200
            profile_id = profiles.json()[0]["id"]
            duplicate = await client.post(
                f"/api/vehicles/{vehicle_id}/load-profiles",
                json={
                    "configuration_type": "TWO_CARGO_SPLIT",
                    "max_actual_axle_load_kg": 8_600,
                },
            )
            assert duplicate.status_code == 409
            assert duplicate.json()["code"] == "VEHICLE_LOAD_PROFILE_DUPLICATE"

            updated_profile = await client.patch(
                f"/api/vehicle-load-profiles/{profile_id}",
                json={"max_actual_axle_load_kg": 8_650},
            )
            assert updated_profile.status_code == 200
            assert updated_profile.json()["max_actual_axle_load_kg"] == 8_650

            invalid_atomic_update = await client.put(
                f"/api/vehicles/{vehicle_id}/configuration",
                json={
                    "vehicle": {"name": "must-not-persist"},
                    "load_profiles": [
                        {
                            "configuration_type": "TWO_CARGO_SPLIT",
                            "max_actual_axle_load_kg": 8_500,
                        },
                        {
                            "configuration_type": "TWO_CARGO_SPLIT",
                            "max_actual_axle_load_kg": 8_400,
                        },
                    ],
                },
            )
            assert invalid_atomic_update.status_code == 422
            unchanged_vehicle = await client.get(f"/api/scenarios/{scenario_id}/vehicles")
            assert unchanged_vehicle.json()[0]["name"] == "Манипулятор 1"

            updated_configuration = await client.put(
                f"/api/vehicles/{vehicle_id}/configuration",
                json={
                    "vehicle": {"name": "Манипулятор 1 · проверен"},
                    "load_profiles": [
                        {
                            "configuration_type": "CARGO_ON_TRUCK",
                            "max_actual_axle_load_kg": 7_800,
                        },
                        {
                            "configuration_type": "TWO_CARGO_SPLIT",
                            "max_actual_axle_load_kg": 8_650,
                        },
                    ],
                },
            )
            assert updated_configuration.status_code == 200, updated_configuration.text
            listed_profiles = await client.get(f"/api/vehicles/{vehicle_id}/load-profiles")
            assert listed_profiles.status_code == 200
            assert [item["configuration_type"] for item in listed_profiles.json()] == [
                "CARGO_ON_TRUCK",
                "TWO_CARGO_SPLIT",
            ]

            profile_id = next(
                item["id"]
                for item in listed_profiles.json()
                if item["configuration_type"] == "TWO_CARGO_SPLIT"
            )

            request = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "DELIVERY",
                    "name": "Две бытовки",
                    "latitude": 55.75,
                    "longitude": 37.61,
                    "quantity": 2,
                    "cargo_length_mm": 6_000,
                    "cargo_width_mm": 2_400,
                    "cargo_height_mm": 2_400,
                    "cargo_weight_kg": 3_500,
                    "date_options": [{"date": "2026-08-26"}],
                },
            )
            assert request.status_code == 201, request.text
            assert request.json()["tasks"][0]["cargo_length_mm"] == 6_000
            assert request.json()["tasks"][0]["cargo_weight_kg"] == 3_500

            partial_cargo = await client.post(
                f"/api/scenarios/{scenario_id}/requests",
                json={
                    "type": "PICKUP",
                    "name": "Неполные габариты",
                    "latitude": 55.75,
                    "longitude": 37.61,
                    "quantity": 1,
                    "cargo_length_mm": 6_000,
                },
            )
            assert partial_cargo.status_code == 422

            broken_patch = await client.patch(
                f"/api/requests/{request.json()['id']}", json={"cargo_weight_kg": None}
            )
            assert broken_patch.status_code == 422
            assert broken_patch.json()["code"] == "INCOMPLETE_CARGO_DIMENSIONS"

            other_trailer = await client.post(
                f"/api/scenarios/{other_scenario_id}/trailers",
                json=_trailer_payload("PR2000"),
            )
            assert other_trailer.status_code == 201
            cross_scenario = await client.patch(
                f"/api/vehicles/{vehicle_id}",
                json={"default_trailer_id": other_trailer.json()["id"]},
            )
            assert cross_scenario.status_code == 422
            assert cross_scenario.json()["code"] == "TRAILER_SCENARIO_MISMATCH"

            deleted_profile = await client.delete(f"/api/vehicle-load-profiles/{profile_id}")
            assert deleted_profile.status_code == 204
            deleted_trailer = await client.delete(f"/api/trailers/{trailer_id}")
            assert deleted_trailer.status_code == 204
            vehicle_after_delete = await client.get(f"/api/scenarios/{scenario_id}/vehicles")
            assert vehicle_after_delete.status_code == 200
            assert vehicle_after_delete.json()[0]["default_trailer_id"] is None
        finally:
            assert (await client.delete(f"/api/scenarios/{scenario_id}")).status_code == 204
            assert (await client.delete(f"/api/scenarios/{other_scenario_id}")).status_code == 204
