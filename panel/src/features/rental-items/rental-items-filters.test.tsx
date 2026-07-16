import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { RentalItemsFilters } from "@/features/rental-items/rental-items-filters"

describe("rental item filters", () => {
  it("uses the table status colors in the status filter options", () => {
    render(
      <RentalItemsFilters
        options={[
          {
            id: "status",
            label: "Статус",
            dataType: "status",
            values: ["Аренда", "Свободна", "Списана"],
          },
        ]}
        filters={{}}
        onFiltersChange={vi.fn()}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Статус" }))

    expect(
      screen.getByText("Аренда").closest('[data-slot="badge"]')?.className
    ).toContain("--status-rented-bg")
    expect(
      screen.getByText("Свободна").closest('[data-slot="badge"]')?.className
    ).toContain("--status-free-bg")
    expect(
      screen.getByText("Списана").closest('[data-slot="badge"]')?.className
    ).toContain("--status-written-off-bg")
  })
})
