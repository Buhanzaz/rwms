import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { InventoryFindingsList } from "@/features/inventory/inventory-findings-list"
import {
  inventoryPublicationLabel,
  inventoryPublicationNotice,
} from "@/features/inventory/inventory-publication-presentation"
import type {
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"

afterEach(cleanup)

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
    repairPriority: 3,
    movementToRepair: false,
    logisticsPlanningMode: "AUTO",
    logisticsScheduledDate: null,
    repairPlans: [],
    publicationStatus,
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    desiredAssetStatus: null,
    publicationError: null,
    ...overrides,
  }
}

function snapshot(
  id: string,
  status: NonNullable<InventoryFindingDto["currentSnapshot"]>["status"]
): NonNullable<InventoryFindingDto["currentSnapshot"]> {
  return {
    rentalItemId: `rental-${id}`,
    number: id,
    canonicalNumber: id,
    warehouseId: "spb",
    status,
    tenant: null,
    passportSnapshot: {},
    contentsSnapshot: [],
    repairsSnapshot: [],
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
    cancellation: null,
    findingCount: findings.length,
    inspectedCount: findings.length,
    findings,
    membershipMovements: [],
    statistics: null,
    publicationStatus,
    reviewStage: "FURNITURE",
    furnitureReconciliationState: "SUCCEEDED",
  }
}

describe("inventory history publication presentation", () => {
  it("keeps the desktop findings table in an opaque isolated surface", () => {
    const { container } = render(
      <InventoryFindingsList
        findings={[finding("СПБ-1", "NOT_REQUIRED")]}
        canInspect={false}
        onOpen={vi.fn()}
      />
    )

    const tableSurface = container.querySelector(
      '[data-slot="inventory-findings-table"]'
    )

    expect(tableSurface).not.toBeNull()
    expect(tableSurface?.className).toContain("bg-background")
    expect(tableSurface?.className).toContain("isolate")
    expect(tableSurface?.className).not.toContain("min-h-full")
  })

  it("shows one compact active-session status instead of inspection and reconciliation badges", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("СПБ-1", "NOT_REQUIRED", {
            inspectionStatus: "NOT_INSPECTED",
            reconciliationStatus: "MISSING",
            currentSnapshot: {
              rentalItemId: "rental-1",
              number: "СПБ-1",
              canonicalNumber: "СПБ-1",
              warehouseId: "spb",
              status: "FREE",
              tenant: null,
              passportSnapshot: {},
              contentsSnapshot: [],
              repairsSnapshot: [],
            },
          }),
        ]}
        canInspect
        onOpen={vi.fn()}
      />
    )

    const statusBadges = screen.getAllByText("Не проверено")
    expect(statusBadges.length).toBeGreaterThan(0)
    expect(statusBadges[0].className).toContain("w-fit")
    expect(statusBadges[0].className).not.toContain("w-full")
    expect(screen.queryByText("Не найдено")).toBeNull()
    expect(screen.queryByText("Ожидает сверки")).toBeNull()
    expect(screen.getAllByText("Свободна").length).toBeGreaterThan(0)
  })

  it("uses final inventory statuses in the completion view", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("СПБ-1", "NOT_REQUIRED", {
            inspectionStatus: "NOT_INSPECTED",
            reconciliationStatus: "MISSING",
          }),
          finding("СПБ-2", "NOT_REQUIRED"),
          finding("СПБ-3", "NOT_REQUIRED", {
            inspectionStatus: "READY",
            lines: [],
          }),
        ]}
        canInspect={false}
        statusMode="COMPLETION"
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Не найдено").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Направлено в ремонт").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Проверено").length).toBeGreaterThan(0)
  })

  it("shows the published repair outcome and movement instead of the rented inspection snapshot", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("230847", "PUBLISHED", {
            currentSnapshot: snapshot("230847", "RENTED"),
            desiredAssetStatus: "REPAIR",
            movementToRepair: true,
            publicationOperationKey: "publication-230847",
          }),
        ]}
        canInspect={false}
        showPublication
        statusMode="COMPLETION"
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Статус по итогу")).toHaveLength(2)
    expect(screen.getAllByText("В ремонте")).toHaveLength(2)
    expect(screen.getAllByText("Перемещение")).toHaveLength(2)
    expect(screen.queryByText("Аренда")).toBeNull()
  })

  it("maps published free and capital outcomes from owner truth", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("СПБ-1", "PUBLISHED", {
            currentSnapshot: snapshot("СПБ-1", "RENTED"),
            desiredAssetStatus: "FREE",
            publicationOperationKey: "publication-free",
          }),
          finding("СПБ-2", "PUBLISHED", {
            currentSnapshot: snapshot("СПБ-2", "RENTED"),
            desiredAssetStatus: "CAPITAL_REPAIR",
            publicationOperationKey: "publication-capital",
          }),
        ]}
        canInspect={false}
        statusMode="COMPLETION"
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Свободна")).toHaveLength(2)
    expect(screen.getAllByText("Капремонт")).toHaveLength(2)
    expect(screen.queryByText("Аренда")).toBeNull()
  })

  it("does not present a pending or failed operation as an applied status", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("СПБ-1", "READY", {
            currentSnapshot: snapshot("СПБ-1", "RENTED"),
            desiredAssetStatus: "REPAIR",
            publicationOperationKey: "publication-pending",
          }),
          finding("СПБ-2", "FAILED", {
            currentSnapshot: snapshot("СПБ-2", "RENTED"),
            desiredAssetStatus: "CAPITAL_REPAIR",
            publicationOperationKey: "publication-failed",
          }),
        ]}
        canInspect={false}
        showPublication
        statusMode="COMPLETION"
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Не применён")).toHaveLength(4)
    expect(screen.queryByText("Аренда")).toBeNull()
    expect(screen.queryByText("В ремонте")).toBeNull()
    expect(screen.queryByText("Капремонт")).toBeNull()
  })

  it("keeps the active view based on the inspection snapshot", () => {
    render(
      <InventoryFindingsList
        findings={[
          finding("230847", "PUBLISHED", {
            currentSnapshot: snapshot("230847", "RENTED"),
            desiredAssetStatus: "REPAIR",
            movementToRepair: true,
            publicationOperationKey: "publication-230847",
          }),
        ]}
        canInspect={false}
        onOpen={vi.fn()}
      />
    )

    expect(screen.getAllByText("Текущий статус")).toHaveLength(2)
    expect(screen.getAllByText("Аренда")).toHaveLength(2)
    expect(screen.queryByText("В ремонте")).toBeNull()
    expect(screen.queryByText("Перемещение")).toBeNull()
  })

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
      screen.getAllByText("Задача на ремонт создана").length
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
