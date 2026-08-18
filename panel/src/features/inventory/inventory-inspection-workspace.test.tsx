import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useEffect } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const catalogPicker = vi.hoisted(() => ({
  canGoBack: true,
  canGoForward: true,
  goBack: vi.fn(),
  goForward: vi.fn(),
}))

const serviceOwnerPhotos = vi.hoisted(() => ({
  props: null as { authoritativeReadyReferences?: unknown } | null,
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: (props: { authoritativeReadyReferences?: unknown }) => {
    serviceOwnerPhotos.props = props
    return null
  },
}))

vi.mock("@/features/repair-estimates/repair-estimate-catalog-picker", () => ({
  RepairEstimateCatalogPicker: ({
    readOnly,
    onPagerChange,
  }: {
    readOnly: boolean
    onPagerChange?: (
      pager: {
        canGoBack: boolean
        canGoForward: boolean
        goBack: () => void
        goForward: () => void
      } | null
    ) => void
  }) => {
    useEffect(() => {
      onPagerChange?.(
        readOnly
          ? null
          : {
              canGoBack: catalogPicker.canGoBack,
              canGoForward: catalogPicker.canGoForward,
              goBack: catalogPicker.goBack,
              goForward: catalogPicker.goForward,
            }
      )
    }, [onPagerChange, readOnly])

    return readOnly ? null : <p>Позиции каталога</p>
  },
}))

vi.mock("@/features/repair-estimates/repair-estimate-lines-editor", () => ({
  RepairEstimateLinesEditor: () => <p>Строки осмотра</p>,
}))

vi.mock("@/features/repair-estimates/repair-estimate-lines-snapshot", () => ({
  RepairEstimateLinesSnapshot: () => <p>Сохранённые строки осмотра</p>,
}))

import { InventoryInspectionWorkspace } from "@/features/inventory/inventory-inspection-workspace"

const inspectionLine: RepairEstimateLineDto = {
  id: "33333333-3333-4333-8333-333333333333",
  sourceLineKey: "inspection-work",
  lineType: "WORK",
  description: "Осмотр корпуса",
  lineComment: "",
  unit: "шт.",
  quantity: 1,
  unitPrice: "100.00",
  lineTotal: "100.00",
  catalogSnapshot: null,
}

function renderWorkspace(
  readOnly = false,
  media: Array<{ mediaId: string; generation: number }> = [],
  lines: RepairEstimateLineDto[] = []
) {
  return render(
    <InventoryInspectionWorkspace
      accessToken="inventory-token"
      warehouseId="11111111-1111-4111-8111-111111111111"
      findingId="22222222-2222-4222-8222-222222222222"
      cabinNumber="БЫТ-001"
      statusLabel="Свободна"
      tenant={null}
      businessDate="2026-07-29"
      comment=""
      passportObservation={{ presence: "ABSENT", value: null }}
      passportSnapshot={null}
      passportOptions={null}
      passportOptionsLoading={false}
      passportOptionsError={null}
      equipmentObservation={{ presence: "ABSENT", value: null }}
      lines={lines}
      media={media}
      repairCompletionMode={null}
      movementToRepair={false}
      repairPlans={[]}
      readOnly={readOnly}
      coverMediaId={null}
      onCommentChange={vi.fn()}
      onPassportObservationChange={vi.fn()}
      onEditFurniture={vi.fn()}
      onRetryPassportOptions={vi.fn()}
      onLinesChange={vi.fn()}
      onMediaChange={vi.fn()}
      onMediaReadyChange={vi.fn()}
      onCoverMediaIdChange={vi.fn()}
    />
  )
}

afterEach(() => {
  cleanup()
  catalogPicker.canGoBack = true
  catalogPicker.canGoForward = true
  serviceOwnerPhotos.props = null
  vi.clearAllMocks()
})

describe("InventoryInspectionWorkspace catalog pager", () => {
  it("keeps the capital-repair choice out of the information block", () => {
    renderWorkspace(false, [], [inspectionLine])

    expect(
      screen.queryByRole("checkbox", {
        name: "Направить на капитальный ремонт",
      })
    ).toBeNull()
  })

  it("passes only accepted finding media to the photo workspace", () => {
    const media = [
      {
        mediaId: "33333333-3333-4333-8333-333333333333",
        generation: 2,
      },
    ]

    renderWorkspace(false, media)

    expect(serviceOwnerPhotos.props).toEqual(
      expect.objectContaining({ authoritativeReadyReferences: media })
    )
  })

  it("places catalog paging controls in the inspection catalog and wires them to the picker", async () => {
    const user = userEvent.setup()

    renderWorkspace()

    const catalogCard = screen
      .getByText("Каталог")
      .closest('[data-slot="card"]') as HTMLElement
    const previous = within(catalogCard).getByRole("button", {
      name: "Предыдущая страница каталога",
    })
    const next = within(catalogCard).getByRole("button", {
      name: "Следующая страница каталога",
    })

    expect(previous.getAttribute("data-size")).toBe("icon-sm")
    expect(next.getAttribute("data-size")).toBe("icon-sm")
    expect(previous.getAttribute("data-variant")).toBe("default")
    expect(next.getAttribute("data-variant")).toBe("default")

    await user.click(previous)
    await user.click(next)

    expect(catalogPicker.goBack).toHaveBeenCalledOnce()
    expect(catalogPicker.goForward).toHaveBeenCalledOnce()
  })

  it("keeps catalog paging hidden in read-only inspection mode", () => {
    renderWorkspace(true)

    expect(
      screen.queryByRole("button", { name: "Предыдущая страница каталога" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Следующая страница каталога" })
    ).toBeNull()
  })
})
