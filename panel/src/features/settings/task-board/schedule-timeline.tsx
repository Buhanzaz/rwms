import { useRef, useState, type PointerEvent as ReactPointerEvent } from "react"
import { Add01Icon, Copy01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type {
  RestPeriodDto,
  RestPeriodType,
  ScheduleDayDto,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  cloneLinkedScheduleDay,
  formatSchedulePeriodCount,
  MIN_PERIOD_MINUTES,
  minutesToTime,
  replaceSchedulePeriod,
  scheduleDayLabels,
  schedulePeriodLabels,
  SCHEDULE_STEP_MINUTES,
  timeToMinutes,
  validateScheduleDay,
} from "@/features/settings/task-board/schedule-timeline-utils"
import { cn } from "@/lib/utils"

type DragMode = "MOVE" | "START" | "END"

type DragState = {
  pointerId: number
  mode: DragMode
  startClientX: number
  trackWidth: number
  shiftMinutes: number
  original: RestPeriodDto
}

function snap(value: number) {
  return Math.round(value / SCHEDULE_STEP_MINUTES) * SCHEDULE_STEP_MINUTES
}

function createPeriodId(day: ScheduleDayDto) {
  return `schedule-${day.dayOfWeek}-${Date.now()}-${day.restPeriods.length}`
}

function findOpenPeriod(day: ScheduleDayDto) {
  const shiftStart = timeToMinutes(day.shiftStartsAt)
  const shiftEnd = timeToMinutes(day.shiftEndsAt)
  for (
    let startsAt = shiftStart + 60;
    startsAt + 10 <= shiftEnd;
    startsAt += SCHEDULE_STEP_MINUTES
  ) {
    const candidate: RestPeriodDto = {
      id: createPeriodId(day),
      version: 0,
      type: "SMOKE_BREAK",
      startsAt: minutesToTime(startsAt),
      endsAt: minutesToTime(startsAt + 10),
      warningMinutes: 5,
      autoPause: true,
    }
    const next = { ...day, restPeriods: [...day.restPeriods, candidate] }
    if (!validateScheduleDay(next)) return candidate
  }
  return null
}

function RestPeriodBlock({
  day,
  period,
  trackWidth,
  onChange,
}: {
  day: ScheduleDayDto
  period: RestPeriodDto
  trackWidth: () => number
  onChange: (day: ScheduleDayDto) => void
}) {
  const [open, setOpen] = useState(false)
  const dragRef = useRef<DragState | null>(null)
  const draggedRef = useRef(false)
  const shiftStart = timeToMinutes(day.shiftStartsAt)
  const shiftEnd = timeToMinutes(day.shiftEndsAt)
  const startsAt = timeToMinutes(period.startsAt)
  const endsAt = timeToMinutes(period.endsAt)
  const shiftMinutes = Math.max(1, shiftEnd - shiftStart)
  const left = ((startsAt - shiftStart) / shiftMinutes) * 100
  const width = ((endsAt - startsAt) / shiftMinutes) * 100

  function startDrag(
    event: ReactPointerEvent<HTMLButtonElement>,
    mode: DragMode
  ) {
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)
    draggedRef.current = false
    dragRef.current = {
      pointerId: event.pointerId,
      mode,
      startClientX: event.clientX,
      trackWidth: Math.max(1, trackWidth()),
      shiftMinutes,
      original: period,
    }
  }

  function drag(event: ReactPointerEvent<HTMLButtonElement>) {
    const state = dragRef.current
    if (!state || state.pointerId !== event.pointerId) return
    const delta = snap(
      ((event.clientX - state.startClientX) / state.trackWidth) *
        state.shiftMinutes
    )
    if (delta === 0) return
    draggedRef.current = true
    const originalStart = timeToMinutes(state.original.startsAt)
    const originalEnd = timeToMinutes(state.original.endsAt)
    let nextStart = originalStart
    let nextEnd = originalEnd
    if (state.mode === "MOVE") {
      const duration = originalEnd - originalStart
      nextStart = Math.max(
        shiftStart,
        Math.min(shiftEnd - duration, originalStart + delta)
      )
      nextEnd = nextStart + duration
    } else if (state.mode === "START") {
      nextStart = Math.max(
        shiftStart,
        Math.min(originalEnd - MIN_PERIOD_MINUTES, originalStart + delta)
      )
    } else {
      nextEnd = Math.min(
        shiftEnd,
        Math.max(originalStart + MIN_PERIOD_MINUTES, originalEnd + delta)
      )
    }
    const next = replaceSchedulePeriod(day, period.id, {
      startsAt: minutesToTime(nextStart),
      endsAt: minutesToTime(nextEnd),
    })
    if (next) onChange(next)
  }

  function stopDrag(event: ReactPointerEvent<HTMLButtonElement>) {
    if (dragRef.current?.pointerId === event.pointerId) {
      dragRef.current = null
    }
  }

  function moveBy(delta: number) {
    const duration = endsAt - startsAt
    const nextStart = Math.max(
      shiftStart,
      Math.min(shiftEnd - duration, startsAt + delta)
    )
    const next = replaceSchedulePeriod(day, period.id, {
      startsAt: minutesToTime(nextStart),
      endsAt: minutesToTime(nextStart + duration),
    })
    if (next) onChange(next)
  }

  function resizeBy(edge: "START" | "END", delta: number) {
    const change =
      edge === "START"
        ? {
            startsAt: minutesToTime(
              Math.max(
                shiftStart,
                Math.min(endsAt - MIN_PERIOD_MINUTES, startsAt + delta)
              )
            ),
          }
        : {
            endsAt: minutesToTime(
              Math.min(
                shiftEnd,
                Math.max(startsAt + MIN_PERIOD_MINUTES, endsAt + delta)
              )
            ),
          }
    const next = replaceSchedulePeriod(day, period.id, change)
    if (next) onChange(next)
  }

  const sharedPointerHandlers = {
    onPointerMove: drag,
    onPointerUp: stopDrag,
    onPointerCancel: stopDrag,
  }

  return (
    <div
      className="absolute inset-y-3"
      style={{ left: `${left}%`, width: `${width}%` }}
    >
      <Popover
        open={open}
        onOpenChange={(next) => {
          if (!draggedRef.current) setOpen(next)
          draggedRef.current = false
        }}
      >
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            className={cn(
              "h-full w-full touch-none justify-center overflow-hidden px-1 shadow-xs",
              period.type === "LUNCH"
                ? "border-chart-3 bg-chart-3/20 hover:bg-chart-3/30"
                : "border-chart-2 bg-chart-2/20 hover:bg-chart-2/30"
            )}
            aria-label={`${schedulePeriodLabels[period.type]} ${period.startsAt}–${period.endsAt}. Стрелки перемещают с шагом 5 минут.`}
            onPointerDown={(event) => startDrag(event, "MOVE")}
            onKeyDown={(event) => {
              if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
                event.preventDefault()
                moveBy(
                  event.key === "ArrowLeft"
                    ? -SCHEDULE_STEP_MINUTES
                    : SCHEDULE_STEP_MINUTES
                )
              }
            }}
            {...sharedPointerHandlers}
          >
            <span className="truncate text-xs">
              {schedulePeriodLabels[period.type]} {period.startsAt}–
              {period.endsAt}
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent
          align="center"
          className="w-80"
          aria-label="Период отдыха"
        >
          <PopoverHeader>
            <PopoverTitle>Период отдыха</PopoverTitle>
            <PopoverDescription>
              Точное время и автоматическая остановка заданий.
            </PopoverDescription>
          </PopoverHeader>
          <FieldGroup className="gap-4">
            <Field>
              <FieldLabel htmlFor={`period-type-${period.id}`}>Тип</FieldLabel>
              <Select
                value={period.type}
                onValueChange={(value) => {
                  const next = replaceSchedulePeriod(day, period.id, {
                    type: value as RestPeriodType,
                  })
                  if (next) onChange(next)
                }}
              >
                <SelectTrigger
                  id={`period-type-${period.id}`}
                  className="w-full"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="SMOKE_BREAK">Перекур</SelectItem>
                    <SelectItem value="LUNCH">Обед</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel htmlFor={`period-start-${period.id}`}>
                  Начало
                </FieldLabel>
                <Input
                  id={`period-start-${period.id}`}
                  type="time"
                  step={300}
                  value={period.startsAt}
                  onChange={(event) => {
                    const next = replaceSchedulePeriod(day, period.id, {
                      startsAt: event.target.value,
                    })
                    if (next) onChange(next)
                  }}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor={`period-end-${period.id}`}>
                  Окончание
                </FieldLabel>
                <Input
                  id={`period-end-${period.id}`}
                  type="time"
                  step={300}
                  value={period.endsAt}
                  onChange={(event) => {
                    const next = replaceSchedulePeriod(day, period.id, {
                      endsAt: event.target.value,
                    })
                    if (next) onChange(next)
                  }}
                />
              </Field>
            </div>
            <Field>
              <FieldLabel htmlFor={`period-warning-${period.id}`}>
                Предупредить, минут
              </FieldLabel>
              <Input
                id={`period-warning-${period.id}`}
                type="number"
                min={0}
                max={60}
                value={period.warningMinutes}
                onChange={(event) => {
                  const next = replaceSchedulePeriod(day, period.id, {
                    warningMinutes: Math.max(
                      0,
                      Number(event.target.value) || 0
                    ),
                  })
                  if (next) onChange(next)
                }}
              />
            </Field>
            <Field orientation="horizontal">
              <Checkbox
                id={`period-auto-${period.id}`}
                checked={period.autoPause}
                onCheckedChange={(checked) => {
                  const next = replaceSchedulePeriod(day, period.id, {
                    autoPause: checked === true,
                  })
                  if (next) onChange(next)
                }}
              />
              <FieldLabel htmlFor={`period-auto-${period.id}`}>
                Автоматически ставить задания на паузу
              </FieldLabel>
            </Field>
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                onChange({
                  ...day,
                  linkedToTemplate: false,
                  restPeriods: day.restPeriods.filter(
                    (item) => item.id !== period.id
                  ),
                })
                setOpen(false)
              }}
            >
              <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
              Удалить период
            </Button>
          </FieldGroup>
        </PopoverContent>
      </Popover>

      <Button
        type="button"
        size="icon-sm"
        variant="ghost"
        className="absolute -bottom-3 left-0 size-6 -translate-x-1/2 cursor-ew-resize touch-none p-0 opacity-80"
        aria-label={`Изменить начало: ${schedulePeriodLabels[period.type]}`}
        onPointerDown={(event) => startDrag(event, "START")}
        onKeyDown={(event) => {
          if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault()
            resizeBy(
              "START",
              event.key === "ArrowLeft"
                ? -SCHEDULE_STEP_MINUTES
                : SCHEDULE_STEP_MINUTES
            )
          }
        }}
        {...sharedPointerHandlers}
      >
        <span
          aria-hidden="true"
          className="h-3 w-0.5 rounded-full bg-current"
        />
      </Button>
      <Button
        type="button"
        size="icon-sm"
        variant="ghost"
        className="absolute -top-3 right-0 size-6 translate-x-1/2 cursor-ew-resize touch-none p-0 opacity-80"
        aria-label={`Изменить окончание: ${schedulePeriodLabels[period.type]}`}
        onPointerDown={(event) => startDrag(event, "END")}
        onKeyDown={(event) => {
          if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault()
            resizeBy(
              "END",
              event.key === "ArrowLeft"
                ? -SCHEDULE_STEP_MINUTES
                : SCHEDULE_STEP_MINUTES
            )
          }
        }}
        {...sharedPointerHandlers}
      >
        <span
          aria-hidden="true"
          className="h-3 w-0.5 rounded-full bg-current"
        />
      </Button>
    </div>
  )
}

