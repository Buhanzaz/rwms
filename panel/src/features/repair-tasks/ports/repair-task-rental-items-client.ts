import type { EstimateRentalItemOptionDto } from "@/features/repair-estimates/model/repair-estimate"

export interface RepairTaskRentalItemsClient {
  resolveById(
    warehouseId: string,
    rentalItemId: string
  ): Promise<EstimateRentalItemOptionDto | null>
}
