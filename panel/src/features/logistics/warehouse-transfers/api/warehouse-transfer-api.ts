import { readRentalItems } from "@/features/rental-items/api/rental-items-api"
import { HttpWarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/adapters/http-warehouse-transfer-task-client"
import { BrowserWarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/adapters/browser-warehouse-transfer-task-client"
import { DEV_AUTH_BYPASS_ENABLED } from "@/features/auth/auth-config"
import { IndexedDbWarehouseTransferMediaClient } from "@/features/logistics/warehouse-transfers/adapters/indexed-db-warehouse-transfer-media-client"
import {
  LocalStorageWarehouseTransferStore,
  WAREHOUSE_TRANSFERS_STORAGE_KEY,
  WAREHOUSE_TRANSFERS_UPDATED_EVENT,
} from "@/features/logistics/warehouse-transfers/adapters/local-storage-warehouse-transfer-store"
import { BrowserWarehouseTransferClient } from "@/features/logistics/warehouse-transfers/browser-warehouse-transfer-client"
import {
  WAREHOUSE_TRANSFER_ALLOWED_STATUSES,
  type CreateWarehouseTransferCommand,
  type WarehouseTransferActorSnapshot,
  type WarehouseTransferPhoto,
  type WarehouseTransferPhotoUpload,
  type WarehouseTransferWarehouseSnapshot,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"
import { hasActiveWarehouseTransferLowLevel } from "@/features/logistics/warehouse-transfers/active-transfer-guard"
import { hasActiveShipmentForRentalItemLowLevel } from "@/features/logistics/shipments/active-shipment-guard"

export { WAREHOUSE_TRANSFERS_STORAGE_KEY, WAREHOUSE_TRANSFERS_UPDATED_EVENT }

export const WAREHOUSE_TRANSFERS_QUERY_KEY = ["warehouse-transfers"] as const

const transferStore = new LocalStorageWarehouseTransferStore()
const client = new BrowserWarehouseTransferClient(
  transferStore,
  DEV_AUTH_BYPASS_ENABLED
    ? new BrowserWarehouseTransferTaskClient()
    : new HttpWarehouseTransferTaskClient()
)
const mediaClient = new IndexedDbWarehouseTransferMediaClient()

export function listWarehouseTransfers() {
  return client.list()
}

export function getWarehouseTransfer(documentId: string) {
  return client.get(documentId)
}

export function createWarehouseTransfer(
  command: CreateWarehouseTransferCommand
) {
  return client.create(command)
}

export function retryWarehouseTransferSourceTask(params: {
  documentId: string
  lineId: string
  expectedDocumentVersion: number
  accessToken: string
  actor: WarehouseTransferActorSnapshot
}) {
  return client.retrySourceTask(params)
}

export function confirmWarehouseTransferDeparture(params: {
  documentId: string
  lineId: string
  expectedDocumentVersion: number
  expectedLineVersion: number
  accessToken: string
  actor: WarehouseTransferActorSnapshot
}) {
  return client.confirmDeparture(params)
}

export function retryWarehouseTransferDestinationTask(params: {
  documentId: string
  lineId: string
  expectedDocumentVersion: number
  accessToken: string
  actor: WarehouseTransferActorSnapshot
}) {
  return client.retryDestinationTask(params)
}

export function acceptWarehouseTransferArrival(params: {
  documentId: string
  lineId: string
  expectedDocumentVersion: number
  expectedLineVersion: number
  actualContents: RentalItemContentsItemDto[]
  photoUploads: WarehouseTransferPhotoUpload[]
  existingPhotos: WarehouseTransferPhoto[]
  actor: WarehouseTransferActorSnapshot
}) {
  return mediaClient.upload(params.photoUploads).then(async (photos) => {
    try {
      return await client.acceptArrival({
        ...params,
        photos: [...params.existingPhotos, ...photos],
      })
    } catch (error) {
      const current = await client.get(params.documentId)
      const attempt = current?.lines.find(
        (line) => line.id === params.lineId
      )?.applicationAttempt
      const retainedMediaIds = new Set(
        attempt?.kind === "ARRIVAL"
          ? attempt.photos.map((photo) => photo.id)
          : []
      )
      await mediaClient.discard(
        photos
          .filter((photo) => !retainedMediaIds.has(photo.id))
          .map((photo) => photo.id)
      )
      throw error
    }
  })
}

export function cancelWarehouseTransferLine(params: {
  documentId: string
  lineId: string
  expectedDocumentVersion: number
  expectedLineVersion: number
  accessToken: string
  actor: WarehouseTransferActorSnapshot
  reason: string
}) {
  return client.cancelLine(params)
}

export function listWarehouseTransferEventsForRentalItem(rentalItemId: string) {
  return client.listEventsForRentalItem(rentalItemId)
}

export function listWarehouseAccountingCorrections() {
  return client.listCorrections()
}

export function getWarehouseTransferCandidates(warehouseId: string) {
  return Promise.all([
    client.listActiveRentalItemIds(),
    client.listCorrections(),
  ]).then(([activeRentalItemIds, corrections]) => {
    const active = new Set(activeRentalItemIds)
    const activeCorrections = new Set(
      corrections
        .filter(
          (correction) =>
            correction.status === "PENDING_APPROVALS" ||
            correction.status === "APPLYING"
        )
        .map((correction) => correction.rentalItemId)
    )
    return readRentalItems()
      .filter(
        (item) =>
          item.warehouseId === warehouseId &&
          WAREHOUSE_TRANSFER_ALLOWED_STATUSES.includes(item.status) &&
          !active.has(item.id) &&
          !activeCorrections.has(item.id) &&
          !hasActiveShipmentForRentalItemLowLevel(item.id)
      )
      .sort((left, right) =>
        left.number.localeCompare(right.number, "ru", { numeric: true })
      )
  })
}

/** Shared active-operation guard for returns, shipments, repairs and write-offs. */
export function hasActiveWarehouseTransfer(rentalItemId: string) {
  return Promise.resolve(hasActiveWarehouseTransferLowLevel(rentalItemId))
}

export { hasActiveWarehouseTransferLowLevel }

export function getWarehouseCorrectionCandidate(rentalItemId: string) {
  return Promise.resolve(
    readRentalItems().find((item) => item.id === rentalItemId) ?? null
  )
}

export function createWarehouseAccountingCorrection(params: {
  rentalItemId: string
  expectedRentalItemVersion: number
  sourceWarehouse: WarehouseTransferWarehouseSnapshot
  destinationWarehouse: WarehouseTransferWarehouseSnapshot
  reason: string
  actor: WarehouseTransferActorSnapshot
  returnConflict?: {
    warehouseId: string
    returnItemId: string
    expectedVersion: number
  } | null
}) {
  return client.createCorrection(params)
}

export function approveWarehouseAccountingCorrection(params: {
  correctionId: string
  expectedVersion: number
  approvingWarehouseId: string
  actor: WarehouseTransferActorSnapshot
}) {
  return client.approveCorrection(params)
}

export function rejectWarehouseAccountingCorrection(params: {
  correctionId: string
  expectedVersion: number
  rejectingWarehouseId: string
  actor: WarehouseTransferActorSnapshot
  reason: string
}) {
  return client.rejectCorrection(params)
}

export function buildWarehouseCorrectionDeepLink(params: {
  rentalItemId: string
  sourceWarehouseId: string
  destinationWarehouseId: string
}) {
  const search = new URLSearchParams({
    correction: "1",
    rentalItemId: params.rentalItemId,
    sourceWarehouseId: params.sourceWarehouseId,
    destinationWarehouseId: params.destinationWarehouseId,
  })
  return `/logistics/transfers?${search.toString()}`
}

export function buildWarehouseTransferDeepLink(params: {
  rentalItemId: string
  sourceWarehouseId: string
  destinationWarehouseId: string
}) {
  const search = new URLSearchParams({
    create: "1",
    rentalItemId: params.rentalItemId,
    sourceWarehouseId: params.sourceWarehouseId,
    destinationWarehouseId: params.destinationWarehouseId,
  })
  return `/logistics/transfers?${search.toString()}`
}
