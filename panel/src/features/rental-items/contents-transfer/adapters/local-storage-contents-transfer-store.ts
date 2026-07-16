import type {
  ContentsTransferAttempt,
  ContentsTransferRecord,
  StoredContentsTransferCommand,
} from "@/features/rental-items/contents-transfer/model/contents-transfer"
import type { ContentsTransferStore } from "@/features/rental-items/contents-transfer/ports/contents-transfer-store"

export const CONTENTS_TRANSFER_STORAGE_KEY =
  "rwms:rental-item-contents-transfers:v1"
export const CONTENTS_TRANSFER_UPDATED_EVENT =
  "rwms:rental-item-contents-transfers-updated"
const CONTENTS_TRANSFER_LOCK_NAME =
  "rwms:rental-item-contents-transfers:mutation"
const CONTENTS_TRANSFER_LEASE_KEY = `${CONTENTS_TRANSFER_LOCK_NAME}:lease`
let storeQueue: Promise<void> = Promise.resolve()

async function withStorageLease<T>(write: () => T) {
  const token = crypto.randomUUID()
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const now = Date.now()
    const current = window.localStorage.getItem(CONTENTS_TRANSFER_LEASE_KEY)
    const expiresAt = current ? Number(current.split(":")[1]) : 0
    if (!current || !Number.isFinite(expiresAt) || expiresAt <= now) {
      window.localStorage.setItem(
        CONTENTS_TRANSFER_LEASE_KEY,
        `${token}:${now + 5_000}`
      )
      if (
        window.localStorage
          .getItem(CONTENTS_TRANSFER_LEASE_KEY)
          ?.startsWith(token)
      ) {
        try {
          return write()
        } finally {
          if (
            window.localStorage
              .getItem(CONTENTS_TRANSFER_LEASE_KEY)
              ?.startsWith(token)
          ) {
            window.localStorage.removeItem(CONTENTS_TRANSFER_LEASE_KEY)
          }
        }
      }
    }
    await new Promise((resolve) => window.setTimeout(resolve, 10))
  }
  throw new Error("Журнал перемещений занят в другой вкладке")
}

type Envelope = {
  service: "rental-item-contents-transfers"
  schemaVersion: 2
  revision: number
  attempts: ContentsTransferAttempt[]
}

function emptyEnvelope(): Envelope {
  return {
    service: "rental-item-contents-transfers",
    schemaVersion: 2,
    revision: 0,
    attempts: [],
  }
}

function commandForRecord(
  record: ContentsTransferRecord
): StoredContentsTransferCommand {
  return {
    externalTaskId: record.externalTaskId,
    warehouseId: record.warehouseId,
    serviceWarehouseId: record.serviceWarehouseId,
    actor: record.actor,
    source: {
      rentalItemId: record.source.rentalItemId,
      number: record.source.number,
      expectedVersion: record.source.versionBefore,
    },
    target: {
      rentalItemId: record.target.rentalItemId,
      number: record.target.number,
      expectedVersion: record.target.versionBefore,
    },
    items: record.items,
  }
}

function readEnvelope(): Envelope {
  if (typeof window === "undefined") return emptyEnvelope()
  const raw = window.localStorage.getItem(CONTENTS_TRANSFER_STORAGE_KEY)
  if (!raw) return emptyEnvelope()
  try {
    const parsed = JSON.parse(raw) as {
      service?: unknown
      schemaVersion?: unknown
      revision?: unknown
      attempts?: unknown
      records?: unknown
    }
    if (parsed.service !== "rental-item-contents-transfers")
      return emptyEnvelope()
    const revision = typeof parsed.revision === "number" ? parsed.revision : 0
    if (parsed.schemaVersion === 2 && Array.isArray(parsed.attempts)) {
      return { ...emptyEnvelope(), revision, attempts: parsed.attempts }
    }
    if (parsed.schemaVersion === 1 && Array.isArray(parsed.records)) {
      const records = parsed.records as ContentsTransferRecord[]
      return {
        ...emptyEnvelope(),
        revision,
        attempts: records.map((record) => ({
          externalTaskId: record.externalTaskId,
          phase: "APPLIED",
          command: commandForRecord(record),
          task: record.task,
          record,
          error: null,
          createdAt: record.occurredAt,
          updatedAt: record.occurredAt,
        })),
      }
    }
    return emptyEnvelope()
  } catch {
    return emptyEnvelope()
  }
}

function matchingCommand(
  left: StoredContentsTransferCommand,
  right: StoredContentsTransferCommand
) {
  return (
    left.warehouseId === right.warehouseId &&
    left.serviceWarehouseId === right.serviceWarehouseId &&
    left.source.rentalItemId === right.source.rentalItemId &&
    left.source.expectedVersion === right.source.expectedVersion &&
    left.target.rentalItemId === right.target.rentalItemId &&
    left.target.expectedVersion === right.target.expectedVersion &&
    JSON.stringify(left.items) === JSON.stringify(right.items)
  )
}

export class LocalStorageContentsTransferStore implements ContentsTransferStore {
  findByExternalTaskId(externalTaskId: string) {
    const attempt = readEnvelope().attempts.find(
      (candidate) => candidate.externalTaskId === externalTaskId
    )
    return attempt ? structuredClone(attempt) : null
  }

  findMatching(command: StoredContentsTransferCommand) {
    const attempt = readEnvelope()
      .attempts.filter(
        (candidate) =>
          candidate.phase !== "APPLIED" &&
          matchingCommand(candidate.command, command)
      )
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))[0]
    return attempt ? structuredClone(attempt) : null
  }

  listForRentalItem(rentalItemId: string) {
    return readEnvelope().attempts.flatMap((attempt) => {
      const record = attempt.phase === "APPLIED" ? attempt.record : null
      return record &&
        (record.source.rentalItemId === rentalItemId ||
          record.target.rentalItemId === rentalItemId)
        ? [structuredClone(record)]
        : []
    })
  }

  saveAttempt(attempt: ContentsTransferAttempt) {
    const operation = async () => {
      const write = () => {
        const envelope = readEnvelope()
        const existing = envelope.attempts.find(
          (candidate) => candidate.externalTaskId === attempt.externalTaskId
        )
        if (existing && !matchingCommand(existing.command, attempt.command)) {
          throw new Error("externalTaskId уже связан с другим перемещением")
        }
        const attempts = existing
          ? envelope.attempts.map((candidate) =>
              candidate.externalTaskId === attempt.externalTaskId
                ? structuredClone(attempt)
                : candidate
            )
          : [...envelope.attempts, structuredClone(attempt)]
        window.localStorage.setItem(
          CONTENTS_TRANSFER_STORAGE_KEY,
          JSON.stringify({
            ...envelope,
            schemaVersion: 2,
            revision: envelope.revision + 1,
            attempts,
          })
        )
        window.dispatchEvent(new Event(CONTENTS_TRANSFER_UPDATED_EVENT))
        return structuredClone(attempt)
      }
      return navigator.locks
        ? navigator.locks.request(CONTENTS_TRANSFER_LOCK_NAME, write)
        : withStorageLease(write)
    }
    const result = storeQueue.then(operation, operation)
    storeQueue = result.then(
      () => undefined,
      () => undefined
    )
    return result
  }
}
