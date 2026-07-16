import { useEffect, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  EQUIPMENT_DISPOSITIONS_STORAGE_KEY,
  EQUIPMENT_DISPOSITIONS_UPDATED_EVENT,
  EQUIPMENT_MOCK_STORAGE_KEY,
  EQUIPMENT_MOCK_UPDATED_EVENT,
  listEquipmentDispositionItems,
  listEquipmentDispositionTransferTargets,
  resolveReturnEquipmentDisposition,
} from "@/api/equipment-api"
import { OperationsListGrid } from "@/components/operations-list-grid"
import { PageToolbar, PageToolbarContent } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox"
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
import { useAuth } from "@/features/auth/use-auth"
import {
  RENTAL_ITEMS_MOCK_STORAGE_KEY,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
} from "@/features/rental-items/api/rental-items-api"
import { useWarehouse } from "@/hooks/use-warehouse"
import type {
  EquipmentDispositionListItemDto,
  ReturnEquipmentDispositionAction,
  ReturnEquipmentDispositionCaseDto,
} from "@/types/equipment"

const EQUIPMENT_WRITE_OFFS_QUERY_KEY = ["equipment-write-offs"] as const

function equipmentWriteOffsQueryKey(warehouseId: string, search: string) {
  return [...EQUIPMENT_WRITE_OFFS_QUERY_KEY, warehouseId, search] as const
}

function dateLabel(value: string) {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(value)
  )
}

function itemName(item: EquipmentDispositionListItemDto) {
  return item.kind === "RETURN_DISPOSITION" ? item.equipmentName : item.name
}

function DispositionStatusBadge({
  item,
}: {
  item: EquipmentDispositionListItemDto
}) {
  if (item.kind === "HISTORICAL_WRITE_OFF") {
    return <Badge variant="destructive">Списано</Badge>
  }
  return item.status === "ACTION_REQUIRED" ? (
    <Badge variant="secondary">Требует действия</Badge>
  ) : (
    <Badge variant="outline">Частично обработано</Badge>
  )
}

function EquipmentDispositionMobileCard({
  item,
  onAction,
}: {
  item: EquipmentDispositionListItemDto
  onAction: (item: ReturnEquipmentDispositionCaseDto) => void
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{itemName(item)}</CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2 text-sm">
        <span className="text-muted-foreground">Бытовка / источник</span>
        <span>
          {item.kind === "RETURN_DISPOSITION"
            ? item.sourceCabinNumber
            : "История списаний"}
        </span>
        <span className="text-muted-foreground">Получено</span>
        <span>
          {item.kind === "RETURN_DISPOSITION"
            ? `${item.receivedQuantity} шт.`
            : "—"}
        </span>
        <span className="text-muted-foreground">Осталось</span>
        <span>
          {item.kind === "RETURN_DISPOSITION"
            ? `${item.remainingQuantity} шт.`
            : `${item.writtenOffQuantity} шт. списано`}
        </span>
        <span className="text-muted-foreground">Дата</span>
        <span>
          {item.kind === "RETURN_DISPOSITION"
            ? dateLabel(item.receivedAt)
            : "—"}
        </span>
        <span className="text-muted-foreground">Статус</span>
        <DispositionStatusBadge item={item} />
      </CardContent>
      {item.kind === "RETURN_DISPOSITION" ? (
        <CardFooter className="justify-end">
          <Button size="sm" variant="outline" onClick={() => onAction(item)}>
            Выбрать действие
          </Button>
        </CardFooter>
      ) : null}
    </Card>
  )
}

