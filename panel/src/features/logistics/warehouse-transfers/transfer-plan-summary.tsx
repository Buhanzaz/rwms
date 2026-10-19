import { useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
import { Badge } from "@/components/ui/badge"
import { FieldDescription } from "@/components/ui/field"
import type { TransferPlan } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import {
  getRentalItemCreationOptions,
  rentalItemCreationOptionsQueryKey,
} from "@/features/rental-items/api/asset-rental-items-api"

/** Props for the compact catalog-resolved transfer-plan facts. */
export type TransferPlanSummaryProps = {
  accessToken: string
  sourceWarehouseId: string
  plan: TransferPlan
}

function formatDateTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(new Date(value))
    : "не указано"
}

/** Renders only server-proven plan facts and resolves names from live catalogs. */
export function TransferPlanSummary({
  accessToken,
  sourceWarehouseId,
  plan,
}: TransferPlanSummaryProps) {
  const optionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(sourceWarehouseId),
    queryFn: () => getRentalItemCreationOptions(accessToken, sourceWarehouseId),
  })
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "transfer-plan", sourceWarehouseId],
    queryFn: () =>
      getEquipmentItems(accessToken, { warehouseId: sourceWarehouseId }),
  })
  const options = optionsQuery.data
  const equipment = equipmentQuery.data ?? []
  const name = (
    items: readonly { id: string; name: string }[],
    id: string | null
  ) => items.find((item) => item.id === id)?.name ?? "характеристика недоступна"

  return (
    <div
      className="grid gap-3 text-sm"
      aria-label="План межскладского перемещения"
    >
      <div className="flex flex-wrap gap-2">
        <Badge variant="secondary">Межскладское перемещение</Badge>
        <Badge variant="outline">
          {plan.state === "CONFIRMED" ? "Подтверждено" : "Черновик"}
        </Badge>
        <Badge variant="outline">
          {plan.reservationReadiness === "RESERVED"
            ? "Имущество зарезервировано"
            : "Без резерва"}
        </Badge>
      </div>
      <p>
        Отправление: {formatDateTime(plan.plannedDepartureAt)} · Прибытие:{" "}
        {formatDateTime(plan.plannedArrivalAt)}
      </p>
      <p>
        Водитель: {plan.tripDriverId ? "назначен" : "не назначен"} · Автомобиль:{" "}
        {plan.tripVehicleId ? "назначен" : "не назначен"}
      </p>
      {plan.cabinGroups.length > 0 ? (
        <ul className="grid gap-1">
          {plan.cabinGroups.map((group) => (
            <li key={group.groupId}>
              {group.quantity} ×{" "}
              {name(options?.rentalTypes ?? [], group.rentalTypeId)}
              {group.finishingId
                ? ` · ${name(options?.finishings ?? [], group.finishingId)}`
                : ""}
              {group.linoleum === true ? " · линолеум" : ""} · выбрано{" "}
              {group.allocatedCabins.length}/{group.quantity}
            </li>
          ))}
        </ul>
      ) : (
        <FieldDescription>Бытовки не запланированы.</FieldDescription>
      )}
      {plan.furnitureTotals.length > 0 ? (
        <p>
          Мебель:{" "}
          {plan.furnitureTotals
            .map(
              (item) =>
                `${name(equipment, item.furnitureCatalogItemId)} — ${item.totalQuantity}`
            )
            .join(", ")}
        </p>
      ) : null}
      {plan.driverReposition.mode !== "NONE" ? (
        <p>
          После прибытия водитель переводится на склад назначения{" "}
          {plan.driverReposition.mode === "TEMPORARY"
            ? `до ${formatDateTime(plan.driverReposition.until)}.`
            : "постоянно."}
        </p>
      ) : null}
      {plan.readinessDetail ? (
        <p className="text-muted-foreground">{plan.readinessDetail}</p>
      ) : null}
    </div>
  )
}
