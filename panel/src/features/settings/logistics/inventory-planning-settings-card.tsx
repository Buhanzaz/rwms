import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  Cancel01Icon,
  FloppyDiskIcon,
  Loading03Icon,
  Refresh01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  getInventoryPlanningSettings,
  updateInventoryPlanningSettings,
} from "@/features/inventory/adapters/http-inventory-adapter"
import { inventoryPlanningSettingsQueryKey } from "@/features/inventory/api/inventory-api"
import type {
  InventoryPlanningSettings,
  InventoryWeekday,
  UpdateInventoryPlanningSettingsRequest,
} from "@/features/inventory/model/inventory-service"
import { ApiError } from "@/lib/api-client"

const WEEKDAYS: Array<{ value: InventoryWeekday; label: string }> = [
  { value: "MONDAY", label: "Пн" },
  { value: "TUESDAY", label: "Вт" },
  { value: "WEDNESDAY", label: "Ср" },
  { value: "THURSDAY", label: "Чт" },
  { value: "FRIDAY", label: "Пт" },
  { value: "SATURDAY", label: "Сб" },
  { value: "SUNDAY", label: "Вс" },
]

const WEEKDAY_VALUES = new Set<InventoryWeekday>(
  WEEKDAYS.map((weekday) => weekday.value)
)
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

