import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  createWarehouse,
  deactivateWarehouse,
  listWarehouses,
  replaceWarehouse,
  type WarehouseCreateInput,
  type WarehouseInfo,
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
  FieldContent,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
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
import { useWarehouse } from "@/hooks/use-warehouse"

const WAREHOUSES_QUERY_KEY = ["warehouse-settings"] as const

type ActivityFilter = "all" | "active" | "inactive"

function WarehouseEditorDialog({
  warehouse,
  pending,
  serverError,
  onOpenChange,
  onSave,
}: {
  warehouse: WarehouseInfo | null
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSave: (input: WarehouseWriteInput) => void
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
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{warehouse ? "Склад" : "Новый склад"}</DialogTitle>
          <DialogDescription>
            Идентичность, метаданные и временная зона. Топология склада здесь не
            редактируется.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={(event) => void submit(event)}>
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field data-invalid={formError !== null || undefined}>
              <FieldLabel htmlFor="warehouse-code">Код</FieldLabel>
              <Input
                id="warehouse-code"
                value={values.code}
                maxLength={64}
                onChange={(event) => updateValue("code", event.target.value)}
                placeholder="WH_NORTH"
                required
                aria-invalid={formError !== null}
              />
            </Field>
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
            {warehouse ? (
              <Field orientation="horizontal" className="self-end pb-2">
                <Checkbox
                  id="warehouse-active"
                  checked={values.active}
                  onCheckedChange={(value) =>
                    updateValue("active", value === true)
                  }
                />
                <FieldContent>
                  <FieldLabel htmlFor="warehouse-active">Активен</FieldLabel>
                </FieldContent>
              </Field>
            ) : null}
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
      </DialogContent>
    </Dialog>
  )
}

function WarehouseActions({
  warehouse,
  pending,
  onEdit,
  onDeactivate,
  onReactivate,
}: {
  warehouse: WarehouseInfo
  pending: boolean
  onEdit: (warehouse: WarehouseInfo) => void
  onDeactivate: (warehouse: WarehouseInfo) => void
  onReactivate: (warehouse: WarehouseInfo) => void
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
      {warehouse.active ? (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={pending}
          onClick={() => onDeactivate(warehouse)}
        >
          Деактивировать
        </Button>
      ) : (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={pending}
          onClick={() => onReactivate(warehouse)}
        >
          Активировать
        </Button>
      )}
    </div>
  )
}

