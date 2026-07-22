import { useMemo, useState } from "react"
import {
  ArrowDown01Icon,
  ArrowRight01Icon,
  WarehouseIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useQuery } from "@tanstack/react-query"
import { useNavigate } from "react-router-dom"

import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import { PageToolbar, PageToolbarContent } from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { getEquipmentItemsWithRentalUsages } from "@/features/equipment/api/equipment-rental-usages-api"
import { MoveEquipmentUsageToStockDialog } from "@/features/equipment/move-equipment-usage-to-stock-dialog"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { RENTAL_ITEM_CONTENTS_TRANSFER_STATUSES } from "@/features/rental-items/rental-item-contents-transfer-support"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useWarehouse } from "@/hooks/use-warehouse"
import { cn } from "@/lib/utils"
import type {
  EquipmentItemDto,
  EquipmentRentalUsageDto,
} from "@/types/equipment"

const EMPTY_EQUIPMENT_ITEMS: EquipmentItemDto[] = []
const quantityFormatter = new Intl.NumberFormat("ru-RU")
const EQUIPMENT_MOVEMENT_ROLES = new Set([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
])

function QuantityCell({
  value,
  tone = "default",
}: {
  value: number
  tone?: "default" | "stock" | "rent" | "written-off" | "lost"
}) {
  return (
    <span
      className={cn(
        "font-semibold tabular-nums",
        tone === "stock" && "text-primary",
        tone === "rent" && "text-foreground",
        tone === "written-off" && "text-muted-foreground",
        tone === "lost" && "text-destructive"
      )}
    >
      {quantityFormatter.format(value)}
    </span>
  )
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

function UsageRows({
  item,
  canMoveToStock,
  onOpenRentalItem,
  onMoveToStock,
}: {
  item: EquipmentItemDto
  canMoveToStock: boolean
  onOpenRentalItem: (rentalItemId: string) => void
  onMoveToStock: (usage: EquipmentRentalUsageDto) => void
}) {
  if (item.usages.length === 0) {
    return (
      <div className="rounded-md border bg-card p-3 text-sm text-muted-foreground">
        В бытовках этого оборудования нет.
      </div>
    )
  }

  return (
    <div
      data-slot="equipment-usage-grid"
      className="overflow-hidden rounded-md border bg-card"
    >
      <div
        data-slot="equipment-usage-header"
        className={cn(
          "hidden gap-4 border-b bg-muted px-3 py-2 text-left text-xs font-medium text-muted-foreground lg:grid",
          canMoveToStock ? "lg:grid-cols-5" : "lg:grid-cols-4"
        )}
      >
        <div>Номер бытовки</div>
        <div>Тип</div>
        <div>Статус</div>
        <div>Количество</div>
        {canMoveToStock ? <div>Действие</div> : null}
      </div>

      {item.usages.map((usage) => {
        const canMoveUsageToStock =
          canMoveToStock &&
          usage.availableQuantity > 0 &&
          RENTAL_ITEM_CONTENTS_TRANSFER_STATUSES.includes(
            usage.rentalItemStatus
          )

        return (
          <div
            key={usage.id}
            data-slot="equipment-usage-row"
            className={cn(
              "grid min-w-0 gap-4 border-b p-3 text-left text-sm last:border-b-0 lg:items-center",
              canMoveToStock ? "lg:grid-cols-5" : "lg:grid-cols-4"
            )}
          >
            <div className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground lg:hidden">
                Номер бытовки
              </div>
              <Button
                variant="link"
                className="h-auto min-w-0 justify-start p-0 font-medium"
                aria-label={`Открыть карточку бытовки ${usage.rentalItemNumber}`}
                onClick={() => onOpenRentalItem(usage.rentalItemId)}
              >
                <span className="truncate">{usage.rentalItemNumber}</span>
              </Button>
            </div>

            <div className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground lg:hidden">
                Тип
              </div>
              <div className="truncate">{usage.rentalItemType}</div>
            </div>

            <div data-slot="equipment-usage-status" className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground lg:hidden">
                Статус
              </div>
              <RentalItemStatusBadge status={usage.rentalItemStatus} />
            </div>

            <div className="min-w-0">
              <div className="text-[0.625rem] font-medium text-muted-foreground lg:hidden">
                Количество
              </div>
              <QuantityCell value={usage.quantity} />
            </div>

            {canMoveToStock ? (
              <div className="min-w-0">
                <div className="text-[0.625rem] font-medium text-muted-foreground lg:hidden">
                  Действие
                </div>
                {canMoveUsageToStock ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => onMoveToStock(usage)}
                  >
                    <HugeiconsIcon
                      icon={WarehouseIcon}
                      data-icon="inline-start"
                    />
                    Переместить на склад
                  </Button>
                ) : (
                  <span className="text-muted-foreground">—</span>
                )}
              </div>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

function EquipmentDesktopGrid({
  items,
  expandedItemId,
  canMoveToStock,
  onToggleExpanded,
  onOpenRentalItem,
  onMoveToStock,
}: {
  items: EquipmentItemDto[]
  expandedItemId: string | null
  canMoveToStock: boolean
  onToggleExpanded: (item: EquipmentItemDto) => void
  onOpenRentalItem: (rentalItemId: string) => void
  onMoveToStock: (
    item: EquipmentItemDto,
    usage: EquipmentRentalUsageDto
  ) => void
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
              className="flex min-w-0 items-center gap-2 rounded-sm text-left outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
              aria-label={`${expanded ? "Свернуть" : "Развернуть"} оборудование ${item.name}`}
              aria-expanded={expanded}
              onClick={() => onToggleExpanded(item)}
            >
              <HugeiconsIcon
                icon={expanded ? ArrowDown01Icon : ArrowRight01Icon}
                className="size-4 shrink-0 text-muted-foreground"
              />
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
          <UsageRows
            item={item}
            canMoveToStock={canMoveToStock}
            onOpenRentalItem={onOpenRentalItem}
            onMoveToStock={(usage) => onMoveToStock(item, usage)}
          />
        )}
      />
    </div>
  )
}

function EquipmentMobileList({
  items,
  expandedItemId,
  canMoveToStock,
  onToggleExpanded,
  onOpenRentalItem,
  onMoveToStock,
}: {
  items: EquipmentItemDto[]
  expandedItemId: string | null
  canMoveToStock: boolean
  onToggleExpanded: (item: EquipmentItemDto) => void
  onOpenRentalItem: (rentalItemId: string) => void
  onMoveToStock: (
    item: EquipmentItemDto,
    usage: EquipmentRentalUsageDto
  ) => void
}) {
  return (
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
                className="flex w-full min-w-0 flex-col gap-3 p-3 text-left text-sm outline-none hover:bg-muted/40 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:ring-inset"
                aria-label={`${expanded ? "Свернуть" : "Развернуть"} оборудование ${item.name}`}
                aria-expanded={expanded}
                onClick={() => onToggleExpanded(item)}
              >
                <div className="flex min-w-0 items-center gap-2">
                  <HugeiconsIcon
                    icon={expanded ? ArrowDown01Icon : ArrowRight01Icon}
                    className="size-4 shrink-0 text-muted-foreground"
                  />
                  <span className="truncate font-medium">{item.name}</span>
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
                  <MobileQuantityStat
                    label="В бытовках"
                    value={item.cabinStockQuantity}
                    tone="stock"
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
              </button>

              {expanded ? (
                <div className="border-t bg-muted/20 p-3">
                  <UsageRows
                    item={item}
                    canMoveToStock={canMoveToStock}
                    onOpenRentalItem={onOpenRentalItem}
                    onMoveToStock={(usage) => onMoveToStock(item, usage)}
                  />
                </div>
              ) : null}
            </div>
          )
        })
      )}
    </div>
  )
}

