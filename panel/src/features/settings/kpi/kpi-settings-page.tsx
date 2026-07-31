import { useRef, useState, type FormEvent } from "react"
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
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { calendarDatePartsInTimeZone } from "@/features/kpi/domain/kpi-period"
import {
  getRepairCapacity,
  repairCapacityKeys,
  updateRepairCapacity,
  type RepairCapacitySetting,
  type RepairCapacityUpdate,
} from "@/features/settings/kpi/api/repair-capacity-api"
import {
  activateKpiSettings,
  deletePendingWorkSchedule,
  getKpiSettings,
  kpiSettingsKeys,
  saveKpiPalette,
  saveWorkSchedule,
  type SaveKpiPaletteInput,
  type SaveWorkScheduleInput,
  type WarehouseKpiSettings,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  getRepairComplexity,
  repairComplexityKeys,
  updateRepairComplexity,
  type RepairComplexityUpdate,
} from "@/features/settings/kpi/api/repair-complexity-api"
import { PaletteSettingsCard } from "@/features/settings/kpi/palette-settings-card"
import { RepairComplexitySettingsCard } from "@/features/settings/kpi/repair-complexity-settings-card"
import { WorkScheduleCard } from "@/features/settings/kpi/work-schedule-card"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

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

function LoadingCard() {
  return (
    <Card aria-label="Загрузка настройки KPI">
      <CardHeader>
        <CardTitle>Количество ремонтных мест</CardTitle>
        <CardDescription>
          Загружаем настройку выбранного склада…
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
        <CardTitle>Не удалось загрузить ремонтные места</CardTitle>
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
  const [validationError, setValidationError] = useState<string | null>(null)
  const parsedValue = Number(repairPlaceCount)
  const validValue =
    Number.isInteger(parsedValue) && parsedValue >= 1 ? parsedValue : null
  const visibleError = validationError ?? actionError

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (validValue === null) {
      setValidationError(
        "Укажите положительное целое количество ремонтных мест."
      )
      return
    }

    setValidationError(null)
    onSave({
      expectedVersion: setting.version,
      repairPlaceCount: validValue,
    })
  }

  return (
    <form onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Количество ремонтных мест</CardTitle>
          <CardDescription>
            Фактическая вместимость обычного ремонта на складе «{warehouseName}
            ».
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
              <FieldError>{visibleError}</FieldError>
            </Field>
          </FieldGroup>
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

function RepairCapacityContent({
  accessToken,
  warehouseId,
  warehouseName,
}: {
  accessToken: string
  warehouseId: string
  warehouseName: string
}) {
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
      toast.success("Количество ремонтных мест сохранено.")
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
        "Не удалось сохранить количество ремонтных мест."
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
      key={`${warehouseId}:${capacityQuery.data.version}:${capacityQuery.data.repairPlaceCount}`}
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

function RepairComplexityContent({
  accessToken,
  warehouseId,
}: {
  accessToken: string
  warehouseId: string
}) {
  const queryClient = useQueryClient()
  const queryKey = repairComplexityKeys.warehouse(warehouseId)
  const [actionError, setActionError] = useState<string | null>(null)
  const settingQuery = useQuery({
    queryKey,
    queryFn: () => getRepairComplexity(accessToken, warehouseId),
  })
  const saveMutation = useMutation({
    mutationFn: (input: RepairComplexityUpdate) =>
      updateRepairComplexity(accessToken, warehouseId, input),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      toast.success("Границы сложности ремонта сохранены.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const message =
          "Границы уже изменены другим пользователем. Данные обновлены — повторите сохранение."
        setActionError(message)
        toast.error(message)
        await queryClient.invalidateQueries({ queryKey })
        return
      }
      const message = errorMessage(
        error,
        "Не удалось сохранить границы сложности ремонта."
      )
      setActionError(message)
      toast.error(message)
    },
  })

  if (settingQuery.isLoading) {
    return (
      <Card aria-label="Загрузка границ сложности ремонта">
        <CardHeader>
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-4 w-3/4" />
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Skeleton className="h-9 w-64" />
          <Skeleton className="h-28 w-full" />
        </CardContent>
      </Card>
    )
  }

  if (settingQuery.isError || !settingQuery.data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Не удалось загрузить сложность ремонта</CardTitle>
          <CardDescription>
            Границы не подменяются настройками доски задач.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(
              settingQuery.error,
              "Сервис ремонтов временно недоступен."
            )}
          </p>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            variant="outline"
            disabled={settingQuery.isFetching}
            onClick={() => void settingQuery.refetch()}
          >
            <HugeiconsIcon
              icon={
                settingQuery.isFetching ? Loading03Icon : Refresh01Icon
              }
              data-icon="inline-start"
              className={
                settingQuery.isFetching ? "animate-spin" : undefined
              }
            />
            Повторить
          </Button>
        </CardFooter>
      </Card>
    )
  }

  return (
    <RepairComplexitySettingsCard
      key={`${warehouseId}:${settingQuery.data.version}:${settingQuery.data.updatedAt}`}
      setting={settingQuery.data}
      saving={saveMutation.isPending}
      blocked={saveMutation.isPending}
      actionError={actionError}
      onSave={(input) => {
        setActionError(null)
        saveMutation.mutate(input)
      }}
    />
  )
}

