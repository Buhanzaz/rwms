import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link, useParams } from "react-router-dom"
import { toast } from "sonner"

import {
  addAssetManualNote,
  getAssetRentalItem,
  listAssetManualNotes,
  updateAssetRentalItemGeneralComment,
  updateAssetRentalItemPassport,
  updateAssetRentalItemStatus,
  type AssetRentalItem,
  type AssetRentalItemStatus,
} from "@/api/asset-api"
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

const RENTAL_ITEM_QUERY_KEY = ["asset-rental-item"] as const
const MANUAL_NOTES_QUERY_KEY = ["asset-rental-item-notes"] as const

const statuses: Array<{ value: AssetRentalItemStatus; label: string }> = [
  { value: "NEW", label: "Новая" },
  { value: "FREE", label: "Свободна" },
  { value: "WAREHOUSE", label: "На складе" },
  { value: "BOOKED", label: "Забронирована" },
  { value: "RESERVED", label: "Резерв" },
  { value: "RENTED", label: "В аренде" },
  { value: "AFTER_RENT", label: "После аренды" },
  { value: "REPAIR", label: "Ремонт" },
  { value: "WAITING_REPAIR_CHECK", label: "Проверка ремонта" },
  { value: "CAPITAL_REPAIR", label: "Капремонт" },
  { value: "WAITING_ESTIMATE_CONFIRMATION", label: "Ожидает смету" },
  { value: "IN_TRANSFER", label: "В перемещении" },
  { value: "OWN_NEEDS", label: "Собственные нужды" },
  { value: "SALE", label: "Продажа" },
  { value: "USED_SALE", label: "Продажа б/у" },
  { value: "WRITTEN_OFF", label: "Списана" },
]

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить запрос к сервису имущества."
}

function canEditRentalItem(
  currentUser: ReturnType<typeof useAuth>["currentUser"],
  warehouseId: string
) {
  if (currentUser === null) return false
  if (isGlobalAdministrator(currentUser.globalRole)) return true
  if (currentUser.warehouseAccessAll) return currentUser.globalRole !== "VIEWER"
  return currentUser.warehouseAccesses.some(
    (grant) =>
      grant.warehouseId === warehouseId &&
      (grant.level === "EDIT" || grant.level === "MANAGE")
  )
}