export function EquipmentWriteOffsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [selectedCase, setSelectedCase] =
    useState<ReturnEquipmentDispositionCaseDto | null>(null)
  const normalizedSearch = search.trim()
  const listQuery = useQuery({
    queryKey: equipmentWriteOffsQueryKey(
      selectedWarehouseId ?? "none",
      normalizedSearch
    ),
    queryFn: () =>
      listEquipmentDispositionItems({
        warehouseId: selectedWarehouseId!,
        search: normalizedSearch,
      }),
    enabled: selectedWarehouseId !== null,
  })

  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({
        queryKey: EQUIPMENT_WRITE_OFFS_QUERY_KEY,
      })
    }
    const handleStorage = (event: StorageEvent) => {
      if (
        event.key === EQUIPMENT_MOCK_STORAGE_KEY ||
        event.key === EQUIPMENT_DISPOSITIONS_STORAGE_KEY ||
        event.key === RENTAL_ITEMS_MOCK_STORAGE_KEY
      ) {
        invalidate()
      }
    }

    window.addEventListener(EQUIPMENT_MOCK_UPDATED_EVENT, invalidate)
    window.addEventListener(EQUIPMENT_DISPOSITIONS_UPDATED_EVENT, invalidate)
    window.addEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", handleStorage)

    return () => {
      window.removeEventListener(EQUIPMENT_MOCK_UPDATED_EVENT, invalidate)
      window.removeEventListener(
        EQUIPMENT_DISPOSITIONS_UPDATED_EVENT,
        invalidate
      )
      window.removeEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", handleStorage)
    }
  }, [queryClient])

  const items = listQuery.data ?? []
  const openAction = (item: ReturnEquipmentDispositionCaseDto) =>
    setSelectedCase(item)

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-sm">
          <Input
            aria-label="Поиск доп. оборудования для списания"
            placeholder="Наименование или номер бытовки"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
      </PageToolbar>

      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {listQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">
            Загрузка доп. оборудования...
          </p>
        ) : listQuery.isError ? (
          <p role="alert" className="text-xs text-destructive">
            Не удалось загрузить список доп. оборудования.
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
                    getSortValue: itemName,
                    render: itemName,
                  },
                  {
                    id: "source",
                    label: "Бытовка / источник",
                    getSortValue: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.sourceCabinNumber
                        : "История списаний",
                    render: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.sourceCabinNumber
                        : "История списаний",
                  },
                  {
                    id: "received",
                    label: "Получено",
                    cellClassName: "tabular-nums",
                    getSortValue: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.receivedQuantity
                        : null,
                    render: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? `${item.receivedQuantity} шт.`
                        : "—",
                  },
                  {
                    id: "remaining",
                    label: "Осталось",
                    cellClassName: "tabular-nums",
                    getSortValue: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.remainingQuantity
                        : item.writtenOffQuantity,
                    render: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? `${item.remainingQuantity} шт.`
                        : `${item.writtenOffQuantity} шт. списано`,
                  },
                  {
                    id: "date",
                    label: "Дата",
                    getSortValue: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.receivedAt
                        : null,
                    render: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? dateLabel(item.receivedAt)
                        : "—",
                  },
                  {
                    id: "status",
                    label: "Статус",
                    getSortValue: (item) =>
                      item.kind === "RETURN_DISPOSITION"
                        ? item.status
                        : "RESOLVED",
                    render: (item) => <DispositionStatusBadge item={item} />,
                  },
                  {
                    id: "actions",
                    label: "Действия",
                    getSortValue: () => null,
                    render: (item) =>
                      item.kind === "RETURN_DISPOSITION" ? (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => openAction(item)}
                        >
                          Выбрать
                        </Button>
                      ) : (
                        "—"
                      ),
                  },
                ]}
              />
            </div>

            {items.length > 0 ? (
              <div className="grid gap-3 md:hidden">
                {items.map((item) => (
                  <EquipmentDispositionMobileCard
                    key={item.id}
                    item={item}
                    onAction={openAction}
                  />
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground md:hidden">
                Доп. оборудования, требующего действия, нет.
              </p>
            )}
          </>
        )}
      </div>

      <EquipmentDispositionActionDialog
        key={
          selectedCase ? `${selectedCase.id}:${selectedCase.version}` : "closed"
        }
        item={selectedCase}
        onOpenChange={(open) => {
          if (!open) setSelectedCase(null)
        }}
        onSaved={() => {
          setSelectedCase(null)
          void queryClient.invalidateQueries({
            queryKey: EQUIPMENT_WRITE_OFFS_QUERY_KEY,
          })
          void queryClient.invalidateQueries({ queryKey: ["equipment-items"] })
          void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
        }}
      />
    </div>
  )
}

