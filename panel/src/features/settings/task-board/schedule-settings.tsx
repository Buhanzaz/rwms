import { useEffect, useMemo, useState } from "react"
import {
  Copy01Icon,
  PauseIcon,
  PlayIcon,
  Refresh01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  GroupScheduleDto,
  ScheduleDayDto,
  WorkerGroupDto,
} from "@/features/settings/task-board/model/task-board-settings"
import { ScheduleDayTimeline } from "@/features/settings/task-board/schedule-timeline"
import { isTaskBoardSettingsConflict } from "@/features/settings/task-board/task-board-settings-errors"
import {
  applyScheduleDayChange,
  isValidIanaTimezone,
  scheduleDayLabels,
  validateScheduleDay,
} from "@/features/settings/task-board/schedule-timeline-utils"
import {
  taskBoardMockClient,
  type MockClockSnapshotDto,
  type MockClockSpeed,
} from "@/features/task-board/mock"

const scheduleKeys = {
  schedules: (warehouseId: string) =>
    ["task-board-mock", warehouseId, "schedules"] as const,
  clock: ["task-board-mock", "clock"] as const,
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Операция не выполнена."
}

function currentClockTime(clock: MockClockSnapshotDto, realNow = Date.now()) {
  if (clock.mode === "REAL") return new Date(realNow)
  const simulated = Date.parse(clock.anchorSimulatedAt)
  if (!clock.running) return new Date(simulated)
  const elapsed = Math.max(0, realNow - Date.parse(clock.anchorRealAt))
  return new Date(simulated + elapsed * clock.speed)
}

