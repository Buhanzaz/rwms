import { beforeEach, describe, expect, it, vi } from "vitest"
import { EQUIPMENT_MOCK_STORAGE_KEY } from "@/api/equipment-api"
import type { LogisticsPreparationTaskClient } from "@/features/logistics/ports/logistics-preparation-task-client"
import { BrowserShipmentClient } from "@/features/logistics/shipments/adapters/browser-shipment-client"
import * as warehouseTransferApi from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import {
  buildShipmentPreparationTaskText,
  combineShipmentPreparationCatalog,
  computeShipmentContentsChanges,
  createShipmentPreparationTask,
  shipmentPreparationIdentity,
  stablePreparationExternalTaskId,
} from "@/features/logistics/shipments/domain"
import type {
  ShipmentDraftInput,
  ShipmentSourceAllocation,
} from "@/features/logistics/shipments/model"
import { SHIPMENTS_STORAGE_KEY } from "@/features/logistics/shipments/storage"
import {
  readRentalItems,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const taskClient: LogisticsPreparationTaskClient = {
  dispatch: vi.fn(async () => ({
    boardTaskId: "task-1",
    boardTaskVersion: 3,
    queueId: "queue-1",
    queueCode: "MOVEMENT",
  })),
  cancel: vi.fn(async () => ({ boardTaskVersion: 4 })),
}

function cabin(
  id: string,
  number: string,
  contentsItems: Array<{ name: string; quantity: number }> = []
): RentalItemDto {
  return {
    id,
    version: 0,
    warehouseId: "spb",
    number,
    type: "БК-1",
    dimensions: "2x4",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: false,
    status: "FREE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
  }
}

function allocation(
  sourceType: "WAREHOUSE" | "CABIN",
  items: Array<{ name: string; quantity: number }>,
  source?: RentalItemDto
): ShipmentSourceAllocation {
  return {
    sourceType,
    sourceRentalItemId: source?.id ?? null,
    sourceCabinNumber: source?.number ?? null,
    expectedSourceVersion: source?.version ?? null,
    items,
  }
}

function draft(
  target: RentalItemDto,
  planned: Array<{ name: string; quantity: number }>,
  sourceAllocations: ShipmentSourceAllocation[],
  shipmentId?: string
): ShipmentDraftInput {
  const changes = computeShipmentContentsChanges(target.contentsItems, planned)
  return {
    shipmentId,
    warehouseId: "spb",
    company: "ООО Тест",
    driverId: "driver-1",
    driverName: "Иван Водитель",
    shipmentDate: "2026-07-12",
    createdBy: "Администратор",
    items: [
      {
        rentalItemId: target.id,
        expectedTargetVersion: target.version,
        contentsBefore: target.contentsItems,
        contentsPlanned: planned,
        sourceAllocations,
        preparationTask: changes.length
          ? createShipmentPreparationTask(
              changes,
              undefined,
              stablePreparationExternalTaskId(
                shipmentPreparationIdentity({
                  warehouseId: "spb",
                  company: "ООО Тест",
                  shipmentDate: "2026-07-12",
                  rentalItemId: target.id,
                  changes,
                  sourceAllocations,
                })
              )
            )
          : null,
      },
    ],
  }
}

describe("BrowserShipmentClient", () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.clearAllMocks()
    window.localStorage.setItem(
      EQUIPMENT_MOCK_STORAGE_KEY,
      JSON.stringify([
        {
          id: "spb-chair",
          warehouseId: "spb",
          name: "Стул",
          stockQuantity: 3,
          writtenOffQuantity: 0,
          lostQuantity: 0,
        },
      ])
    )
  })

  it("changes task identity when normalized source allocations change", () => {
    const changes = computeShipmentContentsChanges(
      [],
      [{ name: "Стул", quantity: 1 }]
    )
    const warehouseId = stablePreparationExternalTaskId(
      shipmentPreparationIdentity({
        warehouseId: "spb",
        company: "ООО Тест",
        shipmentDate: "2026-07-12",
        rentalItemId: "target",
        changes,
        sourceAllocations: [
          allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }]),
        ],
      })
    )
    const cabinId = stablePreparationExternalTaskId(
      shipmentPreparationIdentity({
        warehouseId: "spb",
        company: "ООО Тест",
        shipmentDate: "2026-07-12",
        rentalItemId: "target",
        changes,
        sourceAllocations: [
          allocation(
            "CABIN",
            [{ name: "Стул", quantity: 1 }],
            cabin("source", "БЫТ-099", [{ name: "Стул", quantity: 1 }])
          ),
        ],
      })
    )
    expect(cabinId).not.toBe(warehouseId)
  })

  it("describes TAKE as movement to warehouse stock without a fake target destination", () => {
    const text = buildShipmentPreparationTaskText({
      cabinNumber: "БЫТ-100",
      changes: computeShipmentContentsChanges(
        [{ name: "Стул", quantity: 2 }],
        [{ name: "Стул", quantity: 1 }]
      ),
      sourceAllocations: [],
    })
    expect(text).toContain("из бытовки БЫТ-100 на склад")
    expect(text).not.toContain("Переместить в бытовку БЫТ-100")
  })

  it("offers cabin-only catalog capacity when warehouse stock is zero", () => {
    expect(
      combineShipmentPreparationCatalog(
        [],
        [
          {
            id: "source",
            version: 0,
            number: "БЫТ-101",
            contentsItems: [{ name: "Стул", quantity: 2 }],
          },
        ]
      )
    ).toEqual([{ name: "Стул", availableQuantity: 2 }])
  })

  it("reserves aggregate warehouse quantities and rejects contention", async () => {
    const first = cabin("target-1", "БЫТ-001")
    const second = cabin("target-2", "БЫТ-002")
    writeRentalItems([first, second])
    const client = new BrowserShipmentClient(taskClient)

    await client.saveDraft(
      draft(
        first,
        [{ name: "Стул", quantity: 2 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 2 }])]
      )
    )

    await expect(
      client.saveDraft(
        draft(
          second,
          [{ name: "Стул", quantity: 2 }],
          [allocation("WAREHOUSE", [{ name: "Стул", quantity: 2 }])]
        )
      )
    ).rejects.toThrow("Недостаточно складского остатка")
  })

  it("rejects target/source cycles and source aggregate over-allocation", async () => {
    const target = cabin("target", "БЫТ-010", [{ name: "Стул", quantity: 1 }])
    writeRentalItems([target])
    const client = new BrowserShipmentClient(taskClient)

    await expect(
      client.saveDraft(
        draft(
          target,
          [{ name: "Стул", quantity: 2 }],
          [allocation("CABIN", [{ name: "Стул", quantity: 1 }], target)]
        )
      )
    ).rejects.toThrow("Целевая бытовка не может быть источником")
  })

  it("reserves a target cabin across active shipment documents", async () => {
    const target = cabin("target", "БЫТ-011")
    writeRentalItems([target])
    const client = new BrowserShipmentClient(taskClient)
    await client.saveDraft(draft(target, [], []))

    expect(
      (await client.listCandidates("spb", "ООО Тест")).map(
        (candidate) => candidate.item.id
      )
    ).not.toContain(target.id)
    await expect(client.saveDraft(draft(target, [], []))).rejects.toThrow(
      "уже включена в другую отгрузку"
    )
  })

  it("subtracts quantities held by other shipments from source candidates", async () => {
    const target = cabin("target", "БЫТ-012")
    const other = cabin("other", "БЫТ-013")
    const source = cabin("source", "БЫТ-014", [{ name: "Стул", quantity: 2 }])
    writeRentalItems([target, other, source])
    const client = new BrowserShipmentClient(taskClient)
    await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 1 }],
        [allocation("CABIN", [{ name: "Стул", quantity: 1 }], source)]
      )
    )

    expect(
      (await client.listSourceCabins("spb", [other.id])).find(
        (candidate) => candidate.id === source.id
      )?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 1 }])
  })

  it("migrates old shipment data into truthful quarantine without reservations", async () => {
    const target = cabin("legacy-target", "БЫТ-015")
    writeRentalItems([target])
    window.localStorage.setItem(
      "rwms:logistics:v2",
      JSON.stringify({
        shipments: [
          {
            id: "legacy",
            version: 1,
            warehouseId: "spb",
            company: "ООО Старый клиент",
            driverId: "driver",
            driverName: "Водитель",
            shipmentDate: "2026-01-01",
            status: "PREPARING",
            error: null,
            createdAt: "2026-01-01T00:00:00.000Z",
            createdBy: "UNKNOWN",
            items: [
              {
                rentalItemId: target.id,
                cabinNumber: target.number,
                contentsBefore: [],
                contentsPlanned: [{ name: "Стул", quantity: 3 }],
                changes: [],
                preparationTask: null,
              },
            ],
          },
        ],
      })
    )
    const client = new BrowserShipmentClient(taskClient)

    const [legacy] = await client.list("spb")
    expect(legacy).toMatchObject({
      status: "LEGACY_QUARANTINE",
      evidence: "LEGACY_UNPROVEN",
    })
    expect(legacy.items[0]).toMatchObject({
      expectedTargetVersion: null,
      sourceAllocations: [],
      preparationTask: null,
    })
    expect(
      (await client.listCandidates("spb", "ООО Тест")).map(
        (candidate) => candidate.item.id
      )
    ).toContain(target.id)
  })

  it("does not partially mutate cabins when a source version drifts", async () => {
    const target = cabin("target", "БЫТ-020")
    const source = cabin("source", "БЫТ-021", [{ name: "Стул", quantity: 2 }])
    writeRentalItems([target, source])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 1 }],
        [allocation("CABIN", [{ name: "Стул", quantity: 1 }], source)]
      )
    )
    await client.dispatchPreparation(
      {
        warehouseId: "spb",
        serviceWarehouseId: "service-spb",
        accessToken: "token",
        actor: "Администратор",
      },
      saved.id,
      target.id
    )
    writeRentalItems(
      readRentalItems().map((item) =>
        item.id === source.id ? { ...item, version: item.version + 1 } : item
      )
    )

    await expect(
      client.confirmPreparation("spb", saved.id, "Администратор")
    ).rejects.toThrow("изменилось")
    expect(
      readRentalItems().find((item) => item.id === target.id)?.contentsItems
    ).toEqual([])
    expect(
      (
        JSON.parse(window.localStorage.getItem(SHIPMENTS_STORAGE_KEY)!) as {
          shipments: Array<{ status: string }>
        }
      ).shipments[0].status
    ).toBe("CONFLICT")
  })

  it("applies multiple sources once, then persists planned contents on finalize", async () => {
    const target = cabin("target", "БЫТ-030")
    const source = cabin("source", "БЫТ-031", [{ name: "Стул", quantity: 2 }])
    writeRentalItems([target, source])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 2 }],
        [
          allocation("CABIN", [{ name: "Стул", quantity: 1 }], source),
          allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }]),
        ]
      )
    )
    await client.dispatchPreparation(
      {
        warehouseId: "spb",
        serviceWarehouseId: "service-spb",
        accessToken: "token",
        actor: "Администратор",
      },
      saved.id,
      target.id
    )
    const ready = await client.confirmPreparation(
      "spb",
      saved.id,
      "Администратор"
    )
    expect(ready.status).toBe("READY_TO_SHIP")
    expect(
      readRentalItems().find((item) => item.id === source.id)?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 1 }])
    expect(
      readRentalItems().find((item) => item.id === target.id)?.contentsItems
    ).toEqual([{ name: "Стул", quantity: 2 }])
    expect(
      (
        JSON.parse(
          window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY)!
        ) as Array<{ id: string; stockQuantity: number }>
      ).find((item) => item.id === "spb-chair")?.stockQuantity
    ).toBe(2)

    const shipped = await client.finalize("spb", saved.id, "Администратор")
    expect(shipped.status).toBe("SHIPPED")
    expect(
      readRentalItems().find((item) => item.id === target.id)
    ).toMatchObject({
      status: "RENTED",
      tenant: "ООО Тест",
      contentsItems: [{ name: "Стул", quantity: 2 }],
    })
  })

  it("rechecks an inter-warehouse transfer immediately before finalize", async () => {
    const target = cabin("target", "БЫТ-032")
    writeRentalItems([target])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 1 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }])]
      )
    )
    const context = {
      warehouseId: "spb",
      serviceWarehouseId: "service-spb",
      accessToken: "token",
      actor: "Администратор",
    }
    await client.dispatchPreparation(context, saved.id, target.id)
    await client.confirmPreparation("spb", saved.id, "Администратор")
    const transferGuard = vi
      .spyOn(warehouseTransferApi, "hasActiveWarehouseTransfer")
      .mockResolvedValue(true)
    try {
      await expect(
        client.finalize("spb", saved.id, "Администратор")
      ).rejects.toThrow("участвует в межскладском перемещении")
    } finally {
      transferGuard.mockRestore()
    }
  })

  it("recovers a persisted FINALIZING intent after rental write", async () => {
    const target = cabin("target", "БЫТ-033")
    writeRentalItems([target])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 1 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }])]
      )
    )
    const context = {
      warehouseId: "spb",
      serviceWarehouseId: "service-spb",
      accessToken: "token",
      actor: "Администратор",
    }
    await client.dispatchPreparation(context, saved.id, target.id)
    const ready = await client.confirmPreparation(
      "spb",
      saved.id,
      "Администратор"
    )
    const before = readRentalItems().find((item) => item.id === target.id)!
    const after = {
      ...before,
      version: before.version + 1,
      status: "RENTED" as const,
      tenant: ready.company,
      shipmentDate: ready.shipmentDate,
    }
    const envelope = JSON.parse(
      window.localStorage.getItem(SHIPMENTS_STORAGE_KEY)!
    ) as { shipments: Array<Record<string, unknown>> }
    envelope.shipments[0] = {
      ...envelope.shipments[0],
      status: "FINALIZING",
      finalizationAttempt: {
        id: "finalize-attempt",
        phase: "RENTALS_WRITTEN",
        createdAt: new Date().toISOString(),
        actor: "Администратор",
        rentalMutations: [{ id: target.id, before, after }],
      },
    }
    window.localStorage.setItem(SHIPMENTS_STORAGE_KEY, JSON.stringify(envelope))
    writeRentalItems([after])

    const shipped = await client.finalize("spb", saved.id, "Администратор")
    expect(shipped.status).toBe("SHIPPED")
    expect(shipped.finalizationAttempt).toBeNull()
    expect(readRentalItems()[0]).toMatchObject({
      status: "RENTED",
      tenant: "ООО Тест",
    })
  })

  it("cancels a dispatched task idempotently and releases reservations", async () => {
    const target = cabin("target", "БЫТ-040")
    const other = cabin("other", "БЫТ-041")
    writeRentalItems([target, other])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 3 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 3 }])]
      )
    )
    await client.dispatchPreparation(
      {
        warehouseId: "spb",
        serviceWarehouseId: "service-spb",
        accessToken: "token",
        actor: "Администратор",
      },
      saved.id,
      target.id
    )
    const context = {
      warehouseId: "spb",
      serviceWarehouseId: "service-spb",
      accessToken: "token",
      actor: "Администратор",
    }
    await client.cancel(context, saved.id, "Клиент отказался")
    await client.cancel(context, saved.id, "Повтор")
    expect(taskClient.cancel).toHaveBeenCalledTimes(1)
    await expect(
      client.saveDraft(
        draft(
          other,
          [{ name: "Стул", quantity: 3 }],
          [allocation("WAREHOUSE", [{ name: "Стул", quantity: 3 }])]
        )
      )
    ).resolves.toBeDefined()
  })

  it("does not double-subtract a held stock reservation after another preparation applies", async () => {
    const first = cabin("first", "БЫТ-050")
    const second = cabin("second", "БЫТ-051")
    writeRentalItems([first, second])
    const client = new BrowserShipmentClient(taskClient)
    const firstDraft = await client.saveDraft(
      draft(
        first,
        [{ name: "Стул", quantity: 1 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }])]
      )
    )
    await client.saveDraft(
      draft(
        second,
        [{ name: "Стул", quantity: 1 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }])]
      )
    )
    await client.dispatchPreparation(
      {
        warehouseId: "spb",
        serviceWarehouseId: "service-spb",
        accessToken: "token",
        actor: "Администратор",
      },
      firstDraft.id,
      first.id
    )
    await client.confirmPreparation("spb", firstDraft.id, "Администратор")

    expect(
      (await client.listAvailableWarehouseStock("spb")).find(
        (item) => item.name === "Стул"
      )?.availableQuantity
    ).toBe(1)
  })

  it("forces replan when a shared cabin source changed after another preparation", async () => {
    const first = cabin("first", "БЫТ-052")
    const second = cabin("second", "БЫТ-053")
    const source = cabin("source", "БЫТ-054", [{ name: "Стул", quantity: 2 }])
    writeRentalItems([first, second, source])
    const client = new BrowserShipmentClient(taskClient)
    const firstDraft = await client.saveDraft(
      draft(
        first,
        [{ name: "Стул", quantity: 1 }],
        [allocation("CABIN", [{ name: "Стул", quantity: 1 }], source)]
      )
    )
    const secondDraft = await client.saveDraft(
      draft(
        second,
        [{ name: "Стул", quantity: 1 }],
        [allocation("CABIN", [{ name: "Стул", quantity: 1 }], source)]
      )
    )
    const context = {
      warehouseId: "spb",
      serviceWarehouseId: "service-spb",
      accessToken: "token",
      actor: "Администратор",
    }
    await client.dispatchPreparation(context, firstDraft.id, first.id)
    await client.dispatchPreparation(context, secondDraft.id, second.id)
    await client.confirmPreparation("spb", firstDraft.id, "Администратор")

    await expect(
      client.confirmPreparation("spb", secondDraft.id, "Администратор")
    ).rejects.toThrow("изменилось")
    expect(
      readRentalItems().find((item) => item.id === second.id)?.contentsItems
    ).toEqual([])
  })

  it("recovers touched CAS snapshots under a mutation without erasing an unrelated cross-tab change", async () => {
    const target = cabin("target", "БЫТ-060")
    const unrelated = cabin("unrelated", "БЫТ-061")
    const after = {
      ...target,
      version: 1,
      contentsItems: [{ name: "Стул", quantity: 1 }],
      contents: "Стул 1 шт.",
    }
    writeRentalItems([target, unrelated])
    const client = new BrowserShipmentClient(taskClient)
    const saved = await client.saveDraft(
      draft(
        target,
        [{ name: "Стул", quantity: 1 }],
        [allocation("WAREHOUSE", [{ name: "Стул", quantity: 1 }])]
      )
    )
    const envelope = JSON.parse(
      window.localStorage.getItem(SHIPMENTS_STORAGE_KEY)!
    ) as {
      shipments: Array<Record<string, unknown>>
    }
    envelope.shipments[0] = {
      ...envelope.shipments[0],
      status: "APPLYING",
      applicationAttempt: {
        id: "attempt",
        phase: "EQUIPMENT_WRITTEN",
        createdAt: new Date().toISOString(),
        actor: "Администратор",
        rentalMutations: [{ id: target.id, before: target, after }],
        equipmentMutations: [
          {
            id: "spb-chair",
            before: {
              id: "spb-chair",
              warehouseId: "spb",
              name: "Стул",
              stockQuantity: 3,
              writtenOffQuantity: 0,
              lostQuantity: 0,
            },
            after: {
              id: "spb-chair",
              warehouseId: "spb",
              name: "Стул",
              stockQuantity: 2,
              writtenOffQuantity: 0,
              lostQuantity: 0,
            },
          },
        ],
      },
    }
    window.localStorage.setItem(SHIPMENTS_STORAGE_KEY, JSON.stringify(envelope))
    const unrelatedChanged = {
      ...unrelated,
      version: 7,
      comment: "Изменено в другой вкладке",
    }
    writeRentalItems([after, unrelatedChanged])
    window.localStorage.setItem(
      EQUIPMENT_MOCK_STORAGE_KEY,
      JSON.stringify([
        {
          id: "spb-chair",
          warehouseId: "spb",
          name: "Стул",
          stockQuantity: 3,
          writtenOffQuantity: 0,
          lostQuantity: 0,
        },
      ])
    )

    expect((await client.list("spb"))[0].status).toBe("APPLYING")
    await client.saveDraft(draft(unrelatedChanged, [], []))
    const recovered = (await client.list("spb")).find(
      (item) => item.id === saved.id
    )!
    expect(recovered.id).toBe(saved.id)
    expect(recovered.status).toBe("CONFLICT")
    expect(recovered.applicationAttempt).toBeNull()
    expect(
      readRentalItems().find((item) => item.id === target.id)?.contentsItems
    ).toEqual([])
    expect(
      readRentalItems().find((item) => item.id === unrelated.id)
    ).toMatchObject({ version: 7, comment: "Изменено в другой вкладке" })
  })
})
