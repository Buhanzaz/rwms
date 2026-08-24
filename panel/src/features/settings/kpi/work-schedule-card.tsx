import { useState, type FormEvent } from "react"
import {
  Add01Icon,
  Delete02Icon,
  FloppyDiskIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  KpiWorkSchedule,
  SaveWorkScheduleInput,
  WarehouseKpiSettings,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  scheduleTimeline,
  validateWorkSchedule,
  type EditableWorkSchedule,
} from "@/features/settings/kpi/domain/kpi-settings"

const WEEKDAYS = [
  { value: 1, label: "Пн" },
  { value: 2, label: "Вт" },
  { value: 3, label: "Ср" },
  { value: 4, label: "Чт" },
  { value: 5, label: "Пт" },
  { value: 6, label: "Сб" },
  { value: 7, label: "Вс" },
] as const

function scheduleDraft(
  pendingSchedule: KpiWorkSchedule | null,
  activeSchedule: KpiWorkSchedule | null,
  today: string
): EditableWorkSchedule {
  const schedule = pendingSchedule ?? activeSchedule
  if (schedule) {
    return {
      effectiveFrom: pendingSchedule
        ? schedule.effectiveFrom
        : today,
      shiftStart: schedule.shiftStart,
      shiftEnd: schedule.shiftEnd,
      daysOff: [...schedule.daysOff].sort((left, right) => left - right),
      breaks: schedule.breaks.map((entry) => ({ ...entry })),
    }
  }
  return {
    effectiveFrom: today,
    shiftStart: "08:00",
    shiftEnd: "17:00",
    daysOff: [6, 7],
    breaks: [],
  }
}

function ScheduleSummary({
  title,
  schedule,
}: {
  title: string
  schedule: KpiWorkSchedule
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
      <Badge variant="outline">{title}</Badge>
      <span>
        с {schedule.effectiveFrom}, {schedule.shiftStart}–{schedule.shiftEnd}
      </span>
    </div>
  )
}

