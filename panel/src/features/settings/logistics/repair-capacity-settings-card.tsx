import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  FloppyDiskIcon,
  Loading03Icon,
  Refresh01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

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
import { Skeleton } from "@/components/ui/skeleton"
import {
  getRepairCapacity,
  repairCapacityKeys,
  updateRepairCapacity,
  type RepairCapacitySetting,
  type RepairCapacityUpdate,
} from "@/features/settings/logistics/api/repair-capacity-api"
import { ApiError } from "@/lib/api-client"

type RepairCapacitySettingsCardProps = {
  accessToken: string
  warehouseId: string
  warehouseName: string
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function LoadingCard() {
  return (
    <Card aria-label="Загрузка настроек ремонтных мест">
      <CardHeader>
        <CardTitle>Ремонтные места и автозаполнение</CardTitle>
        <CardDescription>
          Загружаем настройки выбранного склада…
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex flex-col gap-3">
          <Skeleton className="h-4 w-48" />
          <Skeleton className="h-9 w-full max-w-xs" />
          <Skeleton className="h-24 w-full" />
        </div>
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
        <CardTitle>Не удалось загрузить настройки ремонтных мест</CardTitle>
        <CardDescription>
          Настройка не подменяется локальным значением.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(error, "Сервис ремонтов временно недоступен.")}
        </p>
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

function CapacityForm({
  setting,
  warehouseName,
  saving,
  actionError,
  onSave,
}: {
  setting: RepairCapacitySetting
  warehouseName: string
  saving: boolean
  actionError: string | null
  onSave: (input: RepairCapacityUpdate) => void
}) {
  const [repairPlaceCount, setRepairPlaceCount] = useState(
    String(setting.repairPlaceCount)
  )
  const [automaticRefillDelayMinutes, setAutomaticRefillDelayMinutes] =
    useState(String(setting.automaticRefillDelayMinutes))
  const [validationError, setValidationError] = useState<string | null>(null)
  const parsedRepairPlaceCount = Number(repairPlaceCount)
  const validRepairPlaceCount =
    Number.isInteger(parsedRepairPlaceCount) && parsedRepairPlaceCount >= 1
      ? parsedRepairPlaceCount
      : null
  const parsedAutomaticRefillDelayMinutes = Number(automaticRefillDelayMinutes)
  const validAutomaticRefillDelayMinutes =
    Number.isInteger(parsedAutomaticRefillDelayMinutes) &&
    parsedAutomaticRefillDelayMinutes >= 1 &&
    parsedAutomaticRefillDelayMinutes <= 1440
      ? parsedAutomaticRefillDelayMinutes
      : null
  const visibleError = validationError ?? actionError

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (validRepairPlaceCount === null) {
      setValidationError(
        "Укажите положительное целое количество ремонтных мест."
      )
      return
    }

    if (validAutomaticRefillDelayMinutes === null) {
      setValidationError(
        "Укажите задержку автозаполнения от 1 до 1440 минут."
      )
      return
    }

    setValidationError(null)
    onSave({
      expectedVersion: setting.version,
      repairPlaceCount: validRepairPlaceCount,
      automaticRefillDelayMinutes: validAutomaticRefillDelayMinutes,
    })
  }

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Ремонтные места и автозаполнение</CardTitle>
          <CardDescription>
            Фактическая вместимость обычного ремонта и задержка автозаполнения
            текущей очереди на складе «{warehouseName}».
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {setting.version}</Badge>
          </CardAction>
        </CardHeader>

        <CardContent className="flex flex-col gap-7">
          <FieldGroup>
            <Field
              data-invalid={
                validationError !== null || actionError !== null || undefined
              }
            >
              <FieldLabel htmlFor="repair-capacity-count">
                Количество ремонтных мест
              </FieldLabel>
              <Input
                id="repair-capacity-count"
                type="number"
                min={1}
                step={1}
                inputMode="numeric"
                value={repairPlaceCount}
                disabled={saving}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setRepairPlaceCount(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                Одно место одновременно может быть занято одной бытовкой.
                Значение действует только для выбранного склада.
              </FieldDescription>
            </Field>

            <Field
              data-invalid={
                validationError !== null || actionError !== null || undefined
              }
            >
              <FieldLabel htmlFor="repair-capacity-refill-delay">
                Задержка автозаполнения, минут
              </FieldLabel>
              <Input
                id="repair-capacity-refill-delay"
                type="number"
                min={1}
                max={1440}
                step={1}
                inputMode="numeric"
                value={automaticRefillDelayMinutes}
                disabled={saving}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setAutomaticRefillDelayMinutes(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                После ручного освобождения текущей очереди следующее задание
                добавится автоматически не раньше указанного времени. От 1 до
                1440 минут.
              </FieldDescription>
            </Field>
          </FieldGroup>
          <FieldError>{visibleError}</FieldError>
        </CardContent>

        <CardFooter className="justify-between gap-4 border-t">
          <p className="text-sm text-muted-foreground">
            {setting.createdAt === null
              ? "Настройка ещё не сохранялась для выбранного склада."
              : "Настройка сохранена в сервисе ремонтов."}
          </p>
          <Button type="submit" disabled={saving}>
            <HugeiconsIcon
              icon={saving ? Loading03Icon : FloppyDiskIcon}
              data-icon="inline-start"
              className={saving ? "animate-spin" : undefined}
            />
            {saving ? "Сохраняем…" : "Сохранить"}
          </Button>
        </CardFooter>
      </Card>
    </form>
  )
}

export function RepairCapacitySettingsCard({
  accessToken,
  warehouseId,
  warehouseName,
}: RepairCapacitySettingsCardProps) {
  const queryClient = useQueryClient()
  const queryKey = repairCapacityKeys.warehouse(warehouseId)
  const [actionError, setActionError] = useState<string | null>(null)

  const capacityQuery = useQuery({
    queryKey,
    queryFn: () => getRepairCapacity(accessToken, warehouseId),
  })
  const saveMutation = useMutation({
    mutationFn: (input: RepairCapacityUpdate) =>
      updateRepairCapacity(accessToken, warehouseId, input),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      toast.success("Настройки ремонтных мест сохранены.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const message =
          "Настройка уже изменена другим пользователем. Данные обновлены — повторите сохранение."
        setActionError(message)
        toast.error(message)
        await queryClient.invalidateQueries({ queryKey })
        return
      }

      const message = errorMessage(
        error,
        "Не удалось сохранить настройки ремонтных мест."
      )
      setActionError(message)
      toast.error(message)
    },
  })

  if (capacityQuery.isLoading) {
    return <LoadingCard />
  }

  if (capacityQuery.isError) {
    return (
      <QueryErrorCard
        error={capacityQuery.error}
        retrying={capacityQuery.isFetching}
        onRetry={() => void capacityQuery.refetch()}
      />
    )
  }

  if (!capacityQuery.data) {
    return (
      <QueryErrorCard
        error={new Error("Сервис ремонтов не вернул настройку ремонтных мест.")}
        retrying={capacityQuery.isFetching}
        onRetry={() => void capacityQuery.refetch()}
      />
    )
  }

  return (
    <CapacityForm
      key={`${warehouseId}:${capacityQuery.data.version}:${capacityQuery.data.repairPlaceCount}:${capacityQuery.data.automaticRefillDelayMinutes}`}
      setting={capacityQuery.data}
      warehouseName={warehouseName}
      saving={saveMutation.isPending}
      actionError={actionError}
      onSave={(input) => {
        setActionError(null)
        saveMutation.mutate(input)
      }}
    />
  )
}
