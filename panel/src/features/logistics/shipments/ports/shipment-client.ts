import type {
  ShipmentDocument,
  ShipmentPlanLine,
} from "@/features/logistics/shipments/model"

export type ShipmentCreateCommand = {
  accessToken: string
  warehouseId: string
  clientId: string
  rentalOrderId: string
  partySnapshot: string
  driverSnapshot: string
  lines: ShipmentPlanLine[]
  idempotencyKey: string
}

export type ShipmentPlanCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  driverSnapshot: string
  scheduledAt: string
  idempotencyKey: string
}

export type ShipmentVersionedCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  idempotencyKey: string
}

export interface ShipmentClient {
  list(accessToken: string, warehouseId: string): Promise<ShipmentDocument[]>
  get(accessToken: string, documentId: string): Promise<ShipmentDocument>
  create(input: ShipmentCreateCommand): Promise<ShipmentDocument>
  replacePlan(input: ShipmentPlanCommand): Promise<ShipmentDocument>
  confirmPreparation(input: ShipmentVersionedCommand): Promise<ShipmentDocument>
  cancel(input: ShipmentVersionedCommand): Promise<ShipmentDocument>
}
