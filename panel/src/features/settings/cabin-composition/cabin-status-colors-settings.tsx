import { useState, type CSSProperties, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { RgbColorControl } from "@/features/settings/kpi/rgb-color-control"
import { ApiError } from "@/lib/api-client"
import {
  CABIN_STATUS_COLOR_PATTERN,
  CABIN_STATUS_COLOR_STATUSES,
  cabinStatusColorsQueryKey,
  cabinStatusColorVariable,
  saveCabinStatusColors,
  useCabinStatusColors,
  type CabinStatusColors,
} from "./cabin-status-colors-api"

const labels: Record<RentalItemStatus, string> = {
  ...RENTAL_ITEM_STATUS_LABEL,
  WAITING_REPAIR_CHECK: "Ожидает приёмки ремонта",
  RESERVED: "Краткий резерв",
  SALE: "Продажа",
}

/** Edits only the global server palette; remote refresh never discards an unsaved draft. */
export function CabinStatusColorsSettings() {
  const { accessToken, currentUser } = useAuth()
  const query = useCabinStatusColors()
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<Pick<
    CabinStatusColors,
    "version" | "colors"
  > | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const canManage = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )
  const current = draft ?? query.data
  const stale = Boolean(
    draft && query.data && draft.version !== query.data.version
  )
  const invalid = Boolean(
    current &&
    CABIN_STATUS_COLOR_STATUSES.some(
      (status) => !CABIN_STATUS_COLOR_PATTERN.test(current.colors[status])
    )
  )
  const queryKey = cabinStatusColorsQueryKey(currentUser?.id)
  const mutation = useMutation({
    mutationFn: async () => {
      if (!accessToken || !canManage || !draft || invalid || stale)
        throw new Error("Проверьте палитру и права доступа перед сохранением.")
      return saveCabinStatusColors(accessToken, draft.version, draft.colors)
    },
    onSuccess: async (saved) => {
      await queryClient.cancelQueries({ queryKey })
      queryClient.setQueryData(queryKey, saved)
      setDraft(null)
      setActionError(null)
      toast.success("Цвета статусов сохранены для всех складов.")
    },
    onError: async (error) => {
      setActionError(error.message)
      if (error instanceof ApiError && error.status === 409)
        await query.refetch()
    },
  })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setActionError(null)
    mutation.mutate()
  }
  if (!accessToken)
    return <p role="alert">Для просмотра палитры требуется авторизация.</p>
  if (query.error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>Цвета статусов</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p role="alert">{query.error.message}</p>
          <Button variant="outline" onClick={() => void query.refetch()}>
            Повторить загрузку палитры
          </Button>
        </CardContent>
      </Card>
    )
  if (!current)
    return (
      <Skeleton
        className="h-48 w-full"
        aria-label="Загрузка палитры статусов"
      />
    )
  return (
    <form onSubmit={submit} className="min-h-0 overflow-y-auto">
      <Card>
        <CardHeader>
          <CardTitle>Цвета статусов бытовок</CardTitle>
          <CardDescription>
            Единая палитра WMS и веб-панели менеджеров для всех складов. Цвет не
            меняет смысл статуса; оттенок подстраивается под светлую и тёмную
            тему.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <FieldGroup className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {CABIN_STATUS_COLOR_STATUSES.map((status) => (
              <Field
                key={status}
                data-invalid={
                  !CABIN_STATUS_COLOR_PATTERN.test(current.colors[status]) ||
                  undefined
                }
              >
                <FieldLabel>{labels[status]}</FieldLabel>
                <RgbColorControl
                  label={labels[status]}
                  value={current.colors[status]}
                  disabled={!canManage || mutation.isPending}
                  onChange={(color) =>
                    setDraft({
                      version: current.version,
                      colors: { ...current.colors, [status]: color },
                    })
                  }
                />
                <div
                  style={
                    CABIN_STATUS_COLOR_PATTERN.test(current.colors[status])
                      ? ({
                          [cabinStatusColorVariable(status)]:
                            current.colors[status],
                        } as CSSProperties)
                      : undefined
                  }
                >
                  <RentalItemStatusBadge status={status} />
                </div>
              </Field>
            ))}
          </FieldGroup>
          {invalid ? (
            <p role="alert">Укажите каждый цвет в формате #RRGGBB.</p>
          ) : null}
          {stale ? (
            <p role="alert">
              Палитра изменена в другом окне. Ваш черновик сохранён здесь;
              загрузите актуальные цвета перед новым редактированием.
            </p>
          ) : null}
          {actionError ? <p role="alert">{actionError}</p> : null}
        </CardContent>
        <CardFooter className="flex flex-wrap justify-end gap-2">
          {draft ? (
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => {
                setDraft(null)
                setActionError(null)
              }}
            >
              {stale ? "Загрузить актуальные цвета" : "Отменить изменения"}
            </Button>
          ) : null}
          {canManage ? (
            <Button
              type="submit"
              disabled={!draft || invalid || stale || mutation.isPending}
            >
              {mutation.isPending ? "Сохраняем…" : "Сохранить цвета"}
            </Button>
          ) : (
            <p className="text-sm text-muted-foreground">Только просмотр</p>
          )}
        </CardFooter>
      </Card>
    </form>
  )
}
