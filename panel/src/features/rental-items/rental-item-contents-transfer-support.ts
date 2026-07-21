import type { QueryClient } from "@tanstack/react-query"

import { transferEquipment } from "@/api/equipment-api"
import { RENTAL_ITEM_DOSSIER_QUERY_KEY } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type {
  RentalItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"
import type {
  EquipmentBalanceDto,
  EquipmentBalanceLocationKind,
  EquipmentItemDto,
  TransferEquipmentInput,
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

export type RentalItemContentsTransferLine = {
  lineKey: string
  input: TransferEquipmentInput
}

export class RentalItemContentsBatchTransferError extends Error {
  readonly completedCount: number
  readonly totalCount: number
  readonly failure: unknown

  constructor(params: {
    completedCount: number
    totalCount: number
    failure: unknown
  }) {
    super(
      params.failure instanceof Error
        ? params.failure.message
        : "Не удалось изменить наполнение бытовки."
    )
    this.name = "RentalItemContentsBatchTransferError"
    this.completedCount = params.completedCount
    this.totalCount = params.totalCount
    this.failure = params.failure
  }
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

export function createRentalItemContentsTransferInput(params: {
  row: RentalItemContentsTransferRow
  targetWarehouseId: string
  targetRentalItemId: string | null
  targetLocationKind: EquipmentBalanceLocationKind
  quantity: number
}): TransferEquipmentInput {
  const targetBalances = params.row.equipmentBalances.filter(
    (balance) =>
      balance.warehouseId === params.targetWarehouseId &&
      balance.rentalItemId === params.targetRentalItemId &&
      balance.locationKind === params.targetLocationKind
  )
  if (targetBalances.length > 1) {
    throw new Error("Сервис имущества вернул дублирующий целевой остаток.")
  }

  return {
    equipmentId: params.row.equipmentId,
    sourceWarehouseId: params.row.sourceBalance.warehouseId,
    sourceRentalItemId: params.row.sourceBalance.rentalItemId,
    sourceLocationKind: params.row.sourceBalance.locationKind,
    sourceExpectedVersion: params.row.sourceBalance.version,
    targetWarehouseId: params.targetWarehouseId,
    targetRentalItemId: params.targetRentalItemId,
    targetLocationKind: params.targetLocationKind,
    targetExpectedVersion: targetBalances[0]?.version ?? 0,
    quantity: params.quantity,
  }
}

export function rentalItemContentsTransferLineKey(
  input: TransferEquipmentInput
) {
  return [
    input.equipmentId,
    input.sourceWarehouseId,
    input.sourceRentalItemId ?? "stock",
    input.sourceLocationKind,
    input.targetWarehouseId,
    input.targetRentalItemId ?? "stock",
    input.targetLocationKind,
  ].join(":")
}

function createTransferIdempotencyKey() {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID()
  }

  throw new Error("Браузер не поддерживает генерацию ключа идемпотентности.")
}

export async function executeRentalItemContentsTransferBatch(params: {
  accessToken: string | null
  lines: RentalItemContentsTransferLine[]
  idempotencyKeys: Map<string, string>
  completedLineKeys: Set<string>
  onLineCompleted?: (line: RentalItemContentsTransferLine) => void
}) {
  const pendingLines = params.lines.filter(
    (line) => !params.completedLineKeys.has(line.lineKey)
  )
  let completedInBatch = 0

  for (const line of pendingLines) {
    const signature = JSON.stringify(line.input)
    let idempotencyKey = params.idempotencyKeys.get(signature)
    if (!idempotencyKey) {
      idempotencyKey = createTransferIdempotencyKey()
      params.idempotencyKeys.set(signature, idempotencyKey)
    }

    try {
      await transferEquipment(params.accessToken, idempotencyKey, line.input)
    } catch (failure) {
      throw new RentalItemContentsBatchTransferError({
        completedCount: completedInBatch,
        totalCount: pendingLines.length,
        failure,
      })
    }

    completedInBatch += 1
    params.completedLineKeys.add(line.lineKey)
    params.onLineCompleted?.(line)
  }

  return { completedCount: completedInBatch, totalCount: pendingLines.length }
}

export function formatRentalItemContentsTransferError(error: unknown) {
  const batchError =
    error instanceof RentalItemContentsBatchTransferError ? error : null
  const failure = batchError?.failure ?? error

  if (failure instanceof ApiError && failure.status === 409) {
    const partialPrefix =
      batchError && batchError.completedCount > 0
        ? `Перемещено ${batchError.completedCount} из ${batchError.totalCount}. `
        : ""
    return `${partialPrefix}Остатки изменились. Данные обновлены — проверьте количество и повторите.`
  }

  if (batchError && batchError.completedCount > 0) {
    return `Перемещено ${batchError.completedCount} из ${batchError.totalCount}. Данные обновлены; повторите оставшиеся строки. ${batchError.message}`
  }

  return failure instanceof Error
    ? failure.message
    : "Не удалось изменить наполнение бытовки."
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
