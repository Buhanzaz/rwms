import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"

import { InventoryStatistics } from "@/features/inventory/inventory-statistics"
import type {
  InventoryFindingDto,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"

function finding(): InventoryFindingDto {
  return {
    id: "finding-1",
    version: 1,
    rentalItemId: "rental-item-1",
    canonicalNumber: "БЫТ-001",
    cabinNumber: "БЫТ-001",
    origin: "EXPECTED",
    inspectionStatus: "WORK_STAGED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: null,
    currentSnapshot: null,
    inspectionBaseline: null,
    conflictResolution: null,
    conflicts: [],
    comment: "",
    media: [],
    coverMediaId: null,
    inspectionSource: "INVENTORY",
    lines: [
      {
        id: "work-1",
        sourceLineKey: "work-1",
        lineType: "WORK",
        description: "Ремонт каркаса",
        lineComment: "",
        unit: "шт",
        quantity: 1,
        unitPrice: "100.00",
        lineTotal: "100.00",
        catalogSnapshot: null,
      },
      {
        id: "material-1",
        sourceLineKey: "material-1",
        lineType: "MATERIAL",
        description: "Доска",
        lineComment: "",
        unit: "шт",
        quantity: 2,
        unitPrice: "50.00",
        lineTotal: "100.00",
        catalogSnapshot: null,
      },
    ],
    repairCompletionMode: "AUTO",
    repairPriority: 2,
    movementRequired: true,
    repairPlans: [],
    publicationStatus: "READY",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    publicationError: null,
  }
}

const statistics: InventoryStatisticsDto = {
  durationSeconds: 3_600,
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 0,
  withWorkCount: 1,
  addedCount: 0,
  conflictCount: 0,
  workLineCount: 1,
  materialLineCount: 1,
  plannedDurationMinutes: 30,
  workTotal: "100.00",
  materialTotal: "100.00",
  grandTotal: "200.00",
  aggregates: [],
}

describe("InventoryStatistics", () => {
  it("opens the current-session positions and filters work from materials", async () => {
    const user = userEvent.setup()
    render(<InventoryStatistics statistics={statistics} findings={[finding()]} />)

    expect(screen.getByText("Длительность инвентаризации")).toBeTruthy()
    await user.click(
      screen.getByRole("button", {
        name: "Посмотреть все работы и материалы",
      })
    )

    const dialog = screen.getByRole("dialog")
    expect(within(dialog).getByText("Ремонт каркаса")).toBeTruthy()
    expect(within(dialog).getByText("Доска")).toBeTruthy()
    expect(within(dialog).getByText("Бытовка БЫТ-001")).toBeTruthy()

    await user.click(within(dialog).getByRole("radio", { name: "Работы" }))

    expect(within(dialog).getByText("Ремонт каркаса")).toBeTruthy()
    expect(within(dialog).queryByText("Доска")).toBeNull()
  })
})
