import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouses,
  replaceWarehouse,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
  type WarehouseCreateInput,
  type WarehouseInfo,
  type WarehouseLifecycleState,
  type WarehouseWriteInput,
} from "@/api/warehouse-api"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import {
  getWarehouseMutationError,
  isWarehouseConflict,
} from "@/features/settings/warehouses/warehouse-settings-errors"
import {
  createWarehouseFormValues,
  parseWarehouseForm,
  type WarehouseFormValues,
} from "@/features/settings/warehouses/warehouse-settings-form"
import { WarehouseSupportLinksEditor } from "@/features/settings/warehouses/warehouse-support-links-editor"
import {
  createEmptyWarehouseFilters,
  filterWarehouses,
  getWarehouseFilterOptions,
} from "@/features/settings/warehouses/warehouse-settings-filtering"
import {
  WarehouseFiltersToggle,
  WarehouseSettingsFilters,
} from "@/features/settings/warehouses/warehouse-settings-filters"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"

const WAREHOUSES_QUERY_KEY = ["warehouse-settings"] as const

const warehouseLifecyclePresentation: Record<
  WarehouseLifecycleState,
  { label: string; badge: "secondary" | "outline" | "destructive" }
> = {
  ACTIVE: { label: "Активен", badge: "secondary" },
  DRAINING: { label: "Выводится из работы", badge: "destructive" },
  INACTIVE: { label: "Неактивен", badge: "outline" },
}