export function EquipmentPage() {
  const navigate = useNavigate()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const [search, setSearch] = useState("")
  const [expandedItemId, setExpandedItemId] = useState<string | null>(null)
  const [moveTarget, setMoveTarget] = useState<{
    equipmentId: string
    usageId: string
  } | null>(null)
  const warehouseId = selectedWarehouse?.id

  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", "with-rental-usages", warehouseId, search],
    queryFn: () =>
      getEquipmentItemsWithRentalUsages(accessToken, {
        warehouseId: warehouseId!,
        search,
      }),
    enabled: Boolean(warehouseId && accessToken),
  })
  const items = equipmentQuery.data ?? EMPTY_EQUIPMENT_ITEMS
  const moveEquipment = moveTarget
    ? (items.find((item) => item.id === moveTarget.equipmentId) ?? null)
    : null
  const moveUsage = moveEquipment?.usages.find(
    (usage) => usage.id === moveTarget?.usageId
  )
  const canMoveToStock = Boolean(
    currentUser &&
    warehouseId &&
    EQUIPMENT_MOVEMENT_ROLES.has(currentUser.globalRole) &&
    hasWarehouseAccess(currentUser, warehouseId, "MANAGE")
  )

  if (!selectedWarehouse) {
    return (
      <div className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Склад не выбран.
      </div>
    )
  }

  function toggleExpanded(item: EquipmentItemDto) {
    setExpandedItemId((current) => (current === item.id ? null : item.id))
  }

  function openRentalItem(rentalItemId: string) {
    navigate(`/warehouse/${rentalItemId}`)
  }

  function openMoveToStock(
    item: EquipmentItemDto,
    usage: EquipmentRentalUsageDto
  ) {
    setMoveTarget({ equipmentId: item.id, usageId: usage.id })
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Поиск по оборудованию..."
          />
        </PageToolbarContent>
      </PageToolbar>

      {!accessToken ? (
        <div className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
          Для загрузки оборудования требуется авторизация.
        </div>
      ) : equipmentQuery.isLoading ? (
        <div className="flex min-h-0 flex-1 items-center rounded-lg border bg-card p-4 text-sm text-muted-foreground">
          Загрузка оборудования...
        </div>
      ) : equipmentQuery.isError ? (
        <div
          role="alert"
          className="rounded-lg border border-destructive/30 bg-card p-4 text-sm text-destructive"
        >
          {equipmentQuery.error instanceof Error
            ? equipmentQuery.error.message
            : "Не удалось загрузить оборудование."}
        </div>
      ) : (
        <>
          <EquipmentMobileList
            items={items}
            expandedItemId={expandedItemId}
            canMoveToStock={canMoveToStock}
            onToggleExpanded={toggleExpanded}
            onOpenRentalItem={openRentalItem}
            onMoveToStock={openMoveToStock}
          />
          <EquipmentDesktopGrid
            items={items}
            expandedItemId={expandedItemId}
            canMoveToStock={canMoveToStock}
            onToggleExpanded={toggleExpanded}
            onOpenRentalItem={openRentalItem}
            onMoveToStock={openMoveToStock}
          />
        </>
      )}
      {moveEquipment && moveUsage && accessToken ? (
        <MoveEquipmentUsageToStockDialog
          key={`${moveUsage.id}:${moveUsage.balanceVersion}:${moveEquipment.balances.find((balance) => balance.rentalItemId === null && balance.locationKind === "STOCK")?.version ?? 0}`}
          accessToken={accessToken}
          equipment={moveEquipment}
          usage={moveUsage}
          onClose={() => setMoveTarget(null)}
        />
      ) : null}
    </div>
  )
}
