import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const estimatesApi = vi.hoisted(() => ({
  completeRepairEstimate: vi.fn(),
  saveRepairEstimateDraft: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  REPAIR_ESTIMATES_QUERY_KEY: ["repair-estimates"],
  completeRepairEstimate: estimatesApi.completeRepairEstimate,
  repairEstimateDetailQueryKey: (
    warehouseId: string,
    estimateId: string | null
  ) => ["repair-estimates", "detail", warehouseId, estimateId],
  saveRepairEstimateDraft: estimatesApi.saveRepairEstimateDraft,
}))

vi.mock("@/features/repair-estimates/repair-estimate-workspace-layout", () => ({
  RepairEstimateWorkspaceLayout: ({
    controls,
    message,
  }: {
    controls: ReactNode
    message?: ReactNode
  }) => (
    <section>
      {message}
      <section aria-label="Каталог">{controls}</section>
    </section>
  ),
}))

vi.mock("@/features/repair-estimates/repair-estimate-catalog-picker", () => ({
  RepairEstimateCatalogPicker: () => null,
}))
vi.mock("@/features/repair-estimates/repair-estimate-lines-editor", () => ({
  RepairEstimateLinesEditor: () => null,
}))
vi.mock("@/features/repair-estimates/repair-work-information-fields", () => ({
  RepairWorkInformationFields: () => null,
}))
vi.mock(
  "@/features/repair-estimates/repair-estimate-completion-dialog",
  () => ({
    RepairEstimateCompletionDialog: ({
      open,
      onComplete,
    }: {
      open: boolean
      onComplete: (input: {
        completionMode: "AUTO"
        movementToRepair: boolean
        logisticsPlanningMode: "AUTO"
        logisticsScheduledDate: null
        taskPlans: []
        priority: 3
      }) => void
    }) =>
      open ? (
        <button
          type="button"
          onClick={() =>
            onComplete({
              completionMode: "AUTO",
              movementToRepair: false,
              logisticsPlanningMode: "AUTO",
              logisticsScheduledDate: null,
              taskPlans: [],
              priority: 3,
            })
          }
        >
          Подтвердить завершение
        </button>
      ) : null,
  })
)
vi.mock(
  "@/features/repair-estimates/repair-estimate-completed-workspace",
  () => ({ RepairEstimateCompletedWorkspace: () => null })
)
vi.mock("@/features/rental-items/cabin-furniture-panel", () => ({
  CabinFurniturePanel: () => null,
}))
vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: () => null,
}))
vi.mock("@/features/repair-estimates/previous-maintenance-photos", () => ({
  PreviousMaintenancePhotos: () => null,
}))

import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"
import { MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED } from "@/features/repair-estimates/api/unaccounted-furniture-confirmation"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const rentalItemId = "22222222-2222-4222-8222-222222222222"

function renderWorkspace() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RepairEstimateEditorWorkspace
        accessToken="maintenance-token"
        warehouseId={warehouseId}
        estimate={null}
        initialRentalItemId={rentalItemId}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  estimatesApi.completeRepairEstimate.mockReset()
  estimatesApi.saveRepairEstimateDraft.mockReset()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairEstimateEditorWorkspace unaccounted furniture", () => {
  it("asks for explicit confirmation and retries the same completion without warehouse accounting", async () => {
    const user = userEvent.setup()
    estimatesApi.completeRepairEstimate
      .mockRejectedValueOnce(
        new ApiError(
          "Подтвердите выполнение без учёта допоборудования.",
          422,
          MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED
        )
      )
      .mockResolvedValueOnce({ id: "estimate-id" })
    renderWorkspace()

    await user.click(screen.getByRole("button", { name: "Завершить" }))
    await user.click(
      screen.getByRole("button", { name: "Подтвердить завершение" })
    )

    expect(
      await screen.findByRole("dialog", {
        name: "Выполнить без учёта допоборудования?",
      })
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Выполнить без учёта склада" })
    )

    await waitFor(() =>
      expect(estimatesApi.completeRepairEstimate).toHaveBeenCalledTimes(2)
    )
    expect(
      estimatesApi.completeRepairEstimate.mock.calls[0]?.[0]
    ).toMatchObject({
      warehouseId,
      draft: expect.objectContaining({ rentalItemId }),
      allowUnaccountedFurniture: false,
    })
    expect(
      estimatesApi.completeRepairEstimate.mock.calls[1]?.[0]
    ).toMatchObject({
      warehouseId,
      draft: expect.objectContaining({ rentalItemId }),
      allowUnaccountedFurniture: true,
    })
  })
})
