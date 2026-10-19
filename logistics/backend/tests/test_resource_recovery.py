"""Resource incidents must recover safely, once, and no earlier than the actual incident."""

from datetime import datetime, time, timedelta
from hashlib import sha256
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db import utc_now
from app.errors import ApiError
from app.models import (
    LogisticsEvent,
    LogisticsEventType,
    RecoveryProposal,
    RecoveryProposalStatus,
    Trailer,
)
from app.models.domain import PlanStatus
from app.schemas.operations import LogisticsEventCreate
from app.schemas.slot_planning import SlotAvailabilityRequest
from app.services import plans
from app.services.capacity_projection import build_capacity_projection
from app.services.dynamic_impacts import LogisticsImpactAnalyzer
from app.services.dynamic_operations import DynamicLogisticsService
from app.services.dynamic_support import event_command_hash
from app.slot_planning.application import SlotPlanningApplication
from tests.factories import make_driver, make_shift, make_vehicle, make_warehouse
from tests.test_dynamic_operations import (
    PLANNING_DATE,
    ZONE,
    _confirmed_rwms_day,
    _generated_day,
    _PlanningGroupClient,
    _support_link,
)

pytestmark = pytest.mark.integration
ACTOR = UUID("00000000-0000-0000-0000-000000000001")


@pytest.mark.parametrize("foreign_event", (False, True))
async def test_representative_capacity_uses_its_root_incidents_without_cross_group_leaks(
    db_session: AsyncSession,
    foreign_event: bool,
) -> None:
    root = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    representative = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    representative.representative = True
    representative.capacity_generation = 1
    foreign = await make_warehouse(db_session, default_planning_date=PLANNING_DATE)
    driver = await make_driver(db_session, representative)
    vehicle = await make_vehicle(db_session, representative)
    vehicle.capacity = 2
    vehicle.can_use_trailer = True
    trailer = Trailer(
        warehouse_id=representative.id,
        name="Reactivated",
        registration_number=str(uuid4()),
    )
    db_session.add(trailer)
    vehicle.default_trailer = trailer
    await make_shift(
        db_session, representative, driver, vehicle, date_from=PLANNING_DATE, date_to=PLANNING_DATE
    )
    await db_session.flush()
    db_session.add(
        LogisticsEvent(
            warehouse_id=foreign.id if foreign_event else root.id,
            day=PLANNING_DATE,
            event_type=LogisticsEventType.TRAILER_BREAKDOWN,
            actor="test",
            idempotency_key=str(uuid4()),
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            facts={"trailer_id": str(trailer.id)},
        )
    )
    await db_session.flush()
    client = _PlanningGroupClient(_support_link(root, representative))
    projection = await build_capacity_projection(
        db_session,
        representative.id,
        client,  # type: ignore[arg-type]
    )
    expected = 2 if foreign_event else 1
    assert [item.cabin_capacity for item in projection.command.shifts] == [expected]
    context = await SlotPlanningApplication(
        Settings(
            rwms_sync_enabled=True,
            rwms_logistics_base_url="http://rwms.test",
            rwms_token_url="http://auth.test/token",
            rwms_client_secret="test-only",
        ),
        client,  # type: ignore[arg-type]
    )._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=representative.id,
            date=PLANNING_DATE,
            address="Review address",
            latitude=representative.latitude,
            longitude=representative.longitude,
            cabin_count=2,
            site_cabin_capacity=2,
        ),
    )
    assert context.day_plan.drivers[0].vehicle_capacity == expected
    assert trailer.active


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
    vehicle.capacity = 2
    vehicle.can_use_trailer = True
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
    projection = await build_capacity_projection(db_session, source.warehouse_id)
    assert [
        shift.cabin_capacity
        for shift in projection.command.shifts
        if shift.delivery_date == PLANNING_DATE
    ] == [1]
    assert [
        shift.cabin_capacity
        for shift in projection.command.shifts
        if shift.delivery_date == PLANNING_DATE + timedelta(days=1)
    ] == [2]
    context = await SlotPlanningApplication(Settings(rwms_sync_enabled=False))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=source.warehouse_id,
            date=PLANNING_DATE,
            address="Review address",
            latitude=source.warehouse.latitude,
            longitude=source.warehouse.longitude,
            cabin_count=2,
            site_cabin_capacity=2,
        ),
    )
    assert len(context.day_plan.drivers) == 1
    assert context.day_plan.drivers[0].vehicle_capacity == 1
    assert not context.day_plan.drivers[0].has_trailer
    assert context.equipment[str(vehicle.id)].trailer is None
    assert (
        next(item for item in snapshot.input_data.vehicles if item.id == str(vehicle.id)).capacity
        == 1
    )

    # Attaching a different, healthy trailer can restore only its own second platform.
    previous_revision = context.source_revision
    replacement = Trailer(
        warehouse_id=source.warehouse_id,
        name="Replacement",
        registration_number=str(uuid4()),
    )
    db_session.add(replacement)
    vehicle.default_trailer = replacement
    await db_session.flush()
    context = await SlotPlanningApplication(Settings(rwms_sync_enabled=False))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=source.warehouse_id,
            date=PLANNING_DATE,
            address="Review address",
            latitude=source.warehouse.latitude,
            longitude=source.warehouse.longitude,
            cabin_count=2,
            site_cabin_capacity=2,
        ),
    )
    assert context.source_revision != previous_revision
    assert context.day_plan.drivers[0].vehicle_capacity == 2
    assert context.equipment[str(vehicle.id)].trailer.trailer_id == replacement.id


@pytest.mark.parametrize(
    "event_type",
    (
        LogisticsEventType.VEHICLE_BREAKDOWN,
        LogisticsEventType.DRIVER_UNAVAILABLE,
    ),
)
async def test_day_incident_excludes_reactivated_shift_from_slots_and_capacity(
    db_session: AsyncSession,
    event_type: LogisticsEventType,
) -> None:
    runtime, source, _, vehicle, shift = await _generated_day(db_session)
    await DynamicLogisticsService(runtime, None).register_event(
        db_session,
        source.warehouse_id,
        PLANNING_DATE,
        LogisticsEventCreate(
            event_type=event_type,
            plan_id=source.id,
            expected_plan_version=source.version,
            vehicle_id=vehicle.id if event_type == LogisticsEventType.VEHICLE_BREAKDOWN else None,
            driver_shift_id=shift.id
            if event_type == LogisticsEventType.DRIVER_UNAVAILABLE
            else None,
            occurred_at=datetime.combine(PLANNING_DATE, time(9), tzinfo=ZONE),
            reason="Unavailable for this day",
        ),
        actor="dispatcher",
        idempotency_key=str(uuid4()),
    )
    vehicle.active = True
    await db_session.flush()
    snapshot = await runtime._load_snapshot(
        db_session, source.warehouse_id, PLANNING_DATE, None, None
    )
    assert not any(item.active for item in snapshot.input_data.shifts)
    context = await SlotPlanningApplication(Settings(rwms_sync_enabled=False))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=source.warehouse_id,
            date=PLANNING_DATE,
            address="Review address",
            latitude=source.warehouse.latitude,
            longitude=source.warehouse.longitude,
            cabin_count=1,
            site_cabin_capacity=1,
        ),
    )
    assert not context.day_plan.drivers
    projection = await build_capacity_projection(db_session, source.warehouse_id)
    assert not any(item.delivery_date == PLANNING_DATE for item in projection.command.shifts)
    assert any(
        item.delivery_date == PLANNING_DATE + timedelta(days=1)
        for item in projection.command.shifts
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
