import type {
  AmendCompletedRepairEstimateCommand,
  CompleteRepairEstimateCommand,
  RepairEstimateDraftCommand,
  RepairEstimateDto,
  RepairEstimateId,
  RepairEstimateListQuery,
  RepairEstimateSummaryDto,
} from "@/features/repair-estimates/model/repair-estimate"

export interface RepairEstimatesClient {
  list(query: RepairEstimateListQuery): Promise<RepairEstimateSummaryDto[]>
  getById(
    id: RepairEstimateId,
    warehouseId: string
  ): Promise<RepairEstimateDto | null>
  saveDraft(command: RepairEstimateDraftCommand): Promise<RepairEstimateDto>
  complete(command: CompleteRepairEstimateCommand): Promise<RepairEstimateDto>
  amendCompleted(
    command: AmendCompletedRepairEstimateCommand
  ): Promise<RepairEstimateDto>
}
