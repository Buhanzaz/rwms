import type {
  EstimateRentalItemOptionDto,
  EstimateRentalItemSearchPageDto,
  EstimateRentalItemSearchQuery,
} from "@/features/repair-estimates/model/repair-estimate"

export interface EstimateRentalItemsClient {
  search(
    query: EstimateRentalItemSearchQuery
  ): Promise<EstimateRentalItemSearchPageDto>
  resolveById(
    warehouseId: string,
    rentalItemId: string
  ): Promise<EstimateRentalItemOptionDto | null>
  resolveLinkedReturnEstimate(
    warehouseId: string,
    rentalItemId: string,
    estimateId: string
  ): Promise<EstimateRentalItemOptionDto | null>
}