function WarehouseEditorDialog({
  warehouse,
  pending,
  serverError,
  accessToken,
  warehouses,
  onOpenChange,
  onSave,
  onSupportSaved,
  onSupportConflict,
}: {
  warehouse: WarehouseInfo | null
  pending: boolean
  serverError: string | null
  accessToken: string | null
  warehouses: WarehouseInfo[]
  onOpenChange: (open: boolean) => void
  onSave: (input: WarehouseWriteInput) => void
  onSupportSaved: () => Promise<void>
  onSupportConflict: () => Promise<void>
}) {
  const [values, setValues] = useState<WarehouseFormValues>(() =>
    createWarehouseFormValues(warehouse)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const formError = validationError ?? serverError

  function updateValue<Key extends keyof WarehouseFormValues>(
    key: Key,
    value: WarehouseFormValues[Key]
  ) {
    setValues((current) => ({ ...current, [key]: value }))
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const result = parseWarehouseForm(values)
    if (result.input === null) {
      setValidationError(result.error)
      return
    }

    setValidationError(null)
    onSave(result.input)
  }

  return (
    <Dialog open onOpenChange={(open) => !pending && onOpenChange(open)}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{warehouse ? "Склад" : "Новый склад"}</DialogTitle>
        </DialogHeader>

        <form onSubmit={(event) => void submit(event)}>
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-name">Название</FieldLabel>
              <Input
                id="warehouse-name"
                value={values.name}
                maxLength={255}
                onChange={(event) => updateValue("name", event.target.value)}
                required
                aria-invalid={formError !== null}
              />
            </Field>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-city">Город</FieldLabel>
              <Input
                id="warehouse-city"
                value={values.city}
                maxLength={255}
                onChange={(event) => updateValue("city", event.target.value)}
                required
                aria-invalid={formError !== null}
              />
            </Field>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-time-zone">
                Временная зона
              </FieldLabel>
              <Input
                id="warehouse-time-zone"
                value={values.timeZone}
                maxLength={64}
                onChange={(event) =>
                  updateValue("timeZone", event.target.value)
                }
                placeholder="Europe/Moscow"
                required
                aria-invalid={formError !== null}
              />
            </Field>
            <Field
              className="md:col-span-2"
              data-invalid={formError !== null || undefined}
            >
              <FieldLabel htmlFor="warehouse-address">Адрес</FieldLabel>
              <Input
                id="warehouse-address"
                value={values.address}
                maxLength={1000}
                onChange={(event) => updateValue("address", event.target.value)}
                placeholder="Необязательно"
                aria-invalid={formError !== null}
              />
            </Field>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-latitude">Широта</FieldLabel>
              <Input
                id="warehouse-latitude"
                inputMode="decimal"
                value={values.latitude}
                onChange={(event) =>
                  updateValue("latitude", event.target.value)
                }
                placeholder="59.934300"
                aria-invalid={formError !== null}
              />
            </Field>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-longitude">Долгота</FieldLabel>
              <Input
                id="warehouse-longitude"
                inputMode="decimal"
                value={values.longitude}
                onChange={(event) =>
                  updateValue("longitude", event.target.value)
                }
                placeholder="30.335100"
                aria-invalid={formError !== null}
              />
            </Field>
            <p
              className={`text-xs md:col-span-2 ${
                values.latitude.trim() === "" && values.longitude.trim() === ""
                  ? "text-amber-700 dark:text-amber-300"
                  : "text-muted-foreground"
              }`}
            >
              {values.latitude.trim() === "" && values.longitude.trim() === ""
                ? "Не заданы координаты для использования склада в логистике."
                : "Координаты WGS84 используются картой, маршрутизацией и изохронами."}
            </p>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-sort-order">Порядок</FieldLabel>
              <Input
                id="warehouse-sort-order"
                type="number"
                min={0}
                step={1}
                value={values.sortOrder}
                onChange={(event) =>
                  updateValue("sortOrder", event.target.value)
                }
                placeholder="Необязательно"
                aria-invalid={formError !== null}
              />
            </Field>
            <Field orientation="horizontal" className="md:col-span-2">
              <Checkbox
                id="warehouse-representative"
                checked={values.representative}
                onCheckedChange={(checked) =>
                  updateValue("representative", checked === true)
                }
              />
              <FieldLabel htmlFor="warehouse-representative">
                Представительский склад
              </FieldLabel>
            </Field>
          </FieldGroup>

          {formError ? (
            <FieldError className="mt-4">{formError}</FieldError>
          ) : null}

          <DialogFooter className="mt-6">
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Сохранить"}
            </Button>
          </DialogFooter>
        </form>

        {values.representative && warehouse === null ? (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            Сначала сохраните новый склад, затем откройте его снова, чтобы
            настроить опорные склады.
          </p>
        ) : values.representative && warehouse?.representative ? (
          <WarehouseSupportLinksEditor
            accessToken={accessToken}
            warehouse={warehouse}
            warehouses={warehouses}
            onSaved={onSupportSaved}
            onConflict={onSupportConflict}
          />
        ) : values.representative && warehouse ? (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            Сохраните признак представительского склада и откройте форму снова,
            чтобы настроить логистическое обслуживание.
          </p>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

const OFFSET_DATE_TIME_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/

function TimeZoneScheduleDialog({
  warehouse,
  pending,
  serverError,
  onOpenChange,
  onSchedule,
}: {
  warehouse: WarehouseInfo
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSchedule: (timeZone: string, effectiveFrom: string) => void
}) {
  const [timeZone, setTimeZone] = useState(warehouse.timeZone)
  const [effectiveFrom, setEffectiveFrom] = useState("")
  const [validationError, setValidationError] = useState<string | null>(null)
  const formError = validationError ?? serverError

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedTimeZone = timeZone.trim()
    const normalizedEffectiveFrom = effectiveFrom.trim()

    try {
      Intl.DateTimeFormat("ru-RU", { timeZone: normalizedTimeZone })
    } catch {
      setValidationError(
        "Укажите корректную временную зону IANA, например Europe/Samara."
      )
      return
    }

    if (
      !OFFSET_DATE_TIME_PATTERN.test(normalizedEffectiveFrom) ||
      !Number.isFinite(Date.parse(normalizedEffectiveFrom)) ||
      Date.parse(normalizedEffectiveFrom) <= Date.now()
    ) {
      setValidationError(
        "Укажите будущую дату в RFC 3339 с явным смещением, например 2026-09-01T00:00:00+04:00."
      )
      return
    }

    if (normalizedTimeZone === warehouse.timeZone) {
      setValidationError("Новая временная зона совпадает с текущей.")
      return
    }

    setValidationError(null)
    onSchedule(normalizedTimeZone, normalizedEffectiveFrom)
  }

  return (
    <Dialog open onOpenChange={(open) => !pending && onOpenChange(open)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Запланировать смену часового пояса</DialogTitle>
          <DialogDescription>
            Для склада «{warehouse.name}» новая зона начнёт действовать только с
            указанного момента. Уже рассчитанные операции и отчёты не изменятся.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit}>
          <FieldGroup>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-scheduled-time-zone">
                Новая временная зона
              </FieldLabel>
              <Input
                id="warehouse-scheduled-time-zone"
                value={timeZone}
                maxLength={64}
                placeholder="Europe/Samara"
                onChange={(event) => setTimeZone(event.target.value)}
                aria-invalid={formError !== null}
                required
              />
            </Field>
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-time-zone-effective-from">
                Начать с даты и времени
              </FieldLabel>
              <Input
                id="warehouse-time-zone-effective-from"
                value={effectiveFrom}
                placeholder="2026-09-01T00:00:00+04:00"
                onChange={(event) => setEffectiveFrom(event.target.value)}
                aria-describedby="warehouse-time-zone-effective-from-hint"
                aria-invalid={formError !== null}
                required
              />
              <p
                id="warehouse-time-zone-effective-from-hint"
                className="text-xs text-muted-foreground"
              >
                Формат RFC 3339 с часовым смещением склада.
              </p>
            </Field>
          </FieldGroup>

          {formError ? (
            <FieldError className="mt-4">{formError}</FieldError>
          ) : null}

          <DialogFooter className="mt-6">
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Планируем…" : "Запланировать"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function WarehouseActions({
  warehouse,
  pending,
  onEdit,
  onScheduleTimeZone,
  onLifecycleTransition,
}: {
  warehouse: WarehouseInfo
  pending: boolean
  onEdit: (warehouse: WarehouseInfo) => void
  onScheduleTimeZone: (warehouse: WarehouseInfo) => void
  onLifecycleTransition: (warehouse: WarehouseInfo) => void
}) {
  return (
    <div className="flex flex-wrap gap-2">
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={pending}
        onClick={() => onEdit(warehouse)}
      >
        Изменить
      </Button>
      <Button
        type="button"
        size="sm"
        variant="ghost"
        disabled={pending}
        onClick={() => onScheduleTimeZone(warehouse)}
      >
        Сменить часовой пояс
      </Button>
      {warehouse.lifecycleState === "ACTIVE" ? (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={pending}
          onClick={() => onLifecycleTransition(warehouse)}
        >
          Начать вывод
        </Button>
      ) : warehouse.lifecycleState === "DRAINING" ? (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={pending}
          onClick={() => onLifecycleTransition(warehouse)}
        >
          Завершить вывод
        </Button>
      ) : null}
    </div>
  )
}

export function WarehouseSettingsPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { reloadWarehouses } = useWarehouse()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(createEmptyWarehouseFilters)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [editor, setEditor] = useState<WarehouseInfo | "new" | null>(null)
  const [lifecycleTransition, setLifecycleTransition] =
    useState<WarehouseInfo | null>(null)
  const [timeZoneSchedule, setTimeZoneSchedule] =
    useState<WarehouseInfo | null>(null)
  const [serverError, setServerError] = useState<string | null>(null)
  const canManage = currentUser?.globalRole === "SYSTEM_ADMIN"

  const warehousesQuery = useQuery({
    queryKey: WAREHOUSES_QUERY_KEY,
    queryFn: () => listWarehouses(accessToken, true),
    enabled: accessToken !== null && canManage,
  })

  async function refreshAfterMutation() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: WAREHOUSES_QUERY_KEY }),
      reloadWarehouses(),
    ])
  }

  async function refreshAfterConflict() {
    await refreshAfterMutation()
    setEditor(null)
    setLifecycleTransition(null)
    setTimeZoneSchedule(null)
  }

  const saveMutation = useMutation({
    mutationFn: async (input: WarehouseWriteInput) => {
      if (accessToken === null) {
        throw new Error("Сессия завершена.")
      }

      if (editor === "new") {
        const createInput: WarehouseCreateInput = {
          name: input.name,
          city: input.city,
          address: input.address,
          latitude: input.latitude,
          longitude: input.longitude,
          timeZone: input.timeZone,
          sortOrder: input.sortOrder,
          representative: input.representative,
        }
        return createWarehouse(accessToken, crypto.randomUUID(), createInput)
      }

      if (editor === null) {
        throw new Error("Склад не выбран.")
      }

      return replaceWarehouse(accessToken, editor.id, editor.version, input)
    },
    onSuccess: async () => {
      await refreshAfterMutation()
      setEditor(null)
      setServerError(null)
      toast.success("Склад сохранён.")
    },
    onError: async (error) => {
      const message = getWarehouseMutationError(error)

      if (isWarehouseConflict(error)) {
        await refreshAfterConflict()
      } else {
        setServerError(message)
      }

      toast.error(message)
    },
  })

  const lifecycleMutation = useMutation({
    mutationFn: async () => {
      if (accessToken === null || lifecycleTransition === null) {
        throw new Error("Сессия завершена или склад не выбран.")
      }

      if (lifecycleTransition.lifecycleState === "ACTIVE") {
        return startWarehouseDraining(
          accessToken,
          lifecycleTransition.id,
          lifecycleTransition.version
        )
      }

      if (lifecycleTransition.lifecycleState === "DRAINING") {
        return completeWarehouseInactivation(
          accessToken,
          lifecycleTransition.id,
          lifecycleTransition.version
        )
      }

      throw new Error("Неактивный склад уже завершил жизненный цикл.")
    },
    onSuccess: async () => {
      await refreshAfterMutation()
      const completed = lifecycleTransition?.lifecycleState === "DRAINING"
      setLifecycleTransition(null)
      setServerError(null)
      toast.success(
        completed
          ? "Склад переведён в неактивное состояние."
          : "Начат вывод склада из работы."
      )
    },
    onError: async (error) => {
      const message = getWarehouseMutationError(error)

      if (isWarehouseConflict(error)) {
        await refreshAfterConflict()
      } else {
        setServerError(message)
      }

      toast.error(message)
    },
  })

  const timeZoneMutation = useMutation({
    mutationFn: async (input: { timeZone: string; effectiveFrom: string }) => {
      if (accessToken === null || timeZoneSchedule === null) {
        throw new Error("Сессия завершена или склад не выбран.")
      }

      return scheduleWarehouseTimeZone(
        accessToken,
        timeZoneSchedule.id,
        timeZoneSchedule.version,
        input.timeZone,
        input.effectiveFrom
      )
    },
    onSuccess: async (change) => {
      await refreshAfterMutation()
      setTimeZoneSchedule(null)
      setServerError(null)
      toast.success(
        `Смена часового пояса запланирована на ${new Intl.DateTimeFormat(
          "ru-RU",
          { dateStyle: "medium", timeStyle: "short" }
        ).format(new Date(change.effectiveFrom))}.`
      )
    },
    onError: async (error) => {
      const message = getWarehouseMutationError(error)

      if (isWarehouseConflict(error)) {
        await refreshAfterConflict()
      } else {
        setServerError(message)
      }

      toast.error(message)
    },
  })

  const warehouses = useMemo(
    () => warehousesQuery.data ?? [],
    [warehousesQuery.data]
  )
  const filterOptions = useMemo(
    () => getWarehouseFilterOptions(warehouses),
    [warehouses]
  )
  const visibleWarehouses = useMemo(
    () => filterWarehouses(warehouses, search, filters),
    [filters, search, warehouses]
  )

  if (!canManage) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Склады</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Управление складами доступно только системному администратору.
        </CardContent>
      </Card>
    )
  }

  const isMutating =
    saveMutation.isPending ||
    lifecycleMutation.isPending ||
    timeZoneMutation.isPending

  function openEditor(nextEditor: WarehouseInfo | "new") {
    setServerError(null)
    setEditor(nextEditor)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Поиск по названию или городу"
            aria-label="Поиск складов"
          />
        </PageToolbarContent>
        <PageToolbarActions className="w-full sm:w-auto">
          <WarehouseFiltersToggle
            open={filtersOpen}
            controls="warehouse-settings-filters"
            onOpenChange={setFiltersOpen}
          />
          <Button type="button" onClick={() => openEditor("new")}>
            Создать склад
          </Button>
        </PageToolbarActions>
      </PageToolbar>
      <div id="warehouse-settings-filters" hidden={!filtersOpen}>
        <WarehouseSettingsFilters
          filters={filters}
          options={filterOptions}
          onChange={setFilters}
        />
      </div>

      {warehousesQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Загрузка складов…</p>
      ) : warehousesQuery.isError ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Не удалось загрузить склады</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            <p role="alert" className="text-destructive">
              {getWarehouseMutationError(warehousesQuery.error)}
            </p>
            <Button
              type="button"
              variant="outline"
              onClick={() => void warehousesQuery.refetch()}
            >
              Повторить
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <OperationsListGrid
            className="hidden min-h-0 flex-1 overflow-auto md:block"
            items={visibleWarehouses}
            columns={[
              {
                id: "name",
                label: "Название",
                getSortValue: (warehouse) => warehouse.name,
                render: (warehouse) => (
                  <div className="flex flex-wrap items-center gap-2">
                    <span>{warehouse.name}</span>
                    {warehouse.representative ? (
                      <Badge variant="outline">Представительский</Badge>
                    ) : null}
                    {warehouse.latitude === null ||
                    warehouse.longitude === null ? (
                      <Badge variant="outline">Нет координат</Badge>
                    ) : null}
                  </div>
                ),
              },
              {
                id: "city",
                label: "Город",
                getSortValue: (warehouse) => warehouse.city,
                render: (warehouse) => warehouse.city,
              },
              {
                id: "timeZone",
                label: "Временная зона",
                getSortValue: (warehouse) => warehouse.timeZone,
                render: (warehouse) => warehouse.timeZone,
              },
              {
                id: "sortOrder",
                label: "Порядок",
                getSortValue: (warehouse) => warehouse.sortOrder,
                render: (warehouse) => warehouse.sortOrder ?? "—",
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (warehouse) => warehouse.lifecycleState,
                render: (warehouse) => {
                  const presentation =
                    warehouseLifecyclePresentation[warehouse.lifecycleState]
                  return (
                    <Badge variant={presentation.badge}>
                      {presentation.label}
                    </Badge>
                  )
                },
              },
              {
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                cellClassName: "w-72",
                render: (warehouse) => (
                  <WarehouseActions
                    warehouse={warehouse}
                    pending={isMutating}
                    onEdit={openEditor}
                    onScheduleTimeZone={(selected) => {
                      setServerError(null)
                      setTimeZoneSchedule(selected)
                    }}
                    onLifecycleTransition={(selected) => {
                      setServerError(null)
                      setLifecycleTransition(selected)
                    }}
                  />
                ),
              },
            ]}
          />

          <div className="flex min-h-0 flex-col gap-3 overflow-y-auto md:hidden">
            {visibleWarehouses.map((warehouse) => (
              <Card key={warehouse.id} size="sm">
                <CardHeader>
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <CardTitle>{warehouse.name}</CardTitle>
                      {warehouse.representative ? (
                        <Badge variant="outline">Представительский</Badge>
                      ) : null}
                      {warehouse.latitude === null ||
                      warehouse.longitude === null ? (
                        <Badge variant="outline">Нет координат</Badge>
                      ) : null}
                    </div>
                    <Badge
                      variant={
                        warehouseLifecyclePresentation[warehouse.lifecycleState]
                          .badge
                      }
                    >
                      {
                        warehouseLifecyclePresentation[warehouse.lifecycleState]
                          .label
                      }
                    </Badge>
                  </div>
                </CardHeader>
                <CardContent className="flex flex-col gap-3 text-sm">
                  <p className="text-muted-foreground">
                    {warehouse.city} · {warehouse.timeZone} · порядок{" "}
                    {warehouse.sortOrder ?? "—"}
                  </p>
                  <WarehouseActions
                    warehouse={warehouse}
                    pending={isMutating}
                    onEdit={openEditor}
                    onScheduleTimeZone={(selected) => {
                      setServerError(null)
                      setTimeZoneSchedule(selected)
                    }}
                    onLifecycleTransition={(selected) => {
                      setServerError(null)
                      setLifecycleTransition(selected)
                    }}
                  />
                </CardContent>
              </Card>
            ))}
          </div>
        </>
      )}

      {editor !== null ? (
        <WarehouseEditorDialog
          key={editor === "new" ? "new" : editor.id}
          warehouse={editor === "new" ? null : editor}
          pending={saveMutation.isPending}
          serverError={serverError}
          accessToken={accessToken}
          warehouses={warehouses}
          onOpenChange={(open) => {
            if (!open) {
              setEditor(null)
              setServerError(null)
            }
          }}
          onSave={(input) => saveMutation.mutate(input)}
          onSupportSaved={async () => {
            await refreshAfterMutation()
            setEditor(null)
            setServerError(null)
          }}
          onSupportConflict={refreshAfterConflict}
        />
      ) : null}

      {timeZoneSchedule ? (
        <TimeZoneScheduleDialog
          key={`${timeZoneSchedule.id}-${timeZoneSchedule.version}`}
          warehouse={timeZoneSchedule}
          pending={timeZoneMutation.isPending}
          serverError={serverError}
          onOpenChange={(open) => {
            if (!open) {
              setTimeZoneSchedule(null)
              setServerError(null)
            }
          }}
          onSchedule={(timeZone, effectiveFrom) =>
            timeZoneMutation.mutate({ timeZone, effectiveFrom })
          }
        />
      ) : null}

      {lifecycleTransition ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && !lifecycleMutation.isPending) {
              setLifecycleTransition(null)
              setServerError(null)
            }
          }}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>
                {lifecycleTransition.lifecycleState === "ACTIVE"
                  ? "Начать вывод склада из работы?"
                  : "Завершить вывод склада из работы?"}
              </AlertDialogTitle>
              <AlertDialogDescription>
                {lifecycleTransition.lifecycleState === "ACTIVE" ? (
                  <>
                    Склад «{lifecycleTransition.name}» перестанет принимать
                    новые входящие операции, но исходящие операции останутся
                    доступны для освобождения склада. Переход необратим.
                  </>
                ) : (
                  <>
                    Склад «{lifecycleTransition.name}» станет окончательно
                    неактивным. Команда выполнится только после подтверждения
                    готовности сервисами имущества, инвентаризации, логистики,
                    ремонтов и заданий. Переход необратим.
                  </>
                )}
              </AlertDialogDescription>
            </AlertDialogHeader>
            {serverError ? <FieldError>{serverError}</FieldError> : null}
            <AlertDialogFooter>
              <AlertDialogCancel disabled={lifecycleMutation.isPending}>
                Отмена
              </AlertDialogCancel>
              <AlertDialogAction
                variant="destructive"
                disabled={lifecycleMutation.isPending}
                onClick={(event) => {
                  event.preventDefault()
                  lifecycleMutation.mutate()
                }}
              >
                {lifecycleMutation.isPending
                  ? "Выполняем…"
                  : lifecycleTransition.lifecycleState === "ACTIVE"
                    ? "Начать вывод"
                    : "Завершить вывод"}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </div>
  )
}
