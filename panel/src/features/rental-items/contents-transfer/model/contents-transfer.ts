import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"

export type ContentsTransferActorSnapshot = {
  id: string | null
  displayName: string
}

export type ContentsTransferTaskRef = {
  boardTaskId: string
  queueId: string
  queueCode: string
}

export type ContentsTransferCabinSnapshot = {
  rentalItemId: string
  number: string
  versionBefore: number
  versionAfter: number
}

export type ContentsTransferRecord = {
  id: string
  externalTaskId: string
  status: "APPLIED"
  warehouseId: string
  serviceWarehouseId: string
  occurredAt: string
  actor: ContentsTransferActorSnapshot
  source: ContentsTransferCabinSnapshot
  target: ContentsTransferCabinSnapshot
  items: RentalItemContentsItemDto[]
  task: ContentsTransferTaskRef
}

export type CreateContentsTransferCommand = {
  externalTaskId: string
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: ContentsTransferActorSnapshot
  source: {
    rentalItemId: string
    number: string
    expectedVersion: number
  }
  target: {
    rentalItemId: string
    number: string
    expectedVersion: number
  }
  items: RentalItemContentsItemDto[]
}

export type StoredContentsTransferCommand = Omit<
  CreateContentsTransferCommand,
  "accessToken"
>

export type ContentsTransferAttempt = {
  externalTaskId: string
  phase: "PENDING_DISPATCH" | "TASK_REGISTERED" | "CONFLICT" | "APPLIED"
  command: StoredContentsTransferCommand
  task: ContentsTransferTaskRef | null
  record: ContentsTransferRecord | null
  error: string | null
  createdAt: string
  updatedAt: string
}
