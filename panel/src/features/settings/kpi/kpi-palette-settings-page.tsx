import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon, Refresh01Icon } from "@hugeicons/core-free-icons"
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
  getKpiPalette,
  kpiSettingsKeys,
  saveKpiPalette,
  type SaveKpiPaletteInput,
} from "@/features/settings/kpi/api/kpi-settings-api"
import { PaletteSettingsCard } from "@/features/settings/kpi/palette-settings-card"
import { ApiError } from "@/lib/api-client"

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function LoadingCard() {
  return (
    <Card aria-label="Загрузка общей палитры KPI">
      <CardHeader>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-3/4" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-14 w-full" />
      </CardContent>
      <CardFooter>
        <Skeleton className="h-9 w-40" />
      </CardFooter>
    </Card>
  )
}

/** Edits the one KPI palette shared by every RWMS object. */
export function KpiPaletteSettingsPage() {
  const { accessToken } = useAuth()
  const queryClient = useQueryClient()
  const queryKey = kpiSettingsKeys.palette
  const [actionError, setActionError] = useState<string | null>(null)
  const paletteQuery = useQuery({
    queryKey,
    queryFn: () => getKpiPalette(accessToken!),
    enabled: Boolean(accessToken),
  })
  const saveMutation = useMutation({
    mutationFn: (input: SaveKpiPaletteInput) => {
      if (!accessToken) throw new Error("Нет токена доступа.")
      return saveKpiPalette(accessToken, input)
    },
    onSuccess: async (saved) => {
      queryClient.setQueryData(queryKey, saved)
      setActionError(null)
      await queryClient.invalidateQueries({ queryKey: kpiSettingsKeys.all })
      toast.success("Общая палитра KPI сохранена для всех объектов.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        const message =
          "Палитра уже изменена другим пользователем. Данные обновлены — повторите сохранение."
        setActionError(message)
        toast.error(message)
        await queryClient.invalidateQueries({ queryKey })
        return
      }
      const message = errorMessage(
        error,
        "Не удалось сохранить общую палитру KPI."
      )
      setActionError(message)
      toast.error(message)
    },
  })

  if (!accessToken) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Нет токена доступа</CardTitle>
          <CardDescription>
            Повторите вход, чтобы загрузить общую палитру KPI.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (paletteQuery.isLoading) return <LoadingCard />

  if (paletteQuery.isError || !paletteQuery.data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Не удалось загрузить общую палитру KPI</CardTitle>
          <CardDescription>
            Палитра не подменяется настройками отдельных объектов.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(
              paletteQuery.error,
              "Сервис доски задач временно недоступен."
            )}
          </p>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            variant="outline"
            disabled={paletteQuery.isFetching}
            onClick={() => void paletteQuery.refetch()}
          >
            <HugeiconsIcon
              icon={paletteQuery.isFetching ? Loading03Icon : Refresh01Icon}
              data-icon="inline-start"
              className={paletteQuery.isFetching ? "animate-spin" : undefined}
              aria-hidden="true"
            />
            Повторить
          </Button>
        </CardFooter>
      </Card>
    )
  }

  const settings = paletteQuery.data
  return (
    <div className="flex min-h-0 flex-col gap-4 pb-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={settings.palette ? "secondary" : "outline"}>
          {settings.palette ? "Единая палитра" : "Палитра не настроена"}
        </Badge>
        <span className="text-sm text-muted-foreground">
          Применяется ко всем объектам сразу после сохранения.
        </span>
      </div>
      <PaletteSettingsCard
        key={`kpi-palette:${settings.version}`}
        settings={settings}
        saving={saveMutation.isPending}
        blocked={saveMutation.isPending}
        actionError={actionError}
        onSave={(input) => {
          setActionError(null)
          saveMutation.mutate(input)
        }}
      />
    </div>
  )
}
