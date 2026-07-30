import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

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
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
  const base =
    error instanceof Error ? error.message : "Операция не выполнена."
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
}: {
  editor: CatalogEditorState
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSave: (input: { name: string; active: boolean }) => void
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
            {item ? "Настройка бытовки" : `Новая запись: ${catalogKindLabels[kind]}`}
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
                <FieldLabel htmlFor="cabin-catalog-active">
                  Активна
                </FieldLabel>
              </FieldContent>
            </Field>
          ) : null}

          {formError ? <FieldError>{formError}</FieldError> : null}

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
            <Button
              type="submit"
              disabled={pending}
            >
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
          <AlertDialogCancel disabled={pending}>Отмена</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={pending}
            onClick={(event) => {
              event.preventDefault()
              onConfirm()
            }}
          >
            {pending ? "Удаляем…" : "Удалить"}
          </AlertDialogAction>
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
  onAdd,
  onEdit,
  onEditDimensions,
  onDelete,
}: {
  kind: CabinCatalogKind
  items: CabinCatalogItem[]
  settings: CabinSettings
  pending: boolean
  onAdd: (kind: CabinCatalogKind) => void
  onEdit: (item: CabinCatalogItem) => void
  onEditDimensions: (type: CabinCatalogItem) => void
  onDelete: (item: CabinCatalogItem) => void
}) {
  const dimensionsById = new Map(
    settings.dimensions.map((dimension) => [dimension.id, dimension.name])
  )

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle>{catalogKindLabels[kind]}</CardTitle>
            <CardDescription>
              {kind === "TYPE"
                ? "Типы бытовок и доступные для них габариты."
                : "Глобальный каталог состава бытовки."}
            </CardDescription>
          </div>
          <Button type="button" size="sm" onClick={() => onAdd(kind)}>
            Добавить
          </Button>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {items.length > 0 ? (
          items.map((item) => {
            const linkedDimensions =
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
            return (
              <article
                key={item.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{item.name}</span>
                    <StatusBadge active={item.active} />
                  </div>
                  {kind === "TYPE" ? (
                    linkedDimensions.length > 0 ? (
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {linkedDimensions.map((name) => (
                          <Badge key={name} variant="outline">
                            {name}
                          </Badge>
                        ))}
                      </div>
                    ) : (
                      <p className="mt-1 text-xs text-muted-foreground">
                        Габариты не выбраны.
                      </p>
                    )
                  ) : null}
                </div>
                <div className="flex flex-wrap gap-2">
                  {kind === "TYPE" ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={pending}
                      onClick={() => onEditDimensions(item)}
                    >
                      Габариты
                    </Button>
                  ) : null}
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={pending}
                    onClick={() => onEdit(item)}
                  >
                    Изменить
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="destructive"
                    disabled={pending}
                    onClick={() => onDelete(item)}
                  >
                    Удалить
                  </Button>
                </div>
              </article>
            )
          })
        ) : (
          <p className="text-sm text-muted-foreground">Записей пока нет.</p>
        )}
      </CardContent>
    </Card>
  )
}

export function CabinCompositionSettingsPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
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
    return <p className="text-sm text-muted-foreground">Загрузка настроек бытовок…</p>
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
          <Button type="button" variant="outline" onClick={() => void refresh()}>
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }

  const settings = settingsQuery.data

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pb-4">
      <div>
        <h1 className="text-xl font-semibold">Настройки бытовок</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Управляйте типами, габаритами, отделкой и характеристиками бытовок.
        </p>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <CatalogSection
          kind="TYPE"
          items={settings.types}
          settings={settings}
          pending={mutation.isPending || deleteMutation.isPending}
          onAdd={(kind) => setEditor({ mode: "create", kind })}
          onEdit={(item) => setEditor({ mode: "edit", item })}
          onEditDimensions={setDimensionsEditor}
          onDelete={(item) => {
            setDeleteError(null)
            setDeleteTarget(item)
          }}
        />
        <CatalogSection
          kind="DIMENSION"
          items={settings.dimensions}
          settings={settings}
          pending={mutation.isPending || deleteMutation.isPending}
          onAdd={(kind) => setEditor({ mode: "create", kind })}
          onEdit={(item) => setEditor({ mode: "edit", item })}
          onEditDimensions={setDimensionsEditor}
          onDelete={(item) => {
            setDeleteError(null)
            setDeleteTarget(item)
          }}
        />
        <CatalogSection
          kind="FINISHING"
          items={settings.finishings}
          settings={settings}
          pending={mutation.isPending || deleteMutation.isPending}
          onAdd={(kind) => setEditor({ mode: "create", kind })}
          onEdit={(item) => setEditor({ mode: "edit", item })}
          onEditDimensions={setDimensionsEditor}
          onDelete={(item) => {
            setDeleteError(null)
            setDeleteTarget(item)
          }}
        />
        <CatalogSection
          kind="CHARACTERISTIC"
          items={settings.characteristics}
          settings={settings}
          pending={mutation.isPending || deleteMutation.isPending}
          onAdd={(kind) => setEditor({ mode: "create", kind })}
          onEdit={(item) => setEditor({ mode: "edit", item })}
          onEditDimensions={setDimensionsEditor}
          onDelete={(item) => {
            setDeleteError(null)
            setDeleteTarget(item)
          }}
        />
      </div>

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
