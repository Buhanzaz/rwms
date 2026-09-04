import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  Loading03Icon,
  Refresh01Icon,
  Settings02Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import type { WarehouseInfo } from "@/api/warehouse-api"
import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/features/auth/use-auth"
import {
  adminCatalogKeys,
  createAdminCatalogResource,
  deleteAdminCatalogResource,
  listAdminCatalogResources,
  updateAdminCatalogResource,
  type AdminCatalogKind,
  type AdminCatalogResource,
  type AdminCatalogResourceInput,
} from "@/features/settings/fleet/api/admin-catalog-api"
import { SettingsDeleteDialog } from "@/features/settings/task-board/settings-delete-dialog"
import { ApiError } from "@/lib/api-client"

import {
  AdminCatalogResourceEditor,
  type CatalogEditorState,
} from "@/features/settings/fleet/admin-catalog-resource-editor"

function resourceLabel(kind: AdminCatalogKind) {
  return kind === "vehicle" ? "транспорт" : "прицеп"
}

function catalogError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "CATALOG_VERSION_CONFLICT":
        return "Запись уже изменена другим администратором. Закройте редактор и откройте актуальную запись заново; ваши несохранённые значения пока остаются в форме."
      case "DEFAULT_TRAILER_REQUIRES_CAPABILITY":
        return "Для прицепа по умолчанию подтвердите возможность буксировки."
      case "TRAILER_WAREHOUSE_MISMATCH":
        return "Прицеп должен принадлежать тому же объекту, что и автомобиль."
      case "VEHICLE_HAS_ACTIVE_SHIFTS":
        return "Операция недоступна: у транспорта есть активная смена."
      case "VEHICLE_HAS_LINKED_SHIFTS":
        return "Транспорт нельзя удалить: с ним сохранена история смен."
      case "VEHICLE_HAS_DEFAULT_TRAILER":
        return "Сначала отвяжите прицеп по умолчанию от транспорта."
      case "TRAILER_IS_DEFAULT_FOR_VEHICLE":
        return "Прицеп назначен транспортом по умолчанию. Сначала отвяжите его."
      case "WAREHOUSE_NOT_CONFIGURED_FOR_LOGISTICS":
        return "Объект назначения ещё не подключён к сервису логистики."
      case "WAREHOUSE_NOT_ELIGIBLE_FOR_CATALOG":
        return "Объект назначения не является производством или основным складом."
      case "CATALOG_RELOCATION_TARGET_UNCHANGED":
        return "Ресурс уже находится на выбранном объекте."
    }
    return error.message
  }
  return error instanceof Error ? error.message : "Не удалось изменить каталог."
}

