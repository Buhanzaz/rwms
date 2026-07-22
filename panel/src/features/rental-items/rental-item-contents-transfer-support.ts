import type { QueryClient } from "@tanstack/react-query"

import { RENTAL_ITEM_DOSSIER_QUERY_KEY } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type {
  RentalItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import type {
  EquipmentBalanceDto,
  EquipmentBalanceLocationKind,
  EquipmentItemDto,
} from "@/types/equipment"

export const RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY = [
  "rental-item-contents-equipment",
] as const
export const RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY = [
  "rental-item-contents-targets",
] as const

export const RENTAL_ITEM_CONTENTS_TRANSFER_STATUSES: RentalItemStatus[] = [
  "NEW",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "CAPITAL_REPAIR",
  "AFTER_RENT",
  "SALE",
  "USED_SALE",
  "RESERVED",
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
]

type CabinLocationKind = Extract<
  EquipmentBalanceLocationKind,
  "CABIN_NON_RENTED" | "CABIN_RENTED"
>

export type RentalItemContentsTransferRow<
  TLocationKind extends EquipmentBalanceLocationKind =
    EquipmentBalanceLocationKind,
> = {
  equipmentId: string
  code: string
  name: string
  availableQuantity: number
  sourceBalance: EquipmentBalanceDto & { locationKind: TLocationKind }
  equipmentBalances: EquipmentBalanceDto[]
}

const sourceSummaryQuantityFormatter = new Intl.NumberFormat("ru-RU")
const SOURCE_SUMMARY_VISIBLE_ROWS = 3
const SOURCE_SUMMARY_NAME_LENGTH = 36

function compactSourceSummaryName(name: string) {
  const normalized = name.trim().replace(/\s+/g, " ") || "Без названия"
  const characters = Array.from(normalized)
  if (characters.length <= SOURCE_SUMMARY_NAME_LENGTH) return normalized

  return `${characters
    .slice(0, SOURCE_SUMMARY_NAME_LENGTH - 1)
    .join("")
    .trimEnd()}…`
}

export function formatRentalItemContentsSourceSummary(
  rentalItemNumber: string,
  rows: Array<Pick<RentalItemContentsTransferRow, "name" | "availableQuantity">>
) {
  const number = rentalItemNumber.trim() || "Бытовка"
  if (rows.length === 0) return `${number} — без наполнения`

  const composition = rows
    .slice(0, SOURCE_SUMMARY_VISIBLE_ROWS)
    .map(
      (row) =>
        `${compactSourceSummaryName(row.name)}: ${sourceSummaryQuantityFormatter.format(row.availableQuantity)}`
    )
    .join(", ")
  const remainingCount = rows.length - SOURCE_SUMMARY_VISIBLE_ROWS

  return remainingCount > 0
    ? `${number} — ${composition}, ещё ${sourceSummaryQuantityFormatter.format(remainingCount)}`
    : `${number} — ${composition}`
}

export function canTransferRentalItemContents(item: RentalItemDto) {
  return RENTAL_ITEM_CONTENTS_TRANSFER_STATUSES.includes(item.status)
}

export function cabinLocationKind(
  item: Pick<RentalItemDto, "status">
): CabinLocationKind {
  return item.status === "RENTED" ? "CABIN_RENTED" : "CABIN_NON_RENTED"
}

function isCabinLocationKind(
  value: EquipmentBalanceLocationKind
): value is CabinLocationKind {
  return value === "CABIN_NON_RENTED" || value === "CABIN_RENTED"
}

export function warehouseStockTransferRows(
  equipmentItems: EquipmentItemDto[]
): RentalItemContentsTransferRow<"STOCK">[] {
  return equipmentItems
    .flatMap((equipment) => {
      const balance = equipment.balances.find(
        (
          candidate
        ): candidate is EquipmentBalanceDto & { locationKind: "STOCK" } =>
          candidate.rentalItemId === null && candidate.locationKind === "STOCK"
      )
      if (!balance || !equipment.active || balance.availableStock < 1) {
        return []
      }

      return [
        {
          equipmentId: equipment.id,
          code: equipment.code,
          name: equipment.name,
          availableQuantity: balance.availableStock,
          sourceBalance: balance,
          equipmentBalances: equipment.balances,
        },
      ]
    })
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export function rentalItemContentsTransferRows(
  equipmentItems: EquipmentItemDto[],
  rentalItemId: string
): RentalItemContentsTransferRow<CabinLocationKind>[] {
  return equipmentItems
    .flatMap((equipment) =>
      equipment.balances
        .filter(
          (
            balance
          ): balance is EquipmentBalanceDto & {
            locationKind: CabinLocationKind
          } =>
            balance.rentalItemId === rentalItemId &&
            isCabinLocationKind(balance.locationKind) &&
            balance.availableStock > 0
        )
        .map((balance) => ({
          equipmentId: equipment.id,
          code: equipment.code,
          name: equipment.name,
          availableQuantity: balance.availableStock,
          sourceBalance: balance,
          equipmentBalances: equipment.balances,
        }))
    )
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export function eligibleRentalItemContentsTargets(params: {
  items: RentalItemDto[]
  sourceRentalItemId: string
  requireContents?: boolean
  equipmentItems: EquipmentItemDto[]
}) {
  return params.items
    .filter(
      (item) =>
        item.id !== params.sourceRentalItemId &&
        canTransferRentalItemContents(item) &&
        (!params.requireContents ||
          rentalItemContentsTransferRows(params.equipmentItems, item.id)
            .length > 0)
    )
    .sort((left, right) =>
      left.number.localeCompare(right.number, "ru", { numeric: true })
    )
}

export async function invalidateRentalItemContentsQueries(
  queryClient: QueryClient
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["rental-items"] }),
    queryClient.invalidateQueries({ queryKey: ["asset-rental-item"] }),
    queryClient.invalidateQueries({ queryKey: ["rental-item"] }),
    queryClient.invalidateQueries({ queryKey: ["equipment-items"] }),
    queryClient.invalidateQueries({
      queryKey: RENTAL_ITEM_CONTENTS_EQUIPMENT_QUERY_KEY,
    }),
    queryClient.invalidateQueries({
      queryKey: RENTAL_ITEM_CONTENTS_TARGETS_QUERY_KEY,
    }),
    queryClient.invalidateQueries({ queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY }),
  ])
}
