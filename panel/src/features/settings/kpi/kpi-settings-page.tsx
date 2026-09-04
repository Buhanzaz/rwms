import { useRef, useState } from "react"
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
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import {
  activateKpiSettings,
  deletePendingWorkSchedule,
  getKpiSettings,
  kpiSettingsKeys,
  saveWorkSchedule,
  type SaveWorkScheduleInput,
  type KpiSettingsResponse,
} from "@/features/settings/kpi/api/kpi-settings-api"
import { WorkScheduleCard } from "@/features/settings/kpi/work-schedule-card"
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

type KpiConfigurationCommand = {
  kind: "schedule" | "delete-schedule" | "activate"
  execute: () => Promise<KpiSettingsResponse | void>
  success: string
  confirmed?: () => void
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
  presentation,
}: {
  accessToken: string
  presentation: KpiSettingsPresentation
}) {
  const queryClient = useQueryClient()
  const activationAttempt = useRef<{
    version: number
    idempotencyKey: string
  } | null>(null)
  const queryKey = kpiSettingsKeys.settings
  const [actionError, setActionError] = useState<string | null>(null)
  const settingsQuery = useQuery({
    queryKey,
    queryFn: () => getKpiSettings(accessToken),
  })
  const mutation = useMutation({
    mutationFn: (command: KpiConfigurationCommand) => command.execute(),
    onSuccess: async (result, command) => {
      command.confirmed?.()
      setActionError(null)
      toast.success(command.success)
      if (result) queryClient.setQueryData(queryKey, result)
      else await queryClient.invalidateQueries({ queryKey })
      await queryClient.invalidateQueries({
        queryKey: kpiSettingsKeys.palette,
      })
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
          <CardTitle>Не удалось загрузить рабочий график</CardTitle>
          <CardDescription>
            График не подменяется локальными значениями.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(
              settingsQuery.error,
              "Сервис графиков работы временно недоступен."
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
              aria-hidden="true"
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
    settings.pendingSchedule && settings.status === "DRAFT"
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
                  settings.version,
                  activationKey(settings.version)
                ),
              success: "Рабочий график активирован для всех объектов.",
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
            aria-hidden="true"
          />
          {activeKind === "activate" ? "Активируем…" : "Активировать график"}
        </Button>
      </div>

      <WorkScheduleCard
        key={`schedule:${settings.version}`}
        settings={settings}
        today={settings.minimumEffectiveDate}
        saving={activeKind === "schedule"}
        deleting={activeKind === "delete-schedule"}
        blocked={mutation.isPending}
        actionError={actionError}
        hideVersion={presentation === "admin"}
        onSave={(input: SaveWorkScheduleInput) => {
          setActionError(null)
          mutation.mutate({
            kind: "schedule",
            execute: () => saveWorkSchedule(accessToken, input),
            success: "Рабочий график сохранён и готов к активации.",
          })
        }}
        onDeletePending={() => {
          setActionError(null)
          mutation.mutate({
            kind: "delete-schedule",
            execute: () =>
              deletePendingWorkSchedule(accessToken, settings.version),
            success: "Ожидающий активации рабочий график удалён.",
          })
        }}
      />
    </>
  )
}

export type KpiSettingsPresentation = "default" | "admin"

export function KpiSettingsPage({
  presentation = "default",
}: {
  presentation?: KpiSettingsPresentation
} = {}) {
  const { accessToken } = useAuth()

  if (!accessToken) {
    return (
      <StateCard
        title="Нет токена доступа"
        description="Повторите вход, чтобы загрузить график из сервиса доски задач."
      />
    )
  }

  return (
    <div className="flex min-h-0 flex-col gap-4 pb-4">
      <KpiConfigurationContent
        accessToken={accessToken}
        presentation={presentation}
      />
    </div>
  )
}
