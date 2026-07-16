import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  createAssetClassifier,
  createAssetEquipmentCatalogItem,
  listAssetClassifiers,
  listAssetEquipmentCatalog,
  updateAssetClassifier,
  updateAssetEquipmentCatalogItem,
  type AssetClassifier,
  type AssetClassifierType,
  type AssetEquipment,
  type AssetEquipmentCategory,
} from "@/api/asset-api"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
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
  Field,
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
import { Textarea } from "@/components/ui/textarea"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"

const EQUIPMENT_CATALOG_QUERY_KEY = ["asset-equipment-catalog"] as const
const CLASSIFIERS_QUERY_KEY = ["asset-classifiers"] as const

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось сохранить справочник."
}

type EquipmentDraft = {
  code: string
  name: string
  category: AssetEquipmentCategory
  active: boolean
  comment: string
}

function equipmentDraft(item: AssetEquipment | null): EquipmentDraft {
  return {
    code: item?.code ?? "",
    name: item?.name ?? "",
    category: item?.category ?? "FURNITURE",
    active: item?.active ?? true,
    comment: item?.comment ?? "",
  }
}

function EquipmentEditor({
  item,
  pending,
  onClose,
  onSave,
}: {
  item: AssetEquipment | null
  pending: boolean
  onClose: () => void
  onSave: (draft: EquipmentDraft) => Promise<void>
}) {
  const [draft, setDraft] = useState(() => equipmentDraft(item))
  const [error, setError] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draft.code.trim() || !draft.name.trim()) {
      setError("Укажите код и наименование.")
      return
    }
    setError(null)
    await onSave(draft)
  }
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>
            {item ? "Оборудование" : "Новое оборудование"}
          </DialogTitle>
          <DialogDescription>
            Глобальный каталог: складские остатки и комментарии в Kafka не
            публикуются.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="asset-equipment-code">Код</FieldLabel>
              <Input
                id="asset-equipment-code"
                value={draft.code}
                maxLength={64}
                onChange={(event) =>
                  setDraft((value) => ({ ...value, code: event.target.value }))
                }
                required
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-equipment-name">
                Наименование
              </FieldLabel>
              <Input
                id="asset-equipment-name"
                value={draft.name}
                maxLength={255}
                onChange={(event) =>
                  setDraft((value) => ({ ...value, name: event.target.value }))
                }
                required
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-equipment-category">
                Категория
              </FieldLabel>
              <Select
                value={draft.category}
                onValueChange={(value) =>
                  setDraft((item) => ({
                    ...item,
                    category: value as AssetEquipmentCategory,
                  }))
                }
              >
                <SelectTrigger id="asset-equipment-category">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="FURNITURE">Мебель</SelectItem>
                    <SelectItem value="ELECTRICAL">Электрика</SelectItem>
                    <SelectItem value="OTHER">Другое</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            {item ? (
              <Field orientation="horizontal" className="self-end pb-2">
                <Checkbox
                  id="asset-equipment-active"
                  checked={draft.active}
                  onCheckedChange={(checked) =>
                    setDraft((value) => ({
                      ...value,
                      active: checked === true,
                    }))
                  }
                />
                <FieldLabel htmlFor="asset-equipment-active">
                  Активно
                </FieldLabel>
              </Field>
            ) : null}
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="asset-equipment-comment">
                Комментарий
              </FieldLabel>
              <Textarea
                id="asset-equipment-comment"
                value={draft.comment}
                maxLength={2000}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    comment: event.target.value,
                  }))
                }
              />
            </Field>
          </FieldGroup>
          {error ? <FieldError>{error}</FieldError> : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onClose}
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

type ClassifierDraft = {
  type: AssetClassifierType
  parentId: string
  code: string
  name: string
  active: boolean
  sortOrder: string
}

function classifierDraft(item: AssetClassifier | null): ClassifierDraft {
  return {
    type: item?.type ?? "CATEGORY",
    parentId: item?.parentId ?? "",
    code: item?.code ?? "",
    name: item?.name ?? "",
    active: item?.active ?? true,
    sortOrder:
      item?.sortOrder === null || item?.sortOrder === undefined
        ? ""
        : String(item.sortOrder),
  }
}

