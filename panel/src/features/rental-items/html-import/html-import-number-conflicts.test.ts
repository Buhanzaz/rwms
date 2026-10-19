import { describe, expect, it } from "vitest"

import {
  findHtmlImportRentalNumberConflicts,
  type HtmlImportRentalNumberConflictRecord,
} from "@/features/rental-items/html-import/html-import-number-conflicts"

function record(
  id: string,
  overrides: Partial<HtmlImportRentalNumberConflictRecord> = {}
): HtmlImportRentalNumberConflictRecord {
  return {
    id,
    action: "CREATE",
    number: id,
    proposedNumber: null,
    ...overrides,
  }
}

describe("findHtmlImportRentalNumberConflicts", () => {
  it("returns only CREATE groups with the same normalized identity key", () => {
    const conflicts = findHtmlImportRentalNumberConflicts([
      record("row-1", { number: "  быт-042 " }),
      record("row-2", { number: "БЫТ _ 042" }),
      record("row-3", { number: "БЫТ-043" }),
      record("row-4", { number: "быт 043" }),
      record("row-5", { number: "БЫТ-044" }),
    ])

    expect([...conflicts]).toEqual([
      ["БЫТ042", ["row-1", "row-2"]],
      ["БЫТ043", ["row-3", "row-4"]],
    ])
  })

  it("uses a proposed CREATE number when it is present", () => {
    const conflicts = findHtmlImportRentalNumberConflicts([
      record("row-1", {
        number: "БЫТ-001",
        proposedNumber: " БЫТ-101 ",
      }),
      record("row-2", { number: "БЫТ-101" }),
      record("row-3", {
        number: "БЫТ-101",
        proposedNumber: "БЫТ-102",
      }),
    ])

    expect([...conflicts]).toEqual([["БЫТ101", ["row-1", "row-2"]]])
  })

  it("ignores non-CREATE rows and invalid or blank effective numbers", () => {
    const conflicts = findHtmlImportRentalNumberConflicts([
      record("merge", { action: "MERGE", number: "БЫТ-042" }),
      record("exclude", { action: "EXCLUDE", number: "БЫТ-042" }),
      record("review", { action: "REVIEW", number: "БЫТ-042" }),
      record("blank-1", { number: "  " }),
      record("blank-2", { number: "", proposedNumber: "\t" }),
      record("invalid-1", { number: "---" }),
      record("invalid-2", { number: "---" }),
      record("valid", { number: "БЫТ-042" }),
    ])

    expect([...conflicts]).toEqual([])
  })
})
