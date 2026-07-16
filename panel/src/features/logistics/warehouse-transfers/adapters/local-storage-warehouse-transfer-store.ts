import type {
  WarehouseAccountingCorrection,
  WarehouseTransferDocument,
  WarehouseTransferEvent,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferStore } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-store"

export const WAREHOUSE_TRANSFERS_STORAGE_KEY = "rwms:warehouse-transfers:v1"
export const WAREHOUSE_TRANSFERS_UPDATED_EVENT =
  "rwms:warehouse-transfers-updated"

type WarehouseTransferEnvelope = {
  documents: WarehouseTransferDocument[]
  events: WarehouseTransferEvent[]
  corrections: WarehouseAccountingCorrection[]
}

const EMPTY_ENVELOPE: WarehouseTransferEnvelope = {
  documents: [],
  events: [],
  corrections: [],
}

function readEnvelope(): WarehouseTransferEnvelope {
  try {
    const raw = window.localStorage.getItem(WAREHOUSE_TRANSFERS_STORAGE_KEY)
    if (!raw) return structuredClone(EMPTY_ENVELOPE)
    const parsed = JSON.parse(raw) as Partial<WarehouseTransferEnvelope>
    return {
      documents: Array.isArray(parsed.documents) ? parsed.documents : [],
      events: Array.isArray(parsed.events) ? parsed.events : [],
      corrections: Array.isArray(parsed.corrections) ? parsed.corrections : [],
    }
  } catch {
    return structuredClone(EMPTY_ENVELOPE)
  }
}

function writeEnvelope(envelope: WarehouseTransferEnvelope) {
  window.localStorage.setItem(
    WAREHOUSE_TRANSFERS_STORAGE_KEY,
    JSON.stringify(envelope)
  )
  window.dispatchEvent(new CustomEvent(WAREHOUSE_TRANSFERS_UPDATED_EVENT))
}

export class LocalStorageWarehouseTransferStore implements WarehouseTransferStore {
  list() {
    return readEnvelope()
      .documents.slice()
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  findById(documentId: string) {
    return (
      readEnvelope().documents.find((item) => item.id === documentId) ?? null
    )
  }

  findByRequestId(requestId: string) {
    return (
      readEnvelope().documents.find((item) => item.requestId === requestId) ??
      null
    )
  }

  findActiveByRentalItemId(rentalItemId: string) {
    return (
      readEnvelope().documents.find((document) =>
        document.lines.some(
          (line) =>
            line.rentalItemId === rentalItemId &&
            ["PREPARING", "READY_TO_DEPART", "IN_TRANSIT", "CONFLICT"].includes(
              line.status
            )
        )
      ) ?? null
    )
  }

  save(document: WarehouseTransferDocument, expectedVersion: number | null) {
    const envelope = readEnvelope()
    const index = envelope.documents.findIndex(
      (item) => item.id === document.id
    )
    const current = index < 0 ? null : envelope.documents[index]
    if (expectedVersion === null) {
      if (current)
        throw new Error("Перемещение с таким requestId уже существует")
    } else if (!current || current.version !== expectedVersion) {
      throw new Error("Перемещение изменено в другой вкладке. Обновите данные")
    }
    const next = structuredClone(document)
    if (index < 0) envelope.documents.push(next)
    else envelope.documents[index] = next
    writeEnvelope(envelope)
    return next
  }

  appendEvent(event: WarehouseTransferEvent) {
    const envelope = readEnvelope()
    if (envelope.events.some((item) => item.id === event.id)) return
    envelope.events.push(structuredClone(event))
    writeEnvelope(envelope)
  }

  listEventsForRentalItem(rentalItemId: string) {
    return readEnvelope()
      .events.filter((item) => item.rentalItemId === rentalItemId)
      .sort((left, right) => right.occurredAt.localeCompare(left.occurredAt))
  }

  listCorrections() {
    return readEnvelope()
      .corrections.slice()
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  findCorrection(correctionId: string) {
    return (
      readEnvelope().corrections.find((item) => item.id === correctionId) ??
      null
    )
  }

  saveCorrection(
    correction: WarehouseAccountingCorrection,
    expectedVersion: number | null
  ) {
    const envelope = readEnvelope()
    const index = envelope.corrections.findIndex(
      (item) => item.id === correction.id
    )
    const current = index < 0 ? null : envelope.corrections[index]
    if (expectedVersion === null) {
      if (current) throw new Error("Коррекция уже существует")
    } else if (!current || current.version !== expectedVersion) {
      throw new Error("Коррекция изменена в другой вкладке. Обновите данные")
    }
    const next = structuredClone(correction)
    if (index < 0) envelope.corrections.push(next)
    else envelope.corrections[index] = next
    writeEnvelope(envelope)
    return next
  }
}
