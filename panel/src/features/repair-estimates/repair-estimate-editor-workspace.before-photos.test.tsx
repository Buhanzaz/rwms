import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  REPAIR_ESTIMATES_QUERY_KEY: ["repair-estimates"],
  completeRepairEstimate: vi.fn(),
  repairEstimateDetailQueryKey: (
    warehouseId: string,
    estimateId: string | null
  ) => ["repair-estimates", "detail", warehouseId, estimateId],
  saveRepairEstimateDraft: vi.fn(),
}))

vi.mock("@/features/repair-estimates/repair-estimate-catalog-picker", () => ({
  RepairEstimateCatalogPicker: () => null,
}))

vi.mock("@/features/repair-estimates/repair-estimate-lines-editor", () => ({
  RepairEstimateLinesEditor: () => <p>Строки сметы</p>,
}))

vi.mock("@/features/repair-estimates/repair-work-information-fields", () => ({
  RepairWorkInformationFields: () => null,
}))

vi.mock(
  "@/features/repair-estimates/repair-estimate-completion-dialog",
  () => ({
    RepairEstimateCompletionDialog: () => null,
  })
)

vi.mock(
  "@/features/repair-estimates/repair-estimate-completed-workspace",
  () => ({
    RepairEstimateCompletedWorkspace: () => null,
  })
)

vi.mock("@/features/rental-items/cabin-furniture-panel", () => ({
  CabinFurniturePanel: () => null,
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({ toolbarAction }: { toolbarAction?: ReactNode }) => (
    <section>{toolbarAction}</section>
  ),
}))

vi.mock("@/features/repair-estimates/previous-maintenance-photos", () => ({
  PreviousMaintenancePhotos: () => <p>Фотографии до</p>,
}))

import type { RepairEstimateDto } from "@/features/repair-estimates/model/repair-estimate"
import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const rentalItemId = "22222222-2222-4222-8222-222222222222"

const estimate: RepairEstimateDto = {
  id: "33333333-3333-4333-8333-333333333333",
  version: 1,
  status: "DRAFT",
  warehouseId,
  rentalItemId,
  cabinNumber: "БЫТ-001",
  authorName: "Кладовщик",
  sourceParty: "Возврат",
  dispatchDate: "2026-07-27",
  comment: "",
  totalAmount: "0.00",
  lines: [],
  media: [],
  maintenanceMediaReferences: [],
  completionMode: null,
  movementToRepair: null,
  taskPlans: [],
  createdAt: "2026-07-27T10:00:00Z",
  updatedAt: "2026-07-27T10:00:00Z",
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairEstimateEditorWorkspace previous photos", () => {
  it("replaces estimate lines with previous photos and restores them", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RepairEstimateEditorWorkspace
          accessToken="maintenance-token"
          warehouseId={warehouseId}
          estimate={estimate}
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />
      </QueryClientProvider>
    )

    expect(screen.getByText("Строки сметы")).toBeTruthy()
    expect(screen.queryByText("Фотографии до")).toBeNull()

    await user.click(screen.getByRole("button", { name: "Показать до" }))

    expect(screen.queryByText("Строки сметы")).toBeNull()
    expect(screen.getByText("Фотографии до")).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "Скрыть до" }))

    expect(screen.getByText("Строки сметы")).toBeTruthy()
    expect(screen.queryByText("Фотографии до")).toBeNull()
  })
})
