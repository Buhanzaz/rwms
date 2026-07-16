import "fake-indexeddb/auto"

import { beforeEach, describe, expect, it } from "vitest"

import { writeRentalItems } from "@/features/rental-items/api/rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { BrowserWarehouseTransferClient } from "@/features/logistics/warehouse-transfers/browser-warehouse-transfer-client"
import { IndexedDbWarehouseTransferMediaClient } from "@/features/logistics/warehouse-transfers/adapters/indexed-db-warehouse-transfer-media-client"
import type {
  WarehouseAccountingCorrection,
  WarehouseTransferDocument,
  WarehouseTransferEvent,
  WarehouseTransferTaskCommand,
  WarehouseTransferTaskRef,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferStore } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-store"
import type { WarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-task-client"
import { SHIPMENTS_STORAGE_KEY } from "@/features/logistics/shipments/storage"

class MemoryStore implements WarehouseTransferStore {
  documents: WarehouseTransferDocument[] = []
  events: WarehouseTransferEvent[] = []
  corrections: WarehouseAccountingCorrection[] = []
  failNextAppliedCorrectionSave = false
  failNextDepartureFinalSave = false
  failNextArrivalFinalSave = false

  list() {
    return structuredClone(this.documents)
  }
  findById(id: string) {
    return structuredClone(
      this.documents.find((item) => item.id === id) ?? null
    )
  }
  findByRequestId(requestId: string) {
    return structuredClone(
      this.documents.find((item) => item.requestId === requestId) ?? null
    )
  }
  findActiveByRentalItemId(rentalItemId: string) {
    return structuredClone(
      this.documents.find((document) =>
        document.lines.some(
          (line) =>
            line.rentalItemId === rentalItemId &&
            ["PREPARING", "READY_TO_DEPART", "IN_TRANSIT", "CONFLICT"].includes(
              line.status
            )
        )
      ) ?? null
    )
  }
  save(document: WarehouseTransferDocument, expectedVersion: number | null) {
    const index = this.documents.findIndex((item) => item.id === document.id)
    const current = this.documents[index]
    if (expectedVersion === null && current) throw new Error("duplicate")
    if (expectedVersion !== null && current?.version !== expectedVersion) {
      throw new Error("stale")
    }
    if (
      this.failNextDepartureFinalSave &&
      document.lines.some(
        (line) => line.status === "IN_TRANSIT" && !line.applicationAttempt
      )
    ) {
      this.failNextDepartureFinalSave = false
      throw new Error("simulated departure final save failure")
    }
    if (
      this.failNextArrivalFinalSave &&
      document.lines.some(
        (line) => line.status === "RECEIVED" && !line.applicationAttempt
      )
    ) {
      this.failNextArrivalFinalSave = false
      throw new Error("simulated arrival final save failure")
    }
    if (index < 0) this.documents.push(structuredClone(document))
    else this.documents[index] = structuredClone(document)
    return structuredClone(document)
  }
  appendEvent(event: WarehouseTransferEvent) {
    this.events.push(structuredClone(event))
  }
  listEventsForRentalItem(rentalItemId: string) {
    return this.events.filter((item) => item.rentalItemId === rentalItemId)
  }
  listCorrections() {
    return structuredClone(this.corrections)
  }
  findCorrection(id: string) {
    return structuredClone(
      this.corrections.find((item) => item.id === id) ?? null
    )
  }
  saveCorrection(
    correction: WarehouseAccountingCorrection,
    expectedVersion: number | null
  ) {
    if (correction.status === "APPLIED" && this.failNextAppliedCorrectionSave) {
      this.failNextAppliedCorrectionSave = false
      throw new Error("simulated final save failure")
    }
    const index = this.corrections.findIndex(
      (item) => item.id === correction.id
    )
    const current = this.corrections[index]
    if (expectedVersion === null && current) throw new Error("duplicate")
    if (expectedVersion !== null && current?.version !== expectedVersion) {
      throw new Error("stale")
    }
    if (index < 0) this.corrections.push(structuredClone(correction))
    else this.corrections[index] = structuredClone(correction)
    return structuredClone(correction)
  }
}

class FakeTaskClient implements WarehouseTransferTaskClient {
  dispatched: WarehouseTransferTaskCommand[] = []
  cancelled: string[] = []
  failDestination = false
  ambiguousSource = false
  recoveredExternalTaskIds: string[] = []
  activeTaskRefs = new Map<string, WarehouseTransferTaskRef>()

  async dispatch(_token: string, command: WarehouseTransferTaskCommand) {
    if (command.direction === "DESTINATION" && this.failDestination) {
      throw new Error("destination queue unavailable")
    }
    this.dispatched.push(structuredClone(command))
    const ref = {
      boardTaskId: `task-${command.externalTaskId}`,
      taskVersion: 0,
      queueId: `queue-${command.serviceWarehouseId}`,
      queueCode: "MOVEMENT",
    } satisfies WarehouseTransferTaskRef
    this.activeTaskRefs.set(command.externalTaskId, ref)
    if (command.direction === "SOURCE" && this.ambiguousSource) {
      throw new Error("connection closed after task creation")
    }
    return ref
  }
  async recover(_token: string, command: { externalTaskId: string }) {
    this.recoveredExternalTaskIds.push(command.externalTaskId)
    return this.activeTaskRefs.get(command.externalTaskId) ?? null
  }
  async cancel(_token: string, command: { externalTaskId: string }) {
    this.cancelled.push(command.externalTaskId)
    this.activeTaskRefs.delete(command.externalTaskId)
  }
}

const spb = {
  id: "00000000-0000-0000-0000-000000000001",
  code: "СПБ",
  name: "Склад СПБ",
  city: "Санкт-Петербург",
}
const msk = {
  id: "00000000-0000-0000-0000-000000000002",
  code: "МСК",
  name: "Склад МСК",
  city: "Москва",
}
const actor = { id: "user-1", displayName: "Иван Менеджер" }

function cabin(id: string, number: string, version = 1): RentalItemDto {
  return {
    id,
    version,
    warehouseId: "spb",
    number,
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: "row-a",
    contents: "Стол — 2",
    contentsItems: [{ name: "Стол", quantity: 2 }],
    shipmentDate: null,
    tenant: null,
    price: null,
  }
}

function command(requestId = "request-1") {
  return {
    requestId,
    accessToken: "token",
    sourceWarehouse: spb,
    destinationWarehouse: msk,
    plannedDate: "2026-07-13",
    driverName: "Пётр Водитель",
    comment: null,
    actor,
    cabins: [
      { rentalItemId: "cabin-1", expectedVersion: 1 },
      { rentalItemId: "cabin-2", expectedVersion: 1 },
    ],
  }
}

function arrivalPhoto(id: string) {
  return {
    id,
    fileName: "arrival.jpg",
    mimeType: "image/jpeg",
    rotationDegrees: 0 as const,
    storageRef: id,
    previewStorageRef: `${id}-preview`,
    originalAvailable: true,
    processingStatus: "READY" as const,
    createdAt: "2026-07-13T10:00:00.000Z",
  }
}

describe("browser warehouse transfer saga", () => {
  let store: MemoryStore
  let tasks: FakeTaskClient
  let client: BrowserWarehouseTransferClient

  beforeEach(() => {
    window.localStorage.clear()
    writeRentalItems([cabin("cabin-1", "БЫТ-001"), cabin("cabin-2", "БЫТ-002")])
    store = new MemoryStore()
    tasks = new FakeTaskClient()
    client = new BrowserWarehouseTransferClient(store, tasks)
  })

  it("creates stable per-line tasks and recovers create idempotently", async () => {
    const created = await client.create(command())
    expect(created.lines.map((line) => line.status)).toEqual([
      "READY_TO_DEPART",
      "READY_TO_DEPART",
    ])
    expect(tasks.dispatched).toHaveLength(2)

    const repeated = await client.create(command())
    expect(repeated.id).toBe(created.id)
    expect(tasks.dispatched).toHaveLength(2)
    expect(
      new Set(created.lines.map((line) => line.sourceExternalTaskId)).size
    ).toBe(2)

    await expect(
      client.create({
        ...command("request-2"),
        cabins: [command().cabins[0]],
      })
    ).rejects.toThrow("уже есть активное межскладское перемещение")
  })

  it("rejects a transfer when a selected cabin participates in an active shipment", async () => {
    window.localStorage.setItem(
      SHIPMENTS_STORAGE_KEY,
      JSON.stringify({
        service: "browser-logistics-shipments",
        schemaVersion: 1,
        revision: 1,
        shipments: [
          {
            id: "shipment-active",
            evidence: "PROVEN",
            status: "PREPARING",
            items: [{ rentalItemId: "cabin-1" }],
          },
        ],
      })
    )

    await expect(client.create(command())).rejects.toThrow("активной отгрузке")
    expect(store.documents).toHaveLength(0)
    expect(tasks.dispatched).toHaveLength(0)
  })

  it("rejects a second operation while an active transfer exists", async () => {
    await client.create(command())

    await expect(
      client.createCorrection({
        rentalItemId: "cabin-1",
        expectedRentalItemVersion: 1,
        sourceWarehouse: spb,
        destinationWarehouse: msk,
        reason: "Дублирующая коррекция",
        actor,
      })
    ).rejects.toThrow("активное межскладское перемещение")
  })

  it("moves lines independently and rejects stale CAS", async () => {
    const created = await client.create(command())
    const first = created.lines[0]
    const departed = await client.confirmDeparture({
      documentId: created.id,
      lineId: first.id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: first.version,
      accessToken: "token",
      actor,
    })
    expect(departed.lines[0].status).toBe("IN_TRANSIT")
    expect(departed.lines[1].status).toBe("READY_TO_DEPART")
    expect(
      tasks.dispatched.filter((item) => item.direction === "DESTINATION")
    ).toHaveLength(1)

    await expect(
      client.confirmDeparture({
        documentId: created.id,
        lineId: created.lines[1].id,
        expectedDocumentVersion: created.version,
        expectedLineVersion: created.lines[1].version,
        accessToken: "token",
        actor,
      })
    ).rejects.toThrow("Перемещение изменено")
  })

  it("recovers departure after the cabin write succeeded but final store save failed", async () => {
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    store.failNextDepartureFinalSave = true
    await expect(
      client.confirmDeparture({
        documentId: created.id,
        lineId: created.lines[0].id,
        expectedDocumentVersion: created.version,
        expectedLineVersion: created.lines[0].version,
        accessToken: "token",
        actor,
      })
    ).rejects.toThrow("simulated departure final save failure")

    const intent = store.findById(created.id)!
    expect(intent.lines[0].applicationAttempt?.kind).toBe("DEPARTURE")
    const recovered = await client.confirmDeparture({
      documentId: intent.id,
      lineId: intent.lines[0].id,
      expectedDocumentVersion: intent.version,
      expectedLineVersion: intent.lines[0].version,
      accessToken: "token",
      actor,
    })
    expect(recovered.lines[0].status).toBe("IN_TRANSIT")
    expect(recovered.lines[0].applicationAttempt).toBeNull()
    expect(
      tasks.dispatched.filter((item) => item.direction === "SOURCE")
    ).toHaveLength(1)
    expect(
      tasks.dispatched.filter((item) => item.direction === "DESTINATION")
    ).toHaveLength(1)
    expect(
      store.events.filter((item) => item.type === "DEPARTURE_CONFIRMED")
    ).toHaveLength(1)
  })

  it("keeps a mismatched arrival in conflict, then receives an exact retry", async () => {
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    const departed = await client.confirmDeparture({
      documentId: created.id,
      lineId: created.lines[0].id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: created.lines[0].version,
      accessToken: "token",
      actor,
    })
    const line = departed.lines[0]
    const conflicted = await client.acceptArrival({
      documentId: departed.id,
      lineId: line.id,
      expectedDocumentVersion: departed.version,
      expectedLineVersion: line.version,
      actualContents: [{ name: "Стол", quantity: 1 }],
      photos: [
        {
          id: "photo-1",
          fileName: "arrival.jpg",
          mimeType: "image/jpeg",
          rotationDegrees: 0,
          storageRef: "photo-1",
          previewStorageRef: "photo-1-preview",
          originalAvailable: true,
          processingStatus: "READY",
          createdAt: "2026-07-13T10:00:00.000Z",
        },
      ],
      actor,
    })
    expect(conflicted.lines[0].status).toBe("CONFLICT")

    const received = await client.acceptArrival({
      documentId: conflicted.id,
      lineId: conflicted.lines[0].id,
      expectedDocumentVersion: conflicted.version,
      expectedLineVersion: conflicted.lines[0].version,
      actualContents: [{ name: "Стол", quantity: 2 }],
      photos: conflicted.lines[0].photos,
      actor,
    })
    expect(received.lines[0].status).toBe("RECEIVED")
    const storedCabin = JSON.parse(
      window.localStorage.getItem("wms:mock-rental-items") ?? "[]"
    ) as RentalItemDto[]
    expect(storedCabin[0]).toMatchObject({
      warehouseId: "msk",
      status: "WAREHOUSE",
      locationNodeId: null,
      version: 3,
    })
  })

  it("blocks arrival until the destination task is registered", async () => {
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    tasks.failDestination = true
    const departed = await client.confirmDeparture({
      documentId: created.id,
      lineId: created.lines[0].id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: created.lines[0].version,
      accessToken: "token",
      actor,
    })
    expect(departed.lines[0].destinationTask).toBeNull()
    await expect(
      client.acceptArrival({
        documentId: departed.id,
        lineId: departed.lines[0].id,
        expectedDocumentVersion: departed.version,
        expectedLineVersion: departed.lines[0].version,
        actualContents: departed.lines[0].contentsSnapshot,
        photos: [
          {
            id: "photo-without-task",
            fileName: "arrival.jpg",
            mimeType: "image/jpeg",
            rotationDegrees: 0,
            storageRef: "photo-without-task",
            previewStorageRef: "photo-without-task-preview",
            originalAvailable: true,
            processingStatus: "READY",
            createdAt: "2026-07-13T10:00:00.000Z",
          },
        ],
        actor,
      })
    ).rejects.toThrow("Сначала зарегистрируйте задание приёмки")

    tasks.failDestination = false
    const recovered = await client.retryDestinationTask({
      documentId: departed.id,
      lineId: departed.lines[0].id,
      expectedDocumentVersion: departed.version,
      accessToken: "token",
      actor,
    })
    expect(recovered.lines[0].destinationTask).not.toBeNull()
  })

  it("recovers arrival after the cabin write succeeded but final store save failed", async () => {
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    const departed = await client.confirmDeparture({
      documentId: created.id,
      lineId: created.lines[0].id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: created.lines[0].version,
      accessToken: "token",
      actor,
    })
    store.failNextArrivalFinalSave = true
    await expect(
      client.acceptArrival({
        documentId: departed.id,
        lineId: departed.lines[0].id,
        expectedDocumentVersion: departed.version,
        expectedLineVersion: departed.lines[0].version,
        actualContents: departed.lines[0].contentsSnapshot,
        photos: [arrivalPhoto("arrival-intent-photo")],
        actor,
      })
    ).rejects.toThrow("simulated arrival final save failure")

    const intent = store.findById(departed.id)!
    expect(intent.lines[0].applicationAttempt?.kind).toBe("ARRIVAL")
    const recovered = await client.acceptArrival({
      documentId: intent.id,
      lineId: intent.lines[0].id,
      expectedDocumentVersion: intent.version,
      expectedLineVersion: intent.lines[0].version,
      actualContents: [],
      photos: [],
      actor,
    })
    expect(recovered.lines[0].status).toBe("RECEIVED")
    expect(recovered.lines[0].photos).toEqual([
      arrivalPhoto("arrival-intent-photo"),
    ])
    expect(
      store.events.filter((item) => item.type === "ARRIVAL_CONFIRMED")
    ).toHaveLength(1)
    expect(tasks.dispatched).toHaveLength(2)
  })

  it("recovers an ambiguously created source task before cancellation", async () => {
    tasks.ambiguousSource = true
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    const line = created.lines[0]
    expect(line.status).toBe("PREPARING")
    expect(line.sourceTask).toBeNull()
    expect(tasks.activeTaskRefs.has(line.sourceExternalTaskId)).toBe(true)

    const cancelled = await client.cancelLine({
      documentId: created.id,
      lineId: line.id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: line.version,
      accessToken: "token",
      actor,
      reason: "Рейс отменён после ошибки связи",
    })
    expect(cancelled.lines[0].status).toBe("CANCELLED")
    expect(tasks.recoveredExternalTaskIds).toContain(line.sourceExternalTaskId)
    expect(tasks.cancelled).toContain(line.sourceExternalTaskId)
    expect(tasks.activeTaskRefs.has(line.sourceExternalTaskId)).toBe(false)
  })

  it("cancels a task only before departure", async () => {
    const created = await client.create({
      ...command(),
      cabins: [command().cabins[0]],
    })
    const cancelled = await client.cancelLine({
      documentId: created.id,
      lineId: created.lines[0].id,
      expectedDocumentVersion: created.version,
      expectedLineVersion: created.lines[0].version,
      accessToken: "token",
      actor,
      reason: "Рейс отменён",
    })
    expect(cancelled.lines[0].status).toBe("CANCELLED")
    expect(tasks.cancelled).toEqual([created.lines[0].sourceExternalTaskId])
  })

  it("applies an accounting correction only after both warehouses approve", async () => {
    const correction = await client.createCorrection({
      rentalItemId: "cabin-1",
      expectedRentalItemVersion: 1,
      sourceWarehouse: spb,
      destinationWarehouse: msk,
      reason: "Фактически находится в Москве",
      actor,
    })
    const sourceApproved = await client.approveCorrection({
      correctionId: correction.id,
      expectedVersion: correction.version,
      approvingWarehouseId: spb.id,
      actor,
    })
    expect(sourceApproved.status).toBe("PENDING_APPROVALS")
    const applied = await client.approveCorrection({
      correctionId: correction.id,
      expectedVersion: sourceApproved.version,
      approvingWarehouseId: msk.id,
      actor,
    })
    expect(applied.status).toBe("APPLIED")
    const storedCabin = JSON.parse(
      window.localStorage.getItem("wms:mock-rental-items") ?? "[]"
    ) as RentalItemDto[]
    expect(storedCabin[0].warehouseId).toBe("msk")
    expect(tasks.dispatched).toHaveLength(0)
  })

  it("recovers an APPLYING correction after the cabin mutation succeeded", async () => {
    const correction = await client.createCorrection({
      rentalItemId: "cabin-1",
      expectedRentalItemVersion: 1,
      sourceWarehouse: spb,
      destinationWarehouse: msk,
      reason: "Фактически находится в Москве",
      actor,
    })
    const sourceApproved = await client.approveCorrection({
      correctionId: correction.id,
      expectedVersion: correction.version,
      approvingWarehouseId: spb.id,
      actor,
    })
    store.failNextAppliedCorrectionSave = true
    await expect(
      client.approveCorrection({
        correctionId: correction.id,
        expectedVersion: sourceApproved.version,
        approvingWarehouseId: msk.id,
        actor,
      })
    ).rejects.toThrow("simulated final save failure")
    const intent = store.findCorrection(correction.id)!
    expect(intent.status).toBe("APPLYING")
    const recovered = await client.approveCorrection({
      correctionId: correction.id,
      expectedVersion: intent.version,
      approvingWarehouseId: msk.id,
      actor,
    })
    expect(recovered.status).toBe("APPLIED")
  })

  it("stores arrival photo blobs behind durable media references", async () => {
    const media = new IndexedDbWarehouseTransferMediaClient()
    const refs = await media.upload([
      {
        id: `transfer-photo-${crypto.randomUUID()}`,
        fileName: "arrival.png",
        dataUrl:
          "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        rotationDegrees: 0,
      },
    ])
    expect(refs[0]).toMatchObject({
      fileName: "arrival.png",
      originalAvailable: true,
      processingStatus: "READY",
    })
    expect(refs[0].storageRef).not.toBe("")
    await media.discard(refs.map((item) => item.id))
  })
})
