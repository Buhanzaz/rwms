import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
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
  RepairEstimateCatalogPicker: () => <p>Позиции каталога</p>,
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
  ServiceOwnerPhotos: () => null,
}))

vi.mock("@/features/repair-estimates/previous-maintenance-photos", () => ({
  PreviousMaintenancePhotos: () => null,
}))

import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const rentalItemId = "22222222-2222-4222-8222-222222222222"

function renderWorkspace({ readOnly = false, onClose = vi.fn() } = {}) {
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
        estimate={null}
        readOnly={readOnly}
        initialRentalItemId={rentalItemId}
        onClose={onClose}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>
  )

  return { onClose }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairEstimateEditorWorkspace toolbar", () => {
  it("keeps editor actions next to Back and catalog arrows in the catalog footer", async () => {
    const user = userEvent.setup()
    const { onClose } = renderWorkspace()
    const toolbar = document.querySelector('[data-slot="page-toolbar"]')
    const catalog = screen.getByRole("region", { name: "Каталог" })

    expect(toolbar).not.toBeNull()
    expect(screen.getAllByRole("button", { name: "Назад" })).toHaveLength(1)
    expect(
      within(toolbar as HTMLElement).getByRole("button", { name: "Отмена" })
    ).toBeTruthy()
    expect(
      within(toolbar as HTMLElement).getByRole("button", {
        name: "Сохранить черновик",
      })
    ).toBeTruthy()
    expect(
      within(toolbar as HTMLElement).getByRole("button", { name: "Завершить" })
    ).toBeTruthy()

    expect(
      within(catalog).getByRole("button", {
        name: "Предыдущая страница каталога",
      })
    ).toBeTruthy()
    expect(
      within(catalog).getByRole("button", {
        name: "Следующая страница каталога",
      })
    ).toBeTruthy()
    expect(within(catalog).queryByRole("button", { name: "Отмена" })).toBeNull()
    expect(
      within(catalog).queryByRole("button", {
        name: "Сохранить черновик",
      })
    ).toBeNull()
    expect(
      within(catalog).queryByRole("button", { name: "Завершить" })
    ).toBeNull()

    await user.click(
      within(toolbar as HTMLElement).getByRole("button", { name: "Отмена" })
    )

    expect(onClose).toHaveBeenCalledOnce()
  })

  it("keeps the read-only Close action in the top toolbar", async () => {
    const user = userEvent.setup()
    const { onClose } = renderWorkspace({ readOnly: true })
    const toolbar = document.querySelector('[data-slot="page-toolbar"]')
    const catalog = screen.getByRole("region", { name: "Каталог" })

    expect(toolbar).not.toBeNull()
    expect(
      within(toolbar as HTMLElement).getByRole("button", { name: "Закрыть" })
    ).toBeTruthy()
    expect(
      within(toolbar as HTMLElement).queryByRole("button", {
        name: "Сохранить черновик",
      })
    ).toBeNull()
    expect(
      within(catalog).queryByRole("button", { name: "Закрыть" })
    ).toBeNull()

    await user.click(
      within(toolbar as HTMLElement).getByRole("button", { name: "Закрыть" })
    )

    expect(onClose).toHaveBeenCalledOnce()
  })
})
