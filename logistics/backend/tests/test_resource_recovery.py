"""Resource incidents must recover safely, once, and no earlier than the actual incident."""

from datetime import datetime, time, timedelta
from hashlib import sha256
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import utc_now
from app.errors import ApiError
from app.models import LogisticsEventType, RecoveryProposal, RecoveryProposalStatus, Trailer
from app.models.domain import PlanStatus
from app.schemas.operations import LogisticsEventCreate
from app.services import plans
from app.services.dynamic_impacts import LogisticsImpactAnalyzer
from app.services.dynamic_operations import DynamicLogisticsService
from app.services.dynamic_support import event_command_hash
from tests.factories import make_warehouse
from tests.test_dynamic_operations import PLANNING_DATE, ZONE, _confirmed_rwms_day, _generated_day

pytestmark = pytest.mark.integration
ACTOR = UUID("00000000-0000-0000-0000-000000000001")


@pytest.fixture(autouse=True)
def recovery_clock(monkeypatch: pytest.MonkeyPatch) -> None:
    """Freeze the apply clock later than the incident and before customer windows."""

    monkeypatch.setattr(
        "app.services.planner_runtime.utc_now",
        lambda: datetime.combine(PLANNING_DATE, time(9, 30), tzinfo=ZONE),
    )


@pytest.mark.parametrize("mode", ["AUTO", "MANUAL"])
async def test_breakdown_replaces_vehicle_after_incident_and_replays_once(
    db_session: AsyncSession,
    mode: str,
) -> None:
    runtime, source, request, _, _ = await _generated_day(db_session, resource_count=2)
    broken = source.cycles[0].driver_shift.vehicle
    payload = LogisticsEventCreate(
        event_type=LogisticsEventType.VEHICLE_BREAKDOWN,
        plan_id=source.id,
        expected_plan_version=source.version,
        vehicle_id=broken.id,
        recovery_mode=mode,
        occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
        reason="Engine breakdown",
    )
    service = DynamicLogisticsService(runtime, None)
    result = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        payload,
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key=f"breakdown-{mode}",
    )
    assert not broken.active
    proposal = result.proposals[0]
    if mode == "MANUAL":
        assert proposal.status == RecoveryProposalStatus.READY_TO_APPLY
        assert (await plans.get_plan(db_session, source.id)).status == PlanStatus.GENERATED
        proposal = await service.apply_proposal(
            db_session,
            proposal.id,
            expected_version=proposal.version,
            actor="dispatcher",
            actor_subject_id=ACTOR,
            idempotency_key="manual-apply",
        )
    assert proposal.status == RecoveryProposalStatus.APPLIED
    assert proposal.result_plan_id is not None
    successor = await plans.get_plan(db_session, proposal.result_plan_id)
    assert successor.cycles
    assert all(cycle.driver_shift.vehicle_id != broken.id for cycle in successor.cycles)
    assert all(
        cycle.planned_start >= datetime.combine(PLANNING_DATE, time(9, 30), tzinfo=ZONE)
        for cycle in successor.cycles
    )
    assert {stop.task_id for cycle in successor.cycles for stop in cycle.stops} >= {
        request.tasks[0].id
    }
    broken_version = broken.version
    replay = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        payload,
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key=f"breakdown-{mode}",
    )
    assert replay.proposals[0].result_plan_id == successor.id
    assert broken.version == broken_version


async def test_no_replacement_preserves_source_and_reports_failure(
    db_session: AsyncSession,
) -> None:
    runtime, source, request, _, _ = await _generated_day(db_session)
    payload = LogisticsEventCreate(
        event_type=LogisticsEventType.VEHICLE_BREAKDOWN,
        plan_id=source.id,
        expected_plan_version=source.version,
        vehicle_id=source.cycles[0].driver_shift.vehicle_id,
        recovery_mode="AUTO",
        occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
        reason="No spare truck",
    )
    service = DynamicLogisticsService(runtime, None)
    result = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        payload,
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key="no-spare",
    )
    assert result.proposals[0].status == RecoveryProposalStatus.FAILED
    assert result.proposals[0].failure_code in {
        "RECOVERY_RESOURCE_CAPACITY_INSUFFICIENT",
        "RECOVERY_PLAN_FAILED",
    }
    assert any(notice.notice_type == "RECOVERY_APPLY_FAILED" for notice in result.notices)
    assert (await plans.get_plan(db_session, source.id)).status == PlanStatus.GENERATED
    assert request.assigned_contractor_worker_id is None
    replay = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        payload,
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key="no-spare",
    )
    assert replay.proposals[0].version == result.proposals[0].version


