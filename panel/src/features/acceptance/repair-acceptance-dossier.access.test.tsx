import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it } from "vitest"

import { RepairAcceptanceDossier } from "@/features/acceptance/repair-acceptance-dossier"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const task: RepairTaskDto = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 4,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "PENDING",
  startedAt: "2026-07-18T08:00:00Z",
  completedAt: "2026-07-18T10:00:00Z",
  warehouseId: "00000000-0000-4000-8000-000000000002",
  rentalItemId: "00000000-0000-4000-8000-000000000003",
  cabinNumber: "БТ-42",
  actorId: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18T07:30:00Z",
  subtasks: [],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  readyAt: "2026-07-18T10:00:00Z",
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-07-18T07:00:00Z",
  updatedAt: "2026-07-18T10:00:00Z",
}

function renderDossier(canEdit: boolean, canManage: boolean) {
  return render(
    <MemoryRouter>
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: {
              queries: { retry: false },
              mutations: { retry: false },
            },
          })
        }
      >
        <RepairAcceptanceDossier
          task={task}
          mode="ACCEPTANCE"
          canEdit={canEdit}
          canManage={canManage}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(cleanup)

describe("repair acceptance command access", () => {
  it("keeps VIEW access read-only", () => {
    renderDossier(false, false)

    expect(screen.getByText("БТ-42")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Принять" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Переделать" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Списать" })).toBeNull()
  })

  it("waits for an acceptance photo for an EDIT decision without work lines", () => {
    renderDossier(true, false)

    expect(
      (screen.getByRole("button", { name: "Принять" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
    expect(screen.queryByRole("button", { name: "Переделать" })).toBeNull()
    expect(
      screen.getByText(
        "Для приёмки добавьте хотя бы одну фотографию. На доработку можно отправить без нового фото."
      )
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Списать" })).toBeNull()
  })

  it("adds cabin write-off for MANAGE access", () => {
    renderDossier(true, true)

    expect(
      (screen.getByRole("button", { name: "Принять" }) as HTMLButtonElement)
        .disabled
    ).toBe(true)
    expect(screen.queryByRole("button", { name: "Переделать" })).toBeNull()
    expect(screen.getByRole("button", { name: "Списать" })).toBeTruthy()
  })
})
