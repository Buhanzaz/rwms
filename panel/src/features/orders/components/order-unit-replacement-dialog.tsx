import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Loading03Icon, RefreshIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link } from "react-router-dom"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
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
import { Textarea } from "@/components/ui/textarea"
import { listWarehouseSupportLinks } from "@/api/warehouse-api"
import {
  listOrderReplacementCandidates,
  replaceOrderUnit,
} from "@/features/orders/api/orders-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import type {
  OrderDetail,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const CANDIDATE_PAGE_SIZE = 50

function clientReplacementPath(
  clientId: string,
  orderId: string,
  replacementUnitIds: string[]
) {
  const query = new URLSearchParams({
    clientId,
    orderId,
    mode: "REPLACEMENT",
  })
  replacementUnitIds.forEach((unitId) =>
    query.append("replacementUnitId", unitId)
  )
  return `/booking?${query.toString()}`
}

function transientReplacementError(error: unknown) {
  if (
    typeof DOMException !== "undefined" &&
    error instanceof DOMException &&
    error.name === "AbortError"
  ) {
    return "Запрос отменён. Бытовка не заменена."
  }
  return error instanceof Error
    ? error.message
    : "Не удалось заменить бытовку. Замена не выполнена."
}

/**
 * Starts either the existing client presentation replacement or one direct
 * warehouse-manager replacement inside the current order.
 */