async def test_auto_does_not_teleport_a_loaded_cabin(db_session: AsyncSession) -> None:
    service, client, source, _, _ = await _confirmed_rwms_day(db_session, owner_state="CURRENT")
    result = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.VEHICLE_BREAKDOWN,
            plan_id=source.id,
            expected_plan_version=source.version,
            vehicle_id=source.cycles[0].driver_shift.vehicle_id,
            recovery_mode="AUTO",
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Breakdown with cargo",
        ),
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key="loaded-breakdown",
    )
    assert not result.proposals
    assert any(action.action_type == "RESOLVE_IN_PROGRESS_ROUTE" for action in result.actions)
    assert not client.replacement_commands
    assert (await plans.get_plan(db_session, source.id)).status == PlanStatus.CONFIRMED


async def test_trailer_breakdown_keeps_tractor_and_invalidates_capacity(
    db_session: AsyncSession,
) -> None:
    runtime, source, _, vehicle, _ = await _generated_day(db_session)
    trailer = Trailer(
        warehouse_id=source.warehouse_id,
        name="Trailer",
        registration_number=str(uuid4()),
    )
    db_session.add(trailer)
    await db_session.flush()
    vehicle.default_trailer = trailer
    await db_session.flush()
    for cycle in source.cycles:
        for segment in cycle.segments:
            segment.routing_profile_snapshot = {"trailerId": str(trailer.id)}
    before_generation = source.warehouse.capacity_generation
    result = await DynamicLogisticsService(runtime, None).register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=LogisticsEventType.TRAILER_BREAKDOWN,
            plan_id=source.id,
            expected_plan_version=source.version,
            trailer_id=trailer.id,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Axle failure",
        ),
        actor="dispatcher",
        idempotency_key="trailer-breakdown",
    )
    assert not trailer.active
    assert vehicle.active
    assert result.proposals[0].changes["resource_event"] == "TRAILER_BREAKDOWN"
    assert source.warehouse.capacity_generation > before_generation
    # Even reactivation cannot silently reuse the incident trailer in this day's plan.
    trailer.active = True
    snapshot = await runtime._load_snapshot(
        db_session, source.warehouse_id, PLANNING_DATE, None, None
    )
    assert (
        next(
            item for item in snapshot.input_data.vehicles if item.id == str(vehicle.id)
        ).default_trailer
        is None
    )


def test_trailer_impact_uses_routed_identity_not_current_catalog_attachment() -> None:
    routed, current = uuid4(), uuid4()
    cycle = SimpleNamespace(
        segments=[SimpleNamespace(routing_profile_snapshot={"trailerId": str(routed)})],
        driver_shift=SimpleNamespace(vehicle=SimpleNamespace(default_trailer_id=current)),
    )
    assert LogisticsImpactAnalyzer._cycle_uses_trailer(cycle, routed)
    assert not LogisticsImpactAnalyzer._cycle_uses_trailer(cycle, current)


def test_incident_fields_and_auto_scope_cannot_be_spoofed() -> None:
    common = {
        "occurred_at": datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
        "reason": "Test",
    }
    with pytest.raises(ValidationError):
        LogisticsEventCreate(event_type="TRAILER_BREAKDOWN", **common)
    with pytest.raises(ValidationError):
        LogisticsEventCreate(
            event_type="ORDER_CANCELLED", request_id=uuid4(), recovery_mode="AUTO", **common
        )
    with pytest.raises(ValidationError):
        LogisticsEventCreate(
            event_type="TRAILER_BREAKDOWN",
            trailer_id=uuid4(),
            facts={"recovery_mode": "AUTO"},
            **common,
        )


async def test_auto_requires_authenticated_actor_before_mutating(db_session: AsyncSession) -> None:
    runtime, source, _, vehicle, _ = await _generated_day(db_session)
    with pytest.raises(ApiError) as caught:
        await DynamicLogisticsService(runtime, None).register_event(
            db_session,
            source.warehouse_id,
            PLANNING_DATE,
            LogisticsEventCreate(
                event_type="VEHICLE_BREAKDOWN",
                vehicle_id=vehicle.id,
                recovery_mode="AUTO",
                occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
                reason="Test",
            ),
            actor="unverified",
            idempotency_key="missing-actor",
        )
    assert caught.value.code == "RECOVERY_ACTOR_REQUIRED"
    assert vehicle.active


