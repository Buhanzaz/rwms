import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

import { RepairReworkWizardDialog } from "./repair-rework-wizard-dialog"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const repairId = "22222222-2222-4222-8222-222222222222"
const rentalItemId = "33333333-3333-4333-8333-333333333333"
const lineageRootId = "44444444-4444-4444-8444-444444444444"

const task: RepairTaskDto = {
  id: repairId,
  version: 7,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "PENDING",
  startedAt: null,
  completedAt: "2026-08-27T08:00:00Z",
  warehouseId,
  rentalItemId,
  cabinNumber: "БЫТ-17",
  actorId: "operator-1",
  dispatchDate: "2026-08-26",
  subtasks: [],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-08-26T08:00:00Z",
  updatedAt: "2026-08-27T08:00:00Z",
}

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>
}

afterEach(cleanup)

describe("RepairReworkWizardDialog", () => {
  it("puts the fenced rework intent in the URL so refresh preserves it", async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={["/acceptance?acceptanceId=source"]}>
        <RepairReworkWizardDialog
          open
          task={task}
          canEdit
          selectedLineageRootIds={[lineageRootId]}
          onOpenChange={vi.fn()}
        />
        <LocationProbe />
      </MemoryRouter>
    )

    await user.click(screen.getByRole("button", { name: "Открыть черновик" }))

    const location = screen.getByTestId("location").textContent ?? ""
    const url = new URL(location, "https://panel.test")
    expect(url.pathname).toBe("/repairs")
    expect(url.searchParams.get("create")).toBe("1")
    expect(url.searchParams.get("reworkWarehouseId")).toBe(warehouseId)
    expect(url.searchParams.get("reworkSourceId")).toBe(repairId)
    expect(url.searchParams.get("reworkSourceVersion")).toBe("7")
    expect(url.searchParams.getAll("reworkLineage")).toEqual([
      lineageRootId,
    ])
  })
})
