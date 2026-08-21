import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it } from "vitest"

import { InventoryStatistics } from "@/features/inventory/inventory-statistics"
import type {
  InventoryFindingDto,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"

function finding(
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
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
    passportObservation: { presence: "ABSENT", value: null },
    equipmentObservation: { presence: "ABSENT", value: null },
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
    movementToRepair: true,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    repairPlans: [],
    publicationStatus: "READY",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    desiredAssetStatus: null,
    publicationError: null,
    ...overrides,
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

afterEach(cleanup)

describe("InventoryStatistics", () => {
  it("counts a repair delivery and later removal as one movement", () => {
    render(
      <InventoryStatistics
        statistics={statistics}
        showCounters={false}
        findings={[
          finding({
            movementToRepair: true,
            repairPlans: [],
          }),
        ]}
      />
    )

    const movementCard = screen
      .getByText("Перемещения на ремонт и вывозы")
      .closest('[data-slot="card"]')
    if (!(movementCard instanceof HTMLElement)) {
      throw new Error("Не найдена карточка перемещений")
    }
    expect(within(movementCard).getByText("1")).toBeTruthy()
    expect(
      within(movementCard).getByText(
        "1 перемещение = доставка в ремонт и вывоз"
      )
    ).toBeTruthy()
    expect(screen.queryByText("Ожидалось: 1")).toBeNull()
  })

  it("opens the current-session positions and filters work from materials", async () => {
    const user = userEvent.setup()
    render(
      <InventoryStatistics statistics={statistics} findings={[finding()]} />
    )

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

  it("shows the grand total in the aggregate table footer", () => {
    render(
      <InventoryStatistics
        statistics={{
          ...statistics,
          aggregates: [
            {
              key: "work-1",
              lineType: "WORK",
              description: "Ремонт каркаса",
              catalogNodeId: "catalog-work-1",
              unit: "шт",
              unitPrice: "100.00",
              quantity: 2,
              total: "200.00",
            },
          ],
        }}
        findings={[finding()]}
      />
    )

    const totalLabel = screen.getByText("Итого", { selector: "td" })
    const footer = totalLabel.closest("tfoot")
    expect(footer).not.toBeNull()
    expect(within(footer!).getByText("200,00 ₽")).toBeTruthy()
  })

  it("renders separate work and material tables when finishing inventory", () => {
    render(
      <InventoryStatistics
        statistics={{
          ...statistics,
          aggregates: [
            {
              key: "work-1",
              lineType: "WORK",
              description: "Ремонт каркаса",
              catalogNodeId: "catalog-work-1",
              unit: "шт",
              unitPrice: "100.00",
              quantity: 1,
              total: "100.00",
            },
            {
              key: "material-1",
              lineType: "MATERIAL",
              description: "Доска",
              catalogNodeId: "catalog-material-1",
              unit: "шт",
              unitPrice: "50.00",
              quantity: 2,
              total: "100.00",
            },
          ],
        }}
        findings={[finding()]}
        separateAggregateTables
      />
    )

    const workTable = screen.getByRole("table", { name: "Итоги работ" })
    const materialTable = screen.getByRole("table", {
      name: "Итоги материалов",
    })

    expect(within(workTable).getByText("Ремонт каркаса")).toBeTruthy()
    expect(within(workTable).queryByText("Доска")).toBeNull()
    expect(within(materialTable).getByText("Доска")).toBeTruthy()
    expect(within(materialTable).queryByText("Ремонт каркаса")).toBeNull()
    const workFooter = workTable.querySelector("tfoot")
    const materialFooter = materialTable.querySelector("tfoot")
    expect(workFooter).not.toBeNull()
    expect(materialFooter).not.toBeNull()
    expect(within(workFooter!).getByText("100,00 ₽")).toBeTruthy()
    expect(within(materialFooter!).getByText("100,00 ₽")).toBeTruthy()
  })

  it("uses the saved finding line name for a catalog aggregate", () => {
    render(
      <InventoryStatistics
        statistics={{
          ...statistics,
          aggregates: [
            {
              key: "material-1",
              lineType: "MATERIAL",
              description: "Позиция без наименования",
              catalogNodeId: "catalog-material-1",
              unit: "шт",
              unitPrice: "50.00",
              quantity: 2,
              total: "100.00",
            },
          ],
        }}
        findings={[
          finding({
            lines: [
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
                catalogSnapshot: {
                  nodeId: "catalog-material-1",
                  name: "Доска",
                  nodeType: "MATERIAL",
                  furnitureEquipment: null,
                  characteristic: null,
                },
              },
            ],
          }),
        ]}
      />
    )

    expect(screen.getByText("Доска", { selector: "td" })).toBeTruthy()
    expect(screen.queryByText("Позиция без наименования")).toBeNull()
  })
})
