import type {
  CreateReturnLine,
  ReturnDocument,
  ReturnEstimateLine,
  ReturnMediaLine,
} from "@/features/logistics/returns/model"

export type ReturnCreateCommand = {
  accessToken: string
  warehouseId: string
  clientId: string
  driverSnapshot: string
  driverWorkerId: string
  lines: CreateReturnLine[]
  idempotencyKey: string
}

export type ReturnVersionedCommand = {
  accessToken: string
  documentId: string
  expectedVersion: number
  idempotencyKey: string
}

export type ReturnPickupCommand = ReturnVersionedCommand & {
  driverSnapshot: string
  driverWorkerId: string
  scheduledDate: string
}

export type ReturnAcceptUndamagedCommand = ReturnVersionedCommand & {
  lines: ReturnMediaLine[]
}

export type StartReturnEstimatesCommand = ReturnVersionedCommand & {
  lines: ReturnEstimateLine[]
}

export interface ReturnClient {
  list(
    accessToken: string,
    warehouseId: string,
    scheduledDate?: string
  ): Promise<ReturnDocument[]>
  get(accessToken: string, documentId: string): Promise<ReturnDocument>
  create(input: ReturnCreateCommand): Promise<ReturnDocument>
  register(input: ReturnPickupCommand): Promise<ReturnDocument>
  acceptUndamaged(input: ReturnAcceptUndamagedCommand): Promise<ReturnDocument>
  startEstimates(input: StartReturnEstimatesCommand): Promise<ReturnDocument>
}
