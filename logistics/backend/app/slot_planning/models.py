"""Pure immutable models for dynamic customer delivery-slot planning."""

# ruff: noqa: RUF001 -- Russian customer-facing explanations intentionally use Cyrillic.

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time
from enum import StrEnum

from app.routing import GeoPoint


class SlotTaskType(StrEnum):
    """Physical load transition performed at a customer address."""

    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"


class TimelineStopType(StrEnum):
    """Operator-visible operation in a simulated driver timeline."""

    WAREHOUSE_LOAD = "WAREHOUSE_LOAD"
    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"
    WAREHOUSE_UNLOAD = "WAREHOUSE_UNLOAD"
    WAREHOUSE_FINISH = "WAREHOUSE_FINISH"


class SlotAvailabilityStatus(StrEnum):
    """Public availability state for one standard customer window."""

    AVAILABLE = "AVAILABLE"
    UNAVAILABLE = "UNAVAILABLE"


class PlanningReason(StrEnum):
    """Stable machine-readable reason why a candidate schedule is infeasible."""

    DELIVERY_WINDOW_MISSED = "DELIVERY_WINDOW_MISSED"
    SHIFT_END_EXCEEDED = "SHIFT_END_EXCEEDED"
    VEHICLE_CAPACITY_EXCEEDED = "VEHICLE_CAPACITY_EXCEEDED"
    NO_COMPATIBLE_VEHICLE = "NO_COMPATIBLE_VEHICLE"
    NO_FREE_DRIVER = "NO_FREE_DRIVER"
    TRUCK_ROUTE_NOT_FOUND = "TRUCK_ROUTE_NOT_FOUND"
    NO_FREE_TRIP_CAPACITY = "NO_FREE_TRIP_CAPACITY"
    WAREHOUSE_TURNAROUND_TOO_LONG = "WAREHOUSE_TURNAROUND_TOO_LONG"
    NEXT_TRIP_AT_RISK = "NEXT_TRIP_AT_RISK"
    LOCKED_STOP_CONFLICT = "LOCKED_STOP_CONFLICT"
    SLOT_ALREADY_HELD = "SLOT_ALREADY_HELD"
    PICKUP_DEFERRED = "PICKUP_DEFERRED"
    TRAILER_ACCESS_NOT_ALLOWED = "TRAILER_ACCESS_NOT_ALLOWED"
    INVALID_DAY_PLAN = "INVALID_DAY_PLAN"
    NO_LOCAL_DRIVER = "NO_LOCAL_DRIVER"
    SUPPORT_DRIVER_AVAILABLE = "SUPPORT_DRIVER_AVAILABLE"
    VEHICLE_CAPACITY_ONE_CABIN = "VEHICLE_CAPACITY_ONE_CABIN"
    TRAILER_REQUIRED = "TRAILER_REQUIRED"
    SLOT_AFTER_RESOURCE_ARRIVAL = "SLOT_AFTER_RESOURCE_ARRIVAL"
    CONTRACTOR_REQUIRED = "CONTRACTOR_REQUIRED"
    CONTRACTOR_CONFIRMED = "CONTRACTOR_CONFIRMED"
    SHIFT_LIMIT_EXCEEDED = "SHIFT_LIMIT_EXCEEDED"


