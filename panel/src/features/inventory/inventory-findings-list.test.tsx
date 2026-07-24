import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { InventoryFindingsList } from "@/features/inventory/inventory-findings-list"
import {
  inventoryPublicationLabel,
  inventoryPublicationNotice,
} from "@/features/inventory/inventory-publication-presentation"
import type {
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"

function finding(
  id: string,
  publicationStatus: InventoryFindingDto["publicationStatus"],
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
  return {
    id,
    version: 1,
    rentalItemId: `rental-${id}`,
    canonicalNumber: id.toLocaleUpperCase("ru-RU"),
    cabinNumber: id,
    origin: "EXPECTED",
    inspectionStatus: "WORK_STAGED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: null,
    currentSnapshot: null,
    conflicts: [],
    comment: "",
    media: [],
    lines: [
      {
        id: `line-${id}`,
        sourceLineKey: `line-${id}`,
        lineType: "WORK",
        description: "Работа",
        lineComment: "",
        unit: "шт",
        quantity: 1,
        unitPrice: "100.00",
        lineTotal: "100.00",
        catalogSnapshot: null,
      },
    ],
    repairCompletionMode: null,
    movementRequired: false,
    repairPlans: [],
    publicationStatus,
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    publicationError: null,
    ...overrides,
  }
}

function session(
  publicationStatus: InventorySessionDto["publicationStatus"],
  findings: InventoryFindingDto[]
): InventorySessionDto {
  return {
    id: "inventory-1",
    version: 4,
    warehouseId: "spb",
    status: "COMPLETED",
    warehouse: {
      id: "spb",
      code: "СПБ",
      name: "Склад СПБ",
      timeZone: "Europe/Moscow",
    },
    author: {
      id: "manager-1",
      displayName: "Кладовщик",
      permissions: ["MANAGE"],
      authorizedWarehouseIds: null,
    },
    businessDate: "2026-07-11",
    startedAt: "2026-07-11T08:00:00.000Z",
    completedAt: "2026-07-11T10:00:00.000Z",
    findingCount: findings.length,
    inspectedCount: findings.length,
    findings,
    statistics: null,
    publicationStatus,
  }
}

describe("inventory history publication presentation", () => {
  it("renders localized per-finding statuses, task id, and publication error", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("СПБ-1", "PUBLISHED", {
            publishedRepairTaskId: "repair-task-42",
          }),
          finding("СПБ-2", "BLOCKED", {
            publicationError: "Бытовка относится к другому складу",
          }),
          finding("СПБ-3", "FAILED", {
            publicationError: "Сервис ремонтов недоступен",
          }),
        ]}
        canInspect={false}
        showPublication
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Передана").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Заблокирована").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Ошибка передачи").length).toBeGreaterThan(0)
    expect(
      screen.getAllByText("Задача: repair-task-42").length
    ).toBeGreaterThan(0)
    expect(
      screen.getAllByText("Бытовка относится к другому складу").length
    ).toBeGreaterThan(0)
    expect(
      screen.getAllByText("Сервис ремонтов недоступен").length
    ).toBeGreaterThan(0)
  })

  it("reports aggregate and toast outcomes truthfully", () => {
    const published = session("PUBLISHED", [finding("СПБ-1", "PUBLISHED")])
    const partial = session("PARTIAL", [
      finding("СПБ-1", "PUBLISHED"),
      finding("СПБ-2", "BLOCKED"),
    ])
    const failed = session("FAILED", [finding("СПБ-1", "FAILED")])
    const blocked = session("FAILED", [finding("СПБ-1", "BLOCKED")])

    expect(inventoryPublicationLabel(published)).toBe("Передана")
    expect(inventoryPublicationNotice(published)).toMatchObject({
      kind: "success",
      message: "Все работы переданы в ремонты",
    })
    expect(inventoryPublicationLabel(partial)).toBe("Передана частично")
    expect(inventoryPublicationNotice(partial)).toMatchObject({
      kind: "warning",
      message: expect.stringContaining("передано 1, заблокировано 1"),
    })
    expect(inventoryPublicationLabel(failed)).toBe("Ошибка передачи")
    expect(inventoryPublicationNotice(failed)).toMatchObject({
      kind: "error",
      message: expect.stringContaining("ошибок 1"),
    })
    expect(inventoryPublicationLabel(blocked)).toBe("Передача заблокирована")
    expect(inventoryPublicationNotice(blocked)).toMatchObject({
      kind: "warning",
      message: expect.stringContaining("заблокирована конфликтами"),
    })
  })
})
