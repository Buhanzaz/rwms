import { beforeEach, describe, expect, it } from "vitest"

import {
  EQUIPMENT_DISPOSITIONS_STORAGE_KEY,
  EQUIPMENT_MOCK_STORAGE_KEY,
  getEquipmentItems,
  getUnresolvedReturnEquipmentDispositionCases,
  hasUnresolvedReturnEquipmentDispositionForRentalItem,
  moveRentalItemEquipmentToStock,
  prepareReturnEquipmentDispositionReconciliation,
  reconcileReturnEquipmentDispositionCases,
  resolveReturnEquipmentDisposition,
} from "@/api/equipment-api"
import {
  addInventoryFromWarehouseStock,
  transferInventoryFromRentalItem,
} from "@/api/rental-item-inventory-api"
import {
  getRentalItemsForContentsMove,
  moveRentalItemContentsToRentalItem,
  readRentalItems,
  runRentalItemMutation,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"

function seedCabins(equipmentName = "Стул") {
  const items = readRentalItems()
  const warehouseItems = items.filter((item) => item.warehouseId === "spb")
  const source = {
    ...warehouseItems[0],
    id: "return-source",
    number: "БЫТ-ВОЗВРАТ",
    status: "AFTER_RENT" as const,
    contentsItems: [{ name: equipmentName, quantity: 4 }],
  }
  const target = {
    ...warehouseItems[1],
    id: "return-target",
    number: "БЫТ-ЦЕЛЬ",
    status: "FREE" as const,
    contentsItems: [{ name: equipmentName, quantity: 1 }],
  }
  writeRentalItems([
    ...items.filter(
      (item) =>
        item.id !== warehouseItems[0].id && item.id !== warehouseItems[1].id
    ),
    source,
    target,
  ])
  return { source, target }
}

async function register(equipmentName = "Стул") {
  return (
    await reconcileReturnEquipmentDispositionCases({
      warehouseId: "spb",
      returnReceiptId: "receipt-1",
      returnItemId: "return-item-1",
      sourceRentalItemId: "return-source",
      sourceCabinNumber: "БЫТ-ВОЗВРАТ",
      receivedAt: "2026-07-11T10:00:00.000Z",
      contents: [{ name: equipmentName, quantity: 4 }],
    })
  )[0]
}

beforeEach(() => {
  window.localStorage.clear()
})

describe("return equipment disposition ledger", () => {
  it("registers deterministically, resolves partially and replays idempotently", async () => {
    seedCabins()
    const first = await register()
    const repeated = await register()

    expect(repeated.id).toBe(first.id)
    expect(repeated.version).toBe(1)

    const resolved = await resolveReturnEquipmentDisposition({
      caseId: first.id,
      expectedVersion: 1,
      idempotencyKey: "stock-attempt-1",
      action: "RETURN_TO_STOCK",
      quantity: 2,
      createdBy: "Проверяющий",
    })
    const replay = await resolveReturnEquipmentDisposition({
      caseId: first.id,
      expectedVersion: 1,
      idempotencyKey: "stock-attempt-1",
      action: "RETURN_TO_STOCK",
      quantity: 2,
      createdBy: "Проверяющий",
    })

    expect(resolved.status).toBe("PARTIALLY_RESOLVED")
    expect(resolved.remainingQuantity).toBe(2)
    expect(replay).toEqual(resolved)
    expect(
      readRentalItems().find((item) => item.id === "return-source")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 2 }])
    const stock = await getEquipmentItems({ warehouseId: "spb" })
    expect(stock.find((item) => item.name === "Стул")?.stockQuantity).toBe(2)
  })

  it("rejects stale versions and quantities larger than the remainder", async () => {
    seedCabins()
    const disposition = await register()

    await expect(
      resolveReturnEquipmentDisposition({
        caseId: disposition.id,
        expectedVersion: 99,
        idempotencyKey: "stale",
        action: "WRITE_OFF",
        quantity: 1,
        reason: "Повреждено",
        createdBy: "Проверяющий",
      })
    ).rejects.toThrow("другой вкладке")
    await expect(
      resolveReturnEquipmentDisposition({
        caseId: disposition.id,
        expectedVersion: 1,
        idempotencyKey: "too-much",
        action: "RETURN_TO_STOCK",
        quantity: 5,
        createdBy: "Проверяющий",
      })
    ).rejects.toThrow("от 1 до 4")
  })

  it("writes off with an audit reason and preserves conservation", async () => {
    seedCabins()
    const disposition = await register()
    const before = await getEquipmentItems({ warehouseId: "spb" })
    const beforeWrittenOff =
      before.find((item) => item.name === "Стул")?.writtenOffQuantity ?? 0

    await expect(
      resolveReturnEquipmentDisposition({
        caseId: disposition.id,
        expectedVersion: 1,
        idempotencyKey: "write-off-no-reason",
        action: "WRITE_OFF",
        quantity: 1,
        createdBy: "Проверяющий",
      })
    ).rejects.toThrow("причину")
    const saved = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "write-off-1",
      action: "WRITE_OFF",
      quantity: 1,
      reason: "Сломан при возврате",
      createdBy: "Проверяющий",
    })

    expect(saved.remainingQuantity).toBe(3)
    expect(saved.resolutions[0]).toMatchObject({
      action: "WRITE_OFF",
      quantity: 1,
      reason: "Сломан при возврате",
      createdBy: "Проверяющий",
    })
    const after = await getEquipmentItems({ warehouseId: "spb" })
    expect(after.find((item) => item.name === "Стул")?.writtenOffQuantity).toBe(
      beforeWrittenOff + 1
    )
  })

  it("transfers only to another eligible cabin in the same warehouse", async () => {
    seedCabins()
    const disposition = await register()
    const saved = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "transfer-1",
      action: "TRANSFER_TO_CABIN",
      quantity: 3,
      targetRentalItemId: "return-target",
      createdBy: "Проверяющий",
    })

    expect(saved.remainingQuantity).toBe(1)
    expect(
      readRentalItems().find((item) => item.id === "return-source")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 1 }])
    expect(
      readRentalItems().find((item) => item.id === "return-target")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 4 }])
    expect(
      await getUnresolvedReturnEquipmentDispositionCases({
        sourceRentalItemId: "return-source",
      })
    ).toHaveLength(1)
  })

  it("requires explicit confirmation before creating a new stock position", async () => {
    seedCabins("Редкая тумба")
    const disposition = await register("Редкая тумба")

    await expect(
      resolveReturnEquipmentDisposition({
        caseId: disposition.id,
        expectedVersion: 1,
        idempotencyKey: "new-master-1",
        action: "RETURN_TO_STOCK",
        quantity: 4,
        createdBy: "Проверяющий",
      })
    ).rejects.toThrow("Подтвердите создание")
    const saved = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "new-master-2",
      action: "RETURN_TO_STOCK",
      quantity: 4,
      createdBy: "Проверяющий",
      confirmCreateMasterItem: true,
    })

    expect(saved.status).toBe("RESOLVED")
    const stock = await getEquipmentItems({ warehouseId: "spb" })
    expect(
      stock.find((item) => item.name === "Редкая тумба")?.stockQuantity
    ).toBe(4)
  })

  it("recovers only touched entities and preserves unrelated concurrent edits", async () => {
    const { target } = seedCabins()
    const disposition = await register()
    const beforeRentals = readRentalItems()
    const beforeMaster = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY)!
    ) as Array<{ id: string; stockQuantity: number }>
    const beforeState = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_DISPOSITIONS_STORAGE_KEY)!
    ) as {
      revision: number
      cases: Array<{ id: string; version: number; remainingQuantity: number }>
    }

    const resolved = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "crash-attempt",
      action: "RETURN_TO_STOCK",
      quantity: 2,
      createdBy: "Проверяющий",
    })
    const afterRentals = readRentalItems()
    const afterMaster = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY)!
    ) as Array<{ id: string; stockQuantity: number }>
    const afterState = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_DISPOSITIONS_STORAGE_KEY)!
    ) as {
      revision: number
      cases: Array<{ id: string; version: number; remainingQuantity: number }>
    }
    const beforeSource = beforeRentals.find(
      (item) => item.id === "return-source"
    )!
    const afterSource = afterRentals.find(
      (item) => item.id === "return-source"
    )!
    const beforeChair = beforeMaster.find((item) => item.id === "spb-chair")!
    const afterChair = afterMaster.find((item) => item.id === "spb-chair")!
    const beforeCase = beforeState.cases.find(
      (item) => item.id === disposition.id
    )!
    const afterCase = afterState.cases.find(
      (item) => item.id === disposition.id
    )!

    writeRentalItems(
      afterRentals.map((item) =>
        item.id === target.id ? { ...item, comment: "Чужое изменение" } : item
      )
    )
    window.localStorage.setItem(
      EQUIPMENT_MOCK_STORAGE_KEY,
      JSON.stringify(
        afterMaster.map((item) =>
          item.id === "spb-table"
            ? { ...item, stockQuantity: item.stockQuantity + 7 }
            : item
        )
      )
    )
    window.localStorage.setItem(
      EQUIPMENT_DISPOSITIONS_STORAGE_KEY,
      JSON.stringify({
        ...afterState,
        revision: afterState.revision + 1,
        cases: [
          ...afterState.cases,
          { ...afterCase, id: "unrelated-case", version: 5 },
        ],
      })
    )
    window.localStorage.setItem(
      "wms:mock-return-equipment-disposition-journal",
      JSON.stringify({
        id: "crash-attempt",
        state: "PREPARED",
        rentalItems: [
          { id: "return-source", before: beforeSource, after: afterSource },
        ],
        masterItems: [
          { id: "spb-chair", before: beforeChair, after: afterChair },
        ],
        dispositionCases: [
          { id: disposition.id, before: beforeCase, after: afterCase },
        ],
      })
    )

    expect(
      hasUnresolvedReturnEquipmentDispositionForRentalItem("return-source")
    ).toBe(true)
    expect(
      readRentalItems().find((item) => item.id === "return-source")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 4 }])
    expect(
      readRentalItems().find((item) => item.id === target.id)?.comment
    ).toBe("Чужое изменение")
    const recoveredMaster = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY)!
    ) as Array<{ id: string; stockQuantity: number }>
    expect(recoveredMaster.find((item) => item.id === "spb-chair")).toEqual(
      beforeChair
    )
    expect(
      recoveredMaster.find((item) => item.id === "spb-table")?.stockQuantity
    ).toBe(
      (afterMaster.find((item) => item.id === "spb-table")?.stockQuantity ??
        0) + 7
    )
    expect(
      window.localStorage.getItem(
        "wms:mock-return-equipment-disposition-journal"
      )
    ).toBeNull()
    const recoveredState = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_DISPOSITIONS_STORAGE_KEY)!
    ) as { cases: Array<{ id: string; version: number }> }
    expect(
      recoveredState.cases.find((item) => item.id === "unrelated-case")?.version
    ).toBe(5)

    const replay = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "crash-attempt",
      action: "RETURN_TO_STOCK",
      quantity: 2,
      createdBy: "Проверяющий",
    })
    expect(replay.remainingQuantity).toBe(2)
    expect(replay.resolutions).toHaveLength(1)
    expect(resolved.remainingQuantity).toBe(2)
  })

  it("refreshes metadata after a partial resolution without changing history", async () => {
    seedCabins()
    const disposition = await register()
    const partial = await resolveReturnEquipmentDisposition({
      caseId: disposition.id,
      expectedVersion: 1,
      idempotencyKey: "metadata-partial",
      action: "RETURN_TO_STOCK",
      quantity: 2,
      createdBy: "Проверяющий",
    })

    const [updated] = await reconcileReturnEquipmentDispositionCases({
      warehouseId: "spb",
      returnReceiptId: "receipt-1",
      returnItemId: "return-item-1",
      sourceRentalItemId: "return-source",
      sourceCabinNumber: "БЫТ-ВОЗВРАТ",
      receivedAt: "2026-07-12T10:00:00.000Z",
      contents: [{ name: "Стул", quantity: 2 }],
    })

    expect(updated.id).toBe(disposition.id)
    expect(updated.version).toBe(partial.version + 1)
    expect(updated.receivedQuantity).toBe(4)
    expect(updated.remainingQuantity).toBe(2)
    expect(updated.resolutions).toEqual(partial.resolutions)
    expect(updated.receivedAt).toBe("2026-07-12T10:00:00.000Z")
  })

  it("blocks generic stock movement while the return case is unresolved", async () => {
    seedCabins()
    await register()

    await expect(
      moveRentalItemEquipmentToStock("return-source", [
        { name: "Стул", quantity: 1 },
      ])
    ).rejects.toThrow("ожидает решения")
    expect(
      readRentalItems().find((item) => item.id === "return-source")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 4 }])
  })

  it("rechecks quarantine after waiting for the shared rental lock", async () => {
    seedCabins()
    let releaseRegistration!: () => void
    let registrationPrepared!: () => void
    const release = new Promise<void>((resolve) => {
      releaseRegistration = resolve
    })
    const prepared = new Promise<void>((resolve) => {
      registrationPrepared = resolve
    })
    const registration = runRentalItemMutation(async () => {
      const reconciliation = prepareReturnEquipmentDispositionReconciliation({
        upserts: [
          {
            warehouseId: "spb",
            returnReceiptId: "race-receipt",
            returnItemId: "race-return-item",
            sourceRentalItemId: "return-source",
            sourceCabinNumber: "БЫТ-ВОЗВРАТ",
            receivedAt: "2026-07-11T10:00:00.000Z",
            contents: [{ name: "Стул", quantity: 4 }],
          },
        ],
      })
      registrationPrepared()
      await release
      reconciliation.commit()
    })
    await prepared
    const movement = moveRentalItemEquipmentToStock("return-source", [
      { name: "Стул", quantity: 1 },
    ])
    releaseRegistration()
    await registration

    await expect(movement).rejects.toThrow("ожидает решения")
    expect(
      readRentalItems().find((item) => item.id === "return-source")
        ?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 4 }])
  })

  it("blocks direct source and target transfers at the rental API boundary", async () => {
    seedCabins()
    await register()

    await expect(
      moveRentalItemContentsToRentalItem({
        sourceRentalItemId: "return-source",
        targetRentalItemId: "return-target",
        payload: [{ name: "Стул", quantity: 1 }],
      })
    ).rejects.toThrow("ожидает решения")
    await expect(
      transferInventoryFromRentalItem({
        targetRentalItemId: "return-target",
        payload: {
          sourceRentalItemId: "return-source",
          items: [{ name: "Стул", quantity: 1 }],
        },
      })
    ).rejects.toThrow("ожидает решения")
    const candidates = await getRentalItemsForContentsMove({
      warehouseId: "spb",
      sourceRentalItemId: "return-target",
    })
    expect(candidates.some((item) => item.id === "return-source")).toBe(false)
  })

  it("blocks warehouse stock additions to a quarantined target", async () => {
    seedCabins()
    await reconcileReturnEquipmentDispositionCases({
      warehouseId: "spb",
      returnReceiptId: "receipt-target",
      returnItemId: "return-item-target",
      sourceRentalItemId: "return-target",
      sourceCabinNumber: "БЫТ-ЦЕЛЬ",
      receivedAt: "2026-07-11T10:00:00.000Z",
      contents: [{ name: "Стул", quantity: 1 }],
    })

    await expect(
      addInventoryFromWarehouseStock({
        targetRentalItemId: "return-target",
        payload: { items: [{ name: "Кровать", quantity: 1 }] },
      })
    ).rejects.toThrow("ожидает решения")
  })
})
