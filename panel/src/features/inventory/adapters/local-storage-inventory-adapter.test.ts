import { beforeEach, describe, expect, it } from "vitest"
import {
  INVENTORY_STORAGE_KEY,
  LocalStorageInventoryAdapter,
} from "@/features/inventory/adapters/local-storage-inventory-adapter"
import type {
  InventoryStartCommand,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"

const command: InventoryStartCommand = {
  warehouse: {
    id: "spb",
    code: "СПБ",
    name: "Склад СПБ",
    timeZone: "Europe/Moscow",
  },
  actor: {
    id: "u-1",
    displayName: "Тест",
    permissions: ["MANAGE"],
    authorizedWarehouseIds: null,
  },
  businessDate: "2026-07-11",
  expectedItems: [
    {
      rentalItemId: "r-1",
      number: "БЫТ-1",
      canonicalNumber: "БЫТ-1",
      warehouseId: "spb",
      status: "FREE",
      tenant: null,
    },
  ],
}

const emptyStatistics: InventoryStatisticsDto = {
  durationSeconds: 1,
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 1,
  withWorkCount: 0,
  addedCount: 0,
  conflictCount: 0,
  workLineCount: 0,
  materialLineCount: 0,
  plannedDurationMinutes: 0,
  workTotal: "0.00",
  materialTotal: "0.00",
  grandTotal: "0.00",
  aggregates: [],
}

describe("LocalStorageInventoryAdapter", () => {
  beforeEach(() => window.localStorage.clear())

  it("returns one active session when two starts race", async () => {
    const first = new LocalStorageInventoryAdapter()
    const second = new LocalStorageInventoryAdapter()
    const [a, b] = await Promise.all([
      first.start(command),
      second.start(command),
    ])
    expect(a.id).toBe(b.id)
    expect(await first.list("spb")).toHaveLength(1)
  })

  it("rejects stale versions and completed-session edits", async () => {
    const adapter = new LocalStorageInventoryAdapter()
    const session = await adapter.start(command)
    const finding = session.findings[0]!
    const inspection = {
      inventoryId: session.id,
      expectedVersion: session.version,
      actor: command.actor,
      findingId: finding.id,
      comment: "Проверено",
      media: [],
      lines: [],
      repairPlans: [],
      currentSnapshot: finding.currentSnapshot,
      conflicts: [],
    }
    const saved = await adapter.saveFinding(inspection)
    await expect(adapter.saveFinding(inspection)).rejects.toThrow(
      "была изменена"
    )
    const completed = await adapter.complete({
      inventoryId: saved.id,
      expectedVersion: saved.version,
      actor: command.actor,
      findings: saved.findings,
      statistics: emptyStatistics,
    })
    await expect(
      adapter.saveFinding({ ...inspection, expectedVersion: completed.version })
    ).rejects.toThrow("Завершённую")
  })

  it("treats corrupt and old array envelopes as normalized empty state", async () => {
    window.localStorage.setItem(INVENTORY_STORAGE_KEY, "{broken")
    expect(await new LocalStorageInventoryAdapter().list("spb")).toEqual([])
    window.localStorage.setItem(INVENTORY_STORAGE_KEY, "[]")
    expect(await new LocalStorageInventoryAdapter().list("spb")).toEqual([])
  })
})