type KpiConfigurationCommand = {
  kind: "palette" | "schedule" | "delete-schedule" | "activate"
  execute: () => Promise<WarehouseKpiSettings | void>
  success: string
  confirmed?: () => void
}

function localDate(timeZone: string) {
  const { year, month, day } = calendarDatePartsInTimeZone(new Date(), timeZone)
  return [
    String(year).padStart(4, "0"),
    String(month).padStart(2, "0"),
    String(day).padStart(2, "0"),
  ].join("-")
}

function KpiConfigurationLoading() {
  return (
    <>
      {[0, 1].map((item) => (
        <Card key={item} aria-label="Загрузка настроек KPI">
          <CardHeader>
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-3/4" />
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-14 w-full" />
          </CardContent>
          <CardFooter>
            <Skeleton className="h-9 w-40" />
          </CardFooter>
        </Card>
      ))}
    </>
  )
}

function KpiConfigurationContent({
  accessToken,
  warehouseId,
}: {
  accessToken: string
  warehouseId: string
}) {
  const queryClient = useQueryClient()
  const activationAttempt = useRef<{
    version: number
    idempotencyKey: string
  } | null>(null)
  const queryKey = kpiSettingsKeys.warehouse(warehouseId)
  const [actionError, setActionError] = useState<string | null>(null)
  const settingsQuery = useQuery({
    queryKey,
    queryFn: () => getKpiSettings(accessToken, warehouseId),
  })
  const mutation = useMutation({
    mutationFn: (command: KpiConfigurationCommand) => command.execute(),
    onSuccess: async (result, command) => {
      command.confirmed?.()
      setActionError(null)
      toast.success(command.success)
      if (result) queryClient.setQueryData(queryKey, result)
      else await queryClient.invalidateQueries({ queryKey })
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const message =
          "Настройки KPI уже изменены другим пользователем. Данные обновлены — повторите действие."
        setActionError(message)
        toast.error(message)
        await queryClient.invalidateQueries({ queryKey })
        return
      }
      const message = errorMessage(error, "Не удалось изменить настройки KPI.")
      setActionError(message)
      toast.error(message)
    },
  })

  if (settingsQuery.isLoading) return <KpiConfigurationLoading />
  if (settingsQuery.isError || !settingsQuery.data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Не удалось загрузить настройки KPI</CardTitle>
          <CardDescription>
            График и палитра не подменяются локальными значениями.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(
              settingsQuery.error,
              "Сервис доски задач временно недоступен."
            )}
          </p>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            variant="outline"
            disabled={settingsQuery.isFetching}
            onClick={() => void settingsQuery.refetch()}
          >
            <HugeiconsIcon
              icon={settingsQuery.isFetching ? Loading03Icon : Refresh01Icon}
              data-icon="inline-start"
              className={settingsQuery.isFetching ? "animate-spin" : undefined}
            />
            Повторить
          </Button>
        </CardFooter>
      </Card>
    )
  }

  const settings = settingsQuery.data
  const activeKind = mutation.isPending ? mutation.variables?.kind : null
  const canActivate = Boolean(
    settings.palette &&
    (settings.activeSchedule || settings.pendingSchedule) &&
    settings.status !== "ACTIVE"
  )
  function activationKey(version: number) {
    if (activationAttempt.current?.version === version) {
      return activationAttempt.current.idempotencyKey
    }
    const idempotencyKey = crypto.randomUUID()
    activationAttempt.current = { version, idempotencyKey }
    return idempotencyKey
  }

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="secondary">
            {settings.status === "UNCONFIGURED"
              ? "Не настроено"
              : settings.status === "DRAFT"
                ? "Черновик"
                : settings.status === "SCHEDULED"
                  ? "Запланировано"
                  : "Активно"}
          </Badge>
          {settings.dataAvailableFrom ? (
            <span className="text-sm text-muted-foreground">
              История с {settings.dataAvailableFrom}
            </span>
          ) : null}
        </div>
        <Button
          type="button"
          disabled={!canActivate || mutation.isPending}
          onClick={() =>
            mutation.mutate({
              kind: "activate",
              execute: () =>
                activateKpiSettings(
                  accessToken,
                  warehouseId,
                  settings.version,
                  activationKey(settings.version)
                ),
              success: "KPI выбранного склада активирован.",
              confirmed: () => {
                activationAttempt.current = null
              },
            })
          }
        >
          <HugeiconsIcon
            icon={activeKind === "activate" ? Loading03Icon : FloppyDiskIcon}
            data-icon="inline-start"
            className={activeKind === "activate" ? "animate-spin" : undefined}
          />
          {activeKind === "activate" ? "Активируем…" : "Активировать KPI"}
        </Button>
      </div>

      <WorkScheduleCard
        key={`schedule:${settings.version}`}
        settings={settings}
        today={localDate(settings.timeZone)}
        saving={activeKind === "schedule"}
        deleting={activeKind === "delete-schedule"}
        blocked={mutation.isPending}
        actionError={actionError}
        onSave={(input: SaveWorkScheduleInput) => {
          setActionError(null)
          mutation.mutate({
            kind: "schedule",
            execute: () => saveWorkSchedule(accessToken, warehouseId, input),
            success: "Будущий рабочий график сохранён.",
          })
        }}
        onDeletePending={() => {
          setActionError(null)
          mutation.mutate({
            kind: "delete-schedule",
            execute: () =>
              deletePendingWorkSchedule(
                accessToken,
                warehouseId,
                settings.version
              ),
            success: "Будущий рабочий график удалён.",
          })
        }}
      />

      <PaletteSettingsCard
        key={`palette:${settings.version}`}
        settings={settings}
        saving={activeKind === "palette"}
        blocked={mutation.isPending}
        actionError={actionError}
        onSave={(input: SaveKpiPaletteInput) => {
          setActionError(null)
          mutation.mutate({
            kind: "palette",
            execute: () => saveKpiPalette(accessToken, warehouseId, input),
            success: "Палитра KPI сохранена.",
          })
        }}
      />
    </>
  )
}

export function KpiSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()

  if (!selectedWarehouse) {
    return (
      <StateCard
        title="Склад не выбран"
        description="Выберите склад, чтобы открыть его настройку KPI ремонтов."
      />
    )
  }

  const warehouseId = selectedWarehouse.id
  if (!hasWarehouseAccess(currentUser, warehouseId, "MANAGE")) {
    return (
      <StateCard
        title="Недостаточно прав"
        description="Для просмотра и изменения KPI нужен уровень MANAGE выбранного склада."
      />
    )
  }

  if (!accessToken) {
    return (
      <StateCard
        title="Нет токена доступа"
        description="Повторите вход, чтобы загрузить настройку из сервиса ремонтов."
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto pb-4">
      <KpiConfigurationContent
        key={`configuration:${warehouseId}`}
        accessToken={accessToken}
        warehouseId={warehouseId}
      />
      <RepairComplexityContent
        key={`repair-complexity:${warehouseId}`}
        accessToken={accessToken}
        warehouseId={warehouseId}
      />
      <RepairCapacityContent
        key={warehouseId}
        accessToken={accessToken}
        warehouseId={warehouseId}
        warehouseName={selectedWarehouse.name}
      />
    </div>
  )
}