export function WorkScheduleCard({
  settings,
  today,
  saving,
  deleting,
  blocked,
  actionError,
  onSave,
  onDeletePending,
}: {
  settings: WarehouseKpiSettings
  today: string
  saving: boolean
  deleting: boolean
  blocked: boolean
  actionError: string | null
  onSave: (input: SaveWorkScheduleInput) => void
  onDeletePending: () => void
}) {
  const [draft, setDraft] = useState(() =>
    scheduleDraft(settings.pendingSchedule, settings.activeSchedule, today)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const validation = validateWorkSchedule(draft, today)
  const timeline = scheduleTimeline(
    draft.shiftStart,
    draft.shiftEnd,
    draft.breaks
  )
  const disabled = blocked || saving || deleting

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!validation.valid) {
      setValidationError(validation.error)
      return
    }
    setValidationError(null)
    onSave({
      expectedVersion: settings.version,
      ...draft,
      daysOff: [...draft.daysOff].sort((left, right) => left - right),
      breaks: [...draft.breaks].sort((left, right) =>
        left.start.localeCompare(right.start)
      ),
    })
  }

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Рабочий график</CardTitle>
          <CardDescription>
            Общая смена, выходные и отдых выбранного склада. Часовой пояс{" "}
            <strong>{settings.timeZone}</strong>.
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {settings.version}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          {settings.activeSchedule ? (
            <ScheduleSummary
              title="Действует"
              schedule={settings.activeSchedule}
            />
          ) : null}
          {settings.pendingSchedule ? (
            <ScheduleSummary
              title="Ожидает"
              schedule={settings.pendingSchedule}
            />
          ) : null}

          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field data-invalid={validationError !== null || undefined}>
                <FieldLabel htmlFor="kpi-schedule-effective">
                  Дата вступления графика
                </FieldLabel>
                <Input
                  id="kpi-schedule-effective"
                  type="date"
                  min={today}
                  value={draft.effectiveFrom}
                  disabled={disabled}
                  aria-invalid={validationError !== null}
                  onChange={(event) => {
                    setDraft((current) => ({
                      ...current,
                      effectiveFrom: event.target.value,
                    }))
                    setValidationError(null)
                  }}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="kpi-shift-start">Начало смены</FieldLabel>
                <Input
                  id="kpi-shift-start"
                  type="time"
                  value={draft.shiftStart}
                  disabled={disabled}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      shiftStart: event.target.value,
                    }))
                  }
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="kpi-shift-end">Окончание смены</FieldLabel>
                <Input
                  id="kpi-shift-end"
                  type="time"
                  value={draft.shiftEnd}
                  disabled={disabled}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      shiftEnd: event.target.value,
                    }))
                  }
                />
              </Field>
            </div>

            <Field>
              <FieldLabel>Выходные дни</FieldLabel>
              <ToggleGroup
                type="multiple"
                variant="outline"
                value={draft.daysOff.map(String)}
                disabled={disabled}
                className="flex-wrap"
                aria-label="Выходные дни склада"
                onValueChange={(values) =>
                  setDraft((current) => ({
                    ...current,
                    daysOff: values.map(Number),
                  }))
                }
              >
                {WEEKDAYS.map((day) => (
                  <ToggleGroupItem key={day.value} value={String(day.value)}>
                    {day.label}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              <FieldDescription>
                Выбранные дни полностью исключаются из таймеров и KPI.
              </FieldDescription>
            </Field>

            <Field>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <FieldLabel>Интервалы отдыха</FieldLabel>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  onClick={() =>
                    setDraft((current) => ({
                      ...current,
                      breaks: [
                        ...current.breaks,
                        { start: "13:00", end: "14:00" },
                      ],
                    }))
                  }
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить перерыв
                </Button>
              </div>
              {draft.breaks.length === 0 ? (
                <FieldDescription>
                  Перерывы не заданы. Их можно добавить без отдельного окна.
                </FieldDescription>
              ) : (
                <FieldGroup className="gap-3">
                  {draft.breaks.map((entry, index) => (
                    <div
                      key={index}
                      className="grid grid-cols-[1fr_1fr_auto] items-end gap-2"
                    >
                      <Field>
                        <FieldLabel htmlFor={`kpi-break-${index}-start`}>
                          Начало
                        </FieldLabel>
                        <Input
                          id={`kpi-break-${index}-start`}
                          type="time"
                          value={entry.start}
                          disabled={disabled}
                          onChange={(event) =>
                            setDraft((current) => ({
                              ...current,
                              breaks: current.breaks.map((candidate, item) =>
                                item === index
                                  ? { ...candidate, start: event.target.value }
                                  : candidate
                              ),
                            }))
                          }
                        />
                      </Field>
                      <Field>
                        <FieldLabel htmlFor={`kpi-break-${index}-end`}>
                          Окончание
                        </FieldLabel>
                        <Input
                          id={`kpi-break-${index}-end`}
                          type="time"
                          value={entry.end}
                          disabled={disabled}
                          onChange={(event) =>
                            setDraft((current) => ({
                              ...current,
                              breaks: current.breaks.map((candidate, item) =>
                                item === index
                                  ? { ...candidate, end: event.target.value }
                                  : candidate
                              ),
                            }))
                          }
                        />
                      </Field>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        aria-label={`Удалить перерыв ${index + 1}`}
                        disabled={disabled}
                        onClick={() =>
                          setDraft((current) => ({
                            ...current,
                            breaks: current.breaks.filter(
                              (_candidate, item) => item !== index
                            ),
                          }))
                        }
                      >
                        <HugeiconsIcon icon={Delete02Icon} />
                      </Button>
                    </div>
                  ))}
                </FieldGroup>
              )}
              <FieldError>{validationError ?? actionError}</FieldError>
            </Field>
          </FieldGroup>

          <div
            className="flex min-w-0 flex-col gap-2"
            aria-labelledby="kpi-workday-preview-title"
          >
            <span id="kpi-workday-preview-title" className="sr-only">
              Предпросмотр рабочего дня
            </span>
            <div className="flex justify-between gap-3 text-xs text-muted-foreground">
              <span>{draft.shiftStart || "—"}</span>
              <span>{draft.shiftEnd || "—"}</span>
            </div>
            <div
              className="relative mt-6 h-8 min-w-0 overflow-visible rounded-lg border bg-primary/75"
              role="list"
              aria-label="Шкала рабочей смены и перерывов"
            >
              {timeline.map((entry, index) => (
                <span
                  key={index}
                  role="listitem"
                  className="absolute inset-y-0 min-w-px border-x border-background/70 bg-muted/95 shadow-sm"
                  style={{
                    left: `${entry.startPercent}%`,
                    width: `${entry.widthPercent}%`,
                  }}
                  aria-label={`Перерыв ${index + 1}: ${entry.start}–${entry.end}`}
                />
              ))}
              {timeline.map((entry, index) => (
                <span
                  key={`label-${index}`}
                  aria-hidden="true"
                  className="absolute bottom-full mb-1 max-w-[calc(100vw-2rem)] -translate-x-1/2 truncate rounded-md border bg-background/95 px-1.5 py-0.5 text-[10px] leading-none font-medium whitespace-nowrap text-foreground shadow-sm"
                  style={{
                    left: `clamp(2.75rem, ${entry.startPercent + entry.widthPercent / 2}%, calc(100% - 2.75rem))`,
                  }}
                >
                  {entry.start}–{entry.end}
                </span>
              ))}
            </div>
          </div>
        </CardContent>
        <CardFooter className="justify-between gap-3 border-t">
          {settings.pendingSchedule ? (
            <Button
              type="button"
              variant="outline"
              disabled={disabled}
              onClick={onDeletePending}
            >
              <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
              {deleting ? "Удаляем…" : "Удалить будущий график"}
            </Button>
          ) : (
            <span className="text-sm text-muted-foreground">
              При выборе сегодняшней даты график после активации применяется ко
              всему текущему дню склада.
            </span>
          )}
          <Button type="submit" disabled={disabled}>
            <HugeiconsIcon
              icon={saving ? Loading03Icon : FloppyDiskIcon}
              data-icon="inline-start"
              className={saving ? "animate-spin" : undefined}
            />
            {saving ? "Сохраняем…" : "Сохранить график"}
          </Button>
        </CardFooter>
      </Card>
    </form>
  )
}