export function WarehouseSettingsPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { reloadWarehouses } = useWarehouse()
  const [search, setSearch] = useState("")
  const [activityFilter, setActivityFilter] = useState<ActivityFilter>("all")
  const [editor, setEditor] = useState<WarehouseInfo | "new" | null>(null)
  const [deactivating, setDeactivating] = useState<WarehouseInfo | null>(null)
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
    setDeactivating(null)
  }

  const saveMutation = useMutation({
    mutationFn: async (input: WarehouseWriteInput) => {
      if (accessToken === null) {
        throw new Error("Сессия завершена.")
      }

      if (editor === "new") {
        const createInput: WarehouseCreateInput = {
          code: input.code,
          name: input.name,
          city: input.city,
          address: input.address,
          timeZone: input.timeZone,
          sortOrder: input.sortOrder,
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

  const deactivateMutation = useMutation({
    mutationFn: async () => {
      if (accessToken === null || deactivating === null) {
        throw new Error("Сессия завершена или склад не выбран.")
      }

      await deactivateWarehouse(
        accessToken,
        deactivating.id,
        deactivating.version
      )
    },
    onSuccess: async () => {
      await refreshAfterMutation()
      setDeactivating(null)
      setServerError(null)
      toast.success("Склад деактивирован.")
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

  const reactivateMutation = useMutation({
    mutationFn: async (warehouse: WarehouseInfo) => {
      if (accessToken === null) {
        throw new Error("Сессия завершена.")
      }

      return replaceWarehouse(accessToken, warehouse.id, warehouse.version, {
        code: warehouse.code,
        name: warehouse.name,
        city: warehouse.city,
        address: warehouse.address,
        timeZone: warehouse.timeZone,
        active: true,
        sortOrder: warehouse.sortOrder,
      })
    },
    onSuccess: async () => {
      await refreshAfterMutation()
      setServerError(null)
      toast.success("Склад активирован.")
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

  const visibleWarehouses = useMemo(() => {
    const normalizedSearch = search.trim().toLocaleLowerCase("ru")

    return (warehousesQuery.data ?? []).filter((warehouse) => {
      const matchesActivity =
        activityFilter === "all" ||
        (activityFilter === "active" && warehouse.active) ||
        (activityFilter === "inactive" && !warehouse.active)
      const matchesSearch =
        !normalizedSearch ||
        [
          warehouse.code,
          warehouse.name,
          warehouse.city,
          warehouse.timeZone,
        ].some((value) =>
          value.toLocaleLowerCase("ru").includes(normalizedSearch)
        )

      return matchesActivity && matchesSearch
    })
  }, [activityFilter, search, warehousesQuery.data])

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
    deactivateMutation.isPending ||
    reactivateMutation.isPending

  function openEditor(nextEditor: WarehouseInfo | "new") {
    setServerError(null)
    setEditor(nextEditor)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent>
          <h1 className="text-lg font-semibold">Склады</h1>
          <p className="text-sm text-muted-foreground">
            Идентичность, метаданные и временная зона. Топология склада пока не
            определена.
          </p>
        </PageToolbarContent>
        <PageToolbarActions>
          <Button type="button" onClick={() => openEditor("new")}>
            Создать склад
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      <div className="flex flex-col gap-3 sm:flex-row">
        <Input
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="Поиск по коду, названию или городу"
          aria-label="Поиск складов"
          className="sm:max-w-md"
        />
        <Select
          value={activityFilter}
          onValueChange={(value) => setActivityFilter(value as ActivityFilter)}
        >
          <SelectTrigger
            aria-label="Статус склада"
            className="sm:ml-auto sm:w-48"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value="all">Все статусы</SelectItem>
              <SelectItem value="active">Активные</SelectItem>
              <SelectItem value="inactive">Неактивные</SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
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
                id: "code",
                label: "Код",
                getSortValue: (warehouse) => warehouse.code,
                render: (warehouse) => warehouse.code,
              },
              {
                id: "name",
                label: "Название",
                getSortValue: (warehouse) => warehouse.name,
                render: (warehouse) => warehouse.name,
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
                getSortValue: (warehouse) => (warehouse.active ? 1 : 0),
                render: (warehouse) => (
                  <Badge variant={warehouse.active ? "secondary" : "outline"}>
                    {warehouse.active ? "Активен" : "Неактивен"}
                  </Badge>
                ),
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
                    onDeactivate={(selected) => {
                      setServerError(null)
                      setDeactivating(selected)
                    }}
                    onReactivate={(selected) => {
                      setServerError(null)
                      reactivateMutation.mutate(selected)
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
                    <div>
                      <CardTitle>{warehouse.name}</CardTitle>
                      <p className="text-sm text-muted-foreground">
                        {warehouse.code}
                      </p>
                    </div>
                    <Badge variant={warehouse.active ? "secondary" : "outline"}>
                      {warehouse.active ? "Активен" : "Неактивен"}
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
                    onDeactivate={(selected) => {
                      setServerError(null)
                      setDeactivating(selected)
                    }}
                    onReactivate={(selected) => {
                      setServerError(null)
                      reactivateMutation.mutate(selected)
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
          onOpenChange={(open) => {
            if (!open) {
              setEditor(null)
              setServerError(null)
            }
          }}
          onSave={(input) => saveMutation.mutate(input)}
        />
      ) : null}

      {deactivating ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && !deactivateMutation.isPending) {
              setDeactivating(null)
              setServerError(null)
            }
          }}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Деактивировать склад?</AlertDialogTitle>
              <AlertDialogDescription>
                Склад {deactivating.code} исчезнет из обычного выбора.
                Реактивировать его можно из этого списка.
              </AlertDialogDescription>
            </AlertDialogHeader>
            {serverError ? <FieldError>{serverError}</FieldError> : null}
            <AlertDialogFooter>
              <AlertDialogCancel disabled={deactivateMutation.isPending}>
                Отмена
              </AlertDialogCancel>
              <AlertDialogAction
                variant="destructive"
                disabled={deactivateMutation.isPending}
                onClick={(event) => {
                  event.preventDefault()
                  deactivateMutation.mutate()
                }}
              >
                {deactivateMutation.isPending ? "Выполняем…" : "Деактивировать"}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </div>
  )
}