async def test_manually_locked_failed_cycle_is_not_claimed_as_recovered(
    db_session: AsyncSession,
) -> None:
    runtime, source, _, _, _ = await _generated_day(db_session, resource_count=2)
    source.cycles[0].locked = True
    result = await DynamicLogisticsService(runtime, None).register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type="VEHICLE_BREAKDOWN",
            plan_id=source.id,
            expected_plan_version=source.version,
            vehicle_id=source.cycles[0].driver_shift.vehicle_id,
            recovery_mode="AUTO",
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Locked trip",
        ),
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key="locked-trip",
    )
    assert not result.proposals
    assert any("FAILED_RESOURCE_CYCLE_LOCKED" in notice.reason_codes for notice in result.notices)
    assert source.cycles[0].locked


async def test_foreign_trailer_cannot_be_disabled(db_session: AsyncSession) -> None:
    runtime, source, _, _, _ = await _generated_day(db_session)
    foreign = await make_warehouse(db_session)
    trailer = Trailer(warehouse_id=foreign.id, name="Foreign", registration_number=str(uuid4()))
    db_session.add(trailer)
    await db_session.flush()
    with pytest.raises(ApiError) as caught:
        await DynamicLogisticsService(runtime, None).register_event(
            db_session,
            source.warehouse_id,
            PLANNING_DATE,
            LogisticsEventCreate(
                event_type="TRAILER_BREAKDOWN",
                plan_id=source.id,
                expected_plan_version=source.version,
                trailer_id=trailer.id,
                occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
                reason="Test",
            ),
            actor="dispatcher",
            idempotency_key="foreign-trailer",
        )
    assert caught.value.code == "TRAILER_NOT_FOUND"
    assert trailer.active


@pytest.mark.parametrize("claim_age_minutes", [0, 6])
async def test_auto_replay_resumes_interrupted_preparation(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    claim_age_minutes: int,
) -> None:
    runtime, source, _, _, _ = await _generated_day(db_session, resource_count=2)
    service = DynamicLogisticsService(runtime, None)
    payload = LogisticsEventCreate(
        event_type="VEHICLE_BREAKDOWN",
        plan_id=source.id,
        expected_plan_version=source.version,
        vehicle_id=source.cycles[0].driver_shift.vehicle_id,
        recovery_mode="AUTO",
        occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
        reason="Interrupted solve",
    )

    async def interrupt_before_preparation(
        session: AsyncSession,
        proposal_id: UUID,
        **kwargs: object,
    ) -> None:
        proposal = await session.get(RecoveryProposal, proposal_id)
        assert proposal is not None
        proposal.changes = {
            **proposal.changes,
            "active_apply": {
                "token": str(uuid4()),
                "key": kwargs["idempotency_key"],
                "expected_version": kwargs["expected_version"],
                "started_at": (utc_now() - timedelta(minutes=claim_age_minutes)).isoformat(),
            },
        }
        proposal.version += 1
        await session.commit()
        raise RuntimeError("Simulated process interruption after durable claim")

    with monkeypatch.context() as patch:
        patch.setattr(service._recovery, "apply_proposal", interrupt_before_preparation)
        with pytest.raises(RuntimeError, match="Simulated process interruption"):
            await service.register_event(
                db_session,
                source.warehouse_id,
                PLANNING_DATE,
                payload,
                actor="dispatcher",
                actor_subject_id=ACTOR,
                idempotency_key="interrupted-auto",
            )
    replay = await service.register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        payload,
        actor="dispatcher",
        actor_subject_id=ACTOR,
        idempotency_key="interrupted-auto",
    )
    assert replay.proposals[0].status == (
        RecoveryProposalStatus.APPLIED
        if claim_age_minutes >= 5
        else RecoveryProposalStatus.READY_TO_APPLY
    )


def test_existing_manual_event_replay_hash_survives_optional_controls() -> None:
    payload = LogisticsEventCreate(
        event_type="VEHICLE_BREAKDOWN",
        vehicle_id=uuid4(),
        occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
        reason="Existing incident",
    )
    legacy_json = payload.model_dump_json(
        exclude={"recovery_mode", "trailer_id"}, exclude_none=False
    )
    assert event_command_hash(payload) == sha256(legacy_json.encode()).hexdigest()
    assert event_command_hash(
        payload.model_copy(update={"recovery_mode": "AUTO"})
    ) != event_command_hash(payload)