function toDatetimeLocal(date: Date) {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function cloneDayForTarget(
  source: ScheduleDayDto,
  target: ScheduleDayDto
): ScheduleDayDto {
  return {
    ...target,
    enabled: source.enabled,
    linkedToTemplate: false,
    shiftStartsAt: source.shiftStartsAt,
    shiftEndsAt: source.shiftEndsAt,
    restPeriods: source.restPeriods.map((period, index) => ({
      ...period,
      id: `${period.id}-copy-${target.dayOfWeek}-${index}`,
      version: 0,
    })),
  }
}

function CopyDayDialog({
  schedule,
  sourceDay,
  onClose,
  onApply,
}: {
  schedule: GroupScheduleDto
  sourceDay: ScheduleDayDto | null
  onClose: () => void
  onApply: (schedule: GroupScheduleDto) => void
}) {
  const [targetDays, setTargetDays] = useState<Set<number>>(new Set())
  if (!sourceDay) return null
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            Копировать {scheduleDayLabels[sourceDay.dayOfWeek].toLowerCase()}
          </DialogTitle>
          <DialogDescription>
            Смена и периоды отдыха заменят настройки выбранных дней.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup className="gap-4">
          {schedule.days
            .filter((day) => day.dayOfWeek !== sourceDay.dayOfWeek)
            .map((day) => (
              <Field key={day.dayOfWeek} orientation="horizontal">
                <Checkbox
                  id={`copy-day-${day.dayOfWeek}`}
                  checked={targetDays.has(day.dayOfWeek)}
                  onCheckedChange={(checked) => {
                    setTargetDays((current) => {
                      const next = new Set(current)
                      if (checked === true) next.add(day.dayOfWeek)
                      else next.delete(day.dayOfWeek)
                      return next
                    })
                  }}
                />
                <FieldLabel htmlFor={`copy-day-${day.dayOfWeek}`}>
                  {scheduleDayLabels[day.dayOfWeek]}
                </FieldLabel>
              </Field>
            ))}
        </FieldGroup>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="button"
            disabled={targetDays.size === 0}
            onClick={() => {
              onApply({
                ...schedule,
                days: schedule.days.map((day) =>
                  targetDays.has(day.dayOfWeek)
                    ? cloneDayForTarget(sourceDay, day)
                    : day
                ),
              })
              onClose()
            }}
          >
            Копировать
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CopyGroupDialog({
  open,
  source,
  groups,
  schedules,
  pending,
  onClose,
  onCopy,
}: {
  open: boolean
  source: GroupScheduleDto
  groups: WorkerGroupDto[]
  schedules: GroupScheduleDto[]
  pending: boolean
  onClose: () => void
  onCopy: (targetGroupIds: string[]) => void
}) {
  const [targets, setTargets] = useState<Set<string>>(new Set())
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Копировать график бригады</DialogTitle>
          <DialogDescription>
            Все дни и время возвращения заменят график выбранных бригад.
          </DialogDescription>
        </DialogHeader>
        <FieldGroup className="max-h-80 gap-4 overflow-y-auto py-1">
          {groups
            .filter(
              (group) =>
                group.id !== source.workerGroupId &&
                schedules.some(
                  (schedule) => schedule.workerGroupId === group.id
                )
            )
            .map((group) => (
              <Field key={group.id} orientation="horizontal">
                <Checkbox
                  id={`copy-group-${group.id}`}
                  checked={targets.has(group.id)}
                  onCheckedChange={(checked) => {
                    setTargets((current) => {
                      const next = new Set(current)
                      if (checked === true) next.add(group.id)
                      else next.delete(group.id)
                      return next
                    })
                  }}
                />
                <FieldLabel htmlFor={`copy-group-${group.id}`}>
                  {group.name}
                </FieldLabel>
              </Field>
            ))}
        </FieldGroup>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button
            type="button"
            disabled={pending || targets.size === 0}
            onClick={() => onCopy([...targets])}
          >
            {pending ? "Копируем…" : "Копировать"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function SimulationClock({
  clock,
  onConflict,
}: {
  clock: MockClockSnapshotDto
  onConflict: (message: string) => void
}) {
  const queryClient = useQueryClient()
  const [realNow, setRealNow] = useState(() => Date.now())
  const mutation = useMutation({
    mutationFn: async (
      change:
        | { type: "RESET" }
        | {
            type: "UPDATE"
            mode: "REAL" | "SIMULATION"
            running: boolean
            speed: MockClockSpeed
            anchorSimulatedAt: string
          }
    ) => {
      if (change.type === "RESET") {
        return taskBoardMockClient.resetSimulationClock(clock.version)
      }
      return taskBoardMockClient.updateSimulationClock({
        expectedVersion: clock.version,
        mode: change.mode,
        running: change.running,
        speed: change.speed,
        now: change.anchorSimulatedAt,
      })
    },
    onSuccess: (next) => {
      queryClient.setQueryData(scheduleKeys.clock, next)
    },
    onError: async (error) => {
      if (isTaskBoardSettingsConflict(error)) {
        const text =
          "Демонстрационные часы изменены в другой вкладке. Загружена актуальная версия."
        await queryClient.refetchQueries({ queryKey: scheduleKeys.clock })
        onConflict(text)
        toast.error(text)
        return
      }
      toast.error(errorMessage(error))
    },
  })

  useEffect(() => {
    if (!clock.running) return
    const interval = window.setInterval(() => setRealNow(Date.now()), 250)
    return () => window.clearInterval(interval)
  }, [clock.running])

  const displayed = currentClockTime(clock, realNow)

  function update(
    running: boolean,
    speed = clock.speed,
    date = displayed,
    mode: "REAL" | "SIMULATION" = "SIMULATION"
  ) {
    mutation.mutate({
      type: "UPDATE",
      mode,
      running,
      speed,
      anchorSimulatedAt: date.toISOString(),
    })
  }

  return (
    <Card size="sm">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Демонстрационные часы</CardTitle>
          <Badge variant={clock.mode === "REAL" ? "outline" : "secondary"}>
            {clock.mode === "REAL" ? "Реальное время" : "Симуляция"}
          </Badge>
        </div>
        <CardDescription>
          Ускорьте смену, чтобы проверить предупреждения и автоматические паузы.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <FieldGroup className="grid gap-4 lg:grid-cols-[minmax(14rem,1fr)_auto_auto] lg:items-end">
          <Field>
            <FieldLabel htmlFor="simulation-date">Дата и время</FieldLabel>
            <Input
              id="simulation-date"
              type="datetime-local"
              value={toDatetimeLocal(displayed)}
              disabled={mutation.isPending}
              onChange={(event) => {
                const parsed = new Date(event.target.value)
                if (!Number.isNaN(parsed.getTime()))
                  update(false, clock.speed, parsed)
              }}
            />
          </Field>
          <Field>
            <FieldLabel>Скорость</FieldLabel>
            <ToggleGroup
              type="single"
              variant="outline"
              value={String(clock.speed)}
              disabled={mutation.isPending}
              onValueChange={(value) => {
                if (!value) return
                update(clock.running, Number(value) as MockClockSpeed)
              }}
              aria-label="Скорость демонстрационных часов"
            >
              <ToggleGroupItem value="1">×1</ToggleGroupItem>
              <ToggleGroupItem value="60">×60</ToggleGroupItem>
              <ToggleGroupItem value="300">×300</ToggleGroupItem>
            </ToggleGroup>
          </Field>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => update(!clock.running)}
            >
              <HugeiconsIcon
                icon={clock.running ? PauseIcon : PlayIcon}
                data-icon="inline-start"
              />
              {clock.running ? "Пауза" : "Запустить"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              disabled={mutation.isPending}
              onClick={() => mutation.mutate({ type: "RESET" })}
            >
              <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
              Реальное время
            </Button>
          </div>
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

export function ScheduleSettings({
  warehouseId,
  groups,
}: {
  warehouseId: string
  groups: WorkerGroupDto[]
}) {
  const queryClient = useQueryClient()
  const activeGroups = useMemo(
    () => groups.filter((group) => group.active),
    [groups]
  )
  const [selectedGroupId, setSelectedGroupId] = useState(
    () => activeGroups[0]?.id ?? ""
  )
  const [drafts, setDrafts] = useState<Record<string, GroupScheduleDto>>({})
  const [dirtyGroups, setDirtyGroups] = useState<Set<string>>(new Set())
  const [copyDay, setCopyDay] = useState<ScheduleDayDto | null>(null)
  const [copyGroupsOpen, setCopyGroupsOpen] = useState(false)
  const [conflictNotice, setConflictNotice] = useState<string | null>(null)

  const schedulesQuery = useQuery({
    queryKey: scheduleKeys.schedules(warehouseId),
    queryFn: () => taskBoardMockClient.listSchedules(warehouseId),
  })
  const clockQuery = useQuery({
    queryKey: scheduleKeys.clock,
    queryFn: () => taskBoardMockClient.getSimulationClock(),
  })

  useEffect(
    () =>
      taskBoardMockClient.subscribe(() => {
        void queryClient.invalidateQueries({
          queryKey: ["task-board-mock"],
        })
      }),
    [queryClient]
  )

  const effectiveSelectedGroupId = activeGroups.some(
    (group) => group.id === selectedGroupId
  )
    ? selectedGroupId
    : (activeGroups[0]?.id ?? "")
  const schedules = schedulesQuery.data ?? []
  const hasDirtyDraft = dirtyGroups.has(effectiveSelectedGroupId)
  const persistedSchedule = schedules.find(
    (schedule) => schedule.workerGroupId === effectiveSelectedGroupId
  )
  const schedule = hasDirtyDraft
    ? drafts[effectiveSelectedGroupId]
    : persistedSchedule
  const timezoneValidation =
    schedule && !isValidIanaTimezone(schedule.timezone)
      ? "Укажите часовой пояс IANA, например Europe/Moscow."
      : null
  const validation =
    timezoneValidation ??
    schedule?.days.map(validateScheduleDay).find((value) => value !== null) ??
    null

  function updateDraft(next: GroupScheduleDto) {
    setConflictNotice(null)
    setDrafts((current) => ({ ...current, [next.workerGroupId]: next }))
    setDirtyGroups((current) => new Set(current).add(next.workerGroupId))
  }

  async function recoverScheduleConflict(groupIds: string[], notice: string) {
    const affected = new Set(groupIds)
    setDrafts((current) =>
      Object.fromEntries(
        Object.entries(current).filter(([groupId]) => !affected.has(groupId))
      )
    )
    setDirtyGroups((current) => {
      const next = new Set(current)
      for (const groupId of affected) next.delete(groupId)
      return next
    })
    setCopyDay(null)
    setCopyGroupsOpen(false)
    setConflictNotice(notice)
    await queryClient.refetchQueries({
      queryKey: scheduleKeys.schedules(warehouseId),
    })
    toast.error(notice)
  }

  const saveMutation = useMutation({
    mutationFn: (current: GroupScheduleDto) =>
      taskBoardMockClient.saveSchedule(warehouseId, current.workerGroupId, {
        version: current.version,
        timezone: current.timezone,
        returnGraceMinutes: current.returnGraceMinutes,
        days: current.days,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData<GroupScheduleDto[]>(
        scheduleKeys.schedules(warehouseId),
        (current = []) => [
          ...current.filter(
            (item) => item.workerGroupId !== saved.workerGroupId
          ),
          saved,
        ]
      )
      setDrafts((current) => ({ ...current, [saved.workerGroupId]: saved }))
      setDirtyGroups((current) => {
        const next = new Set(current)
        next.delete(saved.workerGroupId)
        return next
      })
      toast.success("График сохранён.")
    },
    onError: async (error, current) => {
      if (isTaskBoardSettingsConflict(error)) {
        await recoverScheduleConflict(
          [current.workerGroupId],
          "График изменён в другой вкладке. Локальный черновик отброшен, загружена актуальная версия."
        )
        return
      }
      toast.error(errorMessage(error))
    },
  })

  const copyMutation = useMutation({
    mutationFn: (targetGroupIds: string[]) => {
      if (!schedule) throw new Error("Выберите бригаду.")
      return taskBoardMockClient.copySchedule(warehouseId, {
        sourceGroupId: schedule.workerGroupId,
        sourceExpectedVersion: schedule.version,
        targets: targetGroupIds.map((groupId) => {
          const target = schedules.find(
            (item) => item.workerGroupId === groupId
          )
          if (!target) throw new Error("График бригады не найден.")
          return { groupId, expectedVersion: target.version }
        }),
      })
    },
    onSuccess: async () => {
      setCopyGroupsOpen(false)
      await queryClient.invalidateQueries({
        queryKey: scheduleKeys.schedules(warehouseId),
      })
      toast.success("График скопирован.")
    },
    onError: async (error, targetGroupIds) => {
      if (isTaskBoardSettingsConflict(error)) {
        await recoverScheduleConflict(
          [schedule?.workerGroupId ?? "", ...targetGroupIds],
          "График источника или целевой бригады изменён. Копирование отменено, загружены актуальные версии."
        )
        return
      }
      toast.error(errorMessage(error))
    },
  })

  if (schedulesQuery.isLoading || clockQuery.isLoading) {
    return <p className="text-sm text-muted-foreground">Загрузка графиков…</p>
  }
  const queryError = schedulesQuery.error ?? clockQuery.error
  if (queryError) {
    return (
      <Card size="sm">
        <CardContent className="flex flex-col gap-3 text-sm text-destructive">
          <p role="alert">{errorMessage(queryError)}</p>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              void schedulesQuery.refetch()
              void clockQuery.refetch()
            }}
          >
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }
  if (!activeGroups.length || !schedule) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
        Для выбранного склада нет активных бригад с графиком.
      </p>
    )
  }

  const selectedGroup = activeGroups.find(
    (group) => group.id === effectiveSelectedGroupId
  )
  const dirty = hasDirtyDraft
  const templateDay = schedule.days.find((day) => day.dayOfWeek === 1)!

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto pb-2">
      {conflictNotice ? (
        <Card size="sm">
          <CardContent
            role="alert"
            className="flex flex-wrap items-center justify-between gap-3 text-sm"
          >
            <span>{conflictNotice}</span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={async () => {
                await Promise.all([
                  schedulesQuery.refetch(),
                  clockQuery.refetch(),
                ])
                setConflictNotice(null)
              }}
            >
              Обновить ещё раз
            </Button>
          </CardContent>
        </Card>
      ) : null}
      <Card size="sm">
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>График бригады</CardTitle>
            <Badge variant="outline">MOCK</Badge>
            {dirty ? <Badge variant="secondary">Не сохранено</Badge> : null}
          </div>
          <CardDescription>
            Смена, отдых и время возвращения после срочного перемещения.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <FieldGroup className="grid gap-4 lg:grid-cols-[minmax(14rem,1fr)_minmax(12rem,1fr)_12rem]">
            <Field>
              <FieldLabel htmlFor="schedule-group">Бригада</FieldLabel>
              <Select
                value={effectiveSelectedGroupId}
                onValueChange={setSelectedGroupId}
              >
                <SelectTrigger id="schedule-group" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {activeGroups.map((group) => (
                      <SelectItem key={group.id} value={group.id}>
                        {group.name}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <FieldDescription>
                {selectedGroup?.members
                  .filter((member) => member.active)
                  .map((member) => member.workerName)
                  .join(", ") || "Нет участников"}
              </FieldDescription>
            </Field>
            <Field data-invalid={Boolean(timezoneValidation)}>
              <FieldLabel htmlFor="schedule-timezone">Часовой пояс</FieldLabel>
              <Input
                id="schedule-timezone"
                value={schedule.timezone}
                aria-invalid={Boolean(timezoneValidation)}
                onChange={(event) =>
                  updateDraft({ ...schedule, timezone: event.target.value })
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="return-grace">Возвращение, минут</FieldLabel>
              <Input
                id="return-grace"
                type="number"
                min={0}
                max={60}
                value={schedule.returnGraceMinutes}
                onChange={(event) =>
                  updateDraft({
                    ...schedule,
                    returnGraceMinutes: Math.max(
                      0,
                      Math.min(60, Number(event.target.value) || 0)
                    ),
                  })
                }
              />
            </Field>
          </FieldGroup>
          {validation ? (
            <FieldError className="mt-4">{validation}</FieldError>
          ) : null}
        </CardContent>
        <CardFooter className="flex-wrap justify-between gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={dirty}
            onClick={() => setCopyGroupsOpen(true)}
          >
            <HugeiconsIcon icon={Copy01Icon} data-icon="inline-start" />
            Копировать бригаде
          </Button>
          <Button
            type="button"
            disabled={!dirty || Boolean(validation) || saveMutation.isPending}
            onClick={() => saveMutation.mutate(schedule)}
          >
            {saveMutation.isPending ? "Сохраняем…" : "Сохранить график"}
          </Button>
        </CardFooter>
      </Card>

      {clockQuery.data ? (
        <SimulationClock
          clock={clockQuery.data}
          onConflict={setConflictNotice}
        />
      ) : null}

      <div className="flex flex-col gap-3">
        {schedule.days.map((day) => (
          <ScheduleDayTimeline
            key={day.dayOfWeek}
            day={day}
            templateDay={templateDay}
            onChange={(nextDay) =>
              updateDraft(applyScheduleDayChange(schedule, nextDay))
            }
            onCopy={() => setCopyDay(day)}
          />
        ))}
      </div>

      <CopyDayDialog
        key={`copy-day-${copyDay?.dayOfWeek ?? "closed"}`}
        schedule={schedule}
        sourceDay={copyDay}
        onClose={() => setCopyDay(null)}
        onApply={updateDraft}
      />
      <CopyGroupDialog
        key={
          copyGroupsOpen
            ? `copy-groups-open-${schedule.workerGroupId}`
            : "copy-groups-closed"
        }
        open={copyGroupsOpen}
        source={schedule}
        groups={activeGroups}
        schedules={schedules}
        pending={copyMutation.isPending}
        onClose={() => setCopyGroupsOpen(false)}
        onCopy={(targets) => copyMutation.mutate(targets)}
      />
    </div>
  )
}
