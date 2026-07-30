import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Refresh01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import {
  getGroupKpi,
  kpiKeys,
  listKpiWorkerGroups,
  type GroupKpiMetric,
  type KpiCoverageStatus,
  type KpiWorkerGroup,
} from "@/features/kpi/api/kpi-api"
import {
  barFillSegments,
  clampKpiValue,
  type KpiBarRange,
} from "@/features/kpi/domain/kpi-bar"
import {
  clampKpiPeriod,
  daysInMonth,
  periodLabel,
  resetKpiPeriod,
  type KpiPeriod,
  type KpiPeriodType,
} from "@/features/kpi/domain/kpi-period"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  getKpiSettings,
  kpiSettingsKeys,
} from "@/features/settings/kpi/api/kpi-settings-api"
import { useWarehouse } from "@/hooks/use-warehouse"

const PERIOD_LABELS: Record<KpiPeriodType, string> = {
  YEAR: "Год",
  QUARTER: "Квартал",
  MONTH: "Месяц",
  DAY: "День",
}

const MONTHS = Array.from({ length: 12 }, (_, index) => ({
  value: index + 1,
  label: new Intl.DateTimeFormat("ru-RU", { month: "long" }).format(
    new Date(Date.UTC(2026, index, 1))
  ),
}))

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function StateCard({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}

function percent(value: number | null) {
  if (value === null) return "—"
  const normalized = Math.round(value * 10) / 10
  return `${normalized.toLocaleString("ru-RU", {
    maximumFractionDigits: 1,
  })}%`
}

function duration(seconds: number) {
  const totalMinutes = Math.round(seconds / 60)
  if (totalMinutes < 60) return `${totalMinutes} мин`
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return minutes === 0 ? `${hours} ч` : `${hours} ч ${minutes} мин`
}

function SegmentedTrack({
  ranges,
  value,
}: {
  ranges: KpiBarRange[]
  value: number | null
}) {
  if (value === null) {
    return (
      <div
        className="h-5 overflow-hidden rounded-full border bg-muted"
        aria-label="KPI бригады: нет данных"
      />
    )
  }

  const clamped = clampKpiValue(value)
  const fill = barFillSegments(ranges, clamped)

  return (
    <div
      className="relative h-7"
      role="img"
      aria-label={`KPI бригады: ${percent(clamped)}`}
    >
      <div className="absolute inset-x-0 top-1 h-5 overflow-hidden rounded-full border bg-muted">
        <div className="flex h-full">
          {ranges.map((range) => (
            <div
              key={`muted-${range.fromPercent}`}
              style={{
                width: `${range.toPercent - range.fromPercent}%`,
                backgroundColor: range.color,
                opacity: 0.22,
              }}
            />
          ))}
        </div>
        {clamped > 0 ? (
          <div
            className="absolute inset-y-0 left-0 overflow-hidden"
            style={{ width: `${clamped}%` }}
          >
            <div
              className="flex h-full"
              style={{ width: `${10000 / clamped}%` }}
            >
              {fill.map((range) => (
                <div
                  key={`fill-${range.fromPercent}`}
                  style={{
                    width: `${range.toPercent - range.fromPercent}%`,
                    backgroundColor: range.color,
                  }}
                />
              ))}
            </div>
          </div>
        ) : null}
      </div>
      <span
        className="absolute top-0 block h-7 w-0.5 -translate-x-1/2 rounded-full bg-foreground"
        style={{ left: `${clamped}%` }}
        aria-hidden="true"
      />
    </div>
  )
}

type DisplayGroup = {
  id: string
  name: string
  active: boolean
  metric: GroupKpiMetric | null
}

function mergeGroups(
  groups: KpiWorkerGroup[],
  metrics: GroupKpiMetric[]
): DisplayGroup[] {
  const metricById = new Map(
    metrics.map((metric) => [metric.workerGroupId, metric])
  )
  const groupById = new Map(groups.map((group) => [group.id, group]))
  const result = groups
    .filter((group) => group.active || metricById.has(group.id))
    .map((group) => ({
      ...group,
      metric: metricById.get(group.id) ?? null,
    }))

  for (const metric of metrics) {
    if (groupById.has(metric.workerGroupId)) continue
    result.push({
      id: metric.workerGroupId,
      name: `Бригада ${metric.workerGroupId.slice(0, 8)}`,
      active: false,
      metric,
    })
  }

  return result.sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

function GroupCard({
  group,
  ranges,
}: {
  group: DisplayGroup
  ranges: KpiBarRange[]
}) {
  const metric = group.metric
  return (
    <Card data-testid={`kpi-group-${group.id}`}>
      <CardHeader>
        <CardTitle>{group.name}</CardTitle>
        <CardDescription>
          {group.active ? "Действующая бригада" : "Исторические данные"}
        </CardDescription>
        <CardAction>
          <Badge
            variant={metric?.kpi === null || !metric ? "outline" : "secondary"}
          >
            {percent(metric?.kpi ?? null)}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <SegmentedTrack ranges={ranges} value={metric?.kpi ?? null} />
        {metric ? (
          <p className="text-sm text-muted-foreground">
            Скорость {percent(metric.speed)} · Занятость{" "}
            {percent(metric.utilization)} · Простой{" "}
            {duration(metric.penalizedIdleSeconds)} · Завершено{" "}
            {metric.completedTaskCount}
          </p>
        ) : (
          <p className="text-sm text-muted-foreground">
            Нет завершений и штрафного простоя за выбранный период.
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function CoverageAlert({
  status,
  dataAvailableFrom,
}: {
  status: KpiCoverageStatus
  dataAvailableFrom: string | null
}) {
  if (status === "COMPLETE") return null
  const copy = {
    PROVISIONAL: {
      title: "Предварительный результат",
      text: "Текущий период ещё продолжается и может быть пересчитан.",
    },
    PARTIAL: {
      title: "Частичное покрытие",
      text: `Достоверная история начинается ${dataAvailableFrom ?? "с даты активации KPI"}.`,
    },
    NO_DATA: {
      title: "Нет данных",
      text: "За выбранный период нет данных KPI.",
    },
  }[status]

  return (
    <Alert>
      <AlertTitle>{copy.title}</AlertTitle>
      <AlertDescription>{copy.text}</AlertDescription>
    </Alert>
  )
}

function PeriodFilters({
  period,
  timeZone,
  dataAvailableFrom,
  onChange,
}: {
  period: KpiPeriod
  timeZone: string
  dataAvailableFrom: string | null
  onChange: (period: KpiPeriod) => void
}) {
  const currentYear = resetKpiPeriod(new Date(), timeZone).year
  const firstYear = dataAvailableFrom
    ? Math.min(currentYear, Number(dataAvailableFrom.slice(0, 4)))
    : currentYear - 5
  const years = Array.from(
    { length: currentYear - firstYear + 1 },
    (_, index) => currentYear - index
  )
  const days = Array.from(
    { length: daysInMonth(period.year, period.month) },
    (_, index) => index + 1
  )
  const usesMonth = period.periodType === "MONTH" || period.periodType === "DAY"

  const update = (next: Partial<KpiPeriod>) =>
    onChange(clampKpiPeriod({ ...period, ...next }))

  return (
    <div className="sticky top-0 z-10 rounded-xl border bg-background/95 p-3 shadow-sm backdrop-blur">
      <FieldGroup className="grid gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(9rem,1fr)_minmax(8rem,1fr)_minmax(10rem,1fr)_minmax(8rem,1fr)_auto]">
        <Field>
          <FieldLabel htmlFor="kpi-period-type">Тип периода</FieldLabel>
          <Select
            value={period.periodType}
            onValueChange={(value) =>
              update({ periodType: value as KpiPeriodType })
            }
          >
            <SelectTrigger id="kpi-period-type" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {Object.entries(PERIOD_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>
                    {label}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field>
          <FieldLabel htmlFor="kpi-period-year">Год</FieldLabel>
          <Select
            value={String(period.year)}
            onValueChange={(value) => update({ year: Number(value) })}
          >
            <SelectTrigger id="kpi-period-year" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {years.map((year) => (
                  <SelectItem key={year} value={String(year)}>
                    {year}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field data-disabled={!usesMonth && period.periodType !== "QUARTER"}>
          <FieldLabel htmlFor="kpi-period-month">
            {period.periodType === "QUARTER" ? "Квартал" : "Месяц"}
          </FieldLabel>
          <Select
            disabled={!usesMonth && period.periodType !== "QUARTER"}
            value={
              period.periodType === "QUARTER"
                ? String(period.quarter)
                : String(period.month)
            }
            onValueChange={(value) =>
              period.periodType === "QUARTER"
                ? update({ quarter: Number(value) })
                : update({ month: Number(value) })
            }
          >
            <SelectTrigger id="kpi-period-month" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {period.periodType === "QUARTER"
                  ? [1, 2, 3, 4].map((quarter) => (
                      <SelectItem key={quarter} value={String(quarter)}>
                        Q{quarter}
                      </SelectItem>
                    ))
                  : MONTHS.map((month) => (
                      <SelectItem key={month.value} value={String(month.value)}>
                        {month.label}
                      </SelectItem>
                    ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field data-disabled={period.periodType !== "DAY"}>
          <FieldLabel htmlFor="kpi-period-day">День</FieldLabel>
          <Select
            disabled={period.periodType !== "DAY"}
            value={String(period.day)}
            onValueChange={(value) => update({ day: Number(value) })}
          >
            <SelectTrigger id="kpi-period-day" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {days.map((day) => (
                  <SelectItem key={day} value={String(day)}>
                    {day}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>

        <Field className="justify-end">
          <span className="sr-only">Действия периода</span>
          <Button
            type="button"
            variant="outline"
            className="w-full xl:w-auto"
            aria-label="Сбросить период"
            onClick={() => onChange(resetKpiPeriod(new Date(), timeZone))}
          >
            <HugeiconsIcon icon={Refresh01Icon} data-icon="inline-start" />
            Сбросить
          </Button>
        </Field>
      </FieldGroup>
    </div>
  )
}

function KpiContent({
  accessToken,
  warehouseId,
  timeZone,
}: {
  accessToken: string
  warehouseId: string
  timeZone: string
}) {
  const [period, setPeriod] = useState(() =>
    resetKpiPeriod(new Date(), timeZone)
  )
  const analyticsQuery = useQuery({
    queryKey: kpiKeys.period(warehouseId, period),
    queryFn: () => getGroupKpi(accessToken, warehouseId, period),
  })
  const groupsQuery = useQuery({
    queryKey: kpiKeys.groups(warehouseId),
    queryFn: () => listKpiWorkerGroups(accessToken, warehouseId),
  })
  const settingsQuery = useQuery({
    queryKey: kpiSettingsKeys.warehouse(warehouseId),
    queryFn: () => getKpiSettings(accessToken, warehouseId),
  })

  const loading =
    analyticsQuery.isLoading || groupsQuery.isLoading || settingsQuery.isLoading
  const queryError =
    analyticsQuery.error ?? groupsQuery.error ?? settingsQuery.error
  const ranges = settingsQuery.data?.palette?.ranges ?? []
  const groups = useMemo(
    () =>
      mergeGroups(groupsQuery.data ?? [], analyticsQuery.data?.groups ?? []),
    [groupsQuery.data, analyticsQuery.data?.groups]
  )

  return (
    <div className="flex min-h-0 flex-col gap-4">
      <PeriodFilters
        period={period}
        timeZone={timeZone}
        dataAvailableFrom={analyticsQuery.data?.dataAvailableFrom ?? null}
        onChange={setPeriod}
      />

      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">KPI бригад</h1>
          <p className="text-sm text-muted-foreground">{periodLabel(period)}</p>
        </div>
        {analyticsQuery.data ? (
          <Badge variant="outline">
            Формула {analyticsQuery.data.formulaVersion}
          </Badge>
        ) : null}
      </div>

      {loading ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {[0, 1].map((item) => (
            <Card key={item}>
              <CardHeader>
                <Skeleton className="h-5 w-36" />
                <Skeleton className="h-4 w-24" />
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <Skeleton className="h-5 w-full rounded-full" />
                <Skeleton className="h-4 w-3/4" />
              </CardContent>
            </Card>
          ))}
        </div>
      ) : queryError ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить KPI</AlertTitle>
          <AlertDescription>
            {errorMessage(queryError, "Сервисы KPI временно недоступны.")}
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {analyticsQuery.data ? (
            <CoverageAlert
              status={analyticsQuery.data.status}
              dataAvailableFrom={analyticsQuery.data.dataAvailableFrom}
            />
          ) : null}

          {ranges.length === 0 ? (
            <Alert>
              <AlertTitle>Палитра не настроена</AlertTitle>
              <AlertDescription>
                Сначала сохраните и активируйте диапазоны KPI выбранного склада.
              </AlertDescription>
            </Alert>
          ) : null}

          <div className="grid gap-4 lg:grid-cols-2">
            {groups.map((group) => (
              <GroupCard key={group.id} group={group} ranges={ranges} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}

export function KpiPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()

  if (!selectedWarehouse) {
    return (
      <StateCard
        title="Склад не выбран"
        description="Выберите склад, чтобы открыть его показатели KPI."
      />
    )
  }
  if (!hasWarehouseAccess(currentUser, selectedWarehouse.id, "VIEW")) {
    return (
      <StateCard
        title="Недостаточно прав"
        description="Для просмотра KPI нужен доступ VIEW к выбранному складу."
      />
    )
  }
  if (!accessToken) {
    return (
      <StateCard
        title="Нет токена доступа"
        description="Повторите вход, чтобы загрузить показатели KPI."
      />
    )
  }

  return (
    <KpiContent
      key={selectedWarehouse.id}
      accessToken={accessToken}
      warehouseId={selectedWarehouse.id}
      timeZone={selectedWarehouse.timeZone}
    />
  )
}