REASON_MESSAGES_RU: dict[PlanningReason, str] = {
    PlanningReason.DELIVERY_WINDOW_MISSED: "Начало разгрузки выходит за обещанный интервал.",
    PlanningReason.SHIFT_END_EXCEEDED: (
        "Водитель не успевает завершить складские операции до конца смены."
    ),
    PlanningReason.VEHICLE_CAPACITY_EXCEEDED: "Вместимость машины для этой ходки исчерпана.",
    PlanningReason.NO_COMPATIBLE_VEHICLE: "Нет машины с подходящей вместимостью и конфигурацией.",
    PlanningReason.NO_FREE_DRIVER: "Нет водителя, чей полный план дня допускает доставку.",
    PlanningReason.TRUCK_ROUTE_NOT_FOUND: "Грузовой маршрут для выбранной конфигурации не найден.",
    PlanningReason.NO_FREE_TRIP_CAPACITY: "Доставку нельзя добавить в существующие ходки.",
    PlanningReason.WAREHOUSE_TURNAROUND_TOO_LONG: (
        "Складская выгрузка и новая загрузка не помещаются между ходками."
    ),
    PlanningReason.NEXT_TRIP_AT_RISK: "Изменение создаёт риск опоздания следующей ходки.",
    PlanningReason.LOCKED_STOP_CONFLICT: "Изменение конфликтует с зафиксированной остановкой.",
    PlanningReason.SLOT_ALREADY_HELD: "Последний вариант временно удерживается другим клиентом.",
    PlanningReason.PICKUP_DEFERRED: "Вывоз перенесён, чтобы не рисковать доставками.",
    PlanningReason.TRAILER_ACCESS_NOT_ALLOWED: "К адресу нельзя безопасно приехать с прицепом.",
    PlanningReason.INVALID_DAY_PLAN: "Существующий план дня уже нарушает обязательные ограничения.",
    PlanningReason.NO_LOCAL_DRIVER: "На складе нет доступного локального водителя.",
    PlanningReason.SUPPORT_DRIVER_AVAILABLE: (
        "Найден выполнимый рейс с ресурсом опорного склада."
    ),
    PlanningReason.VEHICLE_CAPACITY_ONE_CABIN: (
        "Выбранная конфигурация автомобиля вмещает только одну бытовку."
    ),
    PlanningReason.TRAILER_REQUIRED: "Для второй бытовки требуется доступный прицеп.",
    PlanningReason.SLOT_AFTER_RESOURCE_ARRIVAL: (
        "Точный слот возможен только после прибытия ресурса и складских операций."
    ),
    PlanningReason.CONTRACTOR_REQUIRED: (
        "Не найден подходящий штатный ресурс; нужен наёмный водитель."
    ),
    PlanningReason.CONTRACTOR_CONFIRMED: "Используется подтверждённый наёмный водитель.",
    PlanningReason.SHIFT_LIMIT_EXCEEDED: (
        "Дорога и возврат не помещаются в рабочий интервал ресурса."
    ),
}


class TravelTimeUnavailable(RuntimeError):
    """Signal that no exact truck route exists for one directed road leg."""

    def __init__(self, reason: PlanningReason = PlanningReason.TRUCK_ROUTE_NOT_FOUND) -> None:
        super().__init__(reason.value)
        self.reason = reason


@dataclass(frozen=True, slots=True)
class TimeWindow:
    """Time window whose configured semantics require service to start inside it."""

    start: datetime
    end: datetime

    def __post_init__(self) -> None:
        if self.start.tzinfo is None or self.end.tzinfo is None:
            raise ValueError("time-window values must be timezone-aware")
        if self.start >= self.end:
            raise ValueError("time-window start must precede end")


@dataclass(frozen=True, slots=True)
class SlotTask:
    """One delivery or pickup considered by warehouse slot planning."""

    id: str
    task_type: SlotTaskType
    point: GeoPoint
    quantity: int
    window: TimeWindow
    service_minutes: int
    priority: int = 0
    address: str = ""
    mandatory: bool = True
    trailer_access_allowed: bool = True
    assigned_driver_id: str | None = None
    assigned_trip_id: str | None = None
    order_locked: bool = False
    time_locked: bool = False
    locked_service_start: datetime | None = None

    def __post_init__(self) -> None:
        if not 1 <= self.quantity <= 2:
            raise ValueError("task quantity must be one or two cabins")
        if self.service_minutes < 1:
            raise ValueError("task service duration must be positive")
        if self.time_locked and self.locked_service_start is None:
            raise ValueError("time-locked task requires locked_service_start")
        if self.locked_service_start is not None and self.locked_service_start.tzinfo is None:
            raise ValueError("locked_service_start must be timezone-aware")


@dataclass(frozen=True, slots=True)
class TripPlan:
    """One depot-to-depot trip with deliveries followed by return-leg pickups."""

    id: str
    deliveries: tuple[SlotTask, ...]
    pickups: tuple[SlotTask, ...] = ()
    trip_locked: bool = False

    def __post_init__(self) -> None:
        if any(task.task_type is not SlotTaskType.DELIVERY for task in self.deliveries):
            raise ValueError("trip deliveries must contain only delivery tasks")
        if any(task.task_type is not SlotTaskType.PICKUP for task in self.pickups):
            raise ValueError("trip pickups must contain only pickup tasks")

    @property
    def outbound_load(self) -> int:
        """Return cabin count loaded at the depot for this trip."""

        return sum(task.quantity for task in self.deliveries)


