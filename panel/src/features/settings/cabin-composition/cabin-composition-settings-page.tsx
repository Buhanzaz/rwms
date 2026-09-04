import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  Delete02Icon,
  PencilEdit01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import {
  createCabinCatalogItem,
  createIdempotencyKey,
  deleteCabinCatalogItem,
  getCabinSettings,
  replaceCabinTypeDimensions,
  updateCabinCatalogItem,
  type CabinCatalogItem,
  type CabinCatalogKind,
  type CabinSettings,
} from "@/features/rental-items/api/asset-rental-items-api"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  Field,
  FieldContent,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApiError } from "@/lib/api-client"

const CABIN_SETTINGS_QUERY_KEY = ["cabin-composition-settings"] as const

const catalogKindLabels: Record<CabinCatalogKind, string> = {
  TYPE: "Типы",
  DIMENSION: "Габариты",
  FINISHING: "Отделка",
  CHARACTERISTIC: "Характеристики",
}

const catalogKindSingularLabels: Record<CabinCatalogKind, string> = {
  TYPE: "тип бытовки",
  DIMENSION: "габарит",
  FINISHING: "отделку",
  CHARACTERISTIC: "характеристику",
}

type CatalogEditorState =
  | { mode: "create"; kind: CabinCatalogKind }
  | { mode: "edit"; item: CabinCatalogItem }

type SettingsMutation = {
  execute: () => Promise<unknown>
  success: string
  close: () => void
  keepOpenOnConflict?: boolean
}

function errorMessage(error: unknown, stale = false) {
  const base = error instanceof Error ? error.message : "Операция не выполнена."
  return stale
    ? `${base} Данные обновлены с сервера. Откройте действие заново.`
    : base
}

function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <Badge variant={active ? "secondary" : "outline"}>
      {active ? "Активно" : "Отключено"}
    </Badge>
  )
}

