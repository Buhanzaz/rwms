"""Frozen public contract tests for existing unassigned delivery rescheduling."""

from pathlib import Path

from app.main import openapi_document


def test_request_reschedule_openapi_uses_owner_slot_fences_without_manual_times() -> None:
    """Expose date search and slot apply as two narrow server-owned commands."""

    document = openapi_document()
    paths = document["paths"]
    schemas = document["components"]["schemas"]
    options_path = paths["/api/requests/{request_id}/reschedule-options"]["post"]
    apply_path = paths["/api/requests/{request_id}/reschedule"]["post"]
    retry_path = paths["/api/requests/{request_id}/reschedule-retry"]["post"]

    options_query = schemas["RequestRescheduleOptionsQuery"]
    assert set(options_query["required"]) == {"expected_request_version", "date"}
    assert set(options_query["properties"]) == {"expected_request_version", "date"}

    apply = schemas["RequestRescheduleApply"]
    assert set(apply["required"]) == {
        "expected_request_version",
        "source_plan_id",
        "source_plan_version",
        "expected_order_version",
        "expected_session_version",
        "slot_id",
        "slot_version",
    }
    assert "date" not in apply["properties"]
    assert "window_start" not in apply["properties"]
    assert "window_end" not in apply["properties"]

    slot = schemas["RequestRescheduleSlotRead"]
    assert set(slot["required"]) == {
        "slot_id",
        "slot_version",
        "date",
        "kind",
        "window_start",
        "window_end",
        "delivery_price_rubles",
        "expires_at",
    }
    options_read = schemas["RequestRescheduleOptionsRead"]
    assert set(options_read["required"]) == {
        "request_id",
        "request_version",
        "source_plan_id",
        "source_plan_version",
        "order_id",
        "order_version",
        "session_id",
        "session_version",
        "current_slot",
        "options",
    }
    result = schemas["RequestRescheduleResultRead"]
    assert set(result["required"]) == {
        "request_id",
        "request_version",
        "order_id",
        "order_version",
        "session_id",
        "session_version",
        "scheduled_date",
        "confirmed_slot",
    }

    assert options_path["security"] == [{"HTTPBearer": []}]
    assert apply_path["security"] == [{"HTTPBearer": []}]
    assert retry_path["security"] == [{"HTTPBearer": []}]
    retry = schemas["RequestRescheduleRetry"]
    assert set(retry["required"]) == {"hold_id", "expected_quarantine_count"}
    assert retry_path["requestBody"]["required"] is True
    idempotency = next(
        item for item in apply_path["parameters"] if item["name"] == "Idempotency-Key"
    )
    assert idempotency["required"] is True
    assert idempotency["schema"]["format"] == "uuid"
    retry_idempotency = next(
        item for item in retry_path["parameters"] if item["name"] == "Idempotency-Key"
    )
    assert retry_idempotency["required"] is True
    assert retry_idempotency["schema"]["format"] == "uuid"


def test_superseded_hold_state_is_an_additive_upgrade_after_0032() -> None:
    """Keep already-applied 0032 immutable and deliver SUPERSEDED through 0033."""

    migration = (
        Path(__file__).parents[1]
        / "migrations/versions/20260901_0033_reschedule_superseded_state.py"
    ).read_text(encoding="utf-8")
    assert 'revision: str = "20260901_0033"' in migration
    assert 'down_revision: str | None = "20260901_0032"' in migration
    assert "ck_request_reschedule_holds_phase_consistency" in migration
    assert "ck_request_reschedule_holds_valid_state" in migration
    assert "'SUPERSEDED'" in migration
    assert "op.drop_constraint" in migration
    assert "op.create_check_constraint" in migration