@dataclass(frozen=True, slots=True)
class DriverPlan:
    """One driver and vehicle availability snapshot for a local warehouse date."""

    driver_id: str
    shift_id: str
    vehicle_id: str
    shift_start: datetime
    shift_end: datetime
    vehicle_capacity: int
    has_trailer: bool
    trips: tuple[TripPlan, ...] = ()
    resource_origin_warehouse_id: str | None = None
    available_from: datetime | None = None
    employment_type: str = "STAFF"
    availability_kind: str = "HOME"
    support_link_id: str | None = None
    return_required: bool = False
    support_priority: int = 0
    positioning_travel_minutes: int = 0
    positioning_distance_meters: int = 0
    reason_codes: tuple[PlanningReason, ...] = ()
    external_worker_id: str | None = None

    def __post_init__(self) -> None:
        if self.shift_start.tzinfo is None or self.shift_end.tzinfo is None:
            raise ValueError("driver shift timestamps must be timezone-aware")
        if self.shift_start >= self.shift_end:
            raise ValueError("driver shift start must precede end")
        if not 1 <= self.vehicle_capacity <= 2:
            raise ValueError("vehicle capacity must be one or two cabins")
        if self.vehicle_capacity == 2 and not self.has_trailer:
            raise ValueError("two-cabin capacity requires an available trailer")
        if self.available_from is not None and self.available_from.tzinfo is None:
            raise ValueError("available_from must be timezone-aware")
        if self.employment_type not in {"STAFF", "CONTRACTOR"}:
            raise ValueError("unsupported employment_type")
        if self.availability_kind not in {"HOME", "ACTIVE_ASSIGNMENT", "INCOMING"}:
            raise ValueError("unsupported availability_kind")
        if self.support_priority < 0:
            raise ValueError("support_priority cannot be negative")
        if self.positioning_travel_minutes < 0 or self.positioning_distance_meters < 0:
            raise ValueError("positioning metrics cannot be negative")


@dataclass(frozen=True, slots=True)
class DayPlan:
    """Complete existing delivery assignments and unassigned return pickups."""

    planning_date: date
    drivers: tuple[DriverPlan, ...]
    unassigned_deliveries: tuple[SlotTask, ...] = ()
    pickup_pool: tuple[SlotTask, ...] = ()
    version: int = 1
    accepting_requests: bool = False
    availability_reasons: tuple[PlanningReason, ...] = ()


@dataclass(frozen=True, slots=True)
class WarehouseSlotConfiguration:
    """Warehouse-local schedule policy and conservative planning defaults."""

    warehouse_id: str
    point: GeoPoint
    timezone: str
    driver_day_start: time = time(8)
    delivery_day_start: time = time(9)
    delivery_day_end: time = time(18)
    hard_finish: time = time(20)
    customer_slots: tuple[tuple[time, time], ...] = (
        (time(9), time(12)),
        (time(12), time(15)),
        (time(15), time(18)),
    )
    delivery_service_minutes: int = 60
    pickup_service_minutes: int = 60
    load_one_minutes: int = 30
    load_two_minutes: int = 45
    unload_per_cabin_minutes: int = 30
    turnaround_minutes: int = 15
    travel_time_multiplier: float = 1.15
    fixed_travel_buffer_minutes: int = 5
    hold_ttl_minutes: int = 10

    def __post_init__(self) -> None:
        positive = (
            self.delivery_service_minutes,
            self.pickup_service_minutes,
            self.load_one_minutes,
            self.load_two_minutes,
            self.unload_per_cabin_minutes,
            self.hold_ttl_minutes,
        )
        if any(value < 1 for value in positive):
            raise ValueError("warehouse service and hold durations must be positive")
        if self.turnaround_minutes < 0 or self.fixed_travel_buffer_minutes < 0:
            raise ValueError("warehouse and travel buffers cannot be negative")
        if self.travel_time_multiplier < 1:
            raise ValueError("travel_time_multiplier must be at least one")
        if not self.driver_day_start < self.delivery_day_start < self.delivery_day_end:
            raise ValueError("warehouse delivery times are not ordered")
        if self.hard_finish <= self.delivery_day_end:
            raise ValueError("hard finish must be after customer delivery end")
        if len(self.customer_slots) != 3:
            raise ValueError("exactly three customer slots are required")
        previous_end: time | None = None
        for start, end in self.customer_slots:
            if start >= end:
                raise ValueError("customer slot start must precede end")
            if start < self.delivery_day_start or end > self.delivery_day_end:
                raise ValueError("customer slots must stay inside the delivery day")
            if previous_end is not None and start < previous_end:
                raise ValueError("customer slots cannot overlap")
            previous_end = end

    def load_minutes(self, cabin_count: int) -> int:
        """Return configured loading duration for one- or two-cabin departure."""

        if cabin_count == 1:
            return self.load_one_minutes
        if cabin_count == 2:
            return self.load_two_minutes
        if cabin_count == 0:
            return 0
        raise ValueError("only one or two outbound cabins are supported")


