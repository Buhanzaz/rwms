import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  LOGISTICS_STORAGE_KEY,
  acceptReturnUndamaged,
  claimReturnForEstimate,
  createImportedReturnIntake,
  createReturnReplacementEstimateLines,
  getImportedReturnIntakePreview,
  createReturnReceipt,
  finalizeShipment,
  isReturnReceiptMembershipEditable,
  markReturnEstimateCreated,
  listShipmentCandidates,
  listReturnReceipts,
  resolveReturnFurnitureDisposition,
  resumeImportedReturnConflict,
  updateReturnReceipt,
} from "@/features/logistics/api/logistics-api"
import {
  readRentalItems,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import {
  EQUIPMENT_MOCK_STORAGE_KEY,
  getUnresolvedReturnEquipmentDispositionCases,
  getEquipmentItems,
  prepareReturnEquipmentDispositionReconciliation,
  resolveReturnEquipmentDisposition,
} from "@/api/equipment-api"
import * as contentsTransferApi from "@/features/rental-items/contents-transfer/api/contents-transfer-api"
import { HttpContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/http-contents-transfer-task-client"
import { WAREHOUSE_TRANSFERS_STORAGE_KEY } from "@/features/logistics/warehouse-transfers/adapters/local-storage-warehouse-transfer-store"

const warehouseId = "spb"

function rentedCabins() {
  return readRentalItems().filter(
    (item) => item.warehouseId === warehouseId && item.status === "RENTED"
  )
}

describe("rental return receipt editing", () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it("adds and removes pristine cabins atomically and rejects a stale version", async () => {
    const [first, second] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: first.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })

    const added = await updateReturnReceipt({
      warehouseId,
      receiptId: created.id,
      expectedVersion: created.version,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [second.id, second.id],
      fromParty: first.tenant!,
      returnDate: "2026-07-12",
      updatedBy: "Редактор",
    })
    expect(added.version).toBe(2)
    expect(added.items).toHaveLength(2)
    expect(added.updatedBy).toBe("Редактор")
    expect(
      readRentalItems()
        .filter((item) => [first.id, second.id].includes(item.id))
        .every((item) => item.status === "AFTER_RENT")
    ).toBe(true)

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: created.id,
        expectedVersion: created.version,
        clientRentalItemIds: [first.id],
        manualRentalItemIds: [second.id],
        fromParty: first.tenant!,
        returnDate: "2026-07-12",
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("Возврат изменился")

    const removed = await updateReturnReceipt({
      warehouseId,
      receiptId: added.id,
      expectedVersion: added.version,
      clientRentalItemIds: [],
      manualRentalItemIds: [second.id],
      fromParty: "Новый отправитель",
      returnDate: "2026-07-13",
      updatedBy: "Редактор",
    })
    expect(removed.items).toHaveLength(1)
    expect(removed.items[0]).toMatchObject({
      rentalItemId: second.id,
      selectionSource: "MANUAL",
      originalTenant: second.tenant,
    })
    expect(readRentalItems().find((item) => item.id === first.id)?.status).toBe(
      "RENTED"
    )
    expect(
      readRentalItems().find((item) => item.id === second.id)?.status
    ).toBe("AFTER_RENT")
  })

  it("preserves source audit when header metadata changes", async () => {
    const [clientCabin, manualCabin] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [clientCabin.id],
      manualRentalItemIds: [manualCabin.id],
      fromParty: clientCabin.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    const originalAudit = created.items.map((item) => ({
      rentalItemId: item.rentalItemId,
      selectionSource: item.selectionSource,
      originalTenant: item.originalTenant,
    }))

    const saved = await updateReturnReceipt({
      warehouseId,
      receiptId: created.id,
      expectedVersion: created.version,
      clientRentalItemIds: [clientCabin.id],
      manualRentalItemIds: [manualCabin.id],
      fromParty: "Новая компания",
      returnDate: "2026-07-14",
      updatedBy: "Редактор",
    })

    expect(saved.fromParty).toBe("Новая компания")
    expect(saved.returnDate).toBe("2026-07-14")
    expect(
      saved.items.map((item) => ({
        rentalItemId: item.rentalItemId,
        selectionSource: item.selectionSource,
        originalTenant: item.originalTenant,
      }))
    ).toEqual(originalAudit)
  })

  it("rejects a newly selected client cabin when its tenant does not match", async () => {
    const [first, second] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: first.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: created.id,
        expectedVersion: created.version,
        clientRentalItemIds: [first.id, second.id],
        manualRentalItemIds: [],
        fromParty: "Другая компания",
        returnDate: created.returnDate,
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("не принадлежит выбранному клиенту")
    expect(
      readRentalItems().find((item) => item.id === second.id)?.status
    ).toBe("RENTED")
  })

  it("keeps metadata editable but locks membership after estimate work starts", async () => {
    const [first, second] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: first.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    await claimReturnForEstimate({
      warehouseId,
      returnTaskId: created.items[0].id,
      rentalItemId: first.id,
      expectedVersion: created.items[0].version,
    })
    const claimed = (await listReturnReceipts(warehouseId)).find(
      (receipt) => receipt.id === created.id
    )!

    const metadataOnly = await updateReturnReceipt({
      warehouseId,
      receiptId: claimed.id,
      expectedVersion: claimed.version,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: "Исправленный отправитель",
      returnDate: "2026-07-15",
      updatedBy: "Редактор",
    })
    expect(metadataOnly.fromParty).toBe("Исправленный отправитель")

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: metadataOnly.id,
        expectedVersion: metadataOnly.version,
        clientRentalItemIds: [first.id],
        manualRentalItemIds: [second.id],
        fromParty: metadataOnly.fromParty,
        returnDate: metadataOnly.returnDate,
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("Состав бытовок нельзя менять")
    expect(
      readRentalItems().find((item) => item.id === second.id)?.status
    ).toBe("RENTED")
  })

  it("does not allow removing the final cabin", async () => {
    const [first] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: first.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: created.id,
        expectedVersion: created.version,
        clientRentalItemIds: [],
        manualRentalItemIds: [],
        fromParty: created.fromParty,
        returnDate: created.returnDate,
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("хотя бы одна бытовка")
    expect(readRentalItems().find((item) => item.id === first.id)?.status).toBe(
      "AFTER_RENT"
    )
  })

  it("compensates an exact rental status change when receipt persistence fails", async () => {
    const [first, second] = rentedCabins()
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [first.id],
      manualRentalItemIds: [],
      fromParty: first.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    const nativeSetItem = window.localStorage.setItem.bind(window.localStorage)
    const setItem = vi
      .spyOn(window.localStorage, "setItem")
      .mockImplementation((key, value) => {
        if (key === LOGISTICS_STORAGE_KEY) throw new Error("storage failure")
        nativeSetItem(key, value)
      })

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: created.id,
        expectedVersion: created.version,
        clientRentalItemIds: [first.id],
        manualRentalItemIds: [second.id],
        fromParty: created.fromParty,
        returnDate: created.returnDate,
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("storage failure")
    setItem.mockRestore()

    expect(readRentalItems().find((item) => item.id === first.id)?.status).toBe(
      "AFTER_RENT"
    )
    expect(
      readRentalItems().find((item) => item.id === second.id)?.status
    ).toBe("RENTED")
    expect((await listReturnReceipts(warehouseId))[0].items).toHaveLength(1)
  })

  it("registers contents, creates cases for an added cabin, and clears unresolved cases on removal", async () => {
    const cabins = rentedCabins()
    const emptyCabin = cabins.find((item) => item.contentsItems.length === 0)!
    const filledCabin = cabins.find((item) => item.contentsItems.length > 0)!
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [emptyCabin.id],
      manualRentalItemIds: [],
      fromParty: emptyCabin.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    expect(
      await getUnresolvedReturnEquipmentDispositionCases({
        sourceRentalItemId: emptyCabin.id,
      })
    ).toHaveLength(0)

    const withFilledCabin = await updateReturnReceipt({
      warehouseId,
      receiptId: created.id,
      expectedVersion: created.version,
      clientRentalItemIds: [emptyCabin.id],
      manualRentalItemIds: [filledCabin.id],
      fromParty: created.fromParty,
      returnDate: created.returnDate,
      updatedBy: "Редактор",
    })
    expect(
      await getUnresolvedReturnEquipmentDispositionCases({
        sourceRentalItemId: filledCabin.id,
      })
    ).toHaveLength(filledCabin.contentsItems.length)

    await updateReturnReceipt({
      warehouseId,
      receiptId: withFilledCabin.id,
      expectedVersion: withFilledCabin.version,
      clientRentalItemIds: [emptyCabin.id],
      manualRentalItemIds: [],
      fromParty: withFilledCabin.fromParty,
      returnDate: withFilledCabin.returnDate,
      updatedBy: "Редактор",
    })
    expect(
      await getUnresolvedReturnEquipmentDispositionCases({
        sourceRentalItemId: filledCabin.id,
      })
    ).toHaveLength(0)
  })

  it("rejects removing a cabin after its equipment has been processed", async () => {
    const cabins = rentedCabins()
    const emptyCabin = cabins.find((item) => item.contentsItems.length === 0)!
    const filledCabin = cabins.find((item) => item.contentsItems.length > 0)!
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [emptyCabin.id],
      manualRentalItemIds: [filledCabin.id],
      fromParty: emptyCabin.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    const equipmentCases = await getUnresolvedReturnEquipmentDispositionCases({
      sourceRentalItemId: filledCabin.id,
    })
    const equipmentCase = equipmentCases.find(
      (item) => item.remainingQuantity > 1
    )!
    await resolveReturnEquipmentDisposition({
      caseId: equipmentCase.id,
      expectedVersion: equipmentCase.version,
      idempotencyKey: "processed-before-remove",
      action: "WRITE_OFF",
      quantity: 1,
      reason: "Повреждено",
      createdBy: "Тест",
    })

    const metadataOnly = await updateReturnReceipt({
      warehouseId,
      receiptId: created.id,
      expectedVersion: created.version,
      clientRentalItemIds: [emptyCabin.id],
      manualRentalItemIds: [filledCabin.id],
      fromParty: "Исправленный отправитель",
      returnDate: "2026-07-12",
      updatedBy: "Редактор",
    })
    const view = (await listReturnReceipts(warehouseId)).find(
      (receipt) => receipt.id === created.id
    )!
    expect(isReturnReceiptMembershipEditable(view)).toBe(false)

    await expect(
      updateReturnReceipt({
        warehouseId,
        receiptId: metadataOnly.id,
        expectedVersion: metadataOnly.version,
        clientRentalItemIds: [emptyCabin.id],
        manualRentalItemIds: [],
        fromParty: metadataOnly.fromParty,
        returnDate: metadataOnly.returnDate,
        updatedBy: "Редактор",
      })
    ).rejects.toThrow("Состав бытовок нельзя менять")
  })

  it("blocks undamaged acceptance until all equipment is distributed", async () => {
    const filledCabin = rentedCabins().find(
      (item) => item.contentsItems.length > 0
    )!
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [filledCabin.id],
      manualRentalItemIds: [],
      fromParty: filledCabin.tenant!,
      returnDate: "2026-07-11",
      createdBy: "Тест",
    })
    await expect(
      acceptReturnUndamaged({
        warehouseId,
        returnTaskId: created.items[0].id,
        expectedVersion: created.items[0].version,
        uploads: [],
      })
    ).rejects.toThrow("распределите оборудование")

    const cases = await getUnresolvedReturnEquipmentDispositionCases({
      sourceRentalItemId: filledCabin.id,
    })
    for (const equipmentCase of cases) {
      await resolveReturnEquipmentDisposition({
        caseId: equipmentCase.id,
        expectedVersion: equipmentCase.version,
        idempotencyKey: `resolve-${equipmentCase.id}`,
        action: "WRITE_OFF",
        quantity: equipmentCase.remainingQuantity,
        reason: "Повреждено",
        createdBy: "Тест",
      })
    }
    await expect(
      acceptReturnUndamaged({
        warehouseId,
        returnTaskId: created.items[0].id,
        expectedVersion: created.items[0].version,
        uploads: [],
      })
    ).rejects.toThrow("Добавьте хотя бы одну фотографию")
  })

  it("keeps factual returned contents in the cabin and records only estimate-confirmed losses", async () => {
    const cabin = rentedCabins().find((item) => item.contentsItems.length > 0)!
    const expectedContents = cabin.contentsItems.map((item) => ({ ...item }))
    const firstItem = expectedContents[0]
    const returnedContents = expectedContents
      .map((item, index) =>
        index === 0 ? { ...item, quantity: item.quantity - 1 } : item
      )
      .filter((item) => item.quantity > 0)
    const canonicalReturnedContents = [...returnedContents].sort(
      (left, right) => left.name.localeCompare(right.name, "ru")
    )
    const beforeEquipment = await getEquipmentItems({ warehouseId })
    const beforeLost = beforeEquipment.find(
      (item) => item.name === firstItem.name
    )?.lostQuantity

    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [cabin.id],
      manualRentalItemIds: [],
      fromParty: cabin.tenant!,
      returnDate: "2026-07-11",
      contentsByRentalItemId: { [cabin.id]: returnedContents },
      createdBy: "Тест",
    })

    expect(created.items[0]).toMatchObject({
      contentsMode: "FACTUAL",
      expectedContents,
      returnedContents: canonicalReturnedContents,
    })
    expect(
      await getUnresolvedReturnEquipmentDispositionCases({
        sourceRentalItemId: cabin.id,
      })
    ).toHaveLength(0)
    expect(
      readRentalItems().find((item) => item.id === cabin.id)?.contentsItems
    ).toEqual(expectedContents)

    const claimed = await claimReturnForEstimate({
      warehouseId,
      returnTaskId: created.items[0].id,
      rentalItemId: cabin.id,
      expectedVersion: created.items[0].version,
    })
    await markReturnEstimateCreated({
      warehouseId,
      returnTaskId: created.items[0].id,
      rentalItemId: cabin.id,
      expectedVersion: claimed.version,
      claimId: claimed.estimateClaimId,
      estimateId: "estimate-factual-contents",
      estimateStatus: "DRAFT",
    })

    expect(
      readRentalItems().find((item) => item.id === cabin.id)?.contentsItems
    ).toEqual(canonicalReturnedContents)
    expect(
      (await getEquipmentItems({ warehouseId })).find(
        (item) => item.name === firstItem.name
      )?.lostQuantity
    ).toBe((beforeLost ?? 0) + 1)
  })

  it("excludes quarantined cabins from shipment and rechecks before finalization", async () => {
    const cabin = readRentalItems().find(
      (item) =>
        item.warehouseId === warehouseId &&
        item.status === "FREE" &&
        item.contentsItems.length > 0
    )!
    const shipment = {
      id: "shipment-quarantine-test",
      version: 1,
      warehouseId,
      company: "ООО Тест",
      driverId: "driver-1",
      driverName: "Водитель",
      shipmentDate: "2026-07-12",
      status: "PREPARING" as const,
      error: null,
      createdAt: "2026-07-11T00:00:00.000Z",
      createdBy: "Тест",
      items: [
        {
          rentalItemId: cabin.id,
          cabinNumber: cabin.number,
          contentsBefore: cabin.contentsItems,
          contentsPlanned: cabin.contentsItems,
          changes: [],
          preparationTask: null,
        },
      ],
    }
    window.localStorage.setItem(
      LOGISTICS_STORAGE_KEY,
      JSON.stringify({
        service: "logistics",
        schemaVersion: 2,
        revision: 1,
        returnReceipts: [],
        shipments: [shipment],
        preparationDispatches: [],
      })
    )
    const disposition = prepareReturnEquipmentDispositionReconciliation({
      upserts: [
        {
          warehouseId,
          returnReceiptId: "receipt-shipment-gate",
          returnItemId: "return-item-shipment-gate",
          sourceRentalItemId: cabin.id,
          sourceCabinNumber: cabin.number,
          receivedAt: "2026-07-11",
          contents: cabin.contentsItems,
        },
      ],
    })
    disposition.commit()

    expect(
      (await listShipmentCandidates(warehouseId, "ООО Тест")).some(
        (candidate) => candidate.item.id === cabin.id
      )
    ).toBe(false)
    await expect(
      finalizeShipment({ warehouseId, shipmentId: shipment.id })
    ).rejects.toThrow("требует распределения")
  })

  it("registers a new imported cabin and its return in one command", async () => {
    const input = {
      commandId: "intake-new-900",
      warehouseId,
      number: "БЫТ-ИМПОРТ-900",
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: "БК-2",
        dimensions: "2.4x2",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: ["Пластиковое окно"],
        linoleum: false,
      },
      expectedContents: [{ name: "Стол", quantity: 2 }],
      returnedContents: [{ name: "Стол", quantity: 1 }],
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    } satisfies Parameters<typeof createImportedReturnIntake>[0]
    const result = await createImportedReturnIntake(input)
    const repeated = await createImportedReturnIntake(input)

    expect(result.kind).toBe("NEW_CABIN")
    expect(repeated.receipt.id).toBe(result.receipt.id)
    const cabin = readRentalItems().find(
      (item) => item.number === "БЫТ-ИМПОРТ-900"
    )
    expect(cabin).toMatchObject({
      status: "AFTER_RENT",
      tenant: "ООО Клиент",
      contentsItems: [{ name: "Стол", quantity: 1 }],
    })
    expect((await listReturnReceipts(warehouseId))[0]).toMatchObject({
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      receiverName: "Принимающий",
      items: [
        {
          intakeMode: "NEW_CABIN",
          technicalState: "PENDING_INSPECTION",
          expectedContents: [{ name: "Стол", quantity: 2 }],
        },
      ],
    })
  })

  it("persists an other-warehouse conflict without moving or duplicating the cabin", async () => {
    const other = readRentalItems().find(
      (item) => item.warehouseId !== warehouseId
    )!
    writeRentalItems(
      readRentalItems().map((item) =>
        item.id === other.id ? { ...item, number: "МСК-УНИКАЛЬНАЯ-900" } : item
      )
    )
    const uniqueOther = readRentalItems().find((item) => item.id === other.id)!
    const before = readRentalItems().length
    const result = await createImportedReturnIntake({
      commandId: "intake-conflict-900",
      warehouseId,
      number: uniqueOther.number,
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: uniqueOther.type as "БК-2",
        dimensions: (uniqueOther.dimensions ?? "2.4x2") as "2.4x2",
        finishing: (uniqueOther.finishing ?? "ЛДСП") as "ЛДСП",
        category: uniqueOther.category ?? "Обычная",
        characteristics: [],
        linoleum: uniqueOther.linoleum === true,
      },
      expectedContents: uniqueOther.contentsItems,
      returnedContents: uniqueOther.contentsItems,
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: true,
      photos: [],
    })

    expect(result.kind).toBe("CONFLICT")
    expect(readRentalItems()).toHaveLength(before)
    expect(
      readRentalItems().find((item) => item.id === uniqueOther.id)?.warehouseId
    ).toBe(uniqueOther.warehouseId)
    expect((await listReturnReceipts(warehouseId))[0].items[0]).toMatchObject({
      technicalState: "CONFLICT",
      conflicts: [{ code: "OTHER_WAREHOUSE" }],
    })
    writeRentalItems(
      readRentalItems().map((item) =>
        item.id === uniqueOther.id
          ? {
              ...item,
              version: item.version + 1,
              warehouseId,
              status: "WAREHOUSE" as const,
            }
          : item
      )
    )
    await expect(
      resumeImportedReturnConflict({
        warehouseId,
        returnItemId: result.receipt.items[0].id,
        expectedVersion: result.receipt.items[0].version,
        resolvedBy: "Менеджер МСК",
        resolution: {
          kind: "WAREHOUSE_TRANSFER",
          id: "unproven-transfer",
        },
      })
    ).rejects.toThrow("ещё не подтверждены")

    const corrected = readRentalItems().find(
      (item) => item.id === uniqueOther.id
    )!
    window.localStorage.setItem(
      WAREHOUSE_TRANSFERS_STORAGE_KEY,
      JSON.stringify({
        documents: [
          {
            id: "proven-transfer",
            destinationWarehouse: { id: warehouseId },
            lines: [
              {
                rentalItemId: uniqueOther.id,
                status: "RECEIVED",
                rentalItemVersion: corrected.version,
              },
            ],
          },
        ],
        events: [],
        corrections: [],
      })
    )
    const resumed = await resumeImportedReturnConflict({
      warehouseId,
      returnItemId: result.receipt.items[0].id,
      expectedVersion: result.receipt.items[0].version,
      resolvedBy: "Менеджер МСК",
      resolution: {
        kind: "WAREHOUSE_TRANSFER",
        id: "proven-transfer",
      },
    })
    const resumedAgain = await resumeImportedReturnConflict({
      warehouseId,
      returnItemId: result.receipt.items[0].id,
      expectedVersion: result.receipt.items[0].version,
      resolvedBy: "Менеджер МСК",
      resolution: {
        kind: "WAREHOUSE_TRANSFER",
        id: "proven-transfer",
      },
    })
    expect(resumed).toMatchObject({
      technicalState: "PENDING_INSPECTION",
      conflicts: [],
    })
    expect(resumedAgain.version).toBe(resumed.version)
    expect(
      readRentalItems().find((item) => item.id === uniqueOther.id)
    ).toMatchObject({
      status: "AFTER_RENT",
      warehouseId,
      tenant: "ООО Клиент",
      contentsItems: uniqueOther.contentsItems,
    })
  })

  it("requires confirmation before overriding a passive current-warehouse cabin", async () => {
    const passive = readRentalItems().find(
      (item) => item.warehouseId === warehouseId && item.status === "FREE"
    )!
    await expect(
      createImportedReturnIntake({
        commandId: "intake-override-900",
        warehouseId,
        number: passive.number,
        fromParty: "ООО Клиент",
        shipmentDate: "2026-06-01",
        returnDate: "2026-07-12",
        driverId: null,
        driverName: null,
        createdBy: "Принимающий",
        passport: {
          type: passive.type as "БК-2",
          dimensions: (passive.dimensions ?? "2.4x2") as "2.4x2",
          finishing: (passive.finishing ?? "ЛДСП") as "ЛДСП",
          category: passive.category ?? "Обычная",
          characteristics: [],
          linoleum: passive.linoleum === true,
        },
        expectedContents: passive.contentsItems,
        returnedContents: passive.contentsItems,
        expectedContentsEditedReason: null,
        overrideExisting: false,
        passportChangeConfirmed: true,
        photos: [],
      })
    ).rejects.toThrow("Подтвердите перезапись")
    expect(await listReturnReceipts(warehouseId)).toHaveLength(0)
  })

  it("blocks estimate handoff until each extra furniture disposition is resolved", async () => {
    const created = await createImportedReturnIntake({
      commandId: "intake-extra-furniture",
      warehouseId,
      number: "БЫТ-ИМПОРТ-901",
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: "БК-2",
        dimensions: "2.4x2",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: [],
        linoleum: false,
      },
      expectedContents: [{ name: "Стол", quantity: 1 }],
      returnedContents: [{ name: "Стол", quantity: 2 }],
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    })
    const returnItem = created.receipt.items[0]
    expect(returnItem.furnitureDispositions).toMatchObject([
      { name: "Стол", quantity: 1, origin: "EXTRA_FACTUAL", status: "PENDING" },
    ])
    await expect(
      claimReturnForEstimate({
        warehouseId,
        returnTaskId: returnItem.id,
        rentalItemId: returnItem.rentalItemId,
        expectedVersion: returnItem.version,
      })
    ).rejects.toThrow("распределите конфликтную мебель")

    const resolved = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: returnItem.id,
      dispositionId: returnItem.furnitureDispositions[0].id,
      expectedVersion: returnItem.version,
      quantity: 1,
      idempotencyKey: "resolve-extra-keep-1",
      action: "KEEP_IN_CABIN",
      resolvedBy: "Кладовщик",
    })
    expect(resolved.furnitureDispositions[0]).toMatchObject({
      status: "RESOLVED",
      action: "KEEP_IN_CABIN",
      resolvedBy: "Кладовщик",
    })
  })

  it("persists tenant mismatch as a visible conflict", async () => {
    const rented = rentedCabins()[0]
    const result = await createImportedReturnIntake({
      commandId: "intake-tenant-conflict",
      warehouseId,
      number: rented.number,
      fromParty: "Другой арендатор",
      shipmentDate: rented.shipmentDate!,
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: rented.type as "БК-2",
        dimensions: (rented.dimensions ?? "2.4x2") as "2.4x2",
        finishing: (rented.finishing ?? "ЛДСП") as "ЛДСП",
        category: rented.category ?? "Обычная",
        characteristics: (rented.characteristics ?? "")
          .split(",")
          .map((value) => value.trim())
          .filter(Boolean) as [],
        linoleum: rented.linoleum === true,
      },
      expectedContents: rented.contentsItems,
      returnedContents: rented.contentsItems,
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    })
    expect(result.kind).toBe("CONFLICT")
    expect(result.receipt.items[0].conflicts[0].code).toBe("TENANT_MISMATCH")
    expect(
      readRentalItems().find((item) => item.id === rented.id)?.tenant
    ).toBe(rented.tenant)
  })

  it("prefers the latest shipped contents snapshot for an ordinary return", async () => {
    const rented = rentedCabins()[0]
    window.localStorage.setItem(
      LOGISTICS_STORAGE_KEY,
      JSON.stringify({
        service: "logistics",
        schemaVersion: 2,
        revision: 1,
        returnReceipts: [],
        preparationDispatches: [],
        shipments: [
          {
            id: "shipment-latest-snapshot",
            version: 1,
            warehouseId,
            company: rented.tenant,
            driverId: "driver",
            driverName: "Водитель",
            shipmentDate: "2026-06-01",
            status: "SHIPPED",
            error: null,
            createdAt: "2026-06-01T10:00:00.000Z",
            createdBy: "Логист",
            items: [
              {
                rentalItemId: rented.id,
                cabinNumber: rented.number,
                contentsBefore: [],
                contentsPlanned: [{ name: "Стул", quantity: 7 }],
                changes: [],
                preparationTask: null,
              },
            ],
          },
        ],
      })
    )
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [rented.id],
      manualRentalItemIds: [],
      fromParty: rented.tenant!,
      returnDate: "2026-07-12",
      contentsByRentalItemId: { [rented.id]: [] },
      createdBy: "Принимающий",
    })
    expect(created.items[0]).toMatchObject({
      expectedContentsSource: "LATEST_SHIPMENT",
      expectedContents: [{ name: "Стул", quantity: 7 }],
    })
  })

  it("builds deterministic zero-price replacement lines for missing furniture", () => {
    const first = createReturnReplacementEstimateLines({
      id: "return-line-1",
      expectedContents: [{ name: "Стол офисный", quantity: 3 }],
      returnedContents: [{ name: "стол офисный", quantity: 1 }],
    })
    const second = createReturnReplacementEstimateLines({
      id: "return-line-1",
      expectedContents: [{ name: "Стол офисный", quantity: 3 }],
      returnedContents: [{ name: "стол офисный", quantity: 1 }],
    })
    expect(first).toEqual(second)
    expect(first.lines[0]).toMatchObject({
      description: "Замена: Стол офисный",
      quantity: 2,
      unitPrice: "0.00",
      lineTotal: "0.00",
      catalogSnapshot: null,
    })
    expect(first.warnings[0]).toContain("цена установлена 0")
  })

  it("uses one punctuation-insensitive canonical number in preview and commit", async () => {
    const existing = readRentalItems().find(
      (item) => item.number === "БЫТ-001"
    )!
    const preview = await getImportedReturnIntakePreview({
      warehouseId,
      number: "БЫТ001",
    })
    expect(preview.item?.id).toBe(existing.id)
    expect(preview.kind).toBe("CURRENT_WAREHOUSE")
    await expect(
      createImportedReturnIntake({
        commandId: "canonical-punctuation",
        warehouseId,
        number: "БЫТ001",
        fromParty: "ООО Клиент",
        shipmentDate: "2026-06-01",
        returnDate: "2026-07-12",
        driverId: null,
        driverName: null,
        createdBy: "Принимающий",
        passport: {
          type: existing.type as "БК-2",
          dimensions: (existing.dimensions ?? "2.4x2") as "2.4x2",
          finishing: (existing.finishing ?? "ЛДСП") as "ЛДСП",
          category: existing.category ?? "Обычная",
          characteristics: [],
          linoleum: existing.linoleum === true,
        },
        expectedContents: existing.contentsItems,
        returnedContents: existing.contentsItems,
        expectedContentsEditedReason: null,
        overrideExisting: false,
        passportChangeConfirmed: true,
        photos: [],
      })
    ).rejects.toThrow("Подтвердите перезапись")
  })

  it("blocks importing a cabin that is in an active sale", async () => {
    const candidate = readRentalItems().find(
      (item) => item.warehouseId === warehouseId && item.status === "FREE"
    )!
    const sale = { ...candidate, status: "SALE" as const }
    writeRentalItems(
      readRentalItems().map((item) => (item.id === sale.id ? sale : item))
    )
    await expect(
      createImportedReturnIntake({
        commandId: "active-sale-block",
        warehouseId,
        number: sale.number,
        fromParty: "ООО Клиент",
        shipmentDate: "2026-06-01",
        returnDate: "2026-07-12",
        driverId: null,
        driverName: null,
        createdBy: "Принимающий",
        passport: {
          type: sale.type as "БК-2",
          dimensions: (sale.dimensions ?? "2.4x2") as "2.4x2",
          finishing: (sale.finishing ?? "ЛДСП") as "ЛДСП",
          category: sale.category ?? "Обычная",
          characteristics: [],
          linoleum: sale.linoleum === true,
        },
        expectedContents: sale.contentsItems,
        returnedContents: sale.contentsItems,
        expectedContentsEditedReason: null,
        overrideExisting: true,
        passportChangeConfirmed: true,
        photos: [],
      })
    ).rejects.toThrow("активном процессе")
  })

  it("splits one extra position across stock, write-off and keep allocations", async () => {
    const beforeEquipment = await getEquipmentItems({ warehouseId })
    const before = beforeEquipment.find((item) => item.name === "Стол")!
    const intake = await createImportedReturnIntake({
      commandId: "intake-split-four",
      warehouseId,
      number: "БЫТ-ИМПОРТ-904",
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: "БК-2",
        dimensions: "2.4x2",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: [],
        linoleum: false,
      },
      expectedContents: [],
      returnedContents: [{ name: "Стол", quantity: 4 }],
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    })
    const item = intake.receipt.items[0]
    const dispositionId = item.furnitureDispositions[0].id
    const stocked = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: item.id,
      dispositionId,
      expectedVersion: item.version,
      quantity: 2,
      idempotencyKey: "split-stock-2",
      action: "RETURN_TO_STOCK",
      resolvedBy: "Кладовщик",
    })
    const replay = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: item.id,
      dispositionId,
      expectedVersion: item.version,
      quantity: 2,
      idempotencyKey: "split-stock-2",
      action: "RETURN_TO_STOCK",
      resolvedBy: "Кладовщик",
    })
    expect(replay.version).toBe(stocked.version)
    const writtenOff = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: item.id,
      dispositionId,
      expectedVersion: stocked.version,
      quantity: 1,
      idempotencyKey: "split-writeoff-1",
      action: "WRITE_OFF",
      reason: "Сломан при возврате",
      resolvedBy: "Кладовщик",
    })
    const kept = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: item.id,
      dispositionId,
      expectedVersion: writtenOff.version,
      quantity: 1,
      idempotencyKey: "split-keep-1",
      action: "KEEP_IN_CABIN",
      resolvedBy: "Кладовщик",
    })
    expect(kept.furnitureDispositions[0]).toMatchObject({
      remainingQuantity: 0,
      status: "RESOLVED",
    })
    expect(kept.furnitureDispositions[0].allocations).toHaveLength(3)
    const after = (await getEquipmentItems({ warehouseId })).find(
      (equipment) => equipment.name === "Стол"
    )!
    expect(after.stockQuantity).toBe(before.stockQuantity + 2)
    expect(after.writtenOffQuantity).toBe(before.writtenOffQuantity + 1)
  })

  it("recovers a prepared stock allocation exactly once after a ledger write failure", async () => {
    const before = (await getEquipmentItems({ warehouseId })).find(
      (item) => item.name === "Стол"
    )!
    const intake = await createImportedReturnIntake({
      commandId: "intake-ledger-recovery",
      warehouseId,
      number: "БЫТ-ИМПОРТ-LEDGER",
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: "БК-2",
        dimensions: "2.4x2",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: [],
        linoleum: false,
      },
      expectedContents: [],
      returnedContents: [{ name: "Стол", quantity: 1 }],
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    })
    const item = intake.receipt.items[0]
    const dispositionId = item.furnitureDispositions[0].id
    const originalSetItem = window.localStorage.setItem.bind(
      window.localStorage
    )
    let failed = false
    const setItem = vi
      .spyOn(window.localStorage, "setItem")
      .mockImplementation((key, value) => {
        if (key === EQUIPMENT_MOCK_STORAGE_KEY && !failed) {
          failed = true
          throw new Error("equipment storage unavailable")
        }
        originalSetItem(key, value)
      })

    await expect(
      resolveReturnFurnitureDisposition({
        warehouseId,
        returnItemId: item.id,
        dispositionId,
        expectedVersion: item.version,
        quantity: 1,
        idempotencyKey: "stock-ledger-recovery",
        action: "RETURN_TO_STOCK",
        resolvedBy: "Кладовщик",
      })
    ).rejects.toThrow("equipment storage unavailable")
    setItem.mockRestore()

    const pending = (await listReturnReceipts(warehouseId)).find(
      (receipt) => receipt.id === intake.receipt.id
    )!.items[0]
    expect(pending.furnitureDispositions[0].allocations[0].status).toBe(
      "PENDING_LEDGER"
    )

    const recovered = await resolveReturnFurnitureDisposition({
      warehouseId,
      returnItemId: item.id,
      dispositionId,
      expectedVersion: item.version,
      quantity: 1,
      idempotencyKey: "stock-ledger-recovery",
      action: "RETURN_TO_STOCK",
      resolvedBy: "Кладовщик",
    })
    expect(recovered.furnitureDispositions[0].allocations[0].status).toBe(
      "APPLIED"
    )
    const after = (await getEquipmentItems({ warehouseId })).find(
      (equipment) => equipment.name === "Стол"
    )!
    expect(after.stockQuantity).toBe(before.stockQuantity + 1)
  })

  it("recovers an estimate projection and loss ledger exactly once", async () => {
    const cabin = rentedCabins().find((item) => item.contentsItems.length > 0)!
    const missing = cabin.contentsItems[0]
    const returnedContents = cabin.contentsItems
      .map((item, index) =>
        index === 0 ? { ...item, quantity: item.quantity - 1 } : item
      )
      .filter((item) => item.quantity > 0)
    const beforeLost = (await getEquipmentItems({ warehouseId })).find(
      (item) => item.name === missing.name
    )!.lostQuantity
    const created = await createReturnReceipt({
      warehouseId,
      clientRentalItemIds: [cabin.id],
      manualRentalItemIds: [],
      fromParty: cabin.tenant!,
      returnDate: "2026-07-12",
      contentsByRentalItemId: { [cabin.id]: returnedContents },
      createdBy: "Принимающий",
    })
    const claimed = await claimReturnForEstimate({
      warehouseId,
      returnTaskId: created.items[0].id,
      rentalItemId: cabin.id,
      expectedVersion: created.items[0].version,
    })
    const originalSetItem = window.localStorage.setItem.bind(
      window.localStorage
    )
    let failed = false
    const setItem = vi
      .spyOn(window.localStorage, "setItem")
      .mockImplementation((key, value) => {
        if (key === LOGISTICS_STORAGE_KEY && !failed) {
          failed = true
          throw new Error("logistics storage unavailable")
        }
        originalSetItem(key, value)
      })
    const command = {
      warehouseId,
      returnTaskId: created.items[0].id,
      rentalItemId: cabin.id,
      expectedVersion: claimed.version,
      claimId: claimed.estimateClaimId,
      estimateId: "estimate-ledger-recovery",
      estimateStatus: "DRAFT" as const,
    }

    await expect(markReturnEstimateCreated(command)).rejects.toThrow(
      "logistics storage unavailable"
    )
    setItem.mockRestore()
    const recovered = await markReturnEstimateCreated(command)
    expect(recovered).toMatchObject({
      technicalState: "ESTIMATE_CREATED",
      sourceEstimateId: "estimate-ledger-recovery",
    })
    const recoveredCabin = readRentalItems().find(
      (item) => item.id === cabin.id
    )!
    expect(recoveredCabin.status).toBe("WAITING_ESTIMATE_CONFIRMATION")
    expect(recoveredCabin.contentsItems).toHaveLength(returnedContents.length)
    expect(recoveredCabin.contentsItems).toEqual(
      expect.arrayContaining(returnedContents)
    )
    const afterLost = (await getEquipmentItems({ warehouseId })).find(
      (item) => item.name === missing.name
    )!.lostQuantity
    expect(afterLost).toBe(beforeLost + 1)
  })

  it("persists a transfer attempt but does not mutate cabins when task dispatch fails", async () => {
    const target = readRentalItems().find(
      (item) => item.warehouseId === warehouseId && item.status === "FREE"
    )!
    const targetBefore = target.contentsItems
    const intake = await createImportedReturnIntake({
      commandId: "intake-transfer-failure",
      warehouseId,
      number: "БЫТ-ИМПОРТ-905",
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: "БК-2",
        dimensions: "2.4x2",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: [],
        linoleum: false,
      },
      expectedContents: [],
      returnedContents: [{ name: "Стол", quantity: 1 }],
      expectedContentsEditedReason: null,
      overrideExisting: false,
      passportChangeConfirmed: false,
      photos: [],
    })
    vi.spyOn(
      contentsTransferApi,
      "transferRentalItemContentsWithTask"
    ).mockRejectedValueOnce(new Error("task-board offline"))
    const item = intake.receipt.items[0]
    await expect(
      resolveReturnFurnitureDisposition({
        warehouseId,
        serviceWarehouseId: "00000000-0000-0000-0000-000000000001",
        accessToken: "token",
        returnItemId: item.id,
        dispositionId: item.furnitureDispositions[0].id,
        expectedVersion: item.version,
        quantity: 1,
        idempotencyKey: "return-transfer-failure",
        action: "TRANSFER_TO_CABIN",
        targetRentalItemId: target.id,
        resolvedBy: "Кладовщик",
      })
    ).rejects.toThrow("task-board offline")
    expect(
      readRentalItems().find((entry) => entry.id === target.id)?.contentsItems
    ).toEqual(targetBefore)
    const saved = (await listReturnReceipts(warehouseId)).find(
      (receipt) => receipt.id === intake.receipt.id
    )!.items[0]
    expect(saved.furnitureDispositions[0]).toMatchObject({
      status: "PENDING",
      remainingQuantity: 0,
      allocations: [{ status: "PENDING_TASK" }],
    })
  })

  it("moves a previous-snapshot position to a target only after task registration", async () => {
    const source = readRentalItems().find(
      (item) =>
        item.warehouseId === warehouseId &&
        item.status === "FREE" &&
        item.contentsItems.length > 0
    )!
    const target = readRentalItems().find(
      (item) =>
        item.warehouseId === warehouseId &&
        item.status === "FREE" &&
        item.id !== source.id
    )!
    const position = source.contentsItems[0]
    const targetBefore =
      target.contentsItems.find(
        (entry) => entry.name.toLowerCase() === position.name.toLowerCase()
      )?.quantity ?? 0
    const intake = await createImportedReturnIntake({
      commandId: "intake-previous-transfer",
      warehouseId,
      number: source.number,
      fromParty: "ООО Клиент",
      shipmentDate: "2026-06-01",
      returnDate: "2026-07-12",
      driverId: null,
      driverName: null,
      createdBy: "Принимающий",
      passport: {
        type: source.type as "БК-2",
        dimensions: (source.dimensions ?? "2.4x2") as "2.4x2",
        finishing: (source.finishing ?? "ЛДСП") as "ЛДСП",
        category: source.category ?? "Обычная",
        characteristics: (source.characteristics ?? "")
          .split(",")
          .map((value) => value.trim())
          .filter(Boolean) as [],
        linoleum: source.linoleum === true,
      },
      expectedContents: source.contentsItems,
      returnedContents: source.contentsItems.filter(
        (entry) => entry.name !== position.name
      ),
      expectedContentsEditedReason: null,
      overrideExisting: true,
      passportChangeConfirmed: true,
      photos: [],
    })
    vi.spyOn(
      HttpContentsTransferTaskClient.prototype,
      "dispatch"
    ).mockResolvedValueOnce({
      boardTaskId: "task-previous",
      queueId: "queue-movement",
      queueCode: "MOVEMENT",
    })
    const item = intake.receipt.items[0]
    const disposition = item.furnitureDispositions.find(
      (entry) => entry.origin === "PREVIOUS_SNAPSHOT"
    )!
    const saved = await resolveReturnFurnitureDisposition({
      warehouseId,
      serviceWarehouseId: "00000000-0000-0000-0000-000000000001",
      accessToken: "token",
      returnItemId: item.id,
      dispositionId: disposition.id,
      expectedVersion: item.version,
      quantity: 1,
      idempotencyKey: "previous-transfer-one",
      action: "TRANSFER_TO_CABIN",
      targetRentalItemId: target.id,
      resolvedBy: "Кладовщик",
    })
    expect(saved.returnedContents).toEqual(
      source.contentsItems.filter((entry) => entry.name !== position.name)
    )
    const targetAfter = readRentalItems().find(
      (entry) => entry.id === target.id
    )!
    expect(
      targetAfter.contentsItems.find(
        (entry) => entry.name.toLowerCase() === position.name.toLowerCase()
      )?.quantity
    ).toBe(targetBefore + 1)
    expect(
      saved.furnitureDispositions.find((entry) => entry.id === disposition.id)
        ?.allocations[0]
    ).toMatchObject({
      status: "APPLIED",
      taskExternalId: "previous-transfer-one",
    })
  })
})
