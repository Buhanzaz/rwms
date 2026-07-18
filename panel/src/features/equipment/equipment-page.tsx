import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
import { Input } from "@/components/ui/input"
import {
  OperationsListGrid,
  type OperationsListGridColumn,
} from "@/components/operations-list-grid"
import { PageToolbar, PageToolbarContent } from "@/components/page-toolbar"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"
import type { EquipmentItemDto } from "@/types/equipment"

const EMPTY_EQUIPMENT_ITEMS: EquipmentItemDto[] = []

function QuantityCell({
  value,
  tone = "default",
}: {
  value: number
  tone?: "default" | "stock" | "written-off" | "lost"
}) {
  const toneClass =
    tone === "stock"
      ? "text-primary"
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
  tone?: "default" | "stock" | "written-off" | "lost"
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

function EquipmentDesktopGrid({ items }: { items: EquipmentItemDto[] }) {
  const columns = useMemo<OperationsListGridColumn<EquipmentItemDto>[]>(
    () => [
      {
        id: "name",
        label: "Наименование",
        getSortValue: (item) => item.name,
        render: (item) => <span className="font-medium">{item.name}</span>,
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
        render: (item) => <QuantityCell value={item.rentedQuantity} />,
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
    []
  )

  return (
    <div className="hidden h-full min-h-0 overflow-auto md:block">
      <OperationsListGrid columns={columns} items={items} />
    </div>
  )
}

export function EquipmentPage() {
  const { accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const [search, setSearch] = useState("")
  const warehouseId = selectedWarehouse?.id

  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, search],
    queryFn: () =>
      getEquipmentItems(accessToken, {
        warehouseId: warehouseId!,
        search,
      }),
    enabled: Boolean(warehouseId && accessToken),
  })

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
            placeholder="Поиск по оборудованию..."
          />
        </PageToolbarContent>
      </PageToolbar>

      <p className="text-sm text-muted-foreground">
        Показаны сервисные остатки asset-service. Перемещения недоступны, пока
        панель не подключит версионную операцию для исходного и целевого
        баланса; приёмка относится к отдельному рабочему процессу.
      </p>

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
          <div className="min-h-0 flex-1 overflow-y-auto rounded-lg border bg-card md:hidden">
            {items.length === 0 ? (
              <div className="p-4 text-sm text-muted-foreground">
                Оборудование не найдено.
              </div>
            ) : (
              items.map((item) => (
                <div key={item.id} className="border-b p-3 last:border-b-0">
                  <div className="mb-3 truncate text-sm font-medium">
                    {item.name}
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
                </div>
              ))
            )}
          </div>

          <EquipmentDesktopGrid items={items} />
        </>
      )}
    </div>
  )
}
