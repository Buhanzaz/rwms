import { useEffect, useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useNavigate } from "react-router-dom"
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
import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import { PageToolbar, PageToolbarContent } from "@/components/page-toolbar"
import { useWarehouse } from "@/hooks/use-warehouse"
import type {
  EquipmentItemDto,
  EquipmentRentalUsageDto,
} from "@/types/equipment"

const EMPTY_EQUIPMENT_ITEMS: EquipmentItemDto[] = []

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
      <div className="text-[0.625rem] leading-tight font-medium text-muted-foreground">
        В бытовках
      </div>

      <div className="text-sm">
        <QuantityCell value={value} tone="stock" />
      </div>
    </div>
  )
}

function UsageRows({
  item,
  onOpenRentalItem,
}: {
  item: EquipmentItemDto
  onOpenRentalItem: (rentalItemId: string) => void
}) {
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
          <button
            type="button"
            aria-label={`Открыть карточку бытовки ${usage.rentalItemNumber}`}
            className="grid w-full min-w-0 gap-3 rounded-md border border-transparent bg-card p-2 text-left transition-[background-color,border-color,box-shadow] hover:border-border hover:bg-muted/35 hover:shadow-[0_8px_22px_rgba(15,23,42,0.12)] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30 focus-visible:outline-none active:bg-muted/50 md:hidden"
            onClick={() => onOpenRentalItem(usage.rentalItemId)}
          >
            <div className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground">
                Номер бытовки
              </div>

              <div className="truncate font-medium text-primary">
                {usage.rentalItemNumber}
              </div>
            </div>

            <div className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground">
                Тип
              </div>

              <div className="truncate">{usage.rentalItemType}</div>
            </div>

            <div className="flex items-center justify-between gap-2">
              <span className="text-[0.625rem] font-medium text-muted-foreground">
                Количество
              </span>

              <span className="font-semibold">{usage.quantity} шт.</span>
            </div>
          </button>

          <div className="hidden min-w-0 md:block">
            <div className="truncate font-medium text-primary">
              {usage.rentalItemNumber}
            </div>
          </div>

          <div className="hidden min-w-0 md:block">
            <div className="truncate">{usage.rentalItemType}</div>
          </div>

          <div className="hidden text-right md:block">
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

function EquipmentDesktopGrid({
  items,
  expandedItemId,
  onToggleExpanded,
  onOpenRentalItem,
}: {
  items: EquipmentItemDto[]
  expandedItemId: string | null
  onToggleExpanded: (item: EquipmentItemDto) => void
  onOpenRentalItem: (rentalItemId: string) => void
}) {
  const columns = useMemo<OperationsListGridColumn<EquipmentItemDto>[]>(
    () => [
      {
        id: "name",
        label: "Наименование",
        getSortValue: (item) => item.name,
        render: (item) => {
          const expanded = expandedItemId === item.id

          return (
            <button
              type="button"
              className="flex min-w-0 items-center gap-2 text-left"
              aria-label={`${expanded ? "Свернуть" : "Развернуть"} оборудование ${item.name}`}
              aria-expanded={expanded}
              onClick={() => onToggleExpanded(item)}
            >
              {expanded ? (
                <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
              ) : (
                <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
              )}
              <span className="truncate font-medium">{item.name}</span>
            </button>
          )
        },
      },
      {
        id: "totalQuantity",
        label: "Всего",
        getSortValue: (item) => item.totalQuantity,
        cellClassName: "text-left",
        render: (item) => <QuantityCell value={item.totalQuantity} />,
      },
      {
        id: "stockQuantity",
        label: "На складе",
        getSortValue: (item) => item.stockQuantity,
        cellClassName: "text-left",
        render: (item) => (
          <QuantityCell value={item.stockQuantity} tone="stock" />
        ),
      },
      {
        id: "cabinStockQuantity",
        label: "В бытовках",
        getSortValue: (item) => item.cabinStockQuantity,
        cellClassName: "text-left",
        render: (item) => (
          <QuantityCell value={item.cabinStockQuantity} tone="stock" />
        ),
      },
      {
        id: "rentedQuantity",
        label: "В аренде",
        getSortValue: (item) => item.rentedQuantity,
        cellClassName: "text-left",
        render: (item) => (
          <QuantityCell value={item.rentedQuantity} tone="rent" />
        ),
      },
      {
        id: "writtenOffQuantity",
        label: "Списано",
        getSortValue: (item) => item.writtenOffQuantity,
        cellClassName: "text-left",
        render: (item) => (
          <QuantityCell value={item.writtenOffQuantity} tone="written-off" />
        ),
      },
      {
        id: "lostQuantity",
        label: "Утеряно",
        getSortValue: (item) => item.lostQuantity,
        cellClassName: "text-left",
        render: (item) => (
          <QuantityCell value={item.lostQuantity} tone="lost" />
        ),
      },
    ],
    [expandedItemId, onToggleExpanded]
  )

  return (
    <div className="hidden h-full min-h-0 overflow-auto md:block">
      <OperationsListGrid
        columns={columns}
        items={items}
        expandedItemId={expandedItemId}
        renderExpandedRow={(item) => (
          <UsageRows item={item} onOpenRentalItem={onOpenRentalItem} />
        )}
      />
    </div>
  )
}

export function EquipmentPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
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

  function toggleExpanded(item: EquipmentItemDto) {
    setExpandedItemId((current) => {
      return current === item.id ? null : item.id
    })
  }

  const items = equipmentQuery.data ?? EMPTY_EQUIPMENT_ITEMS

  if (!selectedWarehouse) {
    return (
      <div className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Склад не выбран.
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Поиск по мебели..."
          />
        </PageToolbarContent>
      </PageToolbar>

      {equipmentQuery.isLoading ? (
        <div className="flex min-h-0 flex-1 items-center rounded-lg border bg-card p-4 text-sm text-muted-foreground">
          Загрузка оборудования...
        </div>
      ) : (
        <>
          <div className="min-h-0 flex-1 overflow-y-auto rounded-lg border bg-card md:hidden">
            {items.length === 0 ? (
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
                      className="flex w-full min-w-0 flex-col gap-3 p-3 text-left text-sm hover:bg-muted/40"
                      aria-expanded={expanded}
                      onClick={() => toggleExpanded(item)}
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        {expanded ? (
                          <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                        )}

                        <span className="truncate font-medium">
                          {item.name}
                        </span>
                      </div>

                      <div className="grid min-w-0 grid-cols-2 gap-2">
                        <MobileQuantityStat
                          label="Всего"
                          value={item.totalQuantity}
                        />
                        <MobileQuantityStat
                          label="На складе"
                          value={item.stockQuantity}
                          tone="stock"
                        />
                        <MobileCabinStockStat value={item.cabinStockQuantity} />
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
                    </button>

                    {expanded ? (
                      <div className="border-t bg-muted/20 p-3">
                        <UsageRows
                          item={item}
                          onOpenRentalItem={(rentalItemId) => {
                            navigate(`/warehouse/${rentalItemId}`)
                          }}
                        />
                      </div>
                    ) : null}
                  </div>
                )
              })
            )}
          </div>

          <EquipmentDesktopGrid
            items={items}
            expandedItemId={expandedItemId}
            onToggleExpanded={toggleExpanded}
            onOpenRentalItem={(rentalItemId) => {
              navigate(`/warehouse/${rentalItemId}`)
            }}
          />
        </>
      )}
    </div>
  )
}
