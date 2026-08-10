import type {
  ShipmentDocument,
  ShipmentFurnitureReadiness,
  ShipmentFurnitureTaskResult,
  ShipmentPlanLine,
} from "@/features/logistics/shipments/model"

export type ShipmentCreateCommand = {
  accessToken: string
  warehouseId: string
  clientId: string
  rentalOrderId: string
  partySnapshot: string
  driverSnapshot: string
  driverWorkerId: string
  lines: ShipmentPlanLine[]
  idempotencyKey: string
}

export type ShipmentPlanCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  driverSnapshot: string
  driverWorkerId: string | null
  scheduledDate: string
  idempotencyKey: string
}

export type ShipmentVersionedCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  idempotencyKey: string
  /** Explicit operator choice to keep a planned date from another day. */
  keepScheduledDate?: boolean
}

export interface ShipmentClient {
  list(accessToken: string, warehouseId: string): Promise<ShipmentDocument[]>
  get(accessToken: string, documentId: string): Promise<ShipmentDocument>
  getFurnitureReadiness(
    accessToken: string,
    documentId: string
  ): Promise<ShipmentFurnitureReadiness>
  create(input: ShipmentCreateCommand): Promise<ShipmentDocument>
  replacePlan(input: ShipmentPlanCommand): Promise<ShipmentDocument>
  createFurnitureTasks(
    input: ShipmentVersionedCommand
  ): Promise<ShipmentFurnitureTaskResult>
  confirmPreparation(input: ShipmentVersionedCommand): Promise<ShipmentDocument>
  cancel(input: ShipmentVersionedCommand): Promise<ShipmentDocument>
}
