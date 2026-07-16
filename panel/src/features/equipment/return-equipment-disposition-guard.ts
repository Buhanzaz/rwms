import type { ReturnEquipmentDispositionCaseDto } from "@/types/equipment"

const DISPOSITIONS_STORAGE_KEY = "wms:mock-return-equipment-dispositions"
const DISPOSITION_JOURNAL_KEY = "wms:mock-return-equipment-disposition-journal"

function parseJson(value: string | null): unknown {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return undefined
  }
}

function isUnresolved(item: Partial<ReturnEquipmentDispositionCaseDto>) {
  return item.status !== "RESOLVED"
}

/**
 * Cycle-free, synchronous fail-closed guard for rental-item mutation APIs.
 * It deliberately performs no recovery and acquires no lock; callers recheck it
 * after acquiring the shared rental mutation lock.
 */
export function hasUnresolvedReturnEquipmentDispositionLowLevel(
  rentalItemId: string
) {
  const rawState = window.localStorage.getItem(DISPOSITIONS_STORAGE_KEY)
  const state = parseJson(rawState)
  if (rawState && state === undefined) return true
  if (state && typeof state === "object") {
    const cases = (state as { cases?: unknown }).cases
    if (!Array.isArray(cases)) return true
    if (
      cases.some(
        (item) =>
          item &&
          typeof item === "object" &&
          (item as ReturnEquipmentDispositionCaseDto).sourceRentalItemId ===
            rentalItemId &&
          isUnresolved(item as ReturnEquipmentDispositionCaseDto)
      )
    ) {
      return true
    }
  }

  const rawJournal = window.localStorage.getItem(DISPOSITION_JOURNAL_KEY)
  const journal = parseJson(rawJournal)
  if (!rawJournal) return false
  if (!journal || typeof journal !== "object") return true
  const prepared = journal as {
    state?: unknown
    rentalItems?: Array<{ id?: unknown }>
    dispositionCases?: Array<{
      before?: Partial<ReturnEquipmentDispositionCaseDto>
      after?: Partial<ReturnEquipmentDispositionCaseDto>
    }>
  }
  if (prepared.state !== "PREPARED") return true
  if (!Array.isArray(prepared.rentalItems)) return true
  if (prepared.rentalItems.some((item) => item.id === rentalItemId)) return true
  if (!Array.isArray(prepared.dispositionCases)) return true
  return prepared.dispositionCases.some((change) =>
    [change.before, change.after].some(
      (item) => item?.sourceRentalItemId === rentalItemId && isUnresolved(item)
    )
  )
}
