import type { WarehouseInfo } from "@/api/warehouse-api"
import type {
  RentalItemContentsItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

export type WarehouseTransferLineStatus =
  | "PREPARING"
  | "READY_TO_DEPART"
  | "IN_TRANSIT"
  | "RECEIVED"
  | "CONFLICT"
  | "CANCELLED"

export type WarehouseTransferActorSnapshot = {
  id: string | null
  displayName: string
}

export type WarehouseTransferWarehouseSnapshot = Pick<
  WarehouseInfo,
  "id" | "code" | "name" | "city"
>

export type WarehouseTransferTaskRef = {
  boardTaskId: string
  taskVersion: number
  queueId: string
  queueCode: string
}

export type WarehouseTransferPhoto = {
  id: string
  fileName: string
  mimeType: string
  rotationDegrees: 0 | 90 | 180 | 270
  storageRef: string
  previewStorageRef: string
  originalAvailable: boolean
  processingStatus: "UPLOADING" | "PROCESSING" | "READY" | "FAILED"
  createdAt: string
}

export type WarehouseTransferPhotoUpload = {
  id: string
  fileName: string
  dataUrl: string
  rotationDegrees: 0 | 90 | 180 | 270
}

export type WarehouseTransferReturnConflictLink = {
  warehouseId: string
  returnItemId: string
  expectedVersion: number
}

export type WarehouseTransferApplicationAttempt =
  | {
      kind: "DEPARTURE"
      startedAt: string
      expectedRentalItemVersion: number
    }
  | {
      kind: "ARRIVAL"
      startedAt: string
      expectedRentalItemVersion: number
      photos: WarehouseTransferPhoto[]
    }

export type WarehouseTransferLine = {
  id: string
  version: number
  rentalItemId: string
  cabinNumber: string
  sourceStatus: Extract<RentalItemStatus, "FREE" | "WAREHOUSE" | "OWN_NEEDS">
  rentalItemVersion: number
  contentsSnapshot: RentalItemContentsItemDto[]
  status: WarehouseTransferLineStatus
  sourceExternalTaskId: string
  destinationExternalTaskId: string
  sourceTask: WarehouseTransferTaskRef | null
  destinationTask: WarehouseTransferTaskRef | null
  photos: WarehouseTransferPhoto[]
  departedAt: string | null
  receivedAt: string | null
  conflictReason: string | null
  lastError: string | null
  /** Durable browser-saga intent. Optional only for records created before recovery support. */
  applicationAttempt?: WarehouseTransferApplicationAttempt | null
  /** Optional import-conflict continuation proof for records created by a deep link. */
  returnConflict?: WarehouseTransferReturnConflictLink | null
}

export type WarehouseTransferDocument = {
  id: string
  requestId: string
  version: number
  sourceWarehouse: WarehouseTransferWarehouseSnapshot
  destinationWarehouse: WarehouseTransferWarehouseSnapshot
  plannedDate: string
  driverName: string
  /** Read-only compatibility for previously stored documents. New commands keep it null. */
  vehicle: string | null
  comment: string | null
  actor: WarehouseTransferActorSnapshot
  createdAt: string
  updatedAt: string
  lines: WarehouseTransferLine[]
}

export type CreateWarehouseTransferCommand = {
  requestId: string
  accessToken: string
  sourceWarehouse: WarehouseTransferWarehouseSnapshot
  destinationWarehouse: WarehouseTransferWarehouseSnapshot
  plannedDate: string
  driverName: string
  comment: string | null
  actor: WarehouseTransferActorSnapshot
  cabins: Array<{
    rentalItemId: string
    expectedVersion: number
    returnConflict?: WarehouseTransferReturnConflictLink | null
  }>
}

export type WarehouseTransferTaskCommand = {
  externalTaskId: string
  serviceWarehouseId: string
  direction: "SOURCE" | "DESTINATION"
  sourceWarehouse: WarehouseTransferWarehouseSnapshot
  destinationWarehouse: WarehouseTransferWarehouseSnapshot
  cabin: Pick<
    WarehouseTransferLine,
    "rentalItemId" | "cabinNumber" | "contentsSnapshot"
  >
  plannedDate: string
  driverName: string
}

export type WarehouseTransferEventType =
  | "TRANSFER_CREATED"
  | "SOURCE_TASK_REGISTERED"
  | "DEPARTURE_CONFIRMED"
  | "DESTINATION_TASK_REGISTERED"
  | "ARRIVAL_CONFLICT"
  | "ARRIVAL_CONFIRMED"
  | "TRANSFER_CANCELLED"
  | "ACCOUNTING_CORRECTION_CREATED"
  | "ACCOUNTING_CORRECTION_APPROVED"
  | "ACCOUNTING_CORRECTION_APPLIED"
  | "ACCOUNTING_CORRECTION_REJECTED"

export type WarehouseTransferEvent = {
  id: string
  documentId: string
  lineId: string | null
  rentalItemId: string | null
  type: WarehouseTransferEventType
  occurredAt: string
  actor: WarehouseTransferActorSnapshot
  comment: string | null
}

export type WarehouseCorrectionStatus =
  "PENDING_APPROVALS" | "APPLYING" | "APPLIED" | "REJECTED"

export type WarehouseCorrectionApproval = {
  warehouseId: string
  approvedAt: string
  actor: WarehouseTransferActorSnapshot
}

export type WarehouseAccountingCorrection = {
  id: string
  version: number
  rentalItemId: string
  cabinNumber: string
  expectedRentalItemVersion: number
  sourceWarehouse: WarehouseTransferWarehouseSnapshot
  destinationWarehouse: WarehouseTransferWarehouseSnapshot
  reason: string
  status: WarehouseCorrectionStatus
  approvals: WarehouseCorrectionApproval[]
  rejectedReason: string | null
  createdAt: string
  updatedAt: string
  /** Optional import-conflict continuation proof for records created by a deep link. */
  returnConflict?: WarehouseTransferReturnConflictLink | null
}

export const warehouseTransferStatusLabels: Record<
  WarehouseTransferLineStatus,
  string
> = {
  PREPARING: "Подготовка",
  READY_TO_DEPART: "Готова к отправке",
  IN_TRANSIT: "В пути",
  RECEIVED: "Принята",
  CONFLICT: "Конфликт",
  CANCELLED: "Отменена",
}

export const WAREHOUSE_TRANSFER_ALLOWED_STATUSES: RentalItemStatus[] = [
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
]