export function OrderUnitReplacementDialog({
  open,
  order,
  onOpenChange,
  onReplaced,
  onConflict,
}: {
  open: boolean
  order: OrderDetail
  onOpenChange: (open: boolean) => void
  onReplaced: (order: OrderDetail) => void
  onConflict: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Заменить бытовки</DialogTitle>
          <DialogDescription>
            Сначала выберите заменяемые бытовки, затем используйте один из двух
            существующих способов замены.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <OrderUnitReplacementDialogContent
            order={order}
            onClose={() => onOpenChange(false)}
            onReplaced={onReplaced}
            onConflict={onConflict}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function OrderUnitReplacementDialogContent({
  order,
  onClose,
  onReplaced,
  onConflict,
}: {
  order: OrderDetail
  onClose: () => void
  onReplaced: (order: OrderDetail) => void
  onConflict: () => void
}) {
  const { accessToken, currentUser } = useOrdersModule()
  const { warehouses } = useWarehouse()
  const [selectedOldUnitIds, setSelectedOldUnitIds] = useState<string[]>([])
  const [selfReplacementOpen, setSelfReplacementOpen] = useState(false)
  const [reason, setReason] = useState("")
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(0)
  const [replacementUnitId, setReplacementUnitId] = useState<string | null>(
    null
  )
  const [inventorySourceWarehouseId, setInventorySourceWarehouseId] = useState(
    order.warehouseId ?? ""
  )
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const selectedOrderUnits = order.units.filter((candidate) => candidate.added)
  const selectedOldUnit =
    selectedOldUnitIds.length === 1
      ? order.units.find(
          (candidate) => candidate.unit.id === selectedOldUnitIds[0]
        )
      : undefined
  const currentOrderUnitIds = useMemo(
    () => new Set(order.units.map((candidate) => candidate.unit.id)),
    [order.units]
  )
  const supportLinksQuery = useQuery({
    queryKey: [
      "warehouses",
      order.warehouseId ?? "no-service-warehouse",
      "support-links",
    ],
    queryFn: () => listWarehouseSupportLinks(accessToken, order.warehouseId!),
    enabled: Boolean(
      accessToken && order.warehouseId && selfReplacementOpen && selectedOldUnit
    ),
  })
  const sourceWarehouseOptions = useMemo(() => {
    if (!order.warehouseId) return []
    const warehouseById = new Map(
      warehouses.map((warehouse) => [warehouse.id, warehouse] as const)
    )
    const serviceWarehouse = warehouseById.get(order.warehouseId)
    const supportOptions = (supportLinksQuery.data?.links ?? [])
      .filter(
        (link) =>
          link.active &&
          link.allowInventory &&
          link.allowDirectFulfillment &&
          warehouseById.has(link.supportWarehouseId)
      )
      .sort(
        (left, right) =>
          left.priority - right.priority ||
          (
            warehouseById.get(left.supportWarehouseId)?.name ?? ""
          ).localeCompare(
            warehouseById.get(right.supportWarehouseId)?.name ?? "",
            "ru"
          )
      )
      .map((link) => ({
        id: link.supportWarehouseId,
        name: warehouseById.get(link.supportWarehouseId)!.name,
        support: true,
      }))

    return [
      {
        id: order.warehouseId,
        name: serviceWarehouse?.name ?? "Склад обслуживания",
        support: false,
      },
      ...supportOptions,
    ]
  }, [order.warehouseId, supportLinksQuery.data?.links, warehouses])
  const serviceWarehouseName =
    warehouses.find((warehouse) => warehouse.id === order.warehouseId)?.name ??
    "Склад обслуживания"
  const selectedSourceWarehouseName =
    sourceWarehouseOptions.find(
      (warehouse) => warehouse.id === inventorySourceWarehouseId
    )?.name ?? "выбранном складе"
  const candidateQuery = useQuery({
    queryKey: [
      "orders",
      "replacement-candidates",
      currentUser?.id ?? "unknown-user",
      order.id,
      inventorySourceWarehouseId || "no-source-warehouse",
      page,
      CANDIDATE_PAGE_SIZE,
      search,
    ],
    queryFn: () =>
      listOrderReplacementCandidates({
        accessToken: accessToken!,
        orderId: order.id,
        page,
        size: CANDIDATE_PAGE_SIZE,
        search,
        inventorySourceWarehouseId:
          inventorySourceWarehouseId === order.warehouseId
            ? null
            : inventorySourceWarehouseId,
      }),
    enabled: Boolean(
      accessToken &&
      selfReplacementOpen &&
      selectedOldUnit &&
      inventorySourceWarehouseId
    ),
  })
  const candidates = useMemo(
    () =>
      (candidateQuery.data?.content ?? []).filter(
        (candidate) =>
          !candidate.added &&
          candidate.unit.status === "FREE" &&
          candidate.unit.warehouseId === inventorySourceWarehouseId &&
          !currentOrderUnitIds.has(candidate.unit.id)
      ),
    [
      candidateQuery.data?.content,
      currentOrderUnitIds,
      inventorySourceWarehouseId,
    ]
  )

  const replaceMutation = useMutation({
    mutationFn: ({
      oldUnit,
      newUnit,
      normalizedReason,
      expectedVersion,
      fingerprint,
      sourceWarehouseId,
    }: {
      oldUnit: OrderUnitCandidate
      newUnit: OrderUnitCandidate
      normalizedReason: string
      expectedVersion: number
      fingerprint: string
      sourceWarehouseId: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")
      return replaceOrderUnit({
        accessToken,
        orderId: order.id,
        expectedVersion,
        unitId: oldUnit.unit.id,
        replacementRentalItemId: newUnit.unit.id,
        reason: normalizedReason,
        inventorySourceWarehouseId:
          sourceWarehouseId === order.warehouseId ? null : sourceWarehouseId,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { oldUnit, newUnit, fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      onReplaced(projection)
      toast.success(
        `Бытовка ${oldUnit.unit.number} заменена на ${newUnit.unit.number}.`
      )
      onClose()
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        onConflict()
        void candidateQuery.refetch()
        setErrorText(
          "Состав заказа или доступность бытовок изменились. Актуальные данные загружены; проверьте выбор и повторите замену."
        )
        return
      }
      setErrorText(transientReplacementError(error))
    },
  })

  function toggleOldUnit(unitId: string, checked: boolean) {
    setSelectedOldUnitIds((current) =>
      checked
        ? current.includes(unitId)
          ? current
          : [...current, unitId]
        : current.filter((candidateId) => candidateId !== unitId)
    )
    setSelfReplacementOpen(false)
    setReplacementUnitId(null)
    setInventorySourceWarehouseId(order.warehouseId ?? "")
    setPage(0)
    setErrorText(null)
  }

  function submitSelfReplacement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedReason = reason.trim()
    if (!selectedOldUnit || selectedOldUnitIds.length !== 1) {
      setErrorText("Для самостоятельной замены выберите одну бытовку.")
      return
    }
    if (!order.warehouseId || !inventorySourceWarehouseId) {
      setErrorText("У заказа не задан склад обслуживания.")
      return
    }
    if (
      !sourceWarehouseOptions.some(
        (warehouse) => warehouse.id === inventorySourceWarehouseId
      )
    ) {
      setErrorText("Выбранный склад-источник больше недоступен.")
      return
    }
    if (normalizedReason.length === 0) {
      setErrorText("Укажите причину самостоятельной замены.")
      return
    }
    if (normalizedReason.length > 2_000) {
      setErrorText("Причина замены не должна превышать 2000 символов.")
      return
    }
    const newUnit = candidates.find(
      (candidate) => candidate.unit.id === replacementUnitId
    )
    if (!newUnit) {
      setErrorText("Выберите доступную заменяющую бытовку.")
      return
    }
    const fingerprint = JSON.stringify({
      operation: "replace-order-unit",
      orderId: order.id,
      expectedVersion: order.version,
      oldUnitId: selectedOldUnit.unit.id,
      replacementRentalItemId: newUnit.unit.id,
      inventorySourceWarehouseId,
      reason: normalizedReason,
    })
    setErrorText(null)
    replaceMutation.mutate({
      oldUnit: selectedOldUnit,
      newUnit,
      normalizedReason,
      expectedVersion: order.version,
      fingerprint,
      sourceWarehouseId: inventorySourceWarehouseId,
    })
  }

  if (!accessToken || !currentUser) {
    return <FieldError>Сессия завершена.</FieldError>
  }

  return (
    <FieldGroup>
      <Field>
        <FieldLabel>Заменяемые бытовки</FieldLabel>
        <FieldDescription>
          Порядок выбора сохраняется при передаче клиенту.
        </FieldDescription>
        <div className="grid gap-2 sm:grid-cols-2">
          {selectedOrderUnits.map((candidate) => {
            const selected = selectedOldUnitIds.includes(candidate.unit.id)
            return (
              <label
                key={candidate.unit.id}
                className="flex cursor-pointer items-start gap-3 rounded-lg border p-3"
              >
                <Checkbox
                  checked={selected}
                  aria-label={`Заменить бытовку ${candidate.unit.number}`}
                  onCheckedChange={(checked) =>
                    toggleOldUnit(candidate.unit.id, checked === true)
                  }
                />
                <span className="min-w-0">
                  <span className="block font-medium">
                    {candidate.unit.number}
                  </span>
                  <span className="block text-sm text-muted-foreground">
                    {candidate.unit.rentalType ?? "Тип не указан"}
                  </span>
                </span>
              </label>
            )
          })}
        </div>
      </Field>

      {selectedOldUnitIds.length === 0 ? (
        <FieldDescription>
          Выберите хотя бы одну бытовку, чтобы открыть способы замены.
        </FieldDescription>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          <Card size="sm">
            <CardHeader>
              <CardTitle>Через клиента</CardTitle>
              <CardDescription>
                Клиент выберет столько замен, сколько бытовок выбрано здесь.
              </CardDescription>
            </CardHeader>
            <CardFooter>
              <Button asChild className="w-full">
                <Link
                  to={clientReplacementPath(
                    order.client.id,
                    order.id,
                    selectedOldUnitIds
                  )}
                >
                  Продолжить через клиента
                </Link>
              </Button>
            </CardFooter>
          </Card>
          <Card size="sm">
            <CardHeader>
              <CardTitle>Самостоятельно</CardTitle>
              <CardDescription>
                Руководитель склада заменяет одну бытовку с обязательной
                причиной.
              </CardDescription>
            </CardHeader>
            <CardFooter>
              <Button
                type="button"
                variant="outline"
                className="w-full"
                disabled={selectedOldUnitIds.length !== 1}
                onClick={() => {
                  setSelfReplacementOpen(true)
                  setPage(0)
                  setReplacementUnitId(null)
                  setInventorySourceWarehouseId(order.warehouseId ?? "")
                  setErrorText(null)
                }}
              >
                Выбрать замену самостоятельно
              </Button>
            </CardFooter>
          </Card>
        </div>
      )}

      {selfReplacementOpen && selectedOldUnit ? (
        <form noValidate onSubmit={submitSelfReplacement}>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="order-unit-replacement-source">
                Склад-источник бытовки
              </FieldLabel>
              <Select
                value={inventorySourceWarehouseId}
                disabled={
                  replaceMutation.isPending ||
                  !order.warehouseId ||
                  sourceWarehouseOptions.length === 0
                }
                onValueChange={(warehouseId) => {
                  setInventorySourceWarehouseId(warehouseId)
                  setReplacementUnitId(null)
                  setPage(0)
                  setErrorText(null)
                }}
              >
                <SelectTrigger id="order-unit-replacement-source">
                  <SelectValue placeholder="Выберите склад-источник" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {sourceWarehouseOptions.map((warehouse) => (
                      <SelectItem key={warehouse.id} value={warehouse.id}>
                        {warehouse.name}
                        {warehouse.support ? " · опорный" : " · обслуживание"}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <FieldDescription>
                Склад обслуживания заказа: {serviceWarehouseName}. Физическая
                бытовка будет зарезервирована на выбранном складе-источнике.
              </FieldDescription>
              {supportLinksQuery.isFetching ? (
                <FieldDescription>Загружаем опорные склады…</FieldDescription>
              ) : null}
              {supportLinksQuery.isError ? (
                <FieldDescription>
                  Опорные склады недоступны. Можно выбрать только склад
                  обслуживания.
                </FieldDescription>
              ) : null}
            </Field>
            <Field data-invalid={Boolean(errorText) || undefined}>
              <FieldLabel htmlFor="order-unit-replacement-reason">
                Причина замены
              </FieldLabel>
              <Textarea
                id="order-unit-replacement-reason"
                value={reason}
                required
                maxLength={2_000}
                disabled={replaceMutation.isPending}
                aria-invalid={Boolean(errorText)}
                placeholder="Опишите, почему исходную бытовку нельзя отгрузить"
                onChange={(event) => {
                  setReason(event.target.value)
                  setErrorText(null)
                }}
              />
              <FieldDescription>{reason.length} из 2000</FieldDescription>
            </Field>

            <Field>
              <FieldLabel htmlFor="order-unit-replacement-search">
                Заменяющая бытовка
              </FieldLabel>
              <Input
                id="order-unit-replacement-search"
                type="search"
                value={search}
                disabled={replaceMutation.isPending}
                placeholder="Поиск по номеру"
                onChange={(event) => {
                  setSearch(event.target.value)
                  setPage(0)
                  setReplacementUnitId(null)
                }}
              />
            </Field>

            {candidateQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">
                Загружаем доступные бытовки…
              </p>
            ) : candidateQuery.isError ? (
              <div className="flex flex-col items-start gap-2">
                <FieldError>
                  {transientReplacementError(candidateQuery.error)}
                </FieldError>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => void candidateQuery.refetch()}
                >
                  <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
                  Повторить поиск
                </Button>
              </div>
            ) : candidates.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Свободные бытовки на складе «{selectedSourceWarehouseName}» не
                найдены.
              </p>
            ) : (
              <div className="grid gap-2 sm:grid-cols-2">
                {candidates.map((candidate) => {
                  const selected = replacementUnitId === candidate.unit.id
                  return (
                    <Button
                      key={candidate.unit.id}
                      type="button"
                      variant={selected ? "secondary" : "outline"}
                      className="h-auto min-h-14 justify-between text-left"
                      aria-label={`Выбрать заменяющую бытовку ${candidate.unit.number}`}
                      aria-pressed={selected}
                      disabled={replaceMutation.isPending}
                      onClick={() => {
                        setReplacementUnitId(candidate.unit.id)
                        setErrorText(null)
                      }}
                    >
                      <span>{candidate.unit.number}</span>
                      <Badge variant="outline">Свободна</Badge>
                    </Button>
                  )
                })}
              </div>
            )}

            {candidateQuery.data && candidateQuery.data.totalPages > 1 ? (
              <div className="flex items-center justify-between gap-3">
                <span className="text-sm text-muted-foreground">
                  Страница {page + 1} из {candidateQuery.data.totalPages}
                </span>
                <div className="flex gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={page === 0 || candidateQuery.isFetching}
                    onClick={() => {
                      setPage((current) => Math.max(0, current - 1))
                      setReplacementUnitId(null)
                    }}
                  >
                    Назад
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={
                      page + 1 >= candidateQuery.data.totalPages ||
                      candidateQuery.isFetching
                    }
                    onClick={() => {
                      setPage((current) => current + 1)
                      setReplacementUnitId(null)
                    }}
                  >
                    Вперёд
                  </Button>
                </div>
              </div>
            ) : null}

            {errorText ? <FieldError>{errorText}</FieldError> : null}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>
                Отмена
              </Button>
              <Button
                type="submit"
                disabled={
                  replacementUnitId === null || replaceMutation.isPending
                }
              >
                {replaceMutation.isPending ? (
                  <HugeiconsIcon
                    icon={Loading03Icon}
                    data-icon="inline-start"
                    className="animate-spin"
                  />
                ) : null}
                {replaceMutation.isPending ? "Заменяем…" : "Подтвердить замену"}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      ) : null}
    </FieldGroup>
  )
}
