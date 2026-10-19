import { describe, expect, it } from "vitest"

import { canUseOriginalMedia } from "@/features/media/model/media"

describe("media viewer policy", () => {
  it("allows originals only in estimate, inspection and work contexts", () => {
    expect(canUseOriginalMedia("ESTIMATE")).toBe(true)
    expect(canUseOriginalMedia("INSPECTION")).toBe(true)
    expect(canUseOriginalMedia("WORK")).toBe(true)
    expect(canUseOriginalMedia("WAREHOUSE")).toBe(false)
    expect(canUseOriginalMedia("HISTORY")).toBe(false)
  })
})
