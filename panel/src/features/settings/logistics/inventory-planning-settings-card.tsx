import { SingleDayPicker } from "@/components/ui/single-day-picker"
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
import { Skeleton } from "@/components/ui/skeleton"
import {
  getInventoryPlanningSettings,
  updateInventoryPlanningSettings,
} from "@/features/inventory/adapters/http-inventory-adapter"
import { inventoryPlanningSettingsQueryKey } from "@/features/inventory/api/inventory-api"
import type {
  InventoryPlanningSettings,
  UpdateInventoryPlanningSettingsRequest,
} from "@/features/inventory/model/inventory-service"
import { ApiError } from "@/lib/api-client"

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
    <Card size="sm" aria-label="Загрузка праздничных выходных">
      <CardHeader>
        <CardTitle>Праздничные выходные</CardTitle>
        <CardDescription>Загружаем праздничные даты объекта…</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
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
    <Card size="sm">
      <CardHeader>
        <CardTitle>Не удалось загрузить праздничные выходные</CardTitle>
        <CardDescription>
          Локальные значения не подставляются: рабочий календарь строит сервер.
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
            aria-hidden="true"
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
    setValidationError(null)
    onSave({
      expectedSettingsRevision: setting.settingsRevision,
      holidays,
    })
  }

  const visibleError = validationError ?? actionError

  return (
    <form onSubmit={submit}>
      <Card size="sm">
        <CardHeader>
          <CardTitle>Праздничные выходные</CardTitle>
          <CardDescription>
            Исключения из общего рабочего календаря объекта «{warehouseName}».
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {setting.settingsRevision}</Badge>
          </CardAction>
        </CardHeader>

        <CardContent className="flex flex-col gap-6">
          <FieldSet>
            <FieldLegend variant="label">Праздничные даты</FieldLegend>
            <FieldDescription>
              Выберите дату и добавьте её. Обычные выходные задаются в рабочем
              графике выше.
            </FieldDescription>
            <FieldGroup>
              <Field orientation="responsive">
                <FieldLabel htmlFor="inventory-holiday-date">
                  Праздничная дата
                </FieldLabel>
                <SingleDayPicker
                  label="Праздничная дата"
                  hideLabel
                  allowClear
                  id="inventory-holiday-date"
                  name="inventory-holiday-date"
                  value={holidayDraft}
                  disabled={saving}
                  onValueChange={(nextValue) => {
                    setHolidayDraft(nextValue)
                    setValidationError(null)
                  }}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={saving || !holidayDraft}
                  onClick={addHoliday}
                >
                  <HugeiconsIcon
                    icon={Add01Icon}
                    data-icon="inline-start"
                    aria-hidden="true"
                  />
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
                      variant="destructive"
                      disabled={saving}
                      aria-label={`Удалить праздник ${formatHoliday(holiday)}`}
                      onClick={() =>
                        setHolidays((current) =>
                          current.filter((value) => value !== holiday)
                        )
                      }
                    >
                      <HugeiconsIcon icon={Cancel01Icon} aria-hidden="true" />
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

        <CardFooter className="flex-col items-stretch justify-between gap-4 border-t sm:flex-row sm:items-center">
          <p className="text-sm text-muted-foreground">
            Изменение календаря делает подготовленный черновик итогового плана
            устаревшим.
          </p>
          <Button type="submit" className="self-end" disabled={saving}>
            <HugeiconsIcon
              icon={saving ? Loading03Icon : FloppyDiskIcon}
              data-icon="inline-start"
              className={saving ? "animate-spin" : undefined}
              aria-hidden="true"
            />
            {saving ? "Сохраняем…" : "Сохранить праздники"}
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
      toast.success("Праздничные выходные сохранены.")
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
        "Не удалось сохранить праздничные выходные."
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
          new Error("Inventory-service не вернул настройки календаря.")
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
