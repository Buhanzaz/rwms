import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
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
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  getShipmentTaskSettings,
  shipmentTaskSettingsKeys,
  updateShipmentTaskSettings,
  type ShipmentTaskSettings,
  type ShipmentTaskSettingsUpdate,
} from "@/features/settings/logistics/api/shipment-task-settings-api"
import { ApiError } from "@/lib/api-client"

type ShipmentTaskSettingsCardProps = {
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
    <Card aria-label="Загрузка лимита бытовок в задании отгрузки">
      <CardHeader>
        <CardTitle>Максимум бытовок в одном задании отгрузки</CardTitle>
        <CardDescription>
          Загружаем лимит для выбранного склада…
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Skeleton className="h-9 w-full max-w-xs" />
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
        <CardTitle>Не удалось загрузить лимит бытовок</CardTitle>
        <CardDescription>
          Локальное значение не подставляется: лимит задания задаёт сервис
          логистики.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Alert variant="destructive">
          <AlertTitle>Настройка недоступна</AlertTitle>
          <AlertDescription>
            {errorMessage(error, "Сервис логистики временно недоступен.")}
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
  setting: ShipmentTaskSettings
  warehouseName: string
  saving: boolean
  actionError: string | null
  onSave: (request: ShipmentTaskSettingsUpdate) => void
}) {
  const [maxCabinsPerShipmentTask, setMaxCabinsPerShipmentTask] = useState(
    String(setting.maxCabinsPerShipmentTask)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const parsedMaxCabins = Number(maxCabinsPerShipmentTask)
  const validMaxCabins =
    Number.isInteger(parsedMaxCabins) &&
    parsedMaxCabins >= 1 &&
    parsedMaxCabins <= 100
      ? parsedMaxCabins
      : null
  const visibleError = validationError ?? actionError

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (validMaxCabins === null) {
      setValidationError("Укажите целое количество бытовок от 1 до 100.")
      return
    }

    setValidationError(null)
    onSave({
      expectedVersion: setting.version,
      maxCabinsPerShipmentTask: validMaxCabins,
    })
  }

  return (
    <form noValidate onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Максимум бытовок в одном задании отгрузки</CardTitle>
          <CardDescription>
            Лимит для склада «{warehouseName}». Выбранные бытовки одного клиента
            объединяются в одну задачу водителя только в пределах этого
            количества.
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {setting.version}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <Field data-invalid={visibleError !== null || undefined}>
              <FieldLabel htmlFor="shipment-task-max-cabins">
                Максимум бытовок в одном задании отгрузки
              </FieldLabel>
              <Input
                id="shipment-task-max-cabins"
                type="number"
                min={1}
                max={100}
                step={1}
                inputMode="numeric"
                value={maxCabinsPerShipmentTask}
                disabled={saving}
                aria-invalid={visibleError !== null}
                onChange={(event) => {
                  setMaxCabinsPerShipmentTask(event.target.value)
                  setValidationError(null)
                }}
              />
              <FieldDescription>
                От 1 до 100. Ограничение действует только для выбранного склада
                и проверяется также при создании отгрузки.
              </FieldDescription>
            </Field>
            <FieldError>{visibleError}</FieldError>
          </FieldGroup>
        </CardContent>
        <CardFooter className="justify-between gap-4 border-t">
          <p className="text-sm text-muted-foreground">
            Новые отгрузки используют сохранённое значение.
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

export function ShipmentTaskSettingsCard({
  accessToken,
  warehouseId,
  warehouseName,
}: ShipmentTaskSettingsCardProps) {
  const queryClient = useQueryClient()
  const queryKey = shipmentTaskSettingsKeys.warehouse(warehouseId)
  const [actionError, setActionError] = useState<string | null>(null)
  const settingsQuery = useQuery({
    queryKey,
    queryFn: () => getShipmentTaskSettings(accessToken, warehouseId),
  })
  const saveMutation = useMutation({
    mutationFn: (request: ShipmentTaskSettingsUpdate) =>
      updateShipmentTaskSettings(accessToken, warehouseId, request),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      toast.success("Лимит бытовок в задании отгрузки сохранён.")
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
        "Не удалось сохранить лимит бытовок в задании отгрузки."
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
          new Error("Сервис логистики не вернул лимит бытовок в задании.")
        }
        retrying={settingsQuery.isFetching}
        onRetry={() => void settingsQuery.refetch()}
      />
    )
  }

  return (
    <SettingsForm
      key={`${warehouseId}:${settingsQuery.data.version}:${settingsQuery.data.maxCabinsPerShipmentTask}`}
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