@dataclass(frozen=True, slots=True)
class VehicleLegState:
    """Physical configuration required for one directed truck-routing request."""

    vehicle_id: str
    trailer_attached: bool
    current_load: int
    trip_peak_load: int


@dataclass(frozen=True, slots=True)
class RoadMetric:
    """Exact directed truck travel duration and distance for one road leg."""

    travel_seconds: int
    distance_meters: int


@dataclass(frozen=True, slots=True)
class TimelineStop:
    """One fully scheduled operation including waiting and load transition."""

    id: str
    stop_type: TimelineStopType
    point: GeoPoint
    arrival: datetime
    service_start: datetime
    service_end: datetime
    departure: datetime
    waiting_minutes: int
    load_before: int
    load_after: int
    service_minutes: int
    task_id: str | None = None
    locked: bool = False
    infeasibility_reason: PlanningReason | None = None


@dataclass(frozen=True, slots=True)
class ScheduledTrip:
    """One feasible trip timeline and its exact road metrics."""

    trip_id: str
    stops: tuple[TimelineStop, ...]
    travel_seconds: int
    distance_meters: int
    waiting_minutes: int
    minimum_slack_minutes: int
    finish: datetime


@dataclass(frozen=True, slots=True)
class ScheduledDriverDay:
    """A completely resimulated driver day across every depot trip."""

    driver: DriverPlan
    trips: tuple[ScheduledTrip, ...]
    pickup_count: int
    deferred_pickup_ids: tuple[str, ...]

    @property
    def finish(self) -> datetime:
        """Return the final depot-operation timestamp for this driver."""

        return self.trips[-1].finish if self.trips else self.driver.shift_start


@dataclass(frozen=True, slots=True)
class SlotCandidate:
    """One feasible insertion ranked after all hard constraints pass."""

    driver_plan: DriverPlan
    baseline_scheduled_day: ScheduledDriverDay
    scheduled_day: ScheduledDriverDay
    trip_id: str
    insert_after_stop_id: str | None
    insert_before_stop_id: str | None
    new_task_id: str
    inserted_task_ids: tuple[str, ...]
    estimated_service_start: datetime
    estimated_finish: datetime
    warehouse_return_time: datetime
    minimum_slack_minutes: int
    incremental_travel_minutes: int
    incremental_distance_meters: int
    waiting_minutes: int
    pickup_count: int
    affected_stop_ids: tuple[str, ...]
    explanation: tuple[str, ...]

    @property
    def score_key(self) -> tuple[object, ...]:
        """Return the documented lexicographic ranking key for feasible candidates."""

        return (
            -self.minimum_slack_minutes,
            self.incremental_travel_minutes
            + self.driver_plan.positioning_travel_minutes,
            int(self.driver_plan.support_link_id is not None),
            self.driver_plan.support_priority,
            int(self.insert_after_stop_id is None and self.insert_before_stop_id is None),
            -self.pickup_count,
            self.incremental_distance_meters,
            self.waiting_minutes,
            self.warehouse_return_time,
            self.driver_plan.driver_id,
            self.trip_id,
        )


@dataclass(frozen=True, slots=True)
class SlotEvaluation:
    """Availability result for one standard customer delivery window."""

    window: TimeWindow
    status: SlotAvailabilityStatus
    candidates: tuple[SlotCandidate, ...] = ()
    reasons: tuple[PlanningReason, ...] = ()
    explanation: tuple[str, ...] = ()

    @property
    def best_candidate(self) -> SlotCandidate | None:
        """Return the best lexicographically ranked feasible insertion."""

        return min(self.candidates, key=lambda item: item.score_key) if self.candidates else None


@dataclass(frozen=True, slots=True)
class SlotPlanningResult:
    """Three customer-slot evaluations plus the normalized baseline day plan."""

    date: date
    plan_version: int
    slots: tuple[SlotEvaluation, ...]
    baseline: DayPlan


@dataclass(slots=True)
class FailureAccumulator:
    """Mutable bounded reason collector used only during one planning calculation."""

    reasons: set[PlanningReason] = field(default_factory=set)

    def add(self, *reasons: PlanningReason) -> None:
        """Record stable reasons without exposing candidate-search duplicates."""

        self.reasons.update(reasons)
