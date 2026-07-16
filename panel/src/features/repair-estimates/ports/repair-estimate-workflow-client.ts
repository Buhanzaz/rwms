import type { RepairEstimateWorkflowRequestRefDto } from "@/features/repair-estimates/model/repair-estimate"

export type RepairEstimateWorkflowRequestQuery = {
  warehouseId: string
  requestId: string
}

/**
 * Read-only workflow boundary for the frontend.
 * Dispatch is owned by the estimate service outbox and is never a post-commit UI call.
 */
export interface RepairEstimateWorkflowClient {
  getRequestStatus(
    query: RepairEstimateWorkflowRequestQuery
  ): Promise<RepairEstimateWorkflowRequestRefDto | null>
}
