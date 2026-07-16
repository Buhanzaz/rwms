import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"
import { toast } from "sonner"

import {
  createAssetRentalItem,
  listAssetRentalItems,
  type AssetRentalItem,
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
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
import { Textarea } from "@/components/ui/textarea"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

const RENTAL_ITEMS_QUERY_KEY = ["asset-rental-items"] as const

function rentalItemsQueryKey(warehouseId: string, search: string) {
  return [...RENTAL_ITEMS_QUERY_KEY, warehouseId, search] as const
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить запрос к сервису имущества."
}

function canEditWarehouse(
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

function statusLabel(status: AssetRentalItem["status"]) {
  const labels: Record<AssetRentalItem["status"], string> = {
    NEW: "Новая",
    RENTED: "В аренде",
    BOOKED: "Забронирована",
    REPAIR: "Ремонт",
    WAITING_REPAIR_CHECK: "Проверка ремонта",
    WRITTEN_OFF: "Списана",
    CAPITAL_REPAIR: "Капремонт",
    AFTER_RENT: "После аренды",
    WAITING_ESTIMATE_CONFIRMATION: "Ожидает смету",
    SALE: "Продажа",
    USED_SALE: "Продажа б/у",
    RESERVED: "Резерв",
    FREE: "Свободна",
    WAREHOUSE: "На складе",
    OWN_NEEDS: "Собственные нужды",
    IN_TRANSFER: "В перемещении",
  }
  return labels[status]
}

type RentalItemDraft = {
  number: string
  rentalType: string
  dimensions: string
  finishing: string
  category: string
  characteristics: string
  tags: string
}

const emptyDraft: RentalItemDraft = {
  number: "",
  rentalType: "",
  dimensions: "",
  finishing: "",
  category: "",
  characteristics: "",
  tags: "",
}

function CreateRentalItemDialog({
  pending,
  onClose,
  onCreate,
}: {
  pending: boolean
  onClose: () => void
  onCreate: (draft: RentalItemDraft) => Promise<void>
}) {
  const [draft, setDraft] = useState(emptyDraft)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draft.number.trim()) {
      setError("Укажите инвентарный номер.")
      return
    }
    setError(null)
    await onCreate(draft)
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Новая бытовка</DialogTitle>
          <DialogDescription>
            Номер нормализуется и резервируется глобально сервисом имущества.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="asset-rental-number">Номер</FieldLabel>
              <Input
                id="asset-rental-number"
                value={draft.number}
                maxLength={128}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    number: event.target.value,
                  }))
                }
                required
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-rental-type">Тип</FieldLabel>
              <Input
                id="asset-rental-type"
                value={draft.rentalType}
                maxLength={255}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    rentalType: event.target.value,
                  }))
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-rental-dimensions">
                Габариты
              </FieldLabel>
              <Input
                id="asset-rental-dimensions"
                value={draft.dimensions}
                maxLength={255}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    dimensions: event.target.value,
                  }))
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-rental-category">Категория</FieldLabel>
              <Input
                id="asset-rental-category"
                value={draft.category}
                maxLength={255}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    category: event.target.value,
                  }))
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-rental-finishing">Отделка</FieldLabel>
              <Input
                id="asset-rental-finishing"
                value={draft.finishing}
                maxLength={255}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    finishing: event.target.value,
                  }))
                }
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-rental-tags">Теги</FieldLabel>
              <Input
                id="asset-rental-tags"
                value={draft.tags}
                maxLength={1000}
                placeholder="через запятую"
                onChange={(event) =>
                  setDraft((value) => ({ ...value, tags: event.target.value }))
                }
              />
            </Field>
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="asset-rental-characteristics">
                Характеристики
              </FieldLabel>
              <Textarea
                id="asset-rental-characteristics"
                value={draft.characteristics}
                maxLength={2000}
                onChange={(event) =>
                  setDraft((value) => ({
                    ...value,
                    characteristics: event.target.value,
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
              {pending ? "Создаём…" : "Создать"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function AssetRentalItemsPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse, selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [createOpen, setCreateOpen] = useState(false)
  const normalizedSearch = search.trim()
  const canEdit =
    selectedWarehouseId !== null &&
    canEditWarehouse(currentUser, selectedWarehouseId)
  const itemsQuery = useQuery({
    queryKey: rentalItemsQueryKey(
      selectedWarehouseId ?? "none",
      normalizedSearch
    ),
    queryFn: () =>
      listAssetRentalItems(accessToken, {
        warehouseId: selectedWarehouseId!,
        search: normalizedSearch || undefined,
      }),
    enabled: accessToken !== null && selectedWarehouseId !== null,
  })
  const createMutation = useMutation({
    mutationFn: async (draft: RentalItemDraft) => {
      if (selectedWarehouseId === null) throw new Error("Выберите склад.")
      return createAssetRentalItem(accessToken, crypto.randomUUID(), {
        warehouseId: selectedWarehouseId,
        number: draft.number.trim(),
        rentalType: draft.rentalType.trim() || null,
        dimensions: draft.dimensions.trim() || null,
        finishing: draft.finishing.trim() || null,
        category: draft.category.trim() || null,
        characteristics: draft.characteristics.trim() || null,
        linoleum: null,
        passport: {},
        tags: draft.tags
          .split(",")
          .map((tag) => tag.trim())
          .filter(Boolean),
      })
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: RENTAL_ITEMS_QUERY_KEY })
      setCreateOpen(false)
      toast.success("Бытовка создана.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const items = useMemo(() => itemsQuery.data?.content ?? [], [itemsQuery.data])

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-sm">
          <Input
            aria-label="Поиск бытовок"
            placeholder="Номер, категория или тег"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {canEdit ? (
            <Button onClick={() => setCreateOpen(true)}>Новая бытовка</Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

      {selectedWarehouse === null ? (
        <Card size="sm">
          <CardHeader>
            <CardDescription>Выберите доступный склад.</CardDescription>
          </CardHeader>
        </Card>
      ) : itemsQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">
          Загрузка реестра имущества…
        </p>
      ) : itemsQuery.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(itemsQuery.error)}
        </p>
      ) : items.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Бытовок пока нет</CardTitle>
            <CardDescription>
              Production-данные берутся только из asset-service; browser
              fixtures здесь не используются.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {items.map((item) => (
              <Card key={item.id}>
                <CardHeader>
                  <CardTitle>{item.number}</CardTitle>
                  <CardDescription>
                    {item.rentalType ?? item.category ?? "Без классификации"}
                  </CardDescription>
                  <CardAction>
                    <Badge variant="secondary">
                      {statusLabel(item.status)}
                    </Badge>
                  </CardAction>
                </CardHeader>
                <CardContent className="flex flex-col gap-2 text-sm">
                  <p>
                    <span className="text-muted-foreground">Габариты: </span>
                    {item.dimensions ?? "—"}
                  </p>
                  <p>
                    <span className="text-muted-foreground">Наполнение: </span>
                    {item.contents.reduce(
                      (total, entry) => total + entry.quantity,
                      0
                    )}{" "}
                    поз.
                  </p>
                  {item.tags.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {item.tags.map((tag) => (
                        <Badge key={tag} variant="outline">
                          {tag}
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                </CardContent>
                <CardFooter className="justify-end">
                  <Button asChild size="sm" variant="outline">
                    <Link to={`/warehouse/${item.id}`}>Открыть</Link>
                  </Button>
                </CardFooter>
              </Card>
            ))}
          </div>
        </div>
      )}

      {createOpen ? (
        <CreateRentalItemDialog
          pending={createMutation.isPending}
          onClose={() => setCreateOpen(false)}
          onCreate={async (draft) => {
            await createMutation.mutateAsync(draft)
          }}
        />
      ) : null}
    </div>
  )
}