function ClassifierEditor({
  item,
  pending,
  onClose,
  onSave,
}: {
  item: AssetClassifier | null
  pending: boolean
  onClose: () => void
  onSave: (draft: ClassifierDraft) => Promise<void>
}) {
  const [draft, setDraft] = useState(() => classifierDraft(item))
  const [error, setError] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const sortOrder =
      draft.sortOrder.trim() === "" ? null : Number(draft.sortOrder)
    if (
      !draft.code.trim() ||
      !draft.name.trim() ||
      (sortOrder !== null && (!Number.isInteger(sortOrder) || sortOrder < 0))
    ) {
      setError("Укажите код, наименование и неотрицательный целый порядок.")
      return
    }
    setError(null)
    await onSave({
      ...draft,
      code: draft.code.trim(),
      name: draft.name.trim(),
      parentId: draft.parentId.trim(),
      sortOrder: sortOrder === null ? "" : String(sortOrder),
    })
  }
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>
            {item ? "Классификатор" : "Новый классификатор"}
          </DialogTitle>
          <DialogDescription>
            Справочники имущества доступны только глобальным администраторам.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="asset-classifier-type">Тип</FieldLabel>
              <Select
                value={draft.type}
                onValueChange={(value) =>
                  setDraft((current) => ({
                    ...current,
                    type: value as AssetClassifierType,
                  }))
                }
              >
                <SelectTrigger id="asset-classifier-type">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="CATEGORY">Категория</SelectItem>
                    <SelectItem value="SUBCATEGORY">Подкатегория</SelectItem>
                    <SelectItem value="TYPE">Тип</SelectItem>
                    <SelectItem value="CONDITION">Состояние</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-classifier-parent">
                Parent ID
              </FieldLabel>
              <Input
                id="asset-classifier-parent"
                value={draft.parentId}
                placeholder="Необязательно"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    parentId: event.target.value,
                  }))
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-classifier-code">Код</FieldLabel>
              <Input
                id="asset-classifier-code"
                value={draft.code}
                maxLength={64}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    code: event.target.value,
                  }))
                }
                required
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-classifier-name">
                Наименование
              </FieldLabel>
              <Input
                id="asset-classifier-name"
                value={draft.name}
                maxLength={255}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    name: event.target.value,
                  }))
                }
                required
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-classifier-sort">Порядок</FieldLabel>
              <Input
                id="asset-classifier-sort"
                type="number"
                min={0}
                step={1}
                value={draft.sortOrder}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    sortOrder: event.target.value,
                  }))
                }
              />
            </Field>
            {item ? (
              <Field orientation="horizontal" className="self-end pb-2">
                <Checkbox
                  id="asset-classifier-active"
                  checked={draft.active}
                  onCheckedChange={(checked) =>
                    setDraft((current) => ({
                      ...current,
                      active: checked === true,
                    }))
                  }
                />
                <FieldLabel htmlFor="asset-classifier-active">
                  Активен
                </FieldLabel>
              </Field>
            ) : null}
          </FieldGroup>
          {error ? <FieldError>{error}</FieldError> : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onClose}
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

