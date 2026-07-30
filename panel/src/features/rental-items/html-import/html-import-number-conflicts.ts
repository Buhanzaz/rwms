import {
  htmlImportRentalNumberError,
  htmlImportRentalNumberIdentityKey,
} from "@/features/rental-items/html-import/html-import-number"

export type HtmlImportRentalNumberConflictRecord = Readonly<{
  id: string
  action: "CREATE" | "MERGE" | "EXCLUDE" | "REVIEW"
  number: string
  proposedNumber: string | null
}>

export function findHtmlImportRentalNumberConflicts(
  records: readonly HtmlImportRentalNumberConflictRecord[]
): ReadonlyMap<string, readonly string[]> {
  const idsByNumberKey = new Map<string, string[]>()

  for (const record of records) {
    if (record.action !== "CREATE") continue

    const number = record.proposedNumber ?? record.number
    if (htmlImportRentalNumberError(number)) continue

    const key = htmlImportRentalNumberIdentityKey(number)
    const ids = idsByNumberKey.get(key)
    if (ids) {
      ids.push(record.id)
    } else {
      idsByNumberKey.set(key, [record.id])
    }
  }

  return new Map([...idsByNumberKey].filter(([, ids]) => ids.length > 1))
}
