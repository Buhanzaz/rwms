"""Database orchestration for dynamic slot calculation, holding, and confirmation."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, replace
from datetime import date, datetime, time, timedelta
from hashlib import sha256
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import Settings
from app.db import utc_now
from app.errors import ApiError, not_found
from app.geo.policy_classification import (
    PolicyZoneClassification,
    classify_policy_point,
    classify_policy_points,
)
from app.integrations.rwms import RwmsPlanningClient, get_rwms_planning_client
from app.models import (
    DriverShift,
    LogisticsRequest,
    PlanningDayClosure,
    PlanningDayMode,
    PlanningDayPolicy,
    PlanningTask,
    RequestDateOption,
    RouteCycle,
    RoutePlan,
    SlotDayPlan,
    SlotHold,
    Vehicle,
    Warehouse,
    WarehousePolicyZone,
)
from app.models.domain import PlanStatus, RequestStatus, RequestType, TaskStatus
from app.routing import GeoPoint
from app.schemas.slot_planning import (
    CustomerSlotRead,
    SlotAvailabilityRead,
    SlotAvailabilityRequest,
    SlotBestCandidateRead,
    SlotConfirmRead,
    SlotConfirmRequest,
    SlotHoldCreate,
    SlotHoldRead,
    SlotTimelineStopRead,
)
from app.services.planning_group import resolve_planning_warehouse_group
from app.services.resource_incidents import (
    NO_RESOURCE_RESTRICTIONS,
    DayResourceRestrictions,
    load_resource_restrictions,
)
from app.services.support_resource_candidates import (
    SupportResourceFacts,
    load_support_resource_facts,
    route_support_resource_facts,
)

from .configuration import (
    default_slot_cabin,
    effective_vehicle_cabin_capacity,
    truck_travel_time_provider,
    vehicle_equipment_snapshot,
    vehicle_has_available_trailer,
    warehouse_slot_configuration,
)
from .models import (
    DayPlan,
    DriverPlan,
    PlanningReason,
    ScheduledTrip,
    SlotCandidate,
    SlotPlanningResult,
    SlotTask,
    SlotTaskType,
    TimeWindow,
    TravelTimeUnavailable,
    TripPlan,
    VehicleLegState,
    WarehouseSlotConfiguration,
)
from .planner import FeasibleSlotPlanner
from .routing_adapter import CachedTruckTravelTimeProvider, VehicleEquipmentSnapshot

_ACTIVE_REQUEST_STATUSES = {
    RequestStatus.READY,
    RequestStatus.PLANNED,
    RequestStatus.IN_PROGRESS,
    RequestStatus.UNASSIGNED,
}
_POLICY_CLASSIFICATION_BATCH_SIZE = 500


@dataclass(frozen=True, slots=True)
class SlotPlanningContext:
    """Fully loaded persistence snapshot used by one application operation."""

    warehouse: Warehouse
    configuration: WarehouseSlotConfiguration
    day_plan: DayPlan
    equipment: dict[str, VehicleEquipmentSnapshot]
    source_revision: str
    delivery_price_rubles: int | None
    price_isochrone_minutes: int | None
    trailer_access_allowed: bool
    support_facts: SupportResourceFacts | None
    policy_zones: tuple[WarehousePolicyZone, ...] = ()
    special_price_zone: WarehousePolicyZone | None = None
    resource_restrictions: DayResourceRestrictions = NO_RESOURCE_RESTRICTIONS


class SlotPlanningApplication:
    """Own slot calculation and version-fenced hold/confirm transactions."""

    def __init__(
        self,
        settings: Settings,
        rwms_client: RwmsPlanningClient | None = None,
    ) -> None:
        self._settings = settings
        self._rwms_client = rwms_client or get_rwms_planning_client(settings)

    async def calculate(
        self,
        session: AsyncSession,
        command: SlotAvailabilityRequest,
    ) -> SlotAvailabilityRead:
        """Calculate exact availability and classify tariff without coupling the two."""

        context = await self._load_context(session, command)
        day_fence = await self._synchronize_day_fence(session, context)
        context = replace(
            context,
            day_plan=replace(context.day_plan, version=day_fence.version),
        )
        provider = truck_travel_time_provider(
            self._settings,
            context.warehouse,
            context.equipment,
        )
        try:
            context = await self._activate_support_resources(context, provider)
            context = await self._resolve_isochrone_tariff(
                context,
                provider,
                GeoPoint(command.longitude, command.latitude),
            )
            result = await FeasibleSlotPlanner(provider, context.configuration).calculate(
                context.day_plan,
                new_task_id="new-customer-delivery",
                address=command.address,
                point=GeoPoint(command.longitude, command.latitude),
                cabin_count=command.cabin_count,
                site_cabin_capacity=command.site_cabin_capacity,
                service_duration_minutes=command.service_duration_minutes,
                trailer_access_allowed=context.trailer_access_allowed,
            )
            return await self._read_result(context, result, provider)
        finally:
            await provider.aclose()

    async def create_hold(
        self,
        session: AsyncSession,
        command: SlotHoldCreate,
    ) -> SlotHoldRead:
        """Recalculate under a date fence and persist one ten-minute insertion hold."""

        context = await self._load_context(session, command)
        day_fence = await self._synchronize_day_fence(session, context, for_update=True)
        # A concurrent creator can commit while this transaction waits on the
        # date fence. Reload after acquiring it so its active hold participates
        # in this calculation instead of overselling the final capacity.
        context = await self._load_context(session, command)
        context = replace(
            context,
            day_plan=replace(context.day_plan, version=day_fence.version),
        )
        provider = truck_travel_time_provider(
            self._settings,
            context.warehouse,
            context.equipment,
        )
        try:
            context = await self._activate_support_resources(context, provider)
            context = await self._resolve_isochrone_tariff(
                context,
                provider,
                GeoPoint(command.longitude, command.latitude),
            )
            result = await FeasibleSlotPlanner(provider, context.configuration).calculate(
                context.day_plan,
                new_task_id=f"hold:{command.client_session_id}",
                address=command.address,
                point=GeoPoint(command.longitude, command.latitude),
                cabin_count=command.cabin_count,
                site_cabin_capacity=command.site_cabin_capacity,
                service_duration_minutes=command.service_duration_minutes,
                trailer_access_allowed=context.trailer_access_allowed,
            )
        finally:
            await provider.aclose()
        selected = next(
            (
                slot
                for slot in result.slots
                if slot.window.start.time() == command.slot_start
                and slot.window.end.time() == command.slot_end
            ),
            None,
        )
        if selected is None or selected.best_candidate is None:
            reasons = [reason.value for reason in selected.reasons] if selected else []
            raise ApiError(
                409,
                "SLOT_NO_LONGER_AVAILABLE",
                "The selected slot is no longer feasible",
                extra={"reasons": reasons},
            )
        now = utc_now()
        hold = SlotHold(
            day_plan_id=day_fence.id,
            plan_version=day_fence.version,
            source_revision=context.source_revision,
            client_session_id=command.client_session_id,
            status="HELD",
            expires_at=now + timedelta(minutes=context.configuration.hold_ttl_minutes),
            request_snapshot=command.model_dump(mode="json"),
            candidate_snapshot=self._candidate_snapshot(selected.best_candidate),
            delivery_price_rubles=context.delivery_price_rubles,
            price_isochrone_minutes=context.price_isochrone_minutes,
        )
        session.add(hold)
        await session.flush()
        return SlotHoldRead(
            hold_id=hold.id,
            status="HELD",
            plan_version=hold.plan_version,
            expires_at=hold.expires_at,
            slot_start=command.slot_start,
            slot_end=command.slot_end,
            delivery_price_rubles=hold.delivery_price_rubles,
            price_isochrone_minutes=hold.price_isochrone_minutes,
            trailer_access_allowed=context.trailer_access_allowed,
        )

    async def confirm_hold(
        self,
        session: AsyncSession,
        hold_id: UUID,
        command: SlotConfirmRequest,
    ) -> SlotConfirmRead:
        """Atomically recheck source facts and convert one live hold into a delivery."""

        hold = await session.scalar(
            select(SlotHold).where(SlotHold.id == hold_id).with_for_update()
        )
        if hold is None:
            raise not_found("slot_hold", hold_id)
        if hold.status == "CONFIRMED":
            if (
                hold.confirmation_key != command.confirmation_key
                or hold.confirmed_request_id is None
            ):
                raise ApiError(409, "SLOT_HOLD_ALREADY_CONFIRMED", "Hold used by another command")
            day = await session.get(SlotDayPlan, hold.day_plan_id)
            assert day is not None
            return SlotConfirmRead(
                hold_id=hold.id,
                request_id=hold.confirmed_request_id,
                status="CONFIRMED",
                plan_version=day.version,
                replayed=True,
            )
        now = utc_now()
        if hold.expires_at <= now:
            hold.status = "EXPIRED"
            raise ApiError(409, "SLOT_HOLD_EXPIRED", "The slot hold has expired")
        day_fence = await session.scalar(
            select(SlotDayPlan).where(SlotDayPlan.id == hold.day_plan_id).with_for_update()
        )
        if day_fence is None:
            raise ApiError(409, "SLOT_DAY_PLAN_MISSING", "The held day no longer exists")
        request_command = SlotHoldCreate.model_validate(hold.request_snapshot)
        context = await self._load_context(session, request_command, exclude_hold_id=hold.id)
        if (
            context.source_revision != hold.source_revision
            or day_fence.version != hold.plan_version
            or day_fence.source_revision != hold.source_revision
        ):
            raise ApiError(
                409,
                "SLOT_PLAN_VERSION_CONFLICT",
                "The day plan changed; recalculate available slots",
            )
        provider = truck_travel_time_provider(
            self._settings,
            context.warehouse,
            context.equipment,
        )
        try:
            context = await self._activate_support_resources(context, provider)
            context = await self._resolve_isochrone_tariff(
                context,
                provider,
                GeoPoint(request_command.longitude, request_command.latitude),
            )
            if (
                context.delivery_price_rubles != hold.delivery_price_rubles
                or context.price_isochrone_minutes != hold.price_isochrone_minutes
            ):
                raise ApiError(
                    409,
                    "SLOT_PRICE_CHANGED",
                    "Delivery tariff changed; recalculate and hold the slot again",
                )
            result = await FeasibleSlotPlanner(provider, context.configuration).calculate(
                replace(context.day_plan, version=day_fence.version),
                new_task_id=f"confirm:{hold.id}",
                address=request_command.address,
                point=GeoPoint(request_command.longitude, request_command.latitude),
                cabin_count=request_command.cabin_count,
                site_cabin_capacity=request_command.site_cabin_capacity,
                service_duration_minutes=request_command.service_duration_minutes,
                trailer_access_allowed=context.trailer_access_allowed,
            )
        finally:
            await provider.aclose()
        selected = next(
            (
                slot
                for slot in result.slots
                if slot.window.start.time() == request_command.slot_start
                and slot.window.end.time() == request_command.slot_end
            ),
            None,
        )
        if selected is None or selected.best_candidate is None:
            raise ApiError(
                409,
                "SLOT_PLAN_VERSION_CONFLICT",
                "The held insertion is no longer feasible",
            )
        request = await self._create_confirmed_request(
            session,
            context,
            request_command,
            hold,
            selected.best_candidate,
        )
        hold.status = "CONFIRMED"
        hold.confirmation_key = command.confirmation_key
        hold.confirmed_request_id = request.id
        await session.flush()
        confirmed_context = await self._load_context(
            session,
            request_command,
            exclude_hold_id=hold.id,
        )
        day_fence.version += 1
        day_fence.source_revision = confirmed_context.source_revision
        await session.flush()
        return SlotConfirmRead(
            hold_id=hold.id,
            request_id=request.id,
            status="CONFIRMED",
            plan_version=day_fence.version,
            replayed=False,
        )

    async def _load_context(
        self,
        session: AsyncSession,
        command: SlotAvailabilityRequest,
        *,
        exclude_hold_id: UUID | None = None,
    ) -> SlotPlanningContext:
        """Load current warehouse, plan, equipment, holds, and price facts."""

        plan_cycles = selectinload(Warehouse.plans).selectinload(RoutePlan.cycles)
        warehouse = await session.scalar(
            select(Warehouse)
            .where(Warehouse.id == command.warehouse_id)
            .execution_options(populate_existing=True)
            .options(
                selectinload(Warehouse.requests).selectinload(LogisticsRequest.date_options),
                selectinload(Warehouse.requests).selectinload(LogisticsRequest.tasks),
                selectinload(Warehouse.shifts).selectinload(DriverShift.driver),
                selectinload(Warehouse.shifts)
                .selectinload(DriverShift.vehicle)
                .selectinload(Vehicle.default_trailer),
                selectinload(Warehouse.shifts)
                .selectinload(DriverShift.vehicle)
                .selectinload(Vehicle.load_profiles),
                plan_cycles.selectinload(RouteCycle.stops),
                plan_cycles.selectinload(RouteCycle.segments),
            )
        )
        if warehouse is None:
            raise not_found("warehouse", command.warehouse_id)
        planning_group = await resolve_planning_warehouse_group(
            session,
            self._rwms_client if self._settings.rwms_sync_enabled else None,
            warehouse,
            planning_date=command.date,
        )
        day_policy = await session.scalar(
            select(PlanningDayPolicy).where(
                PlanningDayPolicy.warehouse_id == planning_group.root.id,
                PlanningDayPolicy.date == command.date,
            )
        )
        day_mode = (
            PlanningDayMode(day_policy.mode)
            if day_policy is not None
            else PlanningDayMode.DELIVERIES_AND_PICKUPS
        )
        if day_mode == PlanningDayMode.PICKUPS_ONLY:
            raise ApiError(
                409,
                "DELIVERY_DISABLED_BY_DAY_MODE",
                "В выбранный день склад принимает только вывозы.",  # noqa: RUF001
                extra={
                    "planning_root_warehouse_id": str(planning_group.root.id),
                    "planning_day_mode": day_mode.value,
                    "planning_day_mode_version": (
                        day_policy.version if day_policy is not None else 0
                    ),
                },
            )
        policy_zones = tuple(
            await session.scalars(
                select(WarehousePolicyZone)
                .where(WarehousePolicyZone.warehouse_id == warehouse.id)
                .order_by(WarehousePolicyZone.id)
            )
        )
        policies = await classify_policy_point(
            session,
            warehouse.id,
            command.latitude,
            command.longitude,
        )
        if policies.forbidden is not None:
            raise ApiError(
                422,
                "DELIVERY_FORBIDDEN_ZONE",
                "Delivery is prohibited for this address",
                extra={
                    "zone_id": str(policies.forbidden.id),
                    "zone_name": policies.forbidden.name,
                },
            )
        closure_id = await session.scalar(
            select(PlanningDayClosure.id).where(
                PlanningDayClosure.warehouse_id == warehouse.id,
                PlanningDayClosure.date == command.date,
            )
        )
        if closure_id is not None:
            raise ApiError(
                409,
                "DAY_ACCEPTANCE_CLOSED",
                "The selected planning date no longer accepts customer delivery requests",
            )
        configuration = warehouse_slot_configuration(warehouse)
        active_holds = list(
            await session.scalars(
                select(SlotHold)
                .join(SlotDayPlan, SlotDayPlan.id == SlotHold.day_plan_id)
                .where(
                    SlotDayPlan.warehouse_id == warehouse.id,
                    SlotDayPlan.date == command.date,
                    SlotHold.status == "HELD",
                    SlotHold.expires_at > utc_now(),
                    *(
                        (SlotHold.id != exclude_hold_id,)
                        if exclude_hold_id is not None
                        else ()
                    ),
                )
                .order_by(SlotHold.created_at, SlotHold.id)
            )
        )
        active_requests = [
            request
            for request in warehouse.requests
            if request.status in _ACTIVE_REQUEST_STATUSES
            and (
                request.scheduled_date == command.date
                or (
                    request.scheduled_date is None
                    and any(option.date == command.date for option in request.date_options)
                )
            )
        ]
        request_policies = await self._classify_policy_points_in_batches(
            session,
            tuple(
                (
                    request.id,
                    warehouse.id,
                    request.latitude,
                    request.longitude,
                )
                for request in active_requests
            ),
        )
        hold_policies = await self._classify_policy_points_in_batches(
            session,
            tuple(
                (
                    hold.id,
                    warehouse.id,
                    float(hold.request_snapshot["latitude"]),
                    float(hold.request_snapshot["longitude"]),
                )
                for hold in active_holds
            ),
        )
        resource_restrictions = (
            await load_resource_restrictions(
                session, planning_group.root.id, command.date, command.date,
            )
        ).get(command.date, DayResourceRestrictions())
        day_plan, equipment = self._build_day_plan(
            warehouse,
            command.date,
            configuration,
            active_holds,
            requests=active_requests,
            request_policies=request_policies,
            hold_policies=hold_policies,
            resource_restrictions=resource_restrictions,
        )
        support_facts: SupportResourceFacts | None = None
        revision = self._source_revision(
            warehouse,
            command.date,
            policy_zones,
            planning_root_warehouse_id=planning_group.root.id,
            planning_day_mode=day_mode,
            planning_day_mode_version=(day_policy.version if day_policy is not None else 0),
            resource_restrictions=resource_restrictions,
        )
        if self._settings.rwms_sync_enabled:
            zone = ZoneInfo(warehouse.timezone)
            support_facts = await load_support_resource_facts(
                session,
                self._rwms_client,
                warehouse,
                command.date,
                (
                    datetime.combine(command.date, slot_start, tzinfo=zone)
                    for slot_start, _ in configuration.customer_slots
                ),
                lambda vehicle, settings: vehicle_equipment_snapshot(
                    vehicle, settings, resource_restrictions.trailer_ids,
                ),
                resource_restrictions=resource_restrictions,
            )
            equipment.update(support_facts.equipment)
            revision = sha256(
                f"{revision}:{support_facts.source_revision}".encode()
            ).hexdigest()
        return SlotPlanningContext(
            warehouse=warehouse,
            configuration=configuration,
            day_plan=day_plan,
            equipment=equipment,
            source_revision=revision,
            delivery_price_rubles=None,
            price_isochrone_minutes=None,
            trailer_access_allowed=policies.no_trailer is None,
            support_facts=support_facts,
            policy_zones=policy_zones,
            special_price_zone=policies.special_price,
            resource_restrictions=resource_restrictions,
        )

    def _build_day_plan(
        self,
        warehouse: Warehouse,
        planning_date: date,
        configuration: WarehouseSlotConfiguration,
        holds: list[SlotHold],
        *,
        requests: list[LogisticsRequest],
        request_policies: Mapping[UUID, PolicyZoneClassification],
        hold_policies: Mapping[UUID, PolicyZoneClassification],
        resource_restrictions: DayResourceRestrictions = NO_RESOURCE_RESTRICTIONS,
    ) -> tuple[DayPlan, dict[str, VehicleEquipmentSnapshot]]:
        """Translate current persistence and policy facts into immutable planner inputs."""

        zone = ZoneInfo(warehouse.timezone)
        shifts = [
            item
            for item in warehouse.shifts
            if item.date_from <= planning_date <= item.date_to
            and item.active
            and item.driver.active
            and item.vehicle.active
            and resource_restrictions.allows_shift(item.id, item.vehicle_id)
        ]
        shifts.sort(key=lambda item: (item.start_time, item.driver_id, item.id))
        latest_plan = self._latest_plan(warehouse.plans, warehouse.id, planning_date)
        confirmed_task_ids = {
            stop.task_id
            for cycle in (latest_plan.cycles if latest_plan is not None else ())
            for stop in cycle.stops
            if latest_plan is not None
            and latest_plan.status == PlanStatus.CONFIRMED
            and stop.task_id is not None
        }
        tasks_by_uuid: dict[UUID, SlotTask] = {}
        for request in requests:
            policy = request_policies.get(request.id)
            option = self._selected_option(request, planning_date, zone)
            if option is None:
                continue
            stored_tasks = sorted(request.tasks, key=lambda item: item.part_number)
            if not stored_tasks:
                continue
            for task in stored_tasks:
                confirmed_workload = task.id in confirmed_task_ids
                if (
                    not confirmed_workload
                    and policy is not None
                    and policy.forbidden is not None
                ):
                    continue
                task_type = SlotTaskType(task.type)
                mandatory = task.mandatory
                default_minutes = (
                    configuration.delivery_service_minutes
                    if task_type is SlotTaskType.DELIVERY
                    else configuration.pickup_service_minutes
                )
                tasks_by_uuid[task.id] = SlotTask(
                    id=str(task.id),
                    task_type=task_type,
                    point=GeoPoint(task.longitude, task.latitude),
                    quantity=task.quantity,
                    window=option,
                    service_minutes=max(default_minutes, task.service_minutes),
                    priority=task.priority,
                    address=request.address_label,
                    mandatory=mandatory,
                    trailer_access_allowed=(
                        request.trailer_access_allowed is not False
                        and (
                            confirmed_workload
                            or policy is None
                            or policy.no_trailer is None
                        )
                    ),
                )

        assigned: set[UUID] = set()
        trips_by_shift: dict[UUID, list[TripPlan]] = {shift.id: [] for shift in shifts}
        shift_by_id = {shift.id: shift for shift in shifts}
        if latest_plan is not None:
            for cycle in sorted(
                latest_plan.cycles,
                key=lambda item: (item.driver_shift_id, item.sequence, item.id),
            ):
                shift = shift_by_id.get(cycle.driver_shift_id)
                if shift is None:
                    continue
                deliveries: list[SlotTask] = []
                pickups: list[SlotTask] = []
                for stop in sorted(cycle.stops, key=lambda item: item.sequence):
                    if stop.task_id is None:
                        continue
                    slot_task = tasks_by_uuid.get(stop.task_id)
                    if slot_task is None:
                        continue
                    assigned.add(stop.task_id)
                    fixed = replace(
                        slot_task,
                        assigned_driver_id=str(shift.driver_id),
                        assigned_trip_id=str(cycle.id),
                        order_locked=cycle.locked or stop.locked,
                        time_locked=stop.locked,
                        locked_service_start=(stop.planned_arrival if stop.locked else None),
                    )
                    if fixed.task_type is SlotTaskType.DELIVERY:
                        deliveries.append(fixed)
                    else:
                        pickups.append(fixed)
                if deliveries or pickups:
                    trips_by_shift[shift.id].append(
                        TripPlan(
                            id=str(cycle.id),
                            deliveries=tuple(deliveries),
                            pickups=tuple(pickups),
                            trip_locked=cycle.locked,
                        )
                    )

        unassigned_deliveries = [
            task
            for task_id, task in tasks_by_uuid.items()
            if task_id not in assigned and task.task_type is SlotTaskType.DELIVERY
        ]
        pickup_pool = [
            task
            for task_id, task in tasks_by_uuid.items()
            if task_id not in assigned and task.task_type is SlotTaskType.PICKUP
        ]
        for hold in holds:
            policy = hold_policies.get(hold.id)
            if policy is not None and policy.forbidden is not None:
                continue
            snapshot = hold.request_snapshot
            slot_start = time.fromisoformat(str(snapshot["slot_start"]))
            slot_end = time.fromisoformat(str(snapshot["slot_end"]))
            part_quantities = tuple(
                int(value) for value in hold.candidate_snapshot.get("part_quantities", ())
            )
            cabin_count = int(snapshot["cabin_count"])
            if not part_quantities or sum(part_quantities) != cabin_count:
                # Old JSON holds have no part list. Reserving conservative solo
                # trips cannot oversell capacity while those short-lived rows expire.
                part_quantities = (1,) * cabin_count
            unassigned_deliveries.extend(
                SlotTask(
                    id=f"hold:{hold.id}:part:{part_number}",
                    task_type=SlotTaskType.DELIVERY,
                    point=GeoPoint(float(snapshot["longitude"]), float(snapshot["latitude"])),
                    quantity=quantity,
                    window=TimeWindow(
                        datetime.combine(planning_date, slot_start, tzinfo=zone),
                        datetime.combine(planning_date, slot_end, tzinfo=zone),
                    ),
                    service_minutes=int(
                        snapshot.get("service_duration_minutes")
                        or configuration.delivery_service_minutes
                    ),
                    priority=20_000,
                    address=str(snapshot["address"]),
                    mandatory=True,
                    trailer_access_allowed=(
                        int(snapshot["site_cabin_capacity"]) == 2
                        and (policy is None or policy.no_trailer is None)
                    ),
                    assigned_driver_id=str(hold.candidate_snapshot["driver_id"]),
                )
                for part_number, quantity in enumerate(part_quantities, start=1)
            )

        drivers = tuple(
            DriverPlan(
                driver_id=str(shift.driver_id),
                shift_id=str(shift.id),
                vehicle_id=str(shift.vehicle_id),
                shift_start=max(
                    datetime.combine(planning_date, shift.start_time, tzinfo=zone),
                    datetime.combine(planning_date, configuration.driver_day_start, tzinfo=zone),
                ),
                shift_end=min(
                    datetime.combine(planning_date, shift.end_time, tzinfo=zone),
                    datetime.combine(planning_date, configuration.hard_finish, tzinfo=zone),
                ),
                vehicle_capacity=effective_vehicle_cabin_capacity(
                    shift.vehicle, resource_restrictions.trailer_ids,
                ),
                has_trailer=vehicle_has_available_trailer(
                    shift.vehicle, resource_restrictions.trailer_ids,
                ),
                trips=tuple(trips_by_shift[shift.id]),
                resource_origin_warehouse_id=str(warehouse.external_warehouse_id),
                available_from=max(
                    datetime.combine(planning_date, shift.start_time, tzinfo=zone),
                    datetime.combine(
                        planning_date,
                        configuration.driver_day_start,
                        tzinfo=zone,
                    ),
                ),
                external_worker_id=(
                    str(shift.driver.external_worker_id)
                    if shift.driver.external_worker_id is not None
                    else None
                ),
            )
            for shift in shifts
        )
        equipment = {
            str(shift.vehicle.id): vehicle_equipment_snapshot(
                shift.vehicle,
                warehouse.settings,
                resource_restrictions.trailer_ids,
            )
            for shift in shifts
        }
        return (
            DayPlan(
                planning_date=planning_date,
                drivers=drivers,
                unassigned_deliveries=tuple(unassigned_deliveries),
                pickup_pool=tuple(pickup_pool),
                accepting_requests=True,
            ),
            equipment,
        )

    @staticmethod
    async def _classify_policy_points_in_batches(
        session: AsyncSession,
        points: tuple[tuple[UUID, UUID, float, float], ...],
    ) -> dict[UUID, PolicyZoneClassification]:
        """Classify a day snapshot in bounded bulk statements instead of N+1 queries."""

        classifications: dict[UUID, PolicyZoneClassification] = {}
        for offset in range(0, len(points), _POLICY_CLASSIFICATION_BATCH_SIZE):
            classifications.update(
                await classify_policy_points(
                    session,
                    points[offset : offset + _POLICY_CLASSIFICATION_BATCH_SIZE],
                )
            )
        return classifications

    async def _activate_support_resources(
        self,
        context: SlotPlanningContext,
        provider: CachedTruckTravelTimeProvider,
    ) -> SlotPlanningContext:
        """Adapt routed support facts to planner drivers without changing their base."""

        facts = context.support_facts
        local_drivers = context.day_plan.drivers
        if facts is None:
            base_reasons = (
                (PlanningReason.NO_LOCAL_DRIVER,) if not local_drivers else ()
            )
            return replace(
                context,
                day_plan=replace(context.day_plan, availability_reasons=base_reasons),
            )
        local_identities = {
            str(identity.worker_id): identity
            for identity in facts.local_identities
            if identity.employment_type == "STAFF"
        }
        eligible_local_drivers: list[DriverPlan] = []
        orphaned_deliveries: list[SlotTask] = []
        orphaned_pickups: list[SlotTask] = []
        for driver in local_drivers:
            if driver.external_worker_id is None:
                eligible_local_drivers.append(driver)
                continue
            identity = local_identities.get(driver.external_worker_id)
            if identity is None:
                orphaned_deliveries.extend(
                    task for trip in driver.trips for task in trip.deliveries
                )
                orphaned_pickups.extend(
                    task for trip in driver.trips for task in trip.pickups
                )
                continue
            identity_start = identity.available_from or driver.shift_start
            if identity.availability_kind == "INCOMING":
                identity_start += timedelta(
                    minutes=(
                        context.configuration.unload_per_cabin_minutes
                        + context.configuration.turnaround_minutes
                        + context.configuration.load_one_minutes
                    )
                )
            shift_start = max(driver.shift_start, identity_start)
            shift_end = min(
                driver.shift_end,
                identity.available_until or driver.shift_end,
            )
            if shift_start >= shift_end:
                orphaned_deliveries.extend(
                    task for trip in driver.trips for task in trip.deliveries
                )
                orphaned_pickups.extend(
                    task for trip in driver.trips for task in trip.pickups
                )
                continue
            reason_codes: tuple[PlanningReason, ...] = ()
            if identity.availability_kind == "INCOMING":
                reason_codes = (PlanningReason.SLOT_AFTER_RESOURCE_ARRIVAL,)
            eligible_local_drivers.append(
                replace(
                    driver,
                    shift_start=shift_start,
                    shift_end=shift_end,
                    available_from=shift_start,
                    employment_type=identity.employment_type,
                    availability_kind=identity.availability_kind,
                    reason_codes=reason_codes,
                )
            )
        local_drivers = tuple(eligible_local_drivers)
        resolution = await route_support_resource_facts(
            facts,
            context.configuration,
            provider,
        )
        support_drivers = tuple(
            DriverPlan(
                driver_id=str(item.fact.shift.driver_id),
                shift_id=str(item.fact.shift.id),
                vehicle_id=str(item.fact.shift.vehicle_id),
                shift_start=item.available_at_served,
                shift_end=item.latest_served_finish,
                vehicle_capacity=effective_vehicle_cabin_capacity(
                    item.fact.shift.vehicle, context.resource_restrictions.trailer_ids,
                ),
                has_trailer=vehicle_has_available_trailer(
                    item.fact.shift.vehicle, context.resource_restrictions.trailer_ids,
                ),
                resource_origin_warehouse_id=str(
                    item.fact.support_warehouse.external_warehouse_id
                ),
                available_from=item.available_at_served,
                employment_type=item.fact.identity.employment_type,
                availability_kind=item.fact.identity.availability_kind,
                support_link_id=str(item.fact.link.support_link_id),
                return_required=True,
                support_priority=item.fact.link.priority,
                positioning_travel_minutes=(
                    item.inbound_travel_minutes + item.return_travel_minutes
                ),
                positioning_distance_meters=item.positioning_distance_meters,
                reason_codes=item.reason_codes,
            )
            for item in resolution.candidates
        )
        resolution_reasons = list(resolution.reasons)
        if not local_drivers:
            resolution_reasons.append(PlanningReason.NO_LOCAL_DRIVER)
        return replace(
            context,
            day_plan=replace(
                context.day_plan,
                drivers=(*local_drivers, *support_drivers),
                unassigned_deliveries=(
                    *context.day_plan.unassigned_deliveries,
                    *orphaned_deliveries,
                ),
                pickup_pool=(*context.day_plan.pickup_pool, *orphaned_pickups),
                availability_reasons=tuple(dict.fromkeys(resolution_reasons)),
            ),
        )

    async def _synchronize_day_fence(
        self,
        session: AsyncSession,
        context: SlotPlanningContext,
        *,
        for_update: bool = False,
    ) -> SlotDayPlan:
        """Create or advance the monotonic date version when source facts change."""

        statement = select(SlotDayPlan).where(
            SlotDayPlan.warehouse_id == context.warehouse.id,
            SlotDayPlan.date == context.day_plan.planning_date,
        )
        if for_update:
            statement = statement.with_for_update()
        entity = await session.scalar(statement)
        if entity is None:
            entity = SlotDayPlan(
                warehouse_id=context.warehouse.id,
                date=context.day_plan.planning_date,
                version=1,
                source_revision=context.source_revision,
            )
            session.add(entity)
            await session.flush()
            return entity
        if entity.source_revision != context.source_revision:
            entity.version += 1
            entity.source_revision = context.source_revision
            await session.flush()
        return entity

    async def _resolve_isochrone_tariff(
        self,
        context: SlotPlanningContext,
        provider: CachedTruckTravelTimeProvider,
        destination: GeoPoint,
    ) -> SlotPlanningContext:
        """Prove normal reach first, then apply an optional special-price override."""
        travel_seconds: list[int] = []
        seen_vehicles: set[str] = set()
        for driver in context.day_plan.drivers:
            if driver.vehicle_id in seen_vehicles:
                continue
            seen_vehicles.add(driver.vehicle_id)
            try:
                metric = await provider.travel_time(
                    context.configuration.point,
                    destination,
                    driver.shift_start,
                    VehicleLegState(
                        vehicle_id=driver.vehicle_id,
                        trailer_attached=False,
                        current_load=0,
                        trip_peak_load=1,
                    ),
                )
            except TravelTimeUnavailable:
                continue
            travel_seconds.append(metric.travel_seconds)
        if not travel_seconds:
            return context
        tier = self._isochrone_tier(context.warehouse, min(travel_seconds))
        if tier is None:
            raise ApiError(
                422,
                "DELIVERY_OUTSIDE_ISOCHRONE",
                "Road travel time exceeds the warehouse's maximum configured tariff tier",
            )
        special_price = context.special_price_zone
        if special_price is not None:
            if special_price.delivery_price_rubles is None:
                raise ApiError(
                    422,
                    "POLICY_ZONE_VALUES_INVALID",
                    "A SPECIAL_PRICE policy requires a delivery price",
                )
            price = special_price.delivery_price_rubles
            price_isochrone_minutes = None
        else:
            price = next(
                tariff.price_rubles
                for tariff in context.warehouse.isochrone_tariffs
                if tariff.travel_minutes == tier
            )
            price_isochrone_minutes = tier
        return replace(
            context,
            delivery_price_rubles=price,
            price_isochrone_minutes=price_isochrone_minutes,
        )

    @staticmethod
    def _isochrone_tier(warehouse: Warehouse, travel_seconds: int) -> int | None:
        """Return the first configured inclusive hourly price tier."""

        if travel_seconds < 0:
            raise ValueError("travel_seconds cannot be negative")
        for tariff in warehouse.isochrone_tariffs:
            if travel_seconds <= tariff.travel_minutes * 60:
                return tariff.travel_minutes
        return None

    async def _read_result(
        self,
        context: SlotPlanningContext,
        result: SlotPlanningResult,
        provider: CachedTruckTravelTimeProvider,
    ) -> SlotAvailabilityRead:
        """Serialize planner results and enrich only each slot's winning route."""

        slots: list[CustomerSlotRead] = []
        for evaluation in result.slots:
            best = evaluation.best_candidate
            best_read = (
                await self._candidate_read(result.baseline, best, provider)
                if best is not None
                else None
            )
            slots.append(
                CustomerSlotRead(
                    start=evaluation.window.start.time().replace(tzinfo=None),
                    end=evaluation.window.end.time().replace(tzinfo=None),
                    status=evaluation.status.value,
                    candidate_count=len(evaluation.candidates),
                    best_candidate=best_read,
                    reasons=[reason.value for reason in evaluation.reasons],
                    explanation=list(evaluation.explanation),
                )
            )
        return SlotAvailabilityRead(
            date=result.date,
            plan_version=result.plan_version,
            delivery_price_rubles=context.delivery_price_rubles,
            price_isochrone_minutes=context.price_isochrone_minutes,
            trailer_access_allowed=context.trailer_access_allowed,
            slots=slots,
        )

    async def _candidate_read(
        self,
        baseline: DayPlan,
        candidate: SlotCandidate,
        provider: CachedTruckTravelTimeProvider,
    ) -> SlotBestCandidateRead:
        """Add road geometry only for the winning candidate's affected trip."""

        baseline_driver = next(
            (
                driver
                for driver in baseline.drivers
                if driver.driver_id == candidate.driver_plan.driver_id
            ),
            None,
        )
        baseline_trips = {
            trip.id: trip for trip in baseline_driver.trips
        } if baseline_driver is not None else {}
        candidate_trips = {trip.id: trip for trip in candidate.driver_plan.trips}
        route_before: dict[str, object] | None = None
        route_after: dict[str, object] | None = None
        pickup_candidates = self._pickup_candidates(baseline, candidate)
        try:
            route_before = await self._route_evidence(
                candidate.baseline_scheduled_day.trips,
                baseline_trips,
                candidate,
                provider,
            )
            route_after = await self._route_evidence(
                candidate.scheduled_day.trips,
                candidate_trips,
                candidate,
                provider,
            )
        except Exception:
            # Exact matrix simulation already proved feasibility. Focused route
            # geometry is explanatory and cannot reverse that verdict.
            route_before = None
            route_after = None
        timeline = [
            SlotTimelineStopRead(
                id=stop.id,
                stop_type=stop.stop_type.value,
                task_id=stop.task_id,
                latitude=stop.point.lat,
                longitude=stop.point.lon,
                arrival=stop.arrival,
                service_start=stop.service_start,
                service_end=stop.service_end,
                departure=stop.departure,
                waiting_minutes=stop.waiting_minutes,
                load_before=stop.load_before,
                load_after=stop.load_after,
                service_minutes=stop.service_minutes,
                locked=stop.locked,
            )
            for scheduled_trip in candidate.scheduled_day.trips
            for stop in scheduled_trip.stops
        ]
        return SlotBestCandidateRead(
            driver_id=candidate.driver_plan.driver_id,
            shift_id=candidate.driver_plan.shift_id,
            vehicle_id=candidate.driver_plan.vehicle_id,
            resource_origin_warehouse_id=(
                UUID(origin_warehouse_id)
                if (
                    origin_warehouse_id := getattr(
                        candidate.driver_plan,
                        "resource_origin_warehouse_id",
                        None,
                    )
                )
                else None
            ),
            available_from=(
                getattr(candidate.driver_plan, "available_from", None)
                or getattr(
                    candidate.driver_plan,
                    "shift_start",
                    candidate.estimated_service_start,
                )
            ),
            employment_type=getattr(candidate.driver_plan, "employment_type", "STAFF"),
            availability_kind=getattr(candidate.driver_plan, "availability_kind", "HOME"),
            support_link_id=(
                UUID(support_link_id)
                if (
                    support_link_id := getattr(
                        candidate.driver_plan,
                        "support_link_id",
                        None,
                    )
                )
                else None
            ),
            return_required=getattr(candidate.driver_plan, "return_required", False),
            reason_codes=[
                reason.value
                for reason in getattr(candidate.driver_plan, "reason_codes", ())
            ],
            trip_id=candidate.trip_id,
            insert_after_stop_id=candidate.insert_after_stop_id,
            insert_before_stop_id=candidate.insert_before_stop_id,
            estimated_service_start=candidate.estimated_service_start,
            estimated_finish=candidate.estimated_finish,
            warehouse_return_time=candidate.warehouse_return_time,
            minimum_slack_minutes=candidate.minimum_slack_minutes,
            incremental_travel_minutes=candidate.incremental_travel_minutes,
            incremental_distance_meters=candidate.incremental_distance_meters,
            waiting_minutes=candidate.waiting_minutes,
            pickup_count=candidate.pickup_count,
            affected_stops=list(candidate.affected_stop_ids),
            timeline=timeline,
            route_before=route_before,
            route_after=route_after,
            pickup_candidates_geojson=pickup_candidates,
        )

    async def _route_evidence(
        self,
        scheduled_trips: tuple[ScheduledTrip, ...],
        source_trips: Mapping[str, TripPlan],
        candidate: SlotCandidate,
        provider: CachedTruckTravelTimeProvider,
    ) -> dict[str, object] | None:
        """Build best-candidate road geometry without changing feasibility decisions."""

        features: list[dict[str, object]] = []
        for trip in scheduled_trips:
            if trip.trip_id != candidate.trip_id:
                continue
            source_trip = source_trips.get(trip.trip_id)
            if source_trip is None or len(trip.stops) < 2:
                continue
            peak = max(
                source_trip.outbound_load,
                sum(task.quantity for task in source_trip.pickups),
            )
            state = VehicleLegState(
                vehicle_id=candidate.driver_plan.vehicle_id,
                trailer_attached=peak > 1,
                current_load=peak,
                trip_peak_load=peak,
            )
            geometry = await provider.route_geometry(
                tuple(stop.point for stop in trip.stops),
                trip.stops[0].departure,
                state,
            )
            features.append(
                {
                    "type": "Feature",
                    "geometry": geometry,
                    "properties": {"trip_id": trip.trip_id},
                }
            )
        if len(features) == 1:
            raw_geometry = features[0].get("geometry")
            return dict(raw_geometry) if isinstance(raw_geometry, Mapping) else None
        return {"type": "FeatureCollection", "features": features} if features else None

    @staticmethod
    def _pickup_candidates(
        baseline: DayPlan,
        candidate: SlotCandidate,
    ) -> dict[str, object] | None:
        """Expose real pickup points and whether the winning plan selects or defers them."""

        tasks: dict[str, SlotTask] = {
            task.id: task
            for task in baseline.pickup_pool
            if task.task_type is SlotTaskType.PICKUP
        }
        for driver in (*baseline.drivers, candidate.scheduled_day.driver):
            for trip in driver.trips:
                tasks.update(
                    {
                        task.id: task
                        for task in trip.pickups
                        if task.task_type is SlotTaskType.PICKUP
                    }
                )
        selected_ids = {
            stop.task_id
            for trip in candidate.scheduled_day.trips
            for stop in trip.stops
            if stop.stop_type.value == "PICKUP" and stop.task_id is not None
        }
        deferred_ids = set(candidate.scheduled_day.deferred_pickup_ids)
        features = [
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [task.point.lon, task.point.lat],
                },
                "properties": {
                    "task_id": task.id,
                    "address": task.address,
                    "quantity": task.quantity,
                    "priority": task.priority,
                    "mandatory": task.mandatory,
                    "selection_state": (
                        "SELECTED"
                        if task.id in selected_ids
                        else "DEFERRED"
                        if task.id in deferred_ids
                        else "CANDIDATE"
                    ),
                },
            }
            for task in sorted(tasks.values(), key=lambda item: item.id)
        ]
        return {"type": "FeatureCollection", "features": features} if features else None

    async def _create_confirmed_request(
        self,
        session: AsyncSession,
        context: SlotPlanningContext,
        command: SlotHoldCreate,
        hold: SlotHold,
        candidate: SlotCandidate,
    ) -> LogisticsRequest:
        """Persist one order and the exact vehicle-sized parts selected by the planner."""

        inserted_ids = set(candidate.inserted_task_ids)
        task_quantities = tuple(
            task.quantity
            for trip in candidate.driver_plan.trips
            for task in trip.deliveries
            if task.id in inserted_ids
        )
        if sum(task_quantities) != command.cabin_count:
            raise ApiError(
                409,
                "SLOT_CANDIDATE_INVALID",
                "The recalculated insertion no longer represents the complete order",
            )
        cargo = default_slot_cabin(context.warehouse.settings)
        request = LogisticsRequest(
            warehouse_id=context.warehouse.id,
            source_system="CUSTOMER_SLOT_HOLD",
            external_id=hold.id,
            external_version=0,
            external_payload={
                "site_cabin_capacity": command.site_cabin_capacity,
                "slot_hold_id": str(hold.id),
            },
            type=RequestType.DELIVERY,
            name=command.address,
            address_label=command.address,
            latitude=command.latitude,
            longitude=command.longitude,
            quantity=command.cabin_count,
            cargo_length_mm=cargo.length_mm,
            cargo_width_mm=cargo.width_mm,
            cargo_height_mm=cargo.height_mm,
            cargo_weight_kg=cargo.weight_kg,
            service_minutes=(
                command.service_duration_minutes
                or context.configuration.delivery_service_minutes
            ),
            priority=10_000,
            mandatory=True,
            delivery_price_rubles=hold.delivery_price_rubles,
            price_isochrone_minutes=hold.price_isochrone_minutes,
            status=RequestStatus.READY,
            scheduled_date=command.date,
            split_allowed=len(task_quantities) > 1,
            trailer_access_allowed=(
                context.trailer_access_allowed and command.site_cabin_capacity == 2
            ),
            include_driver_passport_in_notification=False,
            contact_name="",
            contact_phone="",
            notes="",
        )
        session.add(request)
        await session.flush()
        session.add(
            RequestDateOption(
                request_id=request.id,
                date=command.date,
                priority=10_000,
                window_start=command.slot_start,
                window_end=command.slot_end,
                is_hard=True,
                travel_zone_hours=None,
            )
        )
        session.add_all(
            PlanningTask(
                request_id=request.id,
                part_number=part_number,
                quantity=quantity,
                cargo_length_mm=cargo.length_mm,
                cargo_width_mm=cargo.width_mm,
                cargo_height_mm=cargo.height_mm,
                cargo_weight_kg=cargo.weight_kg,
                type=RequestType.DELIVERY,
                latitude=command.latitude,
                longitude=command.longitude,
                service_minutes=(
                    command.service_duration_minutes
                    or context.configuration.delivery_service_minutes
                ),
                priority=10_000,
                mandatory=True,
                status=TaskStatus.READY,
                locked=False,
            )
            for part_number, quantity in enumerate(task_quantities, start=1)
        )
        await session.flush()
        return request

    @staticmethod
    def _latest_plan(
        plans: list[RoutePlan],
        warehouse_id: UUID,
        planning_date: date,
    ) -> RoutePlan | None:
        """Choose the newest confirmed/validated/generated plan for the date."""

        rank = {
            PlanStatus.CONFIRMED: 3,
            PlanStatus.VALIDATED: 2,
            PlanStatus.GENERATED: 1,
            PlanStatus.DRAFT: 0,
        }
        eligible = [
            item
            for item in plans
            if item.warehouse_id == warehouse_id
            and item.date == planning_date
            and item.status != PlanStatus.ARCHIVED
        ]
        return max(
            eligible,
            key=lambda item: (rank.get(PlanStatus(item.status), -1), item.updated_at, item.id),
            default=None,
        )

    @staticmethod
    def _selected_option(
        request: LogisticsRequest,
        planning_date: date,
        zone: ZoneInfo,
    ) -> TimeWindow | None:
        """Select one complete window and normalize it into warehouse-local datetimes."""

        options = [
            item
            for item in request.date_options
            if item.date == planning_date
            and item.window_start is not None
            and item.window_end is not None
        ]
        if not options:
            return None
        selected = min(options, key=lambda item: (not item.is_hard, -item.priority, item.id))
        assert selected.window_start is not None and selected.window_end is not None
        return TimeWindow(
            datetime.combine(planning_date, selected.window_start, tzinfo=zone),
            datetime.combine(planning_date, selected.window_end, tzinfo=zone),
        )

    @staticmethod
    def _source_revision(
        warehouse: Warehouse,
        planning_date: date,
        policy_zones: tuple[WarehousePolicyZone, ...] = (),
        *,
        planning_root_warehouse_id: UUID | None = None,
        planning_day_mode: PlanningDayMode = PlanningDayMode.DELIVERIES_AND_PICKUPS,
        planning_day_mode_version: int = 0,
        resource_restrictions: DayResourceRestrictions = NO_RESOURCE_RESTRICTIONS,
    ) -> str:
        """Hash every durable day fact that can change exact slot feasibility."""

        facts = {
            "warehouse": {
                "id": str(warehouse.id),
                "lat": warehouse.latitude,
                "lon": warehouse.longitude,
                "loading": warehouse.loading_minutes,
                "unloading": warehouse.unloading_minutes,
                "turnaround": warehouse.turnaround_minutes,
                "capacity_generation": warehouse.capacity_generation,
                "isochrone_tariffs": [
                    (tariff.travel_minutes, tariff.price_rubles)
                    for tariff in warehouse.isochrone_tariffs
                ],
                "policy_zones": [
                    (str(zone.id), zone.version, zone.kind)
                    for zone in policy_zones
                ],
            },
            "date": planning_date.isoformat(),
            "resource_incidents": {
                "vehicles": sorted(resource_restrictions.vehicle_ids),
                "shifts": sorted(resource_restrictions.shift_ids),
                "trailers": sorted(resource_restrictions.trailer_ids),
            },
            "planning_day_policy": {
                "root_warehouse_id": str(planning_root_warehouse_id or warehouse.id),
                "mode": planning_day_mode.value,
                "version": planning_day_mode_version,
            },
            "settings": warehouse.settings,
            "shifts": [
                (
                    str(item.id),
                    str(item.driver_id),
                    str(item.vehicle_id),
                    item.date_from.isoformat(),
                    item.date_to.isoformat(),
                    item.start_time.isoformat(),
                    item.end_time.isoformat(),
                    item.active,
                    item.driver.active,
                    item.driver.rwms_assignment_mode,
                    str(item.driver.external_worker_id),
                    item.vehicle.active,
                    item.vehicle.capacity,
                    item.vehicle.can_use_trailer,
                    str(item.vehicle.default_trailer_id),
                )
                for item in sorted(warehouse.shifts, key=lambda value: str(value.id))
                if item.date_from <= planning_date <= item.date_to
            ],
            "requests": [
                (
                    str(item.id),
                    item.updated_at.isoformat(),
                    item.status,
                    item.type,
                    item.scheduled_date.isoformat() if item.scheduled_date else None,
                    item.latitude,
                    item.longitude,
                    item.quantity,
                    item.service_minutes,
                    item.trailer_access_allowed,
                    [
                        (
                            option.date.isoformat(),
                            option.window_start.isoformat() if option.window_start else None,
                            option.window_end.isoformat() if option.window_end else None,
                            option.is_hard,
                        )
                        for option in sorted(item.date_options, key=lambda value: str(value.id))
                    ],
                )
                for item in sorted(warehouse.requests, key=lambda value: str(value.id))
            ],
            "plans": [
                (str(item.id), item.version, item.status, item.updated_at.isoformat())
                for item in sorted(warehouse.plans, key=lambda value: str(value.id))
                if item.warehouse_id == warehouse.id and item.date == planning_date
            ],
        }
        return sha256(
            json.dumps(facts, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode()
        ).hexdigest()

    @staticmethod
    def _candidate_snapshot(candidate: SlotCandidate) -> dict[str, Any]:
        """Persist only immutable recheck facts, never the whole route graph."""

        inserted_ids = set(candidate.inserted_task_ids)
        return {
            "driver_id": candidate.driver_plan.driver_id,
            "shift_id": candidate.driver_plan.shift_id,
            "vehicle_id": candidate.driver_plan.vehicle_id,
            "resource_origin_warehouse_id": candidate.driver_plan.resource_origin_warehouse_id,
            "available_from": (
                candidate.driver_plan.available_from.isoformat()
                if candidate.driver_plan.available_from is not None
                else None
            ),
            "employment_type": candidate.driver_plan.employment_type,
            "availability_kind": candidate.driver_plan.availability_kind,
            "support_link_id": candidate.driver_plan.support_link_id,
            "return_required": candidate.driver_plan.return_required,
            "trip_id": candidate.trip_id,
            "part_quantities": [
                task.quantity
                for trip in candidate.driver_plan.trips
                for task in trip.deliveries
                if task.id in inserted_ids
            ],
            "estimated_service_start": candidate.estimated_service_start.isoformat(),
            "warehouse_return_time": candidate.warehouse_return_time.isoformat(),
        }
