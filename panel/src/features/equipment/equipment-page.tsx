import { useEffect, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  ArrowRightLeft,
  ChevronDown,
  ChevronRight,
  Warehouse,
} from "lucide-react"

import {
  EQUIPMENT_MOCK_STORAGE_KEY,
  EQUIPMENT_MOCK_UPDATED_EVENT,
  getEquipmentItems,
  moveEquipmentUsageToStock,
} from "@/api/equipment-api"
import {
  RENTAL_ITEMS_MOCK_STORAGE_KEY,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
} from "@/features/rental-items/api/rental-items-api"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useWarehouse } from "@/hooks/use-warehouse"
import type {
  EquipmentItemDto,
  EquipmentRentalUsageDto,
} from "@/types/equipment"

function QuantityCell({
  value,
  tone = "default",
}: {
  value: number
  tone?: "default" | "stock" | "rent" | "written-off" | "lost"
}) {
  const toneClass =
    tone === "stock"
      ? "text-emerald-700"
      : tone === "rent"
        ? "text-amber-700"
        : tone === "written-off"
          ? "text-muted-foreground"
          : tone === "lost"
            ? "text-destructive"
            : "text-foreground"

  return <span className={`font-semibold ${toneClass}`}>{value}</span>
}

function MobileQuantityStat({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone?: "default" | "stock" | "rent" | "written-off" | "lost"
}) {
  return (
    <div className="min-w-0 rounded-md bg-muted/40 px-2 py-1.5">
      <div className="truncate text-[0.625rem] font-medium text-muted-foreground">
        {label}
      </div>

      <div className="text-sm">
        <QuantityCell value={value} tone={tone} />
      </div>
    </div>
  )
}

function MobileCabinStockStat({ value }: { value: number }) {
  return (
    <div className="col-start-1 min-w-0 rounded-md bg-muted/40 px-2 py-1.5">
      <div className="text-[0.625rem] font-medium leading-tight text-muted-foreground">
        В бытовках на складе
      </div>

      <div className="text-sm">
        <QuantityCell value={value} tone="stock" />
      </div>
    </div>
  )
}

