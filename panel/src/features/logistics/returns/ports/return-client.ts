import type {
  CreateReturnLine,
  ReturnDocument,
  ReturnMediaLine,
  ReturnShortageLine,
} from "@/features/logistics/returns/model"

export type ReturnCreateCommand = {
  accessToken: string
  warehouseId: string
  clientId: string
  driverSnapshot: string
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
  scheduledAt: string
}

export type ReturnAcceptUndamagedCommand = ReturnVersionedCommand & {
  lines: ReturnMediaLine[]
}

export type ReturnEstimateCommand = ReturnVersionedCommand & {
  lines: ReturnShortageLine[]
}

export interface ReturnClient {
  list(accessToken: string, warehouseId: string): Promise<ReturnDocument[]>
  get(accessToken: string, documentId: string): Promise<ReturnDocument>
  create(input: ReturnCreateCommand): Promise<ReturnDocument>
  register(input: ReturnPickupCommand): Promise<ReturnDocument>
  acceptUndamaged(input: ReturnAcceptUndamagedCommand): Promise<ReturnDocument>
  requestEstimate(input: ReturnEstimateCommand): Promise<ReturnDocument>
}
