import { getEquipmentItems } from "@/api/equipment-api"
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type {
  EquipmentBalanceDto,
  EquipmentItemDto,
  EquipmentItemsQueryParams,
  EquipmentRentalUsageDto,
} from "@/types/equipment"

const RENTAL_ITEMS_PAGE_SIZE = 200

type CabinEquipmentBalance = EquipmentBalanceDto & {
  rentalItemId: string
  locationKind: "CABIN_NON_RENTED" | "CABIN_RENTED"
}

function isCabinEquipmentBalance(
  balance: EquipmentBalanceDto
): balance is CabinEquipmentBalance {
  return (
    balance.rentalItemId !== null &&
    (balance.locationKind === "CABIN_NON_RENTED" ||
      balance.locationKind === "CABIN_RENTED") &&
    balance.quantity > 0
  )
}

async function listAllWarehouseRentalItems(
  accessToken: string | null,
  warehouseId: string
) {
  const firstPage = await listAssetRentalItems({
    accessToken,
    warehouseId,
    page: 0,
    size: RENTAL_ITEMS_PAGE_SIZE,
  })
  const remainingPages = await Promise.all(
    Array.from({ length: Math.max(0, firstPage.totalPages - 1) }, (_, index) =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: index + 1,
        size: RENTAL_ITEMS_PAGE_SIZE,
      })
    )
  )

  return [firstPage, ...remainingPages].flatMap((page) => page.content)
}

function indexRentalItems(items: RentalItemDto[], warehouseId: string) {
  const rentalItemsById = new Map<string, RentalItemDto>()

  for (const item of items) {
    if (item.warehouseId !== warehouseId) {
      throw new Error("Сервис имущества вернул бытовку другого склада.")
    }
    if (rentalItemsById.has(item.id)) {
      throw new Error("Сервис имущества вернул бытовку повторно.")
    }
    rentalItemsById.set(item.id, item)
  }

  return rentalItemsById
}

function toRentalUsage(
  balance: CabinEquipmentBalance,
  rentalItemsById: Map<string, RentalItemDto>
): EquipmentRentalUsageDto {
  const rentalItem = rentalItemsById.get(balance.rentalItemId)
  if (!rentalItem) {
    throw new Error(
      "Сервис имущества вернул остаток для неизвестной бытовки. Обновите страницу."
    )
  }
  if (rentalItem.warehouseId !== balance.warehouseId) {
    throw new Error("Сервис имущества вернул остаток бытовки другого склада.")
  }

  return {
    id: balance.id,
    balanceVersion: balance.version,
    rentalItemId: rentalItem.id,
    rentalItemNumber: rentalItem.number,
    rentalItemType: rentalItem.type,
    rentalItemStatus: rentalItem.status,
    warehouseId: balance.warehouseId,
    locationKind: balance.locationKind,
    quantity: balance.quantity,
    availableQuantity: balance.availableStock,
  }
}

const rentalItemNumberCollator = new Intl.Collator("ru", {
  numeric: true,
  sensitivity: "base",
})

/**
 * Builds the equipment page read model exclusively from two public,
 * PostgreSQL-backed asset-service projections. UUID joins preserve the exact
 * cabin numbers and cannot merge cabins that happen to share display fields.
 */
export async function getEquipmentItemsWithRentalUsages(
  accessToken: string | null,
  params: EquipmentItemsQueryParams
): Promise<EquipmentItemDto[]> {
  const [equipmentItems, rentalItems] = await Promise.all([
    getEquipmentItems(accessToken, params),
    listAllWarehouseRentalItems(accessToken, params.warehouseId),
  ])
  const rentalItemsById = indexRentalItems(rentalItems, params.warehouseId)

  return equipmentItems.map((equipment) => ({
    ...equipment,
    usages: equipment.balances
      .filter(isCabinEquipmentBalance)
      .map((balance) => toRentalUsage(balance, rentalItemsById))
      .sort((left, right) =>
        rentalItemNumberCollator.compare(
          left.rentalItemNumber,
          right.rentalItemNumber
        )
      ),
  }))
}
