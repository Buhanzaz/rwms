import { useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  disposeEquipment,
  getEquipmentItems,
  listEquipmentDispositionItems,
} from "@/api/equipment-api"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
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
  FieldDescription,
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
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useWarehouse } from "@/hooks/use-warehouse"
import type {
  EquipmentDispositionListItemDto,
  EquipmentItemDto,
} from "@/types/equipment"

const EQUIPMENT_WRITE_OFFS_QUERY_KEY = ["equipment-write-offs"] as const

function equipmentWriteOffsQueryKey(warehouseId: string, search: string) {
  return [...EQUIPMENT_WRITE_OFFS_QUERY_KEY, warehouseId, search] as const
}

function dateLabel(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function operationLabel(item: EquipmentDispositionListItemDto) {
  return item.kind
}

function EquipmentDispositionMobileCard({
  item,
}: {
  item: EquipmentDispositionListItemDto
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{item.equipmentName}</CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2 text-sm">
        <span className="text-muted-foreground">Код</span>
        <span>{item.equipmentCode}</span>
        <span className="text-muted-foreground">Количество</span>
        <span>{item.quantity} шт.</span>
        <span className="text-muted-foreground">Операция</span>
        <Badge className="w-fit" variant="outline">
          {operationLabel(item)}
        </Badge>
        <span className="text-muted-foreground">Дата</span>
        <span>{dateLabel(item.occurredAt)}</span>
      </CardContent>
    </Card>
  )
}

function stockBalance(item: EquipmentItemDto) {
  return item.balances.find(
    (balance) => balance.locationKind === "STOCK" && balance.availableStock > 0
  )
}

function EquipmentDispositionDialog({
  accessToken,
  warehouseId,
  open,
  onOpenChange,
  onSaved,
}: {
  accessToken: string | null
  warehouseId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}) {
  const queryClient = useQueryClient()
  const [equipmentId, setEquipmentId] = useState("")
  const [quantity, setQuantity] = useState("1")
  const [disposition, setDisposition] = useState<"WRITE_OFF" | "LOSS">(
    "WRITE_OFF"
  )
  const [attempt, setAttempt] = useState<{
    key: string
    signature: string
  } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, "disposition-sources"],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled: open && Boolean(accessToken),
  })
  const sourceItems = useMemo(
    () => (equipmentQuery.data ?? []).filter(stockBalance),
    [equipmentQuery.data]
  )
  const selectedItem = sourceItems.find((item) => item.id === equipmentId)
  const selectedBalance = selectedItem ? stockBalance(selectedItem) : undefined
  const parsedQuantity = Number(quantity)
  const quantityIsValid =
    Number.isSafeInteger(parsedQuantity) &&
    parsedQuantity >= 1 &&
    parsedQuantity <= (selectedBalance?.availableStock ?? 0)

  const mutation = useMutation({
    mutationFn: ({ idempotencyKey }: { idempotencyKey: string }) => {
      if (!selectedItem || !selectedBalance) {
        throw new Error("Выберите оборудование с доступным складским остатком.")
      }

      return disposeEquipment(accessToken, idempotencyKey, {
        equipmentId: selectedItem.id,
        warehouseId,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        sourceExpectedVersion: selectedBalance.version,
        quantity: parsedQuantity,
        disposition,
      })
    },
    onSuccess: () => {
      onSaved()
      void queryClient.invalidateQueries({
        queryKey: ["equipment-items", warehouseId],
      })
      void queryClient.invalidateQueries({
        queryKey: EQUIPMENT_WRITE_OFFS_QUERY_KEY,
      })
    },
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось провести операцию оборудования."
      )
      void queryClient.invalidateQueries({
        queryKey: ["equipment-items", warehouseId],
      })
    },
  })

  function reset(nextOpen: boolean) {
    onOpenChange(nextOpen)
    if (!nextOpen) return
    setEquipmentId("")
    setQuantity("1")
    setDisposition("WRITE_OFF")
    setAttempt(null)
    setError(null)
  }

  function submit() {
    if (!selectedItem || !selectedBalance || !quantityIsValid) return

    const signature = JSON.stringify({
      equipmentId: selectedItem.id,
      warehouseId,
      balanceId: selectedBalance.id,
      sourceExpectedVersion: selectedBalance.version,
      quantity: parsedQuantity,
      disposition,
    })
    const idempotencyKey =
      attempt?.signature === signature ? attempt.key : crypto.randomUUID()

    if (attempt?.signature !== signature) {
      setAttempt({ key: idempotencyKey, signature })
    }

    mutation.mutate({ idempotencyKey })
  }

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Операция с оборудованием</DialogTitle>
          <DialogDescription>
            Доступно только списание или фиксация утраты со складского остатка.
            Перемещения и обработка возвратов требуют отдельного контракта
            asset-service.
          </DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field data-invalid={Boolean(error && !selectedItem)}>
            <FieldLabel>Оборудование</FieldLabel>
            <Select
              value={equipmentId}
              onValueChange={(value) => {
                setEquipmentId(value)
                setAttempt(null)
                setError(null)
              }}
              disabled={equipmentQuery.isLoading || equipmentQuery.isError}
            >
              <SelectTrigger
                className="w-full"
                aria-invalid={Boolean(error && !selectedItem)}
              >
                <SelectValue placeholder="Выберите позицию" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {sourceItems.map((item) => {
                    const balance = stockBalance(item)!
                    return (
                      <SelectItem key={item.id} value={item.id}>
                        {item.name} — доступно {balance.availableStock} шт.
                      </SelectItem>
                    )
                  })}
                </SelectGroup>
              </SelectContent>
            </Select>
            {equipmentQuery.isError ? (
              <FieldError>
                Не удалось загрузить доступные складские остатки.
              </FieldError>
            ) : (
              <FieldDescription>
                Используется актуальная версия складского баланса asset-service.
              </FieldDescription>
            )}
          </Field>

          <Field data-invalid={Boolean(error && !quantityIsValid)}>
            <FieldLabel htmlFor="equipment-disposition-quantity">
              Количество
            </FieldLabel>
            <Input
              id="equipment-disposition-quantity"
              type="number"
              min={1}
              max={selectedBalance?.availableStock ?? 1}
              value={quantity}
              aria-invalid={Boolean(error && !quantityIsValid)}
              onChange={(event) => {
                setQuantity(event.target.value)
                setAttempt(null)
                setError(null)
              }}
            />
            {selectedBalance ? (
              <FieldDescription>
                Доступно к операции: {selectedBalance.availableStock} шт.
              </FieldDescription>
            ) : null}
          </Field>

          <Field>
            <FieldLabel>Тип операции</FieldLabel>
            <Select
              value={disposition}
              onValueChange={(value) => {
                setDisposition(value as "WRITE_OFF" | "LOSS")
                setAttempt(null)
                setError(null)
              }}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value="WRITE_OFF">Списание</SelectItem>
                  <SelectItem value="LOSS">Утрата</SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>

          {error ? <FieldError>{error}</FieldError> : null}
        </FieldGroup>

        <DialogFooter>
          <Button
            variant="outline"
            disabled={mutation.isPending}
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button
            variant="destructive"
            disabled={
              mutation.isPending ||
              !accessToken ||
              !selectedItem ||
              !selectedBalance ||
              !quantityIsValid
            }
            onClick={submit}
          >
            Провести операцию
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function EquipmentWriteOffsPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [dialogOpen, setDialogOpen] = useState(false)
  const canManage = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "MANAGE")
  )
  const normalizedSearch = search.trim()
  const listQuery = useQuery({
    queryKey: equipmentWriteOffsQueryKey(
      selectedWarehouseId ?? "none",
      normalizedSearch
    ),
    queryFn: () =>
      listEquipmentDispositionItems(accessToken, {
        warehouseId: selectedWarehouseId!,
        search: normalizedSearch,
      }),
    enabled: Boolean(selectedWarehouseId && accessToken),
  })
  const items = listQuery.data ?? []

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-sm">
          <Input
            aria-label="Поиск операций оборудования"
            placeholder="Наименование оборудования"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <Button
            disabled={!accessToken || !selectedWarehouseId || !canManage}
            onClick={() => setDialogOpen(true)}
          >
            Операция с оборудованием
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      {!selectedWarehouseId ? (
        <p className="text-sm text-muted-foreground">Склад не выбран.</p>
      ) : !accessToken ? (
        <p className="text-sm text-muted-foreground">
          Для операций с оборудованием требуется авторизация.
        </p>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto md:flex">
          {listQuery.isLoading ? (
            <p className="text-xs text-muted-foreground">
              Загрузка операций оборудования...
            </p>
          ) : listQuery.isError ? (
            <p role="alert" className="text-xs text-destructive">
              {listQuery.error instanceof Error
                ? listQuery.error.message
                : "Не удалось загрузить операции оборудования."}
            </p>
          ) : (
            <>
              {items.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  Операций оборудования пока нет.
                </p>
              ) : (
                <>
                  <div className="hidden min-h-full min-w-0 flex-1 md:block">
                    <OperationsListGrid
                      className="min-h-full"
                      items={items}
                      columns={[
                        {
                          id: "name",
                          label: "Наименование",
                          getSortValue: (item) => item.equipmentName,
                          render: (item) => item.equipmentName,
                        },
                        {
                          id: "code",
                          label: "Код",
                          getSortValue: (item) => item.equipmentCode,
                          render: (item) => item.equipmentCode,
                        },
                        {
                          id: "quantity",
                          label: "Количество",
                          cellClassName: "tabular-nums",
                          getSortValue: (item) => item.quantity,
                          render: (item) => `${item.quantity} шт.`,
                        },
                        {
                          id: "kind",
                          label: "Операция",
                          getSortValue: (item) => item.kind,
                          render: (item) => (
                            <Badge variant="outline">
                              {operationLabel(item)}
                            </Badge>
                          ),
                        },
                        {
                          id: "date",
                          label: "Дата",
                          getSortValue: (item) => item.occurredAt,
                          render: (item) => dateLabel(item.occurredAt),
                        },
                      ]}
                    />
                  </div>

                  <div className="grid gap-3 md:hidden">
                    {items.map((item) => (
                      <EquipmentDispositionMobileCard
                        key={item.id}
                        item={item}
                      />
                    ))}
                  </div>
                </>
              )}
            </>
          )}
        </div>
      )}

      {selectedWarehouseId && canManage ? (
        <EquipmentDispositionDialog
          key={`${selectedWarehouseId}:${dialogOpen}`}
          accessToken={accessToken}
          warehouseId={selectedWarehouseId}
          open={dialogOpen}
          onOpenChange={setDialogOpen}
          onSaved={() => {
            setDialogOpen(false)
            void queryClient.invalidateQueries({
              queryKey: EQUIPMENT_WRITE_OFFS_QUERY_KEY,
            })
          }}
        />
      ) : null}
    </div>
  )
}
