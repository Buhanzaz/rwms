import type { RepairEstimateCatalogSnapshotDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"

/** Service-specific read port. HTTP and mock transports implement this port. */
export interface RepairEstimateCatalogClient {
  getOperationalCatalog(): Promise<RepairEstimateCatalogSnapshotDto>
}