export function AssetSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const canManage =
    currentUser !== null && isGlobalAdministrator(currentUser.globalRole)
  const [equipmentEditor, setEquipmentEditor] = useState<
    AssetEquipment | "new" | null
  >(null)
  const [classifierEditor, setClassifierEditor] = useState<
    AssetClassifier | "new" | null
  >(null)
  const catalogQuery = useQuery({
    queryKey: EQUIPMENT_CATALOG_QUERY_KEY,
    queryFn: () => listAssetEquipmentCatalog(accessToken),
    enabled: accessToken !== null && canManage,
  })
  const classifiersQuery = useQuery({
    queryKey: CLASSIFIERS_QUERY_KEY,
    queryFn: () => listAssetClassifiers(accessToken),
    enabled: accessToken !== null && canManage,
  })
  const equipmentMutation = useMutation({
    mutationFn: async (draft: EquipmentDraft) => {
      if (equipmentEditor === "new")
        return createAssetEquipmentCatalogItem(
          accessToken,
          crypto.randomUUID(),
          {
            code: draft.code.trim(),
            name: draft.name.trim(),
            category: draft.category,
            comment: draft.comment.trim() || null,
          }
        )
      if (equipmentEditor === null) throw new Error("Оборудование не выбрано.")
      return updateAssetEquipmentCatalogItem(accessToken, equipmentEditor.id, {
        version: equipmentEditor.version,
        code: draft.code.trim(),
        name: draft.name.trim(),
        category: draft.category,
        active: draft.active,
        comment: draft.comment.trim() || null,
      })
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: EQUIPMENT_CATALOG_QUERY_KEY,
      })
      setEquipmentEditor(null)
      toast.success("Каталог оборудования сохранён.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const classifierMutation = useMutation({
    mutationFn: async (draft: ClassifierDraft) => {
      const sortOrder =
        draft.sortOrder.trim() === "" ? null : Number(draft.sortOrder)
      if (classifierEditor === "new")
        return createAssetClassifier(accessToken, crypto.randomUUID(), {
          type: draft.type,
          parentId: draft.parentId.trim() || null,
          code: draft.code.trim(),
          name: draft.name.trim(),
          active: draft.active,
          sortOrder,
        })
      if (classifierEditor === null) throw new Error("Классификатор не выбран.")
      return updateAssetClassifier(accessToken, classifierEditor.id, {
        id: classifierEditor.id,
        version: classifierEditor.version,
        type: draft.type,
        parentId: draft.parentId.trim() || null,
        code: draft.code.trim(),
        name: draft.name.trim(),
        active: draft.active,
        sortOrder,
      })
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: CLASSIFIERS_QUERY_KEY })
      setClassifierEditor(null)
      toast.success("Классификатор сохранён.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  if (!canManage)
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Нет доступа</CardTitle>
          <CardDescription>
            Глобальный каталог имущества доступен только SYSTEM_ADMIN и
            WMS_ADMIN.
          </CardDescription>
        </CardHeader>
      </Card>
    )

  return (
    <div className="min-h-0 overflow-y-auto pb-6">
      <div className="flex flex-col gap-4">
        <PageToolbar>
          <PageToolbarContent>
            <p className="text-sm text-muted-foreground">
              Глобальные классификаторы и каталог оборудования asset-service.
            </p>
          </PageToolbarContent>
          <PageToolbarActions>
            <Button
              variant="outline"
              onClick={() => setClassifierEditor("new")}
            >
              Новый классификатор
            </Button>
            <Button onClick={() => setEquipmentEditor("new")}>
              Новое оборудование
            </Button>
          </PageToolbarActions>
        </PageToolbar>
        <div className="grid gap-4 xl:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Каталог оборудования</CardTitle>
              <CardDescription>Код уникален во всём сервисе.</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {catalogQuery.isLoading ? (
                <p className="text-sm text-muted-foreground">
                  Загрузка каталога…
                </p>
              ) : catalogQuery.isError ? (
                <p className="text-sm text-destructive">
                  {errorMessage(catalogQuery.error)}
                </p>
              ) : catalogQuery.data?.length ? (
                catalogQuery.data.map((item) => (
                  <div
                    key={item.id}
                    className="flex flex-col gap-2 rounded-md border p-3 sm:flex-row sm:items-center"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{item.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {item.code} · {item.category}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant={item.active ? "secondary" : "outline"}>
                        {item.active ? "Активно" : "Неактивно"}
                      </Badge>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setEquipmentEditor(item)}
                      >
                        Изменить
                      </Button>
                    </div>
                  </div>
                ))
              ) : (
                <p className="text-sm text-muted-foreground">
                  Каталог пока пуст.
                </p>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Классификаторы</CardTitle>
              <CardDescription>
                Используются паспортом и dynamic attributes.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {classifiersQuery.isLoading ? (
                <p className="text-sm text-muted-foreground">
                  Загрузка классификаторов…
                </p>
              ) : classifiersQuery.isError ? (
                <p className="text-sm text-destructive">
                  {errorMessage(classifiersQuery.error)}
                </p>
              ) : classifiersQuery.data?.length ? (
                classifiersQuery.data.map((item) => (
                  <div
                    key={item.id}
                    className="flex flex-col gap-2 rounded-md border p-3 sm:flex-row sm:items-center"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{item.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {item.type} · {item.code}
                        {item.parentId ? ` · parent ${item.parentId}` : ""}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant={item.active ? "secondary" : "outline"}>
                        {item.active ? "Активен" : "Неактивен"}
                      </Badge>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setClassifierEditor(item)}
                      >
                        Изменить
                      </Button>
                    </div>
                  </div>
                ))
              ) : (
                <p className="text-sm text-muted-foreground">
                  Классификаторов пока нет.
                </p>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
      {equipmentEditor !== null ? (
        <EquipmentEditor
          item={equipmentEditor === "new" ? null : equipmentEditor}
          pending={equipmentMutation.isPending}
          onClose={() => setEquipmentEditor(null)}
          onSave={async (draft) => {
            await equipmentMutation.mutateAsync(draft)
          }}
        />
      ) : null}
      {classifierEditor !== null ? (
        <ClassifierEditor
          item={classifierEditor === "new" ? null : classifierEditor}
          pending={classifierMutation.isPending}
          onClose={() => setClassifierEditor(null)}
          onSave={async (draft) => {
            await classifierMutation.mutateAsync(draft)
          }}
        />
      ) : null}
    </div>
  )
}
