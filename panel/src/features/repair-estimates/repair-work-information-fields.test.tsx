import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

vi.mock(
  "@/features/repair-estimates/repair-estimate-rental-item-picker",
  () => ({
    RepairEstimateRentalItemPicker: () => <button type="button">Бытовка</button>,
  })
)

import { RepairWorkInformationFields } from "@/features/repair-estimates/repair-work-information-fields"

const props = {
  warehouseId: "00000000-0000-4000-8000-000000000001",
  rentalItemId: "",
  contextLabel: "От кого" as const,
  contextValue: "ООО Арендатор",
  dispatchDate: "2026-07-18",
  comment: "",
  disabled: false,
  onRentalItemChange: vi.fn(),
  onContextChange: vi.fn(),
  onDispatchDateChange: vi.fn(),
  onCommentChange: vi.fn(),
}

describe("RepairWorkInformationFields", () => {
  it("hides source and date controls for a direct repair", () => {
    render(
      <RepairWorkInformationFields
        {...props}
        contextLabel="Источник"
        showContext={false}
        showDispatchDate={false}
      />
    )

    expect(screen.getByRole("button", { name: "Бытовка" })).toBeTruthy()
    expect(screen.queryByLabelText("Источник")).toBeNull()
    expect(screen.queryByLabelText("Дата осмотра")).toBeNull()
    expect(screen.queryByText("От кого")).toBeNull()
    expect(screen.queryByText(/Прибытие/)).toBeNull()
  })

  it("labels the estimate date as an inspection", () => {
    render(<RepairWorkInformationFields {...props} />)

    expect(screen.getByLabelText("От кого")).toBeTruthy()
    expect(screen.getByLabelText("Дата осмотра")).toBeTruthy()
    expect(screen.queryByText(/Прибытие/)).toBeNull()
  })
})
