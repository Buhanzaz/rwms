import {
  getAssetRentalItem,
  listAssetRentalItems,
} from "@/features/rental-items/api/asset-rental-items-api"
import { listReturns } from "@/features/logistics/returns/api"
import { currentMaintenanceAccessToken } from "@/features/repair-estimates/api/maintenance-auth"
import { toLocalCalendarDateValue } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import type { ReturnDocument } from "@/features/logistics/returns/model"

const ESTIMATE_ELIGIBLE_STATUS = "AFTER_RENT"

const ESTIMATE_EXCLUDED_STATUSES = (
  Object.keys(RENTAL_ITEM_STATUS_LABEL) as RentalItemStatus[]
).filter((status) => status !== ESTIMATE_ELIGIBLE_STATUS)

function nonBlank(value: string | null | undefined) {
  return value?.trim() || null
}

function returnMetadata(
  returnDocuments: ReturnDocument[],
  rentalItemId: string
) {
  const document = returnDocuments
    .filter((candidate) =>
      candidate.lines.some((line) => line.assetId === rentalItemId)
    )
    .reduce<ReturnDocument | null>(
      (latest, candidate) =>
        latest === null ||
        Date.parse(candidate.createdAt) > Date.parse(latest.createdAt)
          ? candidate
          : latest,
      null
    )
  const line = document?.lines.find(
    (candidate) => candidate.assetId === rentalItemId
  )

  return {
    counterparty:
      nonBlank(document?.partySnapshot) ?? nonBlank(line?.tenantSnapshot),
    arrivalDate: document
      ? toLocalCalendarDateValue(new Date(document.createdAt))
      : null,
  }
}

function toOption(item: RentalItemDto, returnDocuments: ReturnDocument[] = []) {
  const metadata = returnMetadata(returnDocuments, item.id)
  return {
    id: item.id,
    warehouseId: item.warehouseId,
    number: item.number,
    counterparty: metadata.counterparty ?? nonBlank(item.tenant),
    arrivalDate: metadata.arrivalDate,
  }
}

async function search({
  warehouseId,
  search: searchValue,
  page: pageNumber,
  size,
}: Parameters<EstimateRentalItemsClient["search"]>[0]) {
  const accessToken = await currentMaintenanceAccessToken()
  const [page, returnDocuments] = await Promise.all([
    listAssetRentalItems({
      accessToken,
      warehouseId,
      search: searchValue,
      page: pageNumber,
      size,
      excludeStatuses: ESTIMATE_EXCLUDED_STATUSES,
    }),
    listReturns(accessToken, warehouseId),
  ])

  return {
    items: page.content
      .map((item) => toOption(item, returnDocuments))
      .sort((left, right) => left.number.localeCompare(right.number, "ru")),
    page: page.page,
    size: page.size,
    totalElements: page.totalElements,
    totalPages: page.totalPages,
  }
}

export const panelEstimateRentalItemsClient: EstimateRentalItemsClient = {
  search,
  async resolveById(warehouseId, rentalItemId) {
    const accessToken = await currentMaintenanceAccessToken()
    const item = await getAssetRentalItem(accessToken, rentalItemId)
    if (
      item.warehouseId !== warehouseId ||
      item.status !== ESTIMATE_ELIGIBLE_STATUS
    ) {
      return null
    }
    const returnDocuments = await listReturns(accessToken, warehouseId)
    return toOption(item, returnDocuments)
  },
}
