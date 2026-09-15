import { describe, expect, it } from "vitest"
import { requirementStyle } from "./requirement-presentation"

describe("requirement palette", () => {
  const palette = {
    version: 1,
    ranges: [],
    overdueColor: "#000000",
    problemColor: "#DD1122",
    completedColor: "#228844",
  }

  it("uses admin colors for missing, completed and restored rows", () => {
    expect(requirementStyle("MISSING", palette)).toEqual({
      borderColor: "#DD1122",
      backgroundColor: "#DD112226",
    })
    expect(requirementStyle("COMPLETED", palette)).toEqual({
      borderColor: "#228844",
      backgroundColor: "#22884426",
    })
    expect(requirementStyle("RESTORED", palette)).toEqual(
      requirementStyle("COMPLETED", palette)
    )
  })

  it("keeps available and unknown rows neutral", () => {
    expect(requirementStyle("AVAILABLE", palette)).toBeUndefined()
    expect(requirementStyle(undefined, palette)).toBeUndefined()
  })

  it("uses standard status colors when no palette is configured", () => {
    expect(requirementStyle("MISSING", null)?.borderColor).toBe("#FF3B30")
    expect(requirementStyle("COMPLETED", null)?.borderColor).toBe("#238636")
  })
})