function EquipmentDispositionActionDialog({
  item,
  onOpenChange,
  onSaved,
}: {
  item: ReturnEquipmentDispositionCaseDto | null
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}) {
  const { currentUser } = useAuth()
  const [action, setAction] =
    useState<ReturnEquipmentDispositionAction>("RETURN_TO_STOCK")
  const [quantity, setQuantity] = useState(1)
  const [targetRentalItemId, setTargetRentalItemId] = useState("")
  const [reason, setReason] = useState("")
  const [confirmCreateMasterItem, setConfirmCreateMasterItem] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const dialogContentRef = useRef<HTMLDivElement>(null)
  const targetsQuery = useQuery({
    queryKey: [
      "equipment-disposition-targets",
      item?.warehouseId,
      item?.sourceRentalItemId,
    ],
    queryFn: () =>
      listEquipmentDispositionTransferTargets({
        warehouseId: item!.warehouseId,
        sourceRentalItemId: item!.sourceRentalItemId,
      }),
    enabled: Boolean(item && action === "TRANSFER_TO_CABIN"),
  })
  const mutation = useMutation({
    mutationFn: () =>
      resolveReturnEquipmentDisposition({
        caseId: item!.id,
        expectedVersion: item!.version,
        idempotencyKey: crypto.randomUUID(),
        action,
        quantity,
        targetRentalItemId:
          action === "TRANSFER_TO_CABIN" ? targetRentalItemId : null,
        reason: action === "WRITE_OFF" ? reason : null,
        createdBy: currentUser?.displayName ?? "Текущий пользователь",
        confirmCreateMasterItem,
      }),
    onSuccess: onSaved,
    onError: (mutationError) => {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Не удалось выполнить действие"
      )
    },
  })
  const needsMasterConfirmation =
    action === "RETURN_TO_STOCK" && item?.equipmentMasterItemId === null

  function reset(nextOpen: boolean) {
    onOpenChange(nextOpen)
    if (!nextOpen) return
    setAction("RETURN_TO_STOCK")
    setQuantity(1)
    setTargetRentalItemId("")
    setReason("")
    setConfirmCreateMasterItem(false)
    setError(null)
  }

  return (
    <Dialog open={item !== null} onOpenChange={reset}>
      <DialogContent ref={dialogContentRef}>
        <DialogHeader>
          <DialogTitle>Обработать доп. оборудование</DialogTitle>
          <DialogDescription>
            {item
              ? `${item.equipmentName}, бытовка ${item.sourceCabinNumber}. Осталось ${item.remainingQuantity} шт.`
              : "Выберите действие для карантинной позиции."}
          </DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field>
            <FieldLabel>Действие</FieldLabel>
            <Select
              value={action}
              onValueChange={(value) => {
                setAction(value as ReturnEquipmentDispositionAction)
                setError(null)
              }}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value="RETURN_TO_STOCK">
                    Вернуть на склад
                  </SelectItem>
                  <SelectItem value="TRANSFER_TO_CABIN">
                    Переместить в бытовку
                  </SelectItem>
                  <SelectItem value="WRITE_OFF">Списать</SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>

          <Field>
            <FieldLabel htmlFor="equipment-disposition-quantity">
              Количество
            </FieldLabel>
            <Input
              id="equipment-disposition-quantity"
              type="number"
              min={1}
              max={item?.remainingQuantity ?? 1}
              value={quantity}
              onChange={(event) => setQuantity(Number(event.target.value))}
            />
          </Field>

          {action === "TRANSFER_TO_CABIN" ? (
            <Field data-invalid={Boolean(error && !targetRentalItemId)}>
              <FieldLabel>Куда переместить</FieldLabel>
              <Combobox
                items={(targetsQuery.data ?? []).map((target) => target.number)}
                value={
                  targetsQuery.data?.find(
                    (target) => target.id === targetRentalItemId
                  )?.number ?? null
                }
                onValueChange={(value) =>
                  setTargetRentalItemId(
                    targetsQuery.data?.find((target) => target.number === value)
                      ?.id ?? ""
                  )
                }
              >
                <ComboboxInput
                  placeholder="Введите номер бытовки"
                  aria-invalid={Boolean(error && !targetRentalItemId)}
                />
                <ComboboxContent portalContainer={dialogContentRef}>
                  <ComboboxList>
                    <ComboboxEmpty>Доступные бытовки не найдены</ComboboxEmpty>
                    <ComboboxGroup>
                      {(targetsQuery.data ?? []).map((target) => (
                        <ComboboxItem key={target.id} value={target.number}>
                          {target.number}
                        </ComboboxItem>
                      ))}
                    </ComboboxGroup>
                  </ComboboxList>
                </ComboboxContent>
              </Combobox>
              <FieldDescription>
                Доступны активные бытовки того же склада, кроме арендованных.
              </FieldDescription>
            </Field>
          ) : null}

          {action === "WRITE_OFF" ? (
            <Field data-invalid={Boolean(error && !reason.trim())}>
              <FieldLabel htmlFor="equipment-disposition-reason">
                Причина списания
              </FieldLabel>
              <Textarea
                id="equipment-disposition-reason"
                value={reason}
                aria-invalid={Boolean(error && !reason.trim())}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
          ) : null}

          {needsMasterConfirmation ? (
            <Field orientation="horizontal">
              <Checkbox
                id="equipment-create-master"
                checked={confirmCreateMasterItem}
                onCheckedChange={(checked) =>
                  setConfirmCreateMasterItem(checked === true)
                }
              />
              <FieldLabel htmlFor="equipment-create-master">
                Создать новую позицию на складе
              </FieldLabel>
            </Field>
          ) : null}

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
            variant={action === "WRITE_OFF" ? "destructive" : "default"}
            disabled={
              mutation.isPending ||
              !item ||
              !Number.isInteger(quantity) ||
              quantity < 1 ||
              quantity > (item?.remainingQuantity ?? 0) ||
              (action === "TRANSFER_TO_CABIN" && !targetRentalItemId) ||
              (action === "WRITE_OFF" && !reason.trim()) ||
              (needsMasterConfirmation && !confirmCreateMasterItem)
            }
            onClick={() => mutation.mutate()}
          >
            Применить
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
