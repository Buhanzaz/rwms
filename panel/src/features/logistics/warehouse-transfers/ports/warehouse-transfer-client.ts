import type {
  CreateTransferLine,
  TransferDocument,
  TransferArrivalPreflight,
  TransferFurnitureReadiness,
  TransferFurnitureReplacement,
  TransferMediaReference,
  TransferPlan,
  TransferPlanRequest,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

export type TransferCreateCommand = {
  accessToken: string
  warehouseId: string
  destinationWarehouseId: string
  scheduledDate: string
  lines: CreateTransferLine[]
  furnitureReplacements: TransferFurnitureReplacement[]
  /** Optional typed plan; legacy concrete-line callers omit this property. */
  plan?: TransferPlanRequest | null
  idempotencyKey: string
}

/** Complete replacement of one mutable transfer draft plan. */
export type TransferPlanUpdateCommand = TransferVersionedCommand & {
  scheduledDate: string
  plan: TransferPlanRequest
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
  list(
    accessToken: string,
    warehouseId: string,
    scheduledDate?: string
  ): Promise<TransferDocument[]>
  get(accessToken: string, documentId: string): Promise<TransferDocument>
  getPlan(accessToken: string, documentId: string): Promise<TransferPlan>
  getFurnitureReadiness(
    accessToken: string,
    documentId: string
  ): Promise<TransferFurnitureReadiness>
  getArrivalPreflight(
    input: Omit<TransferLineCommand, "idempotencyKey">
  ): Promise<TransferArrivalPreflight>
  create(input: TransferCreateCommand): Promise<TransferDocument>
  updatePlan(input: TransferPlanUpdateCommand): Promise<TransferPlan>
  confirm(input: TransferVersionedCommand): Promise<TransferPlan>
  depart(input: TransferLineCommand): Promise<TransferDocument>
  arrive(input: TransferArrivalCommand): Promise<TransferDocument>
  cancel(input: TransferVersionedCommand): Promise<TransferDocument>
  reconcile(input: TransferReconcileCommand): Promise<TransferDocument>
}