function PassportEditor({
  item,
  pending,
  onClose,
  onSave,
}: {
  item: AssetRentalItem
  pending: boolean
  onClose: () => void
  onSave: (
    input: Parameters<typeof updateAssetRentalItemPassport>[2]
  ) => Promise<void>
}) {
  const [rentalType, setRentalType] = useState(item.rentalType ?? "")
  const [dimensions, setDimensions] = useState(item.dimensions ?? "")
  const [finishing, setFinishing] = useState(item.finishing ?? "")
  const [category, setCategory] = useState(item.category ?? "")
  const [characteristics, setCharacteristics] = useState(
    item.characteristics ?? ""
  )
  const [tags, setTags] = useState(item.tags.join(", "))
  const [linoleum, setLinoleum] = useState(item.linoleum === true)
  const [passportJson, setPassportJson] = useState(() =>
    JSON.stringify(item.passport, null, 2)
  )
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    let passport: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(passportJson || "{}")
      if (
        typeof parsed !== "object" ||
        parsed === null ||
        Array.isArray(parsed)
      )
        throw new Error()
      passport = parsed as Record<string, unknown>
    } catch {
      setError("Паспорт должен быть JSON-объектом.")
      return
    }
    setError(null)
    await onSave({
      version: item.version,
      rentalType: rentalType.trim() || null,
      dimensions: dimensions.trim() || null,
      finishing: finishing.trim() || null,
      category: category.trim() || null,
      characteristics: characteristics.trim() || null,
      linoleum,
      passport,
      tags: tags
        .split(",")
        .map((tag) => tag.trim())
        .filter(Boolean),
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Паспорт {item.number}</DialogTitle>
          <DialogDescription>
            Изменение использует текущую версию агрегата.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="asset-passport-type">Тип</FieldLabel>
              <Input
                id="asset-passport-type"
                value={rentalType}
                maxLength={255}
                onChange={(event) => setRentalType(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-passport-dimensions">
                Габариты
              </FieldLabel>
              <Input
                id="asset-passport-dimensions"
                value={dimensions}
                maxLength={255}
                onChange={(event) => setDimensions(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-passport-finishing">
                Отделка
              </FieldLabel>
              <Input
                id="asset-passport-finishing"
                value={finishing}
                maxLength={255}
                onChange={(event) => setFinishing(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-passport-category">
                Категория
              </FieldLabel>
              <Input
                id="asset-passport-category"
                value={category}
                maxLength={255}
                onChange={(event) => setCategory(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-passport-tags">Теги</FieldLabel>
              <Input
                id="asset-passport-tags"
                value={tags}
                maxLength={1000}
                onChange={(event) => setTags(event.target.value)}
              />
            </Field>
            <Field orientation="horizontal" className="self-end pb-2">
              <Checkbox
                id="asset-passport-linoleum"
                checked={linoleum}
                onCheckedChange={(checked) => setLinoleum(checked === true)}
              />
              <FieldLabel htmlFor="asset-passport-linoleum">
                Линолеум
              </FieldLabel>
            </Field>
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="asset-passport-characteristics">
                Характеристики
              </FieldLabel>
              <Textarea
                id="asset-passport-characteristics"
                value={characteristics}
                maxLength={2000}
                onChange={(event) => setCharacteristics(event.target.value)}
              />
            </Field>
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="asset-passport-json">
                Dynamic attributes (JSON)
              </FieldLabel>
              <Textarea
                id="asset-passport-json"
                value={passportJson}
                className="min-h-40 font-mono text-xs"
                onChange={(event) => setPassportJson(event.target.value)}
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

function GeneralCommentEditor({
  item,
  canEdit,
  pending,
  onSave,
}: {
  item: AssetRentalItem
  canEdit: boolean
  pending: boolean
  onSave: (comment: string) => void
}) {
  const [comment, setComment] = useState(item.generalComment ?? "")

  return (
    <Card>
      <CardHeader>
        <CardTitle>Общий комментарий</CardTitle>
        <CardDescription>
          Редактируемое поле агрегата; не публикуется в Kafka.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Textarea
          value={comment}
          disabled={!canEdit || pending}
          maxLength={4000}
          onChange={(event) => setComment(event.target.value)}
        />
      </CardContent>
      {canEdit ? (
        <CardFooter className="justify-end">
          <Button disabled={pending} onClick={() => onSave(comment)}>
            {pending ? "Сохраняем…" : "Сохранить"}
          </Button>
        </CardFooter>
      ) : null}
    </Card>
  )
}

export function AssetRentalItemDetailPage() {
  const { rentalItemId } = useParams()
  const { accessToken, currentUser } = useAuth()
  const { warehouses } = useWarehouse()
  const queryClient = useQueryClient()
  const [passportOpen, setPassportOpen] = useState(false)
  const [note, setNote] = useState("")
  const itemQuery = useQuery({
    queryKey: [...RENTAL_ITEM_QUERY_KEY, rentalItemId],
    queryFn: () => getAssetRentalItem(accessToken, rentalItemId!),
    enabled: accessToken !== null && rentalItemId !== undefined,
  })
  const notesQuery = useQuery({
    queryKey: [...MANUAL_NOTES_QUERY_KEY, rentalItemId],
    queryFn: () => listAssetManualNotes(accessToken, rentalItemId!),
    enabled: accessToken !== null && rentalItemId !== undefined,
  })
  const item = itemQuery.data
  const canEdit =
    item !== undefined && canEditRentalItem(currentUser, item.warehouseId)

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: RENTAL_ITEM_QUERY_KEY }),
      queryClient.invalidateQueries({ queryKey: MANUAL_NOTES_QUERY_KEY }),
    ])
  }

  const statusMutation = useMutation({
    mutationFn: (status: AssetRentalItemStatus) =>
      updateAssetRentalItemStatus(accessToken, item!.id, item!.version, status),
    onSuccess: async () => {
      await refresh()
      toast.success("Статус обновлён.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const passportMutation = useMutation({
    mutationFn: (input: Parameters<typeof updateAssetRentalItemPassport>[2]) =>
      updateAssetRentalItemPassport(accessToken, item!.id, input),
    onSuccess: async () => {
      await refresh()
      setPassportOpen(false)
      toast.success("Паспорт обновлён.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const commentMutation = useMutation({
    mutationFn: (comment: string) =>
      updateAssetRentalItemGeneralComment(
        accessToken,
        item!.id,
        item!.version,
        comment.trim() || null
      ),
    onSuccess: async () => {
      await refresh()
      toast.success("Комментарий сохранён.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const noteMutation = useMutation({
    mutationFn: () =>
      addAssetManualNote(
        accessToken,
        item!.id,
        crypto.randomUUID(),
        item!.version,
        note.trim()
      ),
    onSuccess: async () => {
      setNote("")
      await refresh()
      toast.success("Заметка добавлена.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  if (itemQuery.isLoading)
    return <p className="text-sm text-muted-foreground">Загрузка бытовки…</p>
  if (itemQuery.isError || item === undefined)
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Бытовка недоступна</CardTitle>
          <CardDescription>
            {itemQuery.isError
              ? errorMessage(itemQuery.error)
              : "Запись не найдена."}
          </CardDescription>
        </CardHeader>
        <CardFooter>
          <Button asChild variant="outline">
            <Link to="/warehouse">К реестру</Link>
          </Button>
        </CardFooter>
      </Card>
    )

  const warehouse = warehouses.find(
    (candidate) => candidate.id === item.warehouseId
  )

  return (
    <div className="min-h-0 overflow-y-auto pb-6">
      <div className="flex flex-col gap-4">
        <Card>
          <CardHeader>
            <CardTitle>{item.number}</CardTitle>
            <CardDescription>
              {warehouse
                ? `${warehouse.code} · ${warehouse.city}`
                : item.warehouseId}
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            <Field>
              <FieldLabel>Статус</FieldLabel>
              <Select
                value={item.status}
                disabled={!canEdit || statusMutation.isPending}
                onValueChange={(value) =>
                  statusMutation.mutate(value as AssetRentalItemStatus)
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {statuses.map((status) => (
                      <SelectItem key={status.value} value={status.value}>
                        {status.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <div className="flex flex-col gap-1">
              <span className="text-sm text-muted-foreground">Теги</span>
              <div className="flex flex-wrap gap-1">
                {item.tags.length
                  ? item.tags.map((tag) => (
                      <Badge key={tag} variant="outline">
                        {tag}
                      </Badge>
                    ))
                  : "—"}
              </div>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-sm text-muted-foreground">Обновлено</span>
              <span className="text-sm">
                {new Intl.DateTimeFormat("ru-RU", {
                  dateStyle: "medium",
                  timeStyle: "short",
                }).format(new Date(item.updatedAt))}
              </span>
            </div>
          </CardContent>
        </Card>

        <Tabs defaultValue="passport">
          <TabsList className="max-w-full overflow-x-auto">
            <TabsTrigger value="passport">Паспорт</TabsTrigger>
            <TabsTrigger value="contents">Наполнение</TabsTrigger>
            <TabsTrigger value="notes">Комментарии и заметки</TabsTrigger>
            <TabsTrigger value="media">Фото</TabsTrigger>
          </TabsList>
          <TabsContent value="passport">
            <Card>
              <CardHeader>
                <CardTitle>Паспорт</CardTitle>
                <CardDescription>
                  Классификаторы, dynamic attributes и теги принадлежат
                  asset-service.
                </CardDescription>
              </CardHeader>
              <CardContent className="grid gap-3 text-sm md:grid-cols-2">
                <p>
                  <span className="text-muted-foreground">Тип: </span>
                  {item.rentalType ?? "—"}
                </p>
                <p>
                  <span className="text-muted-foreground">Габариты: </span>
                  {item.dimensions ?? "—"}
                </p>
                <p>
                  <span className="text-muted-foreground">Категория: </span>
                  {item.category ?? "—"}
                </p>
                <p>
                  <span className="text-muted-foreground">Отделка: </span>
                  {item.finishing ?? "—"}
                </p>
                <p>
                  <span className="text-muted-foreground">Линолеум: </span>
                  {item.linoleum === null ? "—" : item.linoleum ? "Да" : "Нет"}
                </p>
                <p>
                  <span className="text-muted-foreground">
                    Характеристики:{" "}
                  </span>
                  {item.characteristics ?? "—"}
                </p>
                <pre className="overflow-x-auto rounded-md bg-muted p-3 text-xs md:col-span-2">
                  {JSON.stringify(item.passport, null, 2)}
                </pre>
              </CardContent>
              {canEdit ? (
                <CardFooter className="justify-end">
                  <Button
                    variant="outline"
                    onClick={() => setPassportOpen(true)}
                  >
                    Изменить паспорт
                  </Button>
                </CardFooter>
              ) : null}
            </Card>
          </TabsContent>
          <TabsContent value="contents">
            <Card>
              <CardHeader>
                <CardTitle>Наполнение</CardTitle>
                <CardDescription>
                  Текущие остатки бытовки из неизменяемого movement ledger.
                </CardDescription>
              </CardHeader>
              <CardContent>
                {item.contents.length === 0 ? (
                  <p className="text-sm text-muted-foreground">
                    Наполнение пока не зарегистрировано.
                  </p>
                ) : (
                  <div className="grid gap-3 md:grid-cols-2">
                    {item.contents.map((entry) => (
                      <div
                        key={`${entry.equipmentId}-${entry.locationKind}`}
                        className="rounded-md border p-3 text-sm"
                      >
                        <p className="font-medium">{entry.equipmentCode}</p>
                        <p className="text-muted-foreground">
                          {entry.quantity} шт. · {entry.locationKind}
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>
          <TabsContent value="notes">
            <div className="grid gap-4 lg:grid-cols-2">
              <GeneralCommentEditor
                key={`${item.id}-${item.version}`}
                item={item}
                canEdit={canEdit}
                pending={commentMutation.isPending}
                onSave={(comment) => commentMutation.mutate(comment)}
              />
              <Card>
                <CardHeader>
                  <CardTitle>Ручные заметки</CardTitle>
                  <CardDescription>
                    Append-only service-local журнал; текст не покидает
                    asset-service.
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col gap-4">
                  {canEdit ? (
                    <Textarea
                      value={note}
                      maxLength={4000}
                      placeholder="Новая заметка"
                      onChange={(event) => setNote(event.target.value)}
                    />
                  ) : null}
                  {notesQuery.isLoading ? (
                    <p className="text-sm text-muted-foreground">
                      Загрузка заметок…
                    </p>
                  ) : notesQuery.isError ? (
                    <p className="text-sm text-destructive">
                      {errorMessage(notesQuery.error)}
                    </p>
                  ) : notesQuery.data?.length ? (
                    <div className="flex flex-col gap-3">
                      {notesQuery.data.map((entry) => (
                        <div
                          key={entry.id}
                          className="rounded-md border p-3 text-sm"
                        >
                          <p className="whitespace-pre-wrap">{entry.text}</p>
                          <p className="mt-2 text-xs text-muted-foreground">
                            {new Intl.DateTimeFormat("ru-RU", {
                              dateStyle: "medium",
                              timeStyle: "short",
                            }).format(new Date(entry.createdAt))}
                          </p>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      Заметок пока нет.
                    </p>
                  )}
                </CardContent>
                {canEdit ? (
                  <CardFooter className="justify-end">
                    <Button
                      disabled={noteMutation.isPending || !note.trim()}
                      onClick={() => noteMutation.mutate()}
                    >
                      {noteMutation.isPending
                        ? "Добавляем…"
                        : "Добавить заметку"}
                    </Button>
                  </CardFooter>
                ) : null}
              </Card>
            </div>
          </TabsContent>
          <TabsContent value="media">
            <Card>
              <CardHeader>
                <CardTitle>Фотографии</CardTitle>
                <CardDescription>
                  Фото бытовки должны загружаться и читаться через media-service
                  с ownerType=RENTAL_ITEM.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">
                  В текущем runtime отсутствует gateway/API-контракт
                  media-service, поэтому asset-panel не подменяет фотографии
                  browser storage и не отправляет медиа в asset-service.
                </p>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
      {passportOpen ? (
        <PassportEditor
          item={item}
          pending={passportMutation.isPending}
          onClose={() => setPassportOpen(false)}
          onSave={async (input) => {
            await passportMutation.mutateAsync(input)
          }}
        />
      ) : null}
    </div>
  )
}