export function AdminCatalogSettingsPage({
  kind,
  warehouse,
}: {
  kind: AdminCatalogKind
  warehouse: WarehouseInfo
}) {
  const { accessToken } = useAuth()
  const queryClient = useQueryClient()
  const queryKey = adminCatalogKeys.warehouse(kind, warehouse.id)
  const [editor, setEditor] = useState<CatalogEditorState | null>(null)
  const [deletion, setDeletion] = useState<AdminCatalogResource | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const catalogQuery = useQuery({
    queryKey,
    queryFn: () => listAdminCatalogResources(accessToken!, warehouse.id, kind),
    enabled: Boolean(accessToken),
  })

  const trailersQuery = useQuery({
    queryKey: adminCatalogKeys.warehouse("trailer", warehouse.id),
    queryFn: () =>
      listAdminCatalogResources(accessToken!, warehouse.id, "trailer"),
    enabled: Boolean(accessToken && editor && kind === "vehicle"),
  })

  async function refreshWarehouse(warehouseId: string) {
    await queryClient.invalidateQueries({
      queryKey: adminCatalogKeys.warehouse(kind, warehouseId),
    })
  }

  const saveMutation = useMutation({
    mutationFn: async ({
      state,
      input,
    }: {
      state: CatalogEditorState
      input: AdminCatalogResourceInput
    }) =>
      state.resource
        ? updateAdminCatalogResource(accessToken!, kind, state.resource, input)
        : createAdminCatalogResource(
            accessToken!,
            warehouse.id,
            kind,
            input,
            state.idempotencyKey
          ),
    onSuccess: async () => {
      setEditor(null)
      setActionError(null)
      toast.success(`${kind === "vehicle" ? "Транспорт" : "Прицеп"} сохранён.`)
      await refreshWarehouse(warehouse.id)
    },
    onError: async (error) => {
      const message = catalogError(error)
      setActionError(message)
      toast.error(message)
      if (error instanceof ApiError && error.status === 409) {
        await refreshWarehouse(warehouse.id)
      }
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (resource: AdminCatalogResource) =>
      deleteAdminCatalogResource(accessToken!, kind, resource),
    onSuccess: async () => {
      setDeletion(null)
      setActionError(null)
      toast.success(`${kind === "vehicle" ? "Транспорт" : "Прицеп"} удалён.`)
      await refreshWarehouse(warehouse.id)
    },
    onError: async (error) => {
      const message = catalogError(error)
      setActionError(message)
      toast.error(message)
      await refreshWarehouse(warehouse.id)
    },
  })

  if (!accessToken) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Нет токена доступа</AlertTitle>
        <AlertDescription>
          Повторите вход, чтобы загрузить каталог.
        </AlertDescription>
      </Alert>
    )
  }
  if (catalogQuery.isError && catalogQuery.data === undefined) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить каталог</AlertTitle>
        <AlertDescription className="flex flex-col items-start gap-3">
          <span>{catalogError(catalogQuery.error)}</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={catalogQuery.isFetching}
            onClick={() => void catalogQuery.refetch()}
          >
            <HugeiconsIcon
              icon={catalogQuery.isFetching ? Loading03Icon : Refresh01Icon}
              data-icon="inline-start"
              className={catalogQuery.isFetching ? "animate-spin" : undefined}
              aria-hidden="true"
            />
            Повторить
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  const resources = catalogQuery.data ?? []
  const refreshError = catalogQuery.isRefetchError
    ? `Не удалось обновить каталог: ${catalogError(catalogQuery.error)}`
    : null
  const columns: OperationsListGridColumn<AdminCatalogResource>[] = [
    {
      id: "name",
      label: kind === "vehicle" ? "Транспорт" : "Прицеп",
      getSortValue: (item) => item.name,
      render: (item) => <span className="font-medium">{item.name}</span>,
    },
    {
      id: "registration-number",
      label: "Регистрационный номер",
      getSortValue: (item) => item.registrationNumber,
      render: (item) => (
        <span className="font-mono">{item.registrationNumber}</span>
      ),
    },
    ...(kind === "vehicle"
      ? [
          {
            id: "capacity",
            label: "Вместимость",
            getSortValue: (item: AdminCatalogResource) => item.capacity,
            render: (item: AdminCatalogResource) =>
              [item.capacity ?? "—", "бытовки"].join(" "),
          },
        ]
      : []),
    {
      id: "notes",
      label: "Комментарий",
      getSortValue: (item) => item.notes,
      render: (item) =>
        item.notes ? (
          <span
            className="block max-w-80 truncate text-muted-foreground"
            title={item.notes}
          >
            {item.notes}
          </span>
        ) : (
          "—"
        ),
    },
    {
      id: "active",
      label: "Активен",
      getSortValue: (item) => (item.active ? 1 : 0),
      render: (item) => (
        <Badge variant={item.active ? "secondary" : "outline"}>
          {item.active ? "Да" : "Нет"}
        </Badge>
      ),
    },
    {
      id: "actions",
      label: "Действия",
      getSortValue: () => null,
      className: "w-36",
      render: (item) => (
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            setActionError(null)
            setEditor({
              resource: item,
              idempotencyKey: crypto.randomUUID(),
            })
          }}
        >
          <HugeiconsIcon
            icon={Settings02Icon}
            data-icon="inline-start"
            aria-hidden="true"
          />
          Изменить
        </Button>
      ),
    },
  ]

  return (
    <section
      className="flex min-h-0 flex-1 flex-col"
      aria-label={resourceLabel(kind)}
    >
      <div className="absolute top-0 right-0 z-10 flex h-9 items-center">
        <Button
          type="button"
          size="sm"
          onClick={() => {
            setActionError(null)
            setEditor({ resource: null, idempotencyKey: crypto.randomUUID() })
          }}
        >
          <HugeiconsIcon
            icon={Add01Icon}
            data-icon="inline-start"
            aria-hidden="true"
          />
          Добавить
        </Button>
      </div>

      {refreshError ? (
        <Alert variant="destructive" className="mb-4">
          <AlertTitle>Каталог не обновлён</AlertTitle>
          <AlertDescription>
            <span>{refreshError} Показаны последние полученные данные.</span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={catalogQuery.isFetching}
              onClick={() => void catalogQuery.refetch()}
            >
              Обновить каталог
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {catalogQuery.isLoading ? (
        <div className="flex min-h-36 items-center justify-center gap-2 rounded-lg border bg-muted/55 text-sm text-muted-foreground">
          <HugeiconsIcon
            icon={Loading03Icon}
            className="animate-spin"
            aria-hidden="true"
          />
          Загружаем каталог…
        </div>
      ) : resources.length === 0 ? (
        <div className="flex min-h-36 items-center justify-center rounded-lg border bg-muted/55 px-4 text-center text-sm text-muted-foreground">
          {kind === "vehicle" ? "Транспорт" : "Прицепы"} для объекта ещё не
          добавлен.
        </div>
      ) : (
        <OperationsListGrid
          className="min-h-0 flex-1 overflow-auto bg-muted/70"
          items={resources}
          columns={columns}
        />
      )}

      {editor ? (
        <AdminCatalogResourceEditor
          key={`${editor.resource?.id ?? "new"}:${editor.resource?.version ?? 0}`}
          kind={kind}
          state={editor}
          trailers={trailersQuery.data ?? []}
          trailersLoading={trailersQuery.isFetching}
          trailersError={
            trailersQuery.isError ? catalogError(trailersQuery.error) : null
          }
          onReloadTrailers={() => void trailersQuery.refetch()}
          pending={saveMutation.isPending}
          error={[actionError, refreshError].filter(Boolean).join(" ") || null}
          onClose={() => {
            setEditor(null)
            setActionError(null)
          }}
          onSave={async (input) => {
            try {
              await saveMutation.mutateAsync({ state: editor, input })
            } catch {
              // Mutation callbacks keep the actionable server error in the dialog.
            }
          }}
          onDelete={
            editor.resource
              ? () => {
                  setEditor(null)
                  setActionError(null)
                  setDeletion(editor.resource!)
                }
              : undefined
          }
        />
      ) : null}
      {deletion ? (
        <SettingsDeleteDialog
          title={`Удалить «${deletion.name}»?`}
          description="Сервис проверит все связанные смены и назначения. Используемый ресурс удалить нельзя."
          pending={deleteMutation.isPending}
          error={actionError}
          onClose={() => {
            setDeletion(null)
            setActionError(null)
          }}
          onConfirm={async () => {
            try {
              await deleteMutation.mutateAsync(deletion)
            } catch {
              // Mutation callbacks keep the actionable server error in the dialog.
            }
          }}
        />
      ) : null}
    </section>
  )
}
