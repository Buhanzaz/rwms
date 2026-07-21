import { describe, expect, it } from "vitest"

import { smartLocalSearch } from "@/features/rental-items/smart-local-search"

describe("smartLocalSearch", () => {
  const folders = [
    { id: "first", values: ["Фёдор", "Общие фотографии"] },
    { id: "second", values: ["Федоров", "Добавленные фотографии"] },
    { id: "third", values: ["Мария", "Осмотр"] },
  ]

  it("normalizes ё/е, tolerates one typo, and keeps equal-score order stable", () => {
    expect(
      smartLocalSearch(folders, "федор", (folder) => folder.values).map(
        (folder) => folder.id
      )
    ).toEqual(["first", "second"])
    expect(
      smartLocalSearch(folders, "фатографии", (folder) => folder.values).map(
        (folder) => folder.id
      )
    ).toEqual(["first", "second"])
  })
})