function UsageRows({ item }: { item: EquipmentItemDto }) {
  const queryClient = useQueryClient()

  const moveToStockMutation = useMutation({
    mutationFn: (usage: EquipmentRentalUsageDto) => {
      return moveEquipmentUsageToStock(item.id, usage.id)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-items"],
      })

      queryClient.invalidateQueries({
        queryKey: ["rental-item"],
      })
    },
  })

  if (item.usages.length === 0) {
    return (
      <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
        В аренде нет.
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-md border bg-card">
      <div className="hidden grid-cols-[140px_160px_120px_1fr] border-b bg-muted px-3 py-2 text-xs font-medium text-muted-foreground md:grid">
        <div>Номер бытовки</div>
        <div>Тип</div>
        <div className="text-right">Количество</div>
        <div className="text-right">Действия</div>
      </div>

      {item.usages.map((usage) => (
        <div
          key={usage.id}
          className="flex min-w-0 flex-col gap-3 border-b p-3 text-sm last:border-b-0 md:grid md:grid-cols-[140px_160px_120px_1fr] md:items-center md:gap-0 md:px-3 md:py-2"
        >
          <div className="min-w-0">
            <div className="text-[0.625rem] font-medium text-muted-foreground md:hidden">
              Номер бытовки
            </div>

            <div className="truncate font-medium text-primary">
              {usage.rentalItemNumber}
            </div>
          </div>

          <div className="min-w-0">
            <div className="text-[0.625rem] font-medium text-muted-foreground md:hidden">
              Тип
            </div>

            <div className="truncate">{usage.rentalItemType}</div>
          </div>

          <div className="flex items-center justify-between gap-2 md:block md:text-right">
            <span className="text-[0.625rem] font-medium text-muted-foreground md:hidden">
              Количество
            </span>

            <span className="font-semibold">{usage.quantity} шт.</span>
          </div>

          <div className="flex min-w-0 flex-col gap-2 md:flex-row md:justify-end">
            <Button
              size="sm"
              variant="outline"
              className="min-w-0 whitespace-normal md:h-8 md:whitespace-nowrap"
              onClick={(event) => {
                event.stopPropagation()

                console.log("Переместить оборудование", {
                  equipmentItemId: item.id,
                  equipmentName: item.name,
                  usageId: usage.id,
                  rentalItemId: usage.rentalItemId,
                })
              }}
            >
              <ArrowRightLeft data-icon="inline-start" />
              Переместить
            </Button>

            <Button
              size="sm"
              variant="outline"
              className="min-w-0 whitespace-normal md:h-8 md:whitespace-nowrap"
              disabled={moveToStockMutation.isPending}
              onClick={(event) => {
                event.stopPropagation()
                moveToStockMutation.mutate(usage)
              }}
            >
              <Warehouse data-icon="inline-start" />
              Переместить на склад
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

export function EquipmentPage() {
  const queryClient = useQueryClient()
  const { selectedWarehouse } = useWarehouse()

  const [search, setSearch] = useState("")
  const [expandedItemId, setExpandedItemId] = useState<string | null>(null)

  const warehouseId = selectedWarehouse?.id

  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, search],
    queryFn: () =>
      getEquipmentItems({
        warehouseId: warehouseId!,
        search,
      }),
    enabled: warehouseId !== undefined,
  })

  useEffect(() => {
    function invalidateEquipmentItems() {
      queryClient.invalidateQueries({
        queryKey: ["equipment-items"],
      })
    }

    function handleStorageEvent(event: StorageEvent) {
      if (
        event.key !== EQUIPMENT_MOCK_STORAGE_KEY &&
        event.key !== RENTAL_ITEMS_MOCK_STORAGE_KEY
      ) {
        return
      }

      invalidateEquipmentItems()
    }

    window.addEventListener(
      EQUIPMENT_MOCK_UPDATED_EVENT,
      invalidateEquipmentItems
    )
    window.addEventListener(
      RENTAL_ITEMS_MOCK_UPDATED_EVENT,
      invalidateEquipmentItems
    )
    window.addEventListener("storage", handleStorageEvent)

    return () => {
      window.removeEventListener(
        EQUIPMENT_MOCK_UPDATED_EVENT,
        invalidateEquipmentItems
      )
      window.removeEventListener(
        RENTAL_ITEMS_MOCK_UPDATED_EVENT,
        invalidateEquipmentItems
      )
      window.removeEventListener("storage", handleStorageEvent)
    }
  }, [queryClient])

  if (!selectedWarehouse) {
    return (
      <div className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Склад не выбран.
      </div>
    )
  }

  function toggleExpanded(item: EquipmentItemDto) {
    setExpandedItemId((current) => {
      return current === item.id ? null : item.id
    })
  }

  const items = equipmentQuery.data ?? []

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div className="flex min-w-0 items-center gap-3">
          <h2 className="truncate text-xl font-semibold">Доп. оборудование</h2>

          <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {items.length} поз.
          </span>
        </div>
      </div>

      <Input
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="Поиск по мебели..."
        className="h-10 max-w-xl"
      />

      <div className="min-h-0 flex-1 overflow-hidden rounded-lg border bg-card">
        <div className="h-full overflow-y-auto overflow-x-hidden">
          <div className="w-full">
            <div className="sticky top-0 z-10 hidden grid-cols-[minmax(160px,1fr)_80px_90px_128px_90px_90px_90px] border-b bg-muted text-xs font-medium text-muted-foreground shadow-sm md:grid">
              <div className="px-3 py-3">Наименование</div>
              <div className="px-3 py-3 text-right">Всего</div>
              <div className="px-3 py-3 text-right">На складе</div>
              <div className="px-3 py-3 text-right">
                В бытовках на складе
              </div>
              <div className="px-3 py-3 text-right">В аренде</div>
              <div className="px-3 py-3 text-right">Списано</div>
              <div className="px-3 py-3 text-right">Утеряно</div>
            </div>

            {equipmentQuery.isLoading ? (
              <div className="p-4 text-sm text-muted-foreground">
                Загрузка оборудования...
              </div>
            ) : items.length === 0 ? (
              <div className="p-4 text-sm text-muted-foreground">
                Доп. оборудование не найдено.
              </div>
            ) : (
              items.map((item) => {
                const expanded = expandedItemId === item.id

                return (
                  <div key={item.id} className="border-b last:border-b-0">
                    <button
                      type="button"
                      className="flex w-full min-w-0 flex-col gap-3 p-3 text-left text-sm hover:bg-muted/40 md:grid md:grid-cols-[minmax(160px,1fr)_80px_90px_128px_90px_90px_90px] md:items-center md:gap-0 md:p-0"
                      onClick={() => toggleExpanded(item)}
                    >
                      <div className="flex min-w-0 items-center gap-2 md:px-3 md:py-3">
                        {expanded ? (
                          <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                        )}

                        <span className="truncate font-medium">
                          {item.name}
                        </span>
                      </div>

                      <div className="grid min-w-0 grid-cols-2 gap-2 md:hidden">
                        <MobileQuantityStat
                          label="Всего"
                          value={item.totalQuantity}
                        />
                        <MobileQuantityStat
                          label="На складе"
                          value={item.stockQuantity}
                          tone="stock"
                        />
                        <MobileCabinStockStat
                          value={item.cabinStockQuantity}
                        />
                        <MobileQuantityStat
                          label="В аренде"
                          value={item.rentedQuantity}
                          tone="rent"
                        />
                        <MobileQuantityStat
                          label="Списано"
                          value={item.writtenOffQuantity}
                          tone="written-off"
                        />
                        <MobileQuantityStat
                          label="Утеряно"
                          value={item.lostQuantity}
                          tone="lost"
                        />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell value={item.totalQuantity} />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell value={item.stockQuantity} tone="stock" />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell
                          value={item.cabinStockQuantity}
                          tone="stock"
                        />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell value={item.rentedQuantity} tone="rent" />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell
                          value={item.writtenOffQuantity}
                          tone="written-off"
                        />
                      </div>

                      <div className="hidden px-3 py-3 text-right md:block">
                        <QuantityCell value={item.lostQuantity} tone="lost" />
                      </div>
                    </button>

                    {expanded && (
                      <div className="border-t bg-muted/20 p-3">
                        <UsageRows item={item} />
                      </div>
                    )}
                  </div>
                )
              })
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