function CatalogItemDialog({
  editor,
  pending,
  serverError,
  onOpenChange,
  onSave,
  onDelete,
}: {
  editor: CatalogEditorState
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSave: (input: { name: string; active: boolean }) => void
  onDelete?: () => void
}) {
  const item = editor.mode === "edit" ? editor.item : null
  const [name, setName] = useState(item?.name ?? "")
  const [active, setActive] = useState(item?.active ?? true)
  const [validationError, setValidationError] = useState<string | null>(null)
  const kind = editor.mode === "create" ? editor.kind : editor.item.kind
  const formError = validationError ?? serverError

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = name.trim()
    if (!value) {
      setValidationError("Укажите название.")
      return
    }

    setValidationError(null)
    onSave({ name: value, active })
  }

  return (
    <Dialog open onOpenChange={(open) => !pending && onOpenChange(open)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {item
              ? "Настройка бытовки"
              : `Новая запись: ${catalogKindLabels[kind]}`}
          </DialogTitle>
          <DialogDescription>
            В панели отображается только название; идентификатор остаётся внутри
            сервиса.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="flex flex-col gap-5">
          <Field data-invalid={formError !== null || undefined}>
            <FieldLabel htmlFor="cabin-catalog-name">Название</FieldLabel>
            <Input
              id="cabin-catalog-name"
              value={name}
              maxLength={255}
              autoFocus
              aria-invalid={formError !== null}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>

          {item ? (
            <Field orientation="horizontal">
              <Checkbox
                id="cabin-catalog-active"
                checked={active}
                onCheckedChange={(checked) => setActive(checked === true)}
              />
              <FieldContent>
                <FieldLabel htmlFor="cabin-catalog-active">Активна</FieldLabel>
              </FieldContent>
            </Field>
          ) : null}

          {formError ? <FieldError>{formError}</FieldError> : null}

          <DialogFooter>
            {item && onDelete ? (
              <Button
                type="button"
                variant="destructive"
                size="icon"
                className="sm:mr-auto"
                aria-label="Удалить"
                title="Удалить"
                disabled={pending}
                onClick={onDelete}
              >
                <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
              </Button>
            ) : null}
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

function TypeDimensionsDialog({
  type,
  settings,
  pending,
  serverError,
  onOpenChange,
  onSave,
}: {
  type: CabinCatalogItem
  settings: CabinSettings
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSave: (dimensionIds: string[]) => void
}) {
  const linkedDimensionIds = useMemo(
    () =>
      settings.typeDimensions
        .filter((link) => link.typeId === type.id)
        .sort(
          (left, right) =>
            left.sortOrder - right.sortOrder ||
            left.dimensionId.localeCompare(right.dimensionId)
        )
        .map((link) => link.dimensionId),
    [settings.typeDimensions, type.id]
  )
  const [dimensionIds, setDimensionIds] = useState(linkedDimensionIds)
  const activeDimensions = settings.dimensions.filter(
    (dimension) => dimension.active
  )

  function toggle(id: string, checked: boolean) {
    setDimensionIds((current) => {
      if (checked) return current.includes(id) ? current : [...current, id]
      return current.filter((value) => value !== id)
    })
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSave(dimensionIds)
  }

  return (
    <Dialog open onOpenChange={(open) => !pending && onOpenChange(open)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Габариты для типа «{type.name}»</DialogTitle>
          <DialogDescription>
            Выберите активные габариты. Можно снять все габариты только у типа
            без бытовок — наличие бытовок сервис проверит при сохранении.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="flex flex-col gap-5">
          <FieldGroup className="gap-3">
            {activeDimensions.length > 0 ? (
              activeDimensions.map((dimension) => {
                const inputId = `cabin-type-dimension-${dimension.id}`
                return (
                  <Field key={dimension.id} orientation="horizontal">
                    <Checkbox
                      id={inputId}
                      checked={dimensionIds.includes(dimension.id)}
                      onCheckedChange={(checked) =>
                        toggle(dimension.id, checked === true)
                      }
                    />
                    <FieldLabel htmlFor={inputId} className="font-normal">
                      {dimension.name}
                    </FieldLabel>
                  </Field>
                )
              })
            ) : (
              <p className="text-sm text-muted-foreground">
                Сначала добавьте и активируйте хотя бы один габарит.
              </p>
            )}
          </FieldGroup>

          {serverError ? <FieldError>{serverError}</FieldError> : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Сохранить габариты"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function CabinCatalogDeleteDialog({
  item,
  pending,
  error,
  onClose,
  onConfirm,
}: {
  item: CabinCatalogItem
  pending: boolean
  error: string | null
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose()
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Удалить {catalogKindSingularLabels[item.kind]} «{item.name}»?
          </AlertDialogTitle>
          <AlertDialogDescription>
            Это действие нельзя отменить. Если настройка уже используется в
            бытовке или связана с типом, сервис не даст её удалить и покажет
            причину.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {error ? (
          <p role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}

        <AlertDialogFooter>
          <AlertDialogAction
            variant="destructive"
            size="icon"
            className="sm:mr-auto"
            aria-label={pending ? "Удаление…" : "Удалить"}
            title="Удалить"
            disabled={pending}
            onClick={(event) => {
              event.preventDefault()
              onConfirm()
            }}
          >
            <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
          </AlertDialogAction>
          <AlertDialogCancel disabled={pending}>Отмена</AlertDialogCancel>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function CatalogSection({
  kind,
  items,
  settings,
  pending,
  onEdit,
  onEditDimensions,
}: {
  kind: CabinCatalogKind
  items: CabinCatalogItem[]
  settings: CabinSettings
  pending: boolean
  onEdit: (item: CabinCatalogItem) => void
  onEditDimensions: (type: CabinCatalogItem) => void
}) {
  const dimensionsById = new Map(
    settings.dimensions.map((dimension) => [dimension.id, dimension.name])
  )
  const linkedDimensions = (item: CabinCatalogItem) =>
    kind === "TYPE"
      ? settings.typeDimensions
          .filter((link) => link.typeId === item.id)
          .sort(
            (left, right) =>
              left.sortOrder - right.sortOrder ||
              left.dimensionId.localeCompare(right.dimensionId)
          )
          .flatMap((link) => {
            const name = dimensionsById.get(link.dimensionId)
            return name ? [name] : []
          })
      : []
  const columns: OperationsListGridColumn<CabinCatalogItem>[] = [
    {
      id: "name",
      label: "Название",
      getSortValue: (item) => item.name,
      render: (item) => <span className="font-medium">{item.name}</span>,
    },
    {
      id: "status",
      label: "Доступность",
      getSortValue: (item) => (item.active ? 1 : 0),
      render: (item) => <StatusBadge active={item.active} />,
    },
    ...(kind === "TYPE"
      ? [
          {
            id: "dimensions",
            label: "Габариты",
            getSortValue: (item) => linkedDimensions(item).join(", "),
            render: (item) => {
              const names = linkedDimensions(item)
              return names.length > 0 ? names.join(", ") : "—"
            },
          } satisfies OperationsListGridColumn<CabinCatalogItem>,
        ]
      : []),
    {
      id: "actions",
      label: "Действия",
      getSortValue: () => null,
      className: "w-0",
      cellClassName: "w-0",
      render: (item) => (
        <div className="flex justify-end gap-2">
          {kind === "TYPE" ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={pending}
              aria-label={`Настроить габариты ${item.name}`}
              onClick={() => onEditDimensions(item)}
            >
              Габариты
            </Button>
          ) : null}
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            disabled={pending}
            aria-label={`Изменить ${item.name}`}
            onClick={() => onEdit(item)}
          >
            <HugeiconsIcon icon={PencilEdit01Icon} aria-hidden="true" />
          </Button>
        </div>
      ),
    },
  ]

  return (
    <section
      className="flex min-h-0 flex-col gap-3"
      aria-label={catalogKindLabels[kind]}
    >
      {items.length > 0 ? (
        <OperationsListGrid
          className="min-h-0 flex-1 overflow-auto bg-muted/70"
          items={items}
          columns={columns}
        />
      ) : (
        <div className="flex min-h-36 items-center justify-center rounded-lg border bg-muted/55 px-4 text-center text-sm text-muted-foreground">
          Записей пока нет.
        </div>
      )}
    </section>
  )
}

export function CabinCompositionSettingsPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const [activeKind, setActiveKind] = useState<CabinCatalogKind>("TYPE")
  const [editor, setEditor] = useState<CatalogEditorState | null>(null)
  const [dimensionsEditor, setDimensionsEditor] =
    useState<CabinCatalogItem | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<CabinCatalogItem | null>(
    null
  )
  const [actionError, setActionError] = useState<string | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const canManage = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )
  const settingsQuery = useQuery({
    queryKey: CABIN_SETTINGS_QUERY_KEY,
    queryFn: () => getCabinSettings(accessToken),
    enabled: Boolean(accessToken && canManage),
  })

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: CABIN_SETTINGS_QUERY_KEY })
  }

  const mutation = useMutation({
    mutationFn: (command: SettingsMutation) => command.execute(),
    onSuccess: async (_result, command) => {
      setActionError(null)
      command.close()
      await refresh()
      toast.success(command.success)
    },
    onError: async (error, command) => {
      const stale = isConflict(error) && !command.keepOpenOnConflict
      const message = errorMessage(error, stale)
      setActionError(message)
      toast.error(message)
      if (stale) {
        setEditor(null)
        setDimensionsEditor(null)
        await refresh()
      }
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (item: CabinCatalogItem) =>
      deleteCabinCatalogItem({
        accessToken,
        id: item.id,
        expectedVersion: item.version,
      }),
    onSuccess: async () => {
      setDeleteError(null)
      setDeleteTarget(null)
      await refresh()
      toast.success("Настройка удалена.")
    },
    onError: (error) => {
      const message = errorMessage(error)
      setDeleteError(message)
      toast.error(message)
    },
  })

  function run(command: SettingsMutation) {
    setActionError(null)
    mutation.mutate(command)
  }

  if (!canManage) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Настройки бытовок</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Управление составом бытовок доступно только системному администратору.
        </CardContent>
      </Card>
    )
  }

  if (!accessToken) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Настройки бытовок</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Войдите в панель, чтобы открыть настройки.
        </CardContent>
      </Card>
    )
  }

  if (settingsQuery.isLoading) {
    return (
      <p className="text-sm text-muted-foreground">
        Загрузка настроек бытовок…
      </p>
    )
  }

  if (settingsQuery.isError || !settingsQuery.data) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Настройки бытовок</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          <p role="alert" className="text-destructive">
            {errorMessage(settingsQuery.error)}
          </p>
          <Button
            type="button"
            variant="outline"
            onClick={() => void refresh()}
          >
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }

  const settings = settingsQuery.data
  const itemsByKind: Record<CabinCatalogKind, CabinCatalogItem[]> = {
    TYPE: settings.types,
    DIMENSION: settings.dimensions,
    FINISHING: settings.finishings,
    CHARACTERISTIC: settings.characteristics,
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden pb-4">
      <div className="flex min-w-0 items-center justify-between gap-3">
        <Tabs
          className="min-w-0"
          value={activeKind}
          onValueChange={(value) => setActiveKind(value as CabinCatalogKind)}
        >
          <div className="max-w-full overflow-x-auto pb-1">
            <TabsList className="min-w-max bg-muted/75 shadow-xs backdrop-blur-md">
              {(Object.keys(catalogKindLabels) as CabinCatalogKind[]).map(
                (kind) => (
                  <TabsTrigger key={kind} value={kind}>
                    {catalogKindLabels[kind]}
                  </TabsTrigger>
                )
              )}
            </TabsList>
          </div>
        </Tabs>
        <Button
          type="button"
          size="sm"
          onClick={() => setEditor({ mode: "create", kind: activeKind })}
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Добавить
        </Button>
      </div>

      <CatalogSection
        kind={activeKind}
        items={itemsByKind[activeKind]}
        settings={settings}
        pending={mutation.isPending || deleteMutation.isPending}
        onEdit={(item) => setEditor({ mode: "edit", item })}
        onEditDimensions={setDimensionsEditor}
      />

      {editor ? (
        <CatalogItemDialog
          key={
            editor.mode === "create"
              ? `create-${editor.kind}`
              : `edit-${editor.item.id}-${editor.item.version}`
          }
          editor={editor}
          pending={mutation.isPending}
          serverError={actionError}
          onOpenChange={(open) => {
            if (!open) {
              setEditor(null)
              setActionError(null)
            }
          }}
          onSave={(input) => {
            if (editor.mode === "create") {
              run({
                execute: () =>
                  createCabinCatalogItem({
                    accessToken,
                    idempotencyKey: createIdempotencyKey(),
                    input: { kind: editor.kind, name: input.name },
                  }),
                success: "Запись добавлена.",
                close: () => setEditor(null),
              })
              return
            }

            run({
              execute: () =>
                updateCabinCatalogItem({
                  accessToken,
                  id: editor.item.id,
                  expectedVersion: editor.item.version,
                  name: input.name,
                  active: input.active,
                }),
              success: "Настройка сохранена.",
              close: () => setEditor(null),
            })
          }}
          onDelete={
            editor.mode === "edit"
              ? () => {
                  setEditor(null)
                  setActionError(null)
                  setDeleteError(null)
                  setDeleteTarget(editor.item)
                }
              : undefined
          }
        />
      ) : null}

      {dimensionsEditor ? (
        <TypeDimensionsDialog
          key={`${dimensionsEditor.id}-${dimensionsEditor.version}`}
          type={dimensionsEditor}
          settings={settings}
          pending={mutation.isPending}
          serverError={actionError}
          onOpenChange={(open) => {
            if (!open) {
              setDimensionsEditor(null)
              setActionError(null)
            }
          }}
          onSave={(dimensionIds) =>
            run({
              execute: () =>
                replaceCabinTypeDimensions({
                  accessToken,
                  typeId: dimensionsEditor.id,
                  expectedVersion: dimensionsEditor.version,
                  dimensionIds,
                }),
              success: "Габариты типа сохранены.",
              close: () => setDimensionsEditor(null),
              keepOpenOnConflict: true,
            })
          }
        />
      ) : null}

      {deleteTarget ? (
        <CabinCatalogDeleteDialog
          key={`${deleteTarget.id}-${deleteTarget.version}`}
          item={deleteTarget}
          pending={deleteMutation.isPending}
          error={deleteError}
          onClose={() => {
            setDeleteError(null)
            setDeleteTarget(null)
          }}
          onConfirm={() => deleteMutation.mutate(deleteTarget)}
        />
      ) : null}
    </div>
  )
}
