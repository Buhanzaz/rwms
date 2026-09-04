import type { RepairEstimateCatalogSnapshotDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"

/** Service-specific read port for the active maintenance catalog. */
export interface RepairEstimateCatalogClient {
  getOperationalCatalog(): Promise<RepairEstimateCatalogSnapshotDto>
}
