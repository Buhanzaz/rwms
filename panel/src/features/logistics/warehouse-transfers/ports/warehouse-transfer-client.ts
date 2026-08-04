import type {
  CreateTransferLine,
  TransferDocument,
  TransferArrivalPreflight,
  TransferFurnitureReadiness,
  TransferFurnitureReplacement,
  TransferMediaReference,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

export type TransferCreateCommand = {
  accessToken: string
  warehouseId: string
  destinationWarehouseId: string
  driverSnapshot: string | null
  scheduledDate: string
  lines: CreateTransferLine[]
  furnitureReplacements: TransferFurnitureReplacement[]
  idempotencyKey: string
}

export type TransferVersionedCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  idempotencyKey: string
}

export type TransferLineCommand = TransferVersionedCommand & {
  lineId: string
  expectedLineVersion: number
}

export type TransferArrivalCommand = TransferLineCommand & {
  references: TransferMediaReference[]
  priority: number | null
}

export type TransferReconcileCommand = TransferVersionedCommand & {
  reason: string
}

export interface WarehouseTransferClient {
  list(accessToken: string, warehouseId: string): Promise<TransferDocument[]>
  get(accessToken: string, documentId: string): Promise<TransferDocument>
  getFurnitureReadiness(
    accessToken: string,
    documentId: string
  ): Promise<TransferFurnitureReadiness>
  getArrivalPreflight(
    input: Omit<TransferLineCommand, "idempotencyKey">
  ): Promise<TransferArrivalPreflight>
  create(input: TransferCreateCommand): Promise<TransferDocument>
  depart(input: TransferLineCommand): Promise<TransferDocument>
  arrive(input: TransferArrivalCommand): Promise<TransferDocument>
  cancel(input: TransferVersionedCommand): Promise<TransferDocument>
  reconcile(input: TransferReconcileCommand): Promise<TransferDocument>
}
