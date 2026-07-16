import { useMemo } from "react"
import { useQuery } from "@tanstack/react-query"

import { listAssetEquipment } from "@/api/asset-api"
import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { useAuth } from "@/features/auth/use-auth"
import { ASSET_EQUIPMENT_QUERY_KEY } from "@/features/assets/asset-query-keys"
import { useWarehouse } from "@/hooks/use-warehouse"

function equipmentQueryKey(warehouseId: string) {
  return [...ASSET_EQUIPMENT_QUERY_KEY, warehouseId] as const
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось загрузить оборудование."
}

function categoryLabel(category: string) {
  return category === "FURNITURE"
    ? "Мебель"
    : category === "ELECTRICAL"
      ? "Электрика"
      : "Другое"
}

export function AssetEquipmentPage() {
  const { accessToken } = useAuth()
  const { selectedWarehouse, selectedWarehouseId } = useWarehouse()
  const equipmentQuery = useQuery({
    queryKey: equipmentQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listAssetEquipment(accessToken, selectedWarehouseId!),
    enabled: accessToken !== null && selectedWarehouseId !== null,
  })
  const equipment = useMemo(
    () => equipmentQuery.data ?? [],
    [equipmentQuery.data]
  )

  if (selectedWarehouse === null) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardDescription>Выберите доступный склад.</CardDescription>
        </CardHeader>
      </Card>
    )
  }
  if (equipmentQuery.isLoading)
    return (
      <p className="text-sm text-muted-foreground">Загрузка оборудования…</p>
    )
  if (equipmentQuery.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {errorMessage(equipmentQuery.error)}
      </p>
    )

  return (
    <div className="min-h-0 overflow-y-auto pb-6">
      <div className="flex flex-col gap-4">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Дополнительное оборудование</CardTitle>
            <CardDescription>
              {selectedWarehouse.code}: total = stock + non-rented cabin +
              rented cabin + written-off + lost. Активный hold уменьшает только
              available stock.
            </CardDescription>
          </CardHeader>
        </Card>
        {equipment.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>Оборудования пока нет</CardTitle>
              <CardDescription>
                Каталог создаётся глобальными администраторами. Остатки
                появляются только из подтверждённого movement ledger.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {equipment.map(({ equipment: item, totals }) => (
              <Card key={item.id}>
                <CardHeader>
                  <CardTitle>{item.name}</CardTitle>
                  <CardDescription>{item.code}</CardDescription>
                  <div className="flex flex-wrap gap-1">
                    <Badge variant="outline">
                      {categoryLabel(item.category)}
                    </Badge>
                    {item.active ? (
                      <Badge variant="secondary">Активно</Badge>
                    ) : (
                      <Badge variant="outline">Неактивно</Badge>
                    )}
                  </div>
                </CardHeader>
                <CardContent className="grid grid-cols-2 gap-3 text-sm">
                  <p>
                    <span className="block text-muted-foreground">Всего</span>
                    <strong>{totals.totalQuantity}</strong>
                  </p>
                  <p>
                    <span className="block text-muted-foreground">
                      Доступно
                    </span>
                    <strong>{totals.availableStock}</strong>
                  </p>
                  <p>
                    <span className="block text-muted-foreground">Склад</span>
                    {totals.stockQuantity}
                  </p>
                  <p>
                    <span className="block text-muted-foreground">Hold</span>
                    {totals.activeHeldQuantity}
                  </p>
                  <p>
                    <span className="block text-muted-foreground">Бытовки</span>
                    {totals.nonRentedCabinQuantity + totals.rentedCabinQuantity}
                  </p>
                  <p>
                    <span className="block text-muted-foreground">
                      Списано / утрачено
                    </span>
                    {totals.writtenOffQuantity} / {totals.lostQuantity}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
