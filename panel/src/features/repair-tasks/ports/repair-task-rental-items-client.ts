import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"
import type { EstimateRentalItemOptionDto } from "@/features/repair-estimates/model/repair-estimate"

export interface RepairTaskRentalItemsClient {
  resolveById(
    warehouseId: string,
    rentalItemId: string
  ): Promise<EstimateRentalItemOptionDto | null>
  updateStatus(params: {
    warehouseId: string
    rentalItemId: string
    status: RentalItemStatus
    allowWaitingEstimateConfirmation?: boolean
  }): Promise<void>
}
