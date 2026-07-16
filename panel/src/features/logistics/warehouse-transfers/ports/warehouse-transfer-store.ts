import type {
  WarehouseAccountingCorrection,
  WarehouseTransferDocument,
  WarehouseTransferEvent,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

export interface WarehouseTransferStore {
  list(): WarehouseTransferDocument[]
  findById(documentId: string): WarehouseTransferDocument | null
  findByRequestId(requestId: string): WarehouseTransferDocument | null
  findActiveByRentalItemId(
    rentalItemId: string
  ): WarehouseTransferDocument | null
  save(
    document: WarehouseTransferDocument,
    expectedVersion: number | null
  ): WarehouseTransferDocument
  appendEvent(event: WarehouseTransferEvent): void
  listEventsForRentalItem(rentalItemId: string): WarehouseTransferEvent[]
  listCorrections(): WarehouseAccountingCorrection[]
  findCorrection(correctionId: string): WarehouseAccountingCorrection | null
  saveCorrection(
    correction: WarehouseAccountingCorrection,
    expectedVersion: number | null
  ): WarehouseAccountingCorrection
}
