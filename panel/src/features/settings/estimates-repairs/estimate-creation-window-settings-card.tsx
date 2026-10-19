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
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  estimateCreationWindowKeys,
  getEstimateCreationWindow,
  updateEstimateCreationWindow,
  type EstimateCreationWindowSetting,
} from "@/features/settings/estimates-repairs/api/estimate-creation-window-settings-api"
import { ApiError } from "@/lib/api-client"

type Props = {
  accessToken: string
  readOnly?: boolean
}

function message(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function WindowForm({
  setting,
  saving,
  readOnly,
  actionError,
  onSave,
}: {
  setting: EstimateCreationWindowSetting
  saving: boolean
  readOnly: boolean
  actionError: string | null
  onSave: (days: number) => void
}) {
  const [days, setDays] = useState(String(setting.days))
  const [validationError, setValidationError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (readOnly) return
    const parsed = Number(days)
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 3650) {
      setValidationError("Укажите целое количество дней от 1 до 3650.")
      return
    }
    setValidationError(null)
    onSave(parsed)
  }

  return (
    <form noValidate onSubmit={submit}>
      <Card>
        <CardHeader>
          <CardTitle>Срок создания сметы после возврата</CardTitle>
          <CardDescription>
            Единый срок для всех складов. Смету можно создать до конца дня прибытия плюс указанное число дней.
            После срока для бытовки остаётся доступно прямое создание работы.
          </CardDescription>
          <CardAction>
            <Badge variant="outline">Версия {setting.version}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent>
          <Field
            data-invalid={Boolean(validationError ?? actionError) || undefined}
          >
            <FieldLabel htmlFor="estimate-creation-window-days">
              Количество дней
            </FieldLabel>
            <Input
              id="estimate-creation-window-days"
              type="number"
              min={1}
              max={3650}
              step={1}
              inputMode="numeric"
              value={days}
              disabled={saving || readOnly}
              aria-invalid={Boolean(validationError ?? actionError)}
              onChange={(event) => {
                setDays(event.target.value)
                setValidationError(null)
              }}
            />
            <FieldDescription>
              Например, при прибытии 1 августа и значении 7 смета доступна по 8
              августа включительно.
            </FieldDescription>
            <FieldError>{validationError ?? actionError}</FieldError>
          </Field>
        </CardContent>
        <CardFooter className="justify-between gap-4 border-t">
          <p className="text-sm text-muted-foreground">
            {readOnly ? "Только просмотр" : "Применяется ко всем складам."}
          </p>
          <Button type="submit" disabled={saving || readOnly}>
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

export function EstimateCreationWindowSettingsCard({
  accessToken,
  readOnly = false,
}: Props) {
  const queryClient = useQueryClient()
  const queryKey = estimateCreationWindowKeys.all
  const [actionError, setActionError] = useState<string | null>(null)
  const query = useQuery({
    queryKey,
    queryFn: () => getEstimateCreationWindow(accessToken),
  })
  const mutation = useMutation({
    mutationFn: (days: number) =>
      updateEstimateCreationWindow(accessToken, {
        expectedVersion: query.data?.version ?? 0,
        days,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      toast.success("Срок создания сметы сохранён.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const conflict =
          "Настройка уже изменена другим пользователем. Данные обновлены — повторите сохранение."
        setActionError(conflict)
        toast.error(conflict)
        await queryClient.invalidateQueries({ queryKey })
        return
      }
      const failure = message(
        error,
        "Не удалось сохранить срок создания сметы."
      )
      setActionError(failure)
      toast.error(failure)
    },
  })

  if (query.isLoading) {
    return (
      <Card aria-label="Загрузка срока создания сметы">
        <CardHeader>
          <CardTitle>Срок создания сметы после возврата</CardTitle>
        </CardHeader>
        <CardContent>
          <Skeleton className="h-9 w-full max-w-xs" />
        </CardContent>
      </Card>
    )
  }
  if (query.isError || !query.data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Не удалось загрузить срок создания сметы</CardTitle>
          <CardDescription>
            Настройка не подменяется локальным значением.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p role="alert" className="text-sm text-destructive">
            {message(query.error, "Сервис ремонтов временно недоступен.")}
          </p>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            variant="outline"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            <HugeiconsIcon
              icon={query.isFetching ? Loading03Icon : Refresh01Icon}
              data-icon="inline-start"
            />
            {query.isFetching ? "Повторяем…" : "Повторить"}
          </Button>
        </CardFooter>
      </Card>
    )
  }
  return (
    <WindowForm
      key={`${query.data.version}:${query.data.days}`}
      setting={query.data}
      saving={mutation.isPending}
      readOnly={readOnly}
      actionError={actionError}
      onSave={(days) => {
        setActionError(null)
        mutation.mutate(days)
      }}
    />
  )
}