type InventoryPlanningSettingsCardProps = {
  accessToken: string
  warehouseId: string
  warehouseName: string
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function formatHoliday(value: string) {
  const [year, month, day] = value.split("-")
  return `${day}.${month}.${year}`
}

function LoadingCard() {
  return (
    <Card aria-label="Загрузка календаря инвентаризации">
      <CardHeader>
        <CardTitle>Планирование после инвентаризации</CardTitle>
        <CardDescription>
          Загружаем дневные лимиты и рабочий календарь…
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-9 w-full max-w-sm" />
        <Skeleton className="h-9 w-full max-w-sm" />
        <Skeleton className="h-20 w-full" />
      </CardContent>
      <CardFooter>
        <Skeleton className="h-9 w-32" />
      </CardFooter>
    </Card>
  )
}

function QueryErrorCard({
  error,
  retrying,
  onRetry,
}: {
  error: unknown
  retrying: boolean
  onRetry: () => void
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Не удалось загрузить календарь инвентаризации</CardTitle>
        <CardDescription>
          Локальные значения не подставляются: итоговый план строит сервер.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Alert variant="destructive">
          <AlertTitle>Настройки недоступны</AlertTitle>
          <AlertDescription>
            {errorMessage(error, "Inventory-service временно недоступен.")}
          </AlertDescription>
        </Alert>
      </CardContent>
      <CardFooter>
        <Button
          type="button"
          variant="outline"
          disabled={retrying}
          onClick={onRetry}
        >
          <HugeiconsIcon
            icon={retrying ? Loading03Icon : Refresh01Icon}
            data-icon="inline-start"
            className={retrying ? "animate-spin" : undefined}
          />
          {retrying ? "Повторяем…" : "Повторить"}
        </Button>
      </CardFooter>
    </Card>
  )
}

function SettingsForm({
  setting,
  warehouseName,
  saving,
  actionError,
  onSave,
}: {
  setting: InventoryPlanningSettings
  warehouseName: string
  saving: boolean
  actionError: string | null
  onSave: (request: UpdateInventoryPlanningSettingsRequest) => void
}) {
  const [movementCapacity, setMovementCapacity] = useState(
    String(setting.movementDailyCapacity)
  )
  const [repairCapacity, setRepairCapacity] = useState(
    String(setting.repairDailyCapacity)
  )
  const [workingWeekdays, setWorkingWeekdays] = useState<InventoryWeekday[]>(
    setting.workingWeekdays
  )
  const [holidays, setHolidays] = useState(() => [...setting.holidays].sort())
  const [holidayDraft, setHolidayDraft] = useState("")
  const [validationError, setValidationError] = useState<string | null>(null)

  function addHoliday() {
    if (!ISO_DATE_PATTERN.test(holidayDraft)) {
      setValidationError("Выберите праздничную дату в календаре.")
      return
    }
    if (holidays.includes(holidayDraft)) {
      setValidationError("Эта праздничная дата уже добавлена.")
      return
    }
    setHolidays((current) => [...current, holidayDraft].sort())
    setHolidayDraft("")
    setValidationError(null)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const movementDailyCapacity = Number(movementCapacity)
    const repairDailyCapacity = Number(repairCapacity)
    if (
      !Number.isInteger(movementDailyCapacity) ||
      movementDailyCapacity < 1 ||
      movementDailyCapacity > 1000
    ) {
      setValidationError(
        "Лимит перемещений должен быть целым числом от 1 до 1000."
      )
      return
    }
    if (
      !Number.isInteger(repairDailyCapacity) ||
      repairDailyCapacity < 1 ||
      repairDailyCapacity > 1000
    ) {
      setValidationError(
        "Лимит ремонтов должен быть целым числом от 1 до 1000."
      )
      return
    }
    if (workingWeekdays.length === 0) {
      setValidationError("Выберите хотя бы один рабочий день недели.")
      return
    }

    setValidationError(null)
    onSave({
      expectedSettingsRevision: setting.settingsRevision,
      movementDailyCapacity,
      repairDailyCapacity,
      workingWeekdays,
      holidays,
    })
  }

  const visibleError = validationError ?? actionError

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Планирование после инвентаризации</CardTitle>
          <CardDescription>
            Предварительно распределяет бытовки склада «{warehouseName}» по
            датам перемещения и ремонта. Это не меняет фактическую вместимость
            ремонтной зоны и не учитывает дни без задач как простой.
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {setting.settingsRevision}</Badge>
          </CardAction>
        </CardHeader>

        <CardContent className="flex flex-col gap-7">
          <FieldGroup>
            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="inventory-movement-daily-capacity">
                Бытовок на перемещение в день
              </FieldLabel>
              <Input
                id="inventory-movement-daily-capacity"
                type="number"
                min={1}
                max={1000}
                step={1}
                inputMode="numeric"
                value={movementCapacity}
                disabled={saving}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setMovementCapacity(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                Отдельный лимит для предварительной очереди доставки в ремонт.
              </FieldDescription>
            </Field>

            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="inventory-repair-daily-capacity">
                Бытовок на ремонт в день
              </FieldLabel>
              <Input
                id="inventory-repair-daily-capacity"
                type="number"
                min={1}
                max={1000}
                step={1}
                inputMode="numeric"
                value={repairCapacity}
                disabled={saving}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setRepairCapacity(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                Отдельный лимит для предварительной очереди ремонтов.
              </FieldDescription>
            </Field>
          </FieldGroup>

          <FieldSet>
            <FieldLegend variant="label">Рабочие дни недели</FieldLegend>
            <FieldDescription>
              Автоматический план пропускает остальные дни и праздничные даты.
            </FieldDescription>
            <ToggleGroup
              type="multiple"
              variant="outline"
              value={workingWeekdays}
              disabled={saving}
              aria-label="Рабочие дни инвентаризации"
              onValueChange={(values) => {
                setWorkingWeekdays(
                  values.filter((value): value is InventoryWeekday =>
                    WEEKDAY_VALUES.has(value as InventoryWeekday)
                  )
                )
                setValidationError(null)
              }}
            >
              {WEEKDAYS.map((weekday) => (
                <ToggleGroupItem key={weekday.value} value={weekday.value}>
                  {weekday.label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </FieldSet>

          <FieldSet>
            <FieldLegend variant="label">Праздничные выходные</FieldLegend>
            <FieldDescription>
              Выберите дату в календаре и добавьте её. Пустые дни без задач сюда
              добавлять не нужно.
            </FieldDescription>
            <FieldGroup>
              <Field orientation="responsive">
                <FieldLabel htmlFor="inventory-holiday-date">
                  Праздничная дата
                </FieldLabel>
                <Input
                  id="inventory-holiday-date"
                  type="date"
                  value={holidayDraft}
                  disabled={saving}
                  onChange={(event) => {
                    setHolidayDraft(event.target.value)
                    setValidationError(null)
                  }}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={saving || !holidayDraft}
                  onClick={addHoliday}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить дату
                </Button>
              </Field>
            </FieldGroup>
            {holidays.length > 0 ? (
              <div
                className="flex flex-wrap gap-2"
                aria-label="Праздничные даты"
              >
                {holidays.map((holiday) => (
                  <Badge key={holiday} variant="secondary">
                    {formatHoliday(holiday)}
                    <Button
                      type="button"
                      size="icon-xs"
                      variant="ghost"
                      disabled={saving}
                      aria-label={`Удалить праздник ${formatHoliday(holiday)}`}
                      onClick={() =>
                        setHolidays((current) =>
                          current.filter((value) => value !== holiday)
                        )
                      }
                    >
                      <HugeiconsIcon icon={Cancel01Icon} />
                    </Button>
                  </Badge>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                Отдельные праздничные даты не заданы.
              </p>
            )}
          </FieldSet>

          <FieldError>{visibleError}</FieldError>
        </CardContent>

        <CardFooter className="justify-between gap-4 border-t">
          <p className="text-sm text-muted-foreground">
            Новый календарь применяется при следующем построении или пересчёте
            итогового плана.
          </p>
          <Button type="submit" disabled={saving}>
            <HugeiconsIcon
              icon={saving ? Loading03Icon : FloppyDiskIcon}
              data-icon="inline-start"
              className={saving ? "animate-spin" : undefined}
            />
            {saving ? "Сохраняем…" : "Сохранить календарь"}
          </Button>
        </CardFooter>
      </Card>
    </form>
  )
}

export function InventoryPlanningSettingsCard({
  accessToken,
  warehouseId,
  warehouseName,
}: InventoryPlanningSettingsCardProps) {
  const queryClient = useQueryClient()
  const queryKey = inventoryPlanningSettingsQueryKey(warehouseId)
  const [actionError, setActionError] = useState<string | null>(null)
  const settingsQuery = useQuery({
    queryKey,
    queryFn: () => getInventoryPlanningSettings(accessToken, warehouseId),
  })
  const saveMutation = useMutation({
    mutationFn: (request: UpdateInventoryPlanningSettingsRequest) =>
      updateInventoryPlanningSettings({
        accessToken,
        warehouseId,
        request,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      toast.success("Календарь итогового плана сохранён.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const message =
          "Настройки уже изменены другим пользователем. Данные обновлены — повторите сохранение."
        setActionError(message)
        toast.error(message)
        await queryClient.invalidateQueries({ queryKey })
        return
      }
      const message = errorMessage(
        error,
        "Не удалось сохранить календарь итогового плана."
      )
      setActionError(message)
      toast.error(message)
    },
  })

  if (settingsQuery.isLoading) return <LoadingCard />
  if (settingsQuery.isError || !settingsQuery.data) {
    return (
      <QueryErrorCard
        error={
          settingsQuery.error ??
          new Error("Inventory-service не вернул настройки планирования.")
        }
        retrying={settingsQuery.isFetching}
        onRetry={() => void settingsQuery.refetch()}
      />
    )
  }

  return (
    <SettingsForm
      key={`${warehouseId}:${settingsQuery.data.settingsRevision}`}
      setting={settingsQuery.data}
      warehouseName={warehouseName}
      saving={saveMutation.isPending}
      actionError={actionError}
      onSave={(request) => {
        setActionError(null)
        saveMutation.mutate(request)
      }}
    />
  )
}