export function ScheduleDayTimeline({
  day,
  templateDay,
  onChange,
  onCopy,
}: {
  day: ScheduleDayDto
  templateDay: ScheduleDayDto
  onChange: (day: ScheduleDayDto) => void
  onCopy: () => void
}) {
  const trackRef = useRef<HTMLDivElement | null>(null)
  const validation = validateScheduleDay(day)
  const shiftStart = timeToMinutes(day.shiftStartsAt)
  const shiftEnd = timeToMinutes(day.shiftEndsAt)
  const firstHour = Math.floor(shiftStart / 60)
  const lastHour = Math.ceil(shiftEnd / 60)
  const hours = Array.from(
    { length: Math.max(0, lastHour - firstHour + 1) },
    (_, index) => firstHour + index
  )

  function updateShift(change: Partial<ScheduleDayDto>) {
    onChange({ ...day, ...change, linkedToTemplate: false })
  }

  return (
    <Card size="sm" className="overflow-hidden">
      <CardHeader>
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>{scheduleDayLabels[day.dayOfWeek]}</CardTitle>
            <Badge variant={day.enabled ? "secondary" : "outline"}>
              {day.enabled ? "Рабочий день" : "Выходной"}
            </Badge>
            {day.linkedToTemplate ? (
              <Badge variant="outline">Шаблон</Badge>
            ) : null}
          </div>
          <CardDescription>
            {day.enabled
              ? `${day.shiftStartsAt}–${day.shiftEndsAt} · ${formatSchedulePeriodCount(day.restPeriods.length)} отдыха`
              : "Задания по графику не запускаются"}
          </CardDescription>
        </div>
        <CardAction>
          <Button type="button" variant="ghost" size="sm" onClick={onCopy}>
            <HugeiconsIcon icon={Copy01Icon} data-icon="inline-start" />
            Копировать
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <FieldGroup className="grid gap-3 md:grid-cols-[minmax(0,1fr)_9rem_9rem]">
          <Field orientation="horizontal">
            <Checkbox
              id={`day-enabled-${day.dayOfWeek}`}
              checked={day.enabled}
              onCheckedChange={(checked) =>
                onChange({
                  ...day,
                  enabled: checked === true,
                  linkedToTemplate:
                    day.dayOfWeek === templateDay.dayOfWeek ? true : false,
                })
              }
            />
            <FieldLabel htmlFor={`day-enabled-${day.dayOfWeek}`}>
              Рабочий день
            </FieldLabel>
          </Field>
          <Field data-invalid={Boolean(validation)}>
            <FieldLabel htmlFor={`shift-start-${day.dayOfWeek}`}>
              Начало смены
            </FieldLabel>
            <Input
              id={`shift-start-${day.dayOfWeek}`}
              type="time"
              step={300}
              value={day.shiftStartsAt}
              aria-invalid={Boolean(validation)}
              disabled={!day.enabled}
              onChange={(event) =>
                updateShift({ shiftStartsAt: event.target.value })
              }
            />
          </Field>
          <Field data-invalid={Boolean(validation)}>
            <FieldLabel htmlFor={`shift-end-${day.dayOfWeek}`}>
              Конец смены
            </FieldLabel>
            <Input
              id={`shift-end-${day.dayOfWeek}`}
              type="time"
              step={300}
              value={day.shiftEndsAt}
              aria-invalid={Boolean(validation)}
              disabled={!day.enabled}
              onChange={(event) =>
                updateShift({ shiftEndsAt: event.target.value })
              }
            />
          </Field>
        </FieldGroup>

        {day.enabled && Number.isFinite(shiftStart) && shiftEnd > shiftStart ? (
          <div className="overflow-x-auto pb-2">
            <div className="min-w-[960px]">
              <div className="relative h-5 text-xs text-muted-foreground">
                {hours.map((hour) => {
                  const position =
                    ((hour * 60 - shiftStart) / (shiftEnd - shiftStart)) * 100
                  if (position < 0 || position > 100) return null
                  return (
                    <span
                      key={hour}
                      className="absolute -translate-x-1/2 tabular-nums"
                      style={{ left: `${position}%` }}
                    >
                      {String(hour).padStart(2, "0")}:00
                    </span>
                  )
                })}
              </div>
              <div
                ref={trackRef}
                data-track
                className="relative h-16 rounded-lg border bg-muted shadow-inner"
                aria-label={`График: ${scheduleDayLabels[day.dayOfWeek]}`}
              >
                {day.restPeriods.map((period) => (
                  <RestPeriodBlock
                    key={period.id}
                    day={day}
                    period={period}
                    trackWidth={() =>
                      trackRef.current?.getBoundingClientRect().width ?? 1
                    }
                    onChange={onChange}
                  />
                ))}
              </div>
              <FieldDescription className="mt-2">
                Перетаскивайте период целиком или его края. С клавиатуры
                используйте стрелки; шаг — 5 минут.
              </FieldDescription>
            </div>
          </div>
        ) : null}

        {validation ? <FieldError>{validation}</FieldError> : null}

        <div className="flex flex-wrap items-center justify-between gap-2">
          <Field orientation="horizontal" className="w-auto">
            <Checkbox
              id={`day-template-${day.dayOfWeek}`}
              checked={day.linkedToTemplate}
              disabled={day.dayOfWeek === templateDay.dayOfWeek}
              onCheckedChange={(checked) => {
                if (checked !== true) {
                  onChange({ ...day, linkedToTemplate: false })
                  return
                }
                onChange(cloneLinkedScheduleDay(templateDay, day, true))
              }}
            />
            <FieldLabel htmlFor={`day-template-${day.dayOfWeek}`}>
              Следовать шаблону понедельника
            </FieldLabel>
          </Field>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!day.enabled}
            onClick={() => {
              const period = findOpenPeriod(day)
              if (period) {
                onChange({
                  ...day,
                  linkedToTemplate: false,
                  restPeriods: [...day.restPeriods, period],
                })
              }
            }}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить перерыв
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
