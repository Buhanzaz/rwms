import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ForceCapitalRepairField } from "@/features/repair-estimates/force-capital-repair-field"

afterEach(cleanup)

describe("ForceCapitalRepairField", () => {
  it("reports the explicit manager choice", async () => {
    const onCheckedChange = vi.fn()
    render(
      <ForceCapitalRepairField
        id="force-capital-editable"
        checked={false}
        onCheckedChange={onCheckedChange}
      />
    )

    await userEvent.click(
      screen.getByRole("checkbox", {
        name: "Направить на капитальный ремонт",
      })
    )

    expect(onCheckedChange).toHaveBeenCalledWith(true)
  })

  it("shows the authoritative value without allowing read-only changes", async () => {
    const onCheckedChange = vi.fn()
    render(
      <ForceCapitalRepairField
        id="force-capital-read-only"
        checked
        disabled
        onCheckedChange={onCheckedChange}
      />
    )

    const checkbox = screen.getByRole("checkbox", {
      name: "Направить на капитальный ремонт",
    })
    expect(checkbox.getAttribute("aria-checked")).toBe("true")
    expect(checkbox.hasAttribute("disabled")).toBe(true)

    await userEvent.click(checkbox)
    expect(onCheckedChange).not.toHaveBeenCalled()
  })
})
