import { beforeEach, describe, expect, it, vi } from "vitest"

import { BrowserRentalItemDossierAdapter } from "@/features/rental-items/dossier/adapters/browser-rental-item-dossier-adapter"
import { INVENTORY_STORAGE_KEY } from "@/features/inventory/adapters/local-storage-inventory-adapter"
import { LOGISTICS_STORAGE_KEY } from "@/features/logistics/api/logistics-api"
import { SHIPMENTS_STORAGE_KEY } from "@/features/logistics/shipments/storage"
import { WAREHOUSE_TRANSFERS_STORAGE_KEY } from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import { REPAIR_TASKS_MOCK_STORAGE_KEY } from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import { LocalStorageContentsTransferStore } from "@/features/rental-items/contents-transfer/adapters/local-storage-contents-transfer-store"
import { writeRentalItems } from "@/features/rental-items/api/rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const currentActor = { id: "user-1", displayName: "Иван Петров" }

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
  return {
    id: "spb-cabin-1",
    version: 0,
    warehouseId: "spb",
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Обычная",
    characteristics: null,
    linoleum: false,
    status: "FREE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    previewPhotoUrls: [],
    locationNodeId: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    ...overrides,
  }
}

describe("BrowserRentalItemDossierAdapter", () => {
  beforeEach(() => {
    window.localStorage.clear()
    writeRentalItems([rentalItem()], false)
  })

  it("does not invent history or placeholder reserves and returns", async () => {
    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(dossier).not.toBeNull()
    expect(dossier?.activities).toEqual([])
    expect(dossier?.inspections).toEqual([])
    expect(dossier?.estimates).toEqual([])
    expect(dossier?.repairs).toEqual([])
    expect(dossier?.reservations).toEqual([])
    expect(dossier?.returns).toEqual([])
    expect(dossier?.repairAction).toEqual({
      type: "CREATE_REPAIR",
      disabledReason: null,
    })
  })

  it("appends immutable comments and keeps actor and event time", async () => {
    const adapter = new BrowserRentalItemDossierAdapter()
    const saved = await adapter.addComment({
      rentalItemId: "spb-cabin-1",
      warehouseId: "spb",
      actor: currentActor,
      text: "  Проверить дверь  ",
    })

    expect(saved.comments).toEqual([
      expect.objectContaining({
        text: "Проверить дверь",
        actor: currentActor,
        sourceType: "MANUAL",
        editable: false,
      }),
    ])
    expect(saved.activities).toEqual([
      expect.objectContaining({
        type: "COMMENT_ADDED",
        comment: "Проверить дверь",
        actor: currentActor,
      }),
    ])
    expect(Number.isNaN(Date.parse(saved.activities[0]!.occurredAt))).toBe(
      false
    )
  })

  it("requires a reason, records the transition and rejects a stale version", async () => {
    const adapter = new BrowserRentalItemDossierAdapter()
    await expect(
      adapter.updateStatus({
        rentalItemId: "spb-cabin-1",
        warehouseId: "spb",
        expectedVersion: 0,
        actor: currentActor,
        status: "WAREHOUSE",
        reason: " ",
      })
    ).rejects.toThrow("Укажите причину")

    const saved = await adapter.updateStatus({
      rentalItemId: "spb-cabin-1",
      warehouseId: "spb",
      expectedVersion: 0,
      actor: currentActor,
      status: "WAREHOUSE",
      reason: "Перемещена на хранение",
    })
    expect(saved.rentalItem).toMatchObject({ version: 1, status: "WAREHOUSE" })
    expect(saved.activities[0]).toMatchObject({
      type: "STATUS_CHANGED",
      statusTransition: {
        from: "FREE",
        to: "WAREHOUSE",
        reason: "Перемещена на хранение",
      },
    })

    await expect(
      adapter.updateStatus({
        rentalItemId: "spb-cabin-1",
        warehouseId: "spb",
        expectedVersion: 1,
        actor: currentActor,
        status: "WAREHOUSE",
        reason: "Повтор без изменения",
      })
    ).rejects.toThrow("отличный от текущего")

    await expect(
      adapter.updateStatus({
        rentalItemId: "spb-cabin-1",
        warehouseId: "spb",
        expectedVersion: 0,
        actor: currentActor,
        status: "OWN_NEEDS",
        reason: "Повторная команда",
      })
    ).rejects.toThrow("Данные бытовки изменились")
  })

  it("versions the editable general comment and blocks service statuses", async () => {
    const adapter = new BrowserRentalItemDossierAdapter()
    const saved = await adapter.updateGeneralComment({
      rentalItemId: "spb-cabin-1",
      warehouseId: "spb",
      expectedVersion: 0,
      actor: currentActor,
      comment: "  Общий комментарий  ",
    })
    expect(saved.rentalItem).toMatchObject({
      version: 1,
      comment: "Общий комментарий",
    })
    expect(saved.activities[0]).toMatchObject({
      type: "GENERAL_COMMENT_UPDATED",
      comment: "Общий комментарий",
    })
    expect(saved.comments).toEqual([
      expect.objectContaining({
        id: `${saved.activities[0]!.id}:comment`,
        text: "Общий комментарий",
        actor: currentActor,
        sourceLabel: "Общий комментарий",
        editable: false,
      }),
    ])

    const reloaded = await adapter.get("spb", "spb-cabin-1")
    expect(reloaded?.comments).toHaveLength(1)

    const cleared = await adapter.updateGeneralComment({
      rentalItemId: "spb-cabin-1",
      warehouseId: "spb",
      expectedVersion: 1,
      actor: currentActor,
      comment: " ",
    })
    expect(cleared.rentalItem.comment).toBeNull()
    expect(
      cleared.activities.filter(
        (activity) => activity.type === "GENERAL_COMMENT_UPDATED"
      )
    ).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ comment: "Общий комментарий" }),
        expect.objectContaining({ comment: null }),
      ])
    )
    expect(cleared.comments).toHaveLength(1)

    writeRentalItems([rentalItem({ status: "RENTED" })], false)
    await expect(
      adapter.updateStatus({
        rentalItemId: "spb-cabin-1",
        warehouseId: "spb",
        expectedVersion: 0,
        actor: currentActor,
        status: "FREE",
        reason: "Недопустимый обход аренды",
      })
    ).rejects.toThrow("активного рабочего процесса")
  })

  it("blocks direct repair creation when a proven active repair exists", async () => {
    window.localStorage.setItem(
      REPAIR_TASKS_MOCK_STORAGE_KEY,
      JSON.stringify({
        service: "repair-tasks",
        schemaVersion: 4,
        revision: 1,
        tasks: [
          {
            id: "repair-active",
            version: 0,
            status: "DRAFT",
            kind: "REPAIR",
            origin: "DIRECT_REPAIR",
            acceptanceStatus: "NOT_READY",
            startedAt: null,
            completedAt: null,
            acceptanceDecidedAt: null,
            acceptanceDecidedBy: null,
            acceptanceComment: null,
            warehouseId: "spb",
            rentalItemId: "spb-cabin-1",
            cabinNumber: "БЫТ-001",
            authorName: "Мастер",
            reason: "Требуется ремонт",
            dispatchDate: null,
            comment: "",
            media: [],
            subtasks: [],
            sourceEstimateId: null,
            sourceEstimateVersion: null,
            sourceInventoryId: null,
            sourceInventoryFindingId: null,
            sourceRepairTaskId: null,
            sourceRepairTaskVersion: null,
            createdAt: "2026-07-11T08:00:00.000Z",
            updatedAt: "2026-07-11T08:00:00.000Z",
          },
        ],
      })
    )

    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(dossier?.repairAction).toEqual({
      type: "NONE",
      disabledReason: "У бытовки уже есть активный ремонт",
    })
  })

  it("projects one mirrored contents-transfer event into each cabin history", async () => {
    writeRentalItems(
      [
        rentalItem({ contentsItems: [{ name: "Стул", quantity: 2 }] }),
        rentalItem({
          id: "spb-cabin-2",
          number: "БЫТ-002",
          contentsItems: [{ name: "Стул", quantity: 2 }],
        }),
      ],
      false
    )
    const record = {
      id: "transfer-1",
      externalTaskId: "transfer-1",
      status: "APPLIED" as const,
      warehouseId: "spb",
      serviceWarehouseId: "service-spb",
      occurredAt: "2026-07-12T11:00:00.000Z",
      actor: currentActor,
      source: {
        rentalItemId: "spb-cabin-1",
        number: "БЫТ-001",
        versionBefore: 0,
        versionAfter: 1,
      },
      target: {
        rentalItemId: "spb-cabin-2",
        number: "БЫТ-002",
        versionBefore: 0,
        versionAfter: 1,
      },
      items: [{ name: "Стул", quantity: 2 }],
      task: {
        boardTaskId: "task-1",
        queueId: "queue-move",
        queueCode: "MOVE",
      },
    }
    await new LocalStorageContentsTransferStore().saveAttempt({
      externalTaskId: record.externalTaskId,
      phase: "APPLIED",
      command: {
        externalTaskId: record.externalTaskId,
        warehouseId: record.warehouseId,
        serviceWarehouseId: record.serviceWarehouseId,
        actor: record.actor,
        source: {
          rentalItemId: record.source.rentalItemId,
          number: record.source.number,
          expectedVersion: record.source.versionBefore,
        },
        target: {
          rentalItemId: record.target.rentalItemId,
          number: record.target.number,
          expectedVersion: record.target.versionBefore,
        },
        items: record.items,
      },
      task: record.task,
      record,
      error: null,
      createdAt: record.occurredAt,
      updatedAt: record.occurredAt,
    })

    const adapter = new BrowserRentalItemDossierAdapter()
    const source = await adapter.get("spb", "spb-cabin-1")
    const target = await adapter.get("spb", "spb-cabin-2")

    expect(source?.activities).toContainEqual(
      expect.objectContaining({
        id: "transfer-1:source",
        type: "CONTENTS_TRANSFERRED",
        sourceId: "transfer-1",
        sourceLabel: "Передано в БЫТ-002",
        comment: "Стул — 2 шт.",
        actor: currentActor,
        occurredAt: "2026-07-12T11:00:00.000Z",
        links: [],
      })
    )
    expect(target?.activities).toContainEqual(
      expect.objectContaining({
        id: "transfer-1:target",
        type: "CONTENTS_TRANSFERRED",
        sourceId: "transfer-1",
        sourceLabel: "Получено из БЫТ-001",
      })
    )
  })

  it("groups proven inventory media and projects only completed rental movements", async () => {
    const media = {
      id: "inspection-photo-1",
      fileName: "inspection.jpg",
      mimeType: "image/jpeg",
      rotationDegrees: 0 as const,
      variants: {
        small: {
          url: "/thumb.jpg",
          storageRef: "inspection-photo-1",
          mimeType: "image/webp",
          width: 360,
          height: 240,
        },
        largeWebp: {
          url: "/preview.webp",
          storageRef: "inspection-photo-1",
          mimeType: "image/webp",
          width: 1600,
          height: 900,
        },
      },
      createdAt: "2026-07-08T08:30:00.000Z",
    }
    const returnMedia = {
      ...media,
      id: "return-photo-1",
      createdAt: "2026-07-10T09:00:00.000Z",
      variants: {
        small: { ...media.variants.small, storageRef: "return-photo-1" },
        largeWebp: {
          ...media.variants.largeWebp,
          storageRef: "return-photo-1",
        },
      },
    }
    vi.spyOn(
      IndexedDbRepairEstimateMediaAdapter.prototype,
      "hydrate"
    ).mockImplementation(async (refs) => refs)
    window.localStorage.setItem(
      INVENTORY_STORAGE_KEY,
      JSON.stringify({
        service: "inventory",
        schemaVersion: 2,
        revision: 1,
        sessions: [
          {
            id: "inventory-1",
            version: 2,
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
              displayName: "Начальник склада",
              permissions: ["MANAGE"],
              authorizedWarehouseIds: null,
            },
            businessDate: "2026-07-08",
            startedAt: "2026-07-08T08:00:00.000Z",
            completedAt: "2026-07-08T09:00:00.000Z",
            expectedItems: [],
            statistics: null,
            publicationStatus: "NOT_REQUESTED",
            findings: [
              {
                id: "finding-1",
                rentalItemId: "spb-cabin-1",
                canonicalNumber: "БЫТ-001",
                cabinNumber: "БЫТ-001",
                origin: "EXPECTED",
                inspectionStatus: "READY",
                reconciliationStatus: "MATCHED",
                expectedSnapshot: null,
                currentSnapshot: null,
                conflicts: [],
                comment: "Осмотрена",
                media: [media],
                lines: [],
                repairCompletionMode: null,
                movementRequired: false,
                repairPlans: [],
                publicationStatus: "NOT_REQUIRED",
                publicationOperationKey: null,
                publishedRepairTaskId: null,
                publicationError: null,
                inspectedAt: "2026-07-08T08:45:00.000Z",
                inspectedBy: {
                  id: "worker-1",
                  displayName: "Пётр Осмотров",
                  permissions: ["EDIT"],
                  authorizedWarehouseIds: ["spb"],
                },
              },
            ],
          },
        ],
      })
    )
    window.localStorage.setItem(
      LOGISTICS_STORAGE_KEY,
      JSON.stringify({
        service: "logistics",
        schemaVersion: 2,
        revision: 1,
        preparationDispatches: [],
        shipments: [
          {
            id: "shipment-draft",
            version: 0,
            warehouseId: "spb",
            company: "Черновик",
            driverId: "driver-1",
            driverName: "Водитель",
            shipmentDate: "2026-07-11",
            status: "PREPARING",
            error: null,
            createdAt: "2026-07-11T08:00:00.000Z",
            createdBy: "Логист",
            items: [
              {
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                contentsBefore: [],
                contentsPlanned: [],
                changes: [],
                preparationTask: null,
              },
            ],
          },
          {
            id: "shipment-accepted",
            version: 1,
            warehouseId: "spb",
            company: "ООО Клиент",
            driverId: "driver-1",
            driverName: "Водитель",
            shipmentDate: "2026-07-09",
            status: "SHIPPED",
            error: null,
            createdAt: "2026-07-09T08:00:00.000Z",
            createdBy: "Логист",
            items: [
              {
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                contentsBefore: [],
                contentsPlanned: [{ name: "Стул", quantity: 2 }],
                changes: [],
                preparationTask: null,
              },
            ],
          },
        ],
        returnReceipts: [
          {
            id: "receipt-pending",
            version: 0,
            warehouseId: "spb",
            fromParty: "ООО Ожидание",
            driverId: null,
            driverName: null,
            returnDate: "2026-07-12",
            receptionMethod: "WAREHOUSE_INSPECTION",
            createdAt: "2026-07-12T08:00:00.000Z",
            createdBy: "Кладовщик",
            updatedAt: null,
            updatedBy: null,
            items: [
              {
                id: "return-item-pending",
                version: 0,
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                selectionSource: "MANUAL",
                originalTenant: null,
                contentsMode: "FACTUAL",
                expectedContents: [],
                returnedContents: [],
                technicalState: "PENDING_INSPECTION",
                acceptedAt: null,
                media: [],
                sourceEstimateId: null,
                pendingEstimateId: null,
                estimateClaimId: null,
                estimateClaimedAt: null,
              },
            ],
          },
          {
            id: "receipt-accepted",
            version: 1,
            warehouseId: "spb",
            fromParty: "ООО Клиент",
            driverId: null,
            driverName: "Водитель",
            returnDate: "2026-07-10",
            receptionMethod: "WAREHOUSE_INSPECTION",
            createdAt: "2026-07-10T08:00:00.000Z",
            createdBy: "Кладовщик",
            updatedAt: "2026-07-10T09:00:00.000Z",
            updatedBy: "Приёмщик",
            items: [
              {
                id: "return-item-accepted",
                version: 1,
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                selectionSource: "CLIENT_LIST",
                originalTenant: "ООО Клиент",
                contentsMode: "FACTUAL",
                expectedContents: [],
                returnedContents: [{ name: "Стул", quantity: 2 }],
                technicalState: "ACCEPTED",
                acceptedAt: "2026-07-10T09:00:00.000Z",
                media: [returnMedia],
                sourceEstimateId: null,
                pendingEstimateId: null,
                estimateClaimId: null,
                estimateClaimedAt: null,
              },
            ],
          },
        ],
      })
    )

    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(dossier?.photoGroups).toContainEqual(
      expect.objectContaining({
        id: "inventory:inventory-1:finding-1:photos",
        occurredAt: "2026-07-08T08:45:00.000Z",
        actor: { id: "worker-1", displayName: "Пётр Осмотров" },
        stage: "ACCEPTANCE",
        photos: [
          expect.objectContaining({
            id: "inspection-photo-1",
            stage: "ACCEPTANCE",
            originalAvailable: false,
          }),
        ],
      })
    )
    expect(dossier?.inspections).toContainEqual(
      expect.objectContaining({
        id: "return:receipt-accepted:return-item-accepted",
        sourceType: "RETURN_RECEIPT",
        inventoryId: null,
        returnReceiptId: "receipt-accepted",
        findingId: "return-item-accepted",
        occurredAt: "2026-07-10T09:00:00.000Z",
        actor: { id: null, displayName: "Приёмщик" },
        status: "READY",
        photoGroupId: "return:receipt-accepted:return-item-accepted:photos",
        links: [
          expect.objectContaining({
            href: "/logistics/returns?receiptId=receipt-accepted&returnItemId=return-item-accepted",
          }),
        ],
      })
    )
    expect(dossier?.comments).toContainEqual(
      expect.objectContaining({
        text: "Осмотрена",
        sourceType: "INVENTORY",
      })
    )
    expect(dossier?.shipments.map((item) => item.id)).toEqual([
      "return:receipt-accepted:return-item-accepted",
      "shipment-accepted",
    ])
    expect(dossier?.activities.map((item) => item.sourceId)).not.toContain(
      "shipment-draft"
    )
    expect(dossier?.activities.map((item) => item.sourceId)).not.toContain(
      "receipt-pending"
    )
    expect(
      dossier?.shipments.find((item) => item.id === "shipment-accepted")?.link
        .href
    ).toBe("/logistics/shipments?shipmentId=shipment-accepted")
    expect(
      dossier?.shipments.find(
        (item) => item.id === "return:receipt-accepted:return-item-accepted"
      )?.link.href
    ).toBe(
      "/logistics/returns?receiptId=receipt-accepted&returnItemId=return-item-accepted"
    )
    expect(dossier?.activities[0]?.occurredAt).toBe("2026-07-10T09:00:00.000Z")
  })

  it("projects audited shipment creation, dispatch, preparation and finalization without inventing migrated lifecycle", async () => {
    window.localStorage.setItem(
      SHIPMENTS_STORAGE_KEY,
      JSON.stringify({
        service: "browser-logistics-shipments",
        schemaVersion: 1,
        revision: 2,
        shipments: [
          {
            id: "shipment-audited",
            version: 4,
            warehouseId: "spb",
            company: "ООО Север",
            driverId: "driver-1",
            driverName: "Илья Водитель",
            shipmentDate: "2026-07-12",
            status: "SHIPPED",
            error: null,
            createdAt: "2026-07-12T08:00:00.000Z",
            createdBy: "Анна Логист",
            items: [
              {
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                expectedTargetVersion: 1,
                contentsBefore: [],
                contentsPlanned: [{ name: "Стул", quantity: 3 }],
                changes: [],
                sourceAllocations: [],
                preparationState: "READY",
                preparationTask: null,
                conflict: null,
              },
            ],
            audit: [
              {
                id: "shipment-audit-created",
                type: "STOCK_RESERVED",
                occurredAt: "2026-07-12T08:00:00.000Z",
                actor: "Анна Логист",
                details: "Источники мебели зарезервированы",
              },
              {
                id: "shipment-audit-dispatched",
                type: "TASK_DISPATCHED",
                occurredAt: "2026-07-12T08:10:00.000Z",
                actor: "Анна Логист",
                details: "Задача подготовки БЫТ-001 зарегистрирована",
              },
              {
                id: "shipment-audit-prepared",
                type: "PREPARATION_CONFIRMED",
                occurredAt: "2026-07-12T09:00:00.000Z",
                actor: "Пётр Кладовщик",
                details: "Фактическая подготовка подтверждена",
              },
              {
                id: "shipment-audit-finalized",
                type: "SHIPMENT_FINALIZED",
                occurredAt: "2026-07-12T10:00:00.000Z",
                actor: "Анна Логист",
                details: "Отгрузка завершена",
              },
            ],
            applicationAttempt: null,
          },
          {
            id: "shipment-migrated",
            version: 1,
            warehouseId: "spb",
            company: "Старый клиент",
            driverId: "driver-2",
            driverName: "Не зафиксирован",
            shipmentDate: "2026-07-01",
            status: "SHIPPED",
            error: null,
            createdAt: "2026-07-01T08:00:00.000Z",
            createdBy: "Не зафиксирован",
            items: [
              {
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                expectedTargetVersion: 0,
                contentsBefore: [],
                contentsPlanned: [],
                changes: [],
                sourceAllocations: [],
                preparationState: "PLANNED",
                preparationTask: null,
                conflict: null,
              },
            ],
            audit: [],
            applicationAttempt: null,
          },
        ],
      })
    )

    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(
      dossier?.activities
        .filter((activity) => activity.sourceId === "shipment-audited")
        .map((activity) => activity.type)
    ).toEqual([
      "SHIPPED",
      "SHIPMENT_PREPARED",
      "SHIPMENT_TASK_DISPATCHED",
      "SHIPMENT_CREATED",
    ])
    expect(
      dossier?.activities.find(
        (activity) => activity.type === "SHIPMENT_TASK_DISPATCHED"
      )
    ).toMatchObject({
      actor: { id: null, displayName: "Анна Логист" },
      parentActivityId:
        "shipment:shipment-audited:spb-cabin-1:audit:shipment-audit-created",
      links: [
        {
          kind: "SHIPMENT",
          label: "Открыть отгрузку",
          href: "/logistics/shipments?shipmentId=shipment-audited",
        },
      ],
    })
    expect(dossier?.shipments).toContainEqual(
      expect.objectContaining({
        id: "shipment:shipment-audited:spb-cabin-1",
        occurredAt: "2026-07-12T10:00:00.000Z",
        contents: [{ name: "Стул", quantity: 3 }],
      })
    )
    expect(
      dossier?.activities.some(
        (activity) => activity.sourceId === "shipment-migrated"
      )
    ).toBe(false)
  })

  it("projects transfer creation, departure, receipt and accounting correction from proven events", async () => {
    window.localStorage.setItem(
      WAREHOUSE_TRANSFERS_STORAGE_KEY,
      JSON.stringify({
        documents: [
          {
            id: "transfer-document-1",
            requestId: "request-1",
            version: 4,
            sourceWarehouse: {
              id: "00000000-0000-0000-0000-000000000002",
              code: "МСК",
              name: "Склад МСК",
              city: "Москва",
            },
            destinationWarehouse: {
              id: "00000000-0000-0000-0000-000000000001",
              code: "СПБ",
              name: "Склад СПБ",
              city: "Санкт-Петербург",
            },
            plannedDate: "2026-07-12",
            driverName: "Илья Водитель",
            vehicle: "А123АА",
            comment: "Перевозка по заявке",
            actor: currentActor,
            createdAt: "2026-07-12T07:00:00.000Z",
            updatedAt: "2026-07-12T11:00:00.000Z",
            lines: [
              {
                id: "transfer-line-1",
                version: 4,
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                sourceStatus: "FREE",
                rentalItemVersion: 2,
                contentsSnapshot: [{ name: "Стол", quantity: 1 }],
                status: "RECEIVED",
                sourceExternalTaskId: "source-task-1",
                destinationExternalTaskId: "destination-task-1",
                sourceTask: null,
                destinationTask: null,
                photos: [],
                departedAt: "2026-07-12T09:00:00.000Z",
                receivedAt: "2026-07-12T11:00:00.000Z",
                conflictReason: null,
                lastError: null,
                applicationAttempt: null,
              },
            ],
          },
        ],
        events: [
          {
            id: "transfer-departed",
            documentId: "transfer-document-1",
            lineId: "transfer-line-1",
            rentalItemId: "spb-cabin-1",
            type: "DEPARTURE_CONFIRMED",
            occurredAt: "2026-07-12T09:00:00.000Z",
            actor: { id: "user-2", displayName: "Кладовщик МСК" },
            comment: null,
          },
          {
            id: "transfer-received",
            documentId: "transfer-document-1",
            lineId: "transfer-line-1",
            rentalItemId: "spb-cabin-1",
            type: "ARRIVAL_CONFIRMED",
            occurredAt: "2026-07-12T11:00:00.000Z",
            actor: { id: "user-3", displayName: "Кладовщик СПБ" },
            comment: null,
          },
          {
            id: "correction-created",
            documentId: "correction-1",
            lineId: null,
            rentalItemId: "spb-cabin-1",
            type: "ACCOUNTING_CORRECTION_CREATED",
            occurredAt: "2026-07-12T12:00:00.000Z",
            actor: currentActor,
            comment: "Исправление склада",
          },
          {
            id: "correction-applied",
            documentId: "correction-1",
            lineId: null,
            rentalItemId: "spb-cabin-1",
            type: "ACCOUNTING_CORRECTION_APPLIED",
            occurredAt: "2026-07-12T13:00:00.000Z",
            actor: currentActor,
            comment: null,
          },
          {
            id: "correction-applied",
            documentId: "correction-1",
            lineId: null,
            rentalItemId: "spb-cabin-1",
            type: "ACCOUNTING_CORRECTION_APPLIED",
            occurredAt: "2026-07-12T13:00:00.000Z",
            actor: currentActor,
            comment: null,
          },
        ],
        corrections: [],
      })
    )

    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(dossier?.activities).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "warehouse-transfer:transfer-document-1:transfer-line-1:created",
          type: "WAREHOUSE_TRANSFER_CREATED",
          occurredAt: "2026-07-12T07:00:00.000Z",
        }),
        expect.objectContaining({
          id: "warehouse-transfer:transfer-departed:spb-cabin-1",
          type: "WAREHOUSE_TRANSFER_DEPARTED",
          statusTransition: expect.objectContaining({
            from: "FREE",
            to: "IN_TRANSFER",
          }),
        }),
        expect.objectContaining({
          id: "warehouse-transfer:transfer-received:spb-cabin-1",
          type: "WAREHOUSE_TRANSFER_RECEIVED",
          statusTransition: expect.objectContaining({
            from: "IN_TRANSFER",
            to: "FREE",
          }),
          links: [
            expect.objectContaining({
              href: "/logistics/transfers?transferId=transfer-document-1&lineId=transfer-line-1",
            }),
          ],
        }),
        expect.objectContaining({
          id: "warehouse-transfer:correction-created:spb-cabin-1",
          type: "WAREHOUSE_CORRECTION_CREATED",
          sourceType: "ACCOUNTING_CORRECTION",
        }),
        expect.objectContaining({
          id: "warehouse-transfer:correction-applied:spb-cabin-1",
          type: "WAREHOUSE_CORRECTION_APPLIED",
          links: [
            expect.objectContaining({
              href: "/logistics/transfers?correctionId=correction-1",
            }),
          ],
        }),
      ])
    )
    expect(
      dossier?.activities.filter(
        (activity) =>
          activity.id === "warehouse-transfer:correction-applied:spb-cabin-1"
      )
    ).toHaveLength(1)
  })

  it("projects return intake, conflict resolution and each furniture disposition without accepting a pending return", async () => {
    window.localStorage.setItem(
      LOGISTICS_STORAGE_KEY,
      JSON.stringify({
        service: "logistics",
        schemaVersion: 2,
        revision: 1,
        preparationDispatches: [],
        shipments: [],
        returnReceipts: [
          {
            id: "receipt-conflict",
            version: 2,
            warehouseId: "spb",
            fromParty: "ООО Клиент",
            driverId: null,
            driverName: "Водитель",
            shipmentDate: "2026-06-01",
            returnDate: "2026-07-12",
            receptionMethod: "WAREHOUSE_INSPECTION",
            createdAt: "2026-07-12T08:00:00.000Z",
            createdBy: "Анна Логист",
            receiverName: "Пётр Приёмщик",
            updatedAt: "2026-07-12T09:00:00.000Z",
            updatedBy: "Пётр Приёмщик",
            items: [
              {
                id: "return-item-conflict",
                version: 2,
                rentalItemId: "spb-cabin-1",
                cabinNumber: "БЫТ-001",
                selectionSource: "MANUAL",
                originalTenant: "ООО Клиент",
                intakeMode: "OVERRIDE_EXISTING",
                passportSnapshot: null,
                previousContents: [{ name: "Стул", quantity: 2 }],
                expectedContentsSource: "CURRENT_CABIN",
                expectedContentsEditedReason: null,
                conflicts: [],
                conflictResolutions: [
                  {
                    kind: "ACCOUNTING_CORRECTION",
                    id: "correction-return-1",
                    resolvedAt: "2026-07-12T08:30:00.000Z",
                    resolvedBy: "Пётр Приёмщик",
                  },
                ],
                intakeHistory: [
                  {
                    id: "return-audit-conflict",
                    type: "CONFLICT_REGISTERED",
                    createdAt: "2026-07-12T08:00:00.000Z",
                    createdBy: "Анна Логист",
                    reason: "Бытовка числится на другом складе",
                  },
                  {
                    id: "return-audit-resumed",
                    type: "INTAKE_REGISTERED",
                    createdAt: "2026-07-12T08:30:00.000Z",
                    createdBy: "Пётр Приёмщик",
                    reason: "ACCOUNTING_CORRECTION:correction-return-1",
                  },
                ],
                furnitureDispositions: [
                  {
                    id: "disposition-chair",
                    name: "Стул",
                    quantity: 2,
                    remainingQuantity: 0,
                    origin: "PREVIOUS_SNAPSHOT",
                    status: "RESOLVED",
                    allocations: [
                      {
                        id: "allocation-chair-1",
                        idempotencyKey: "allocation-key-1",
                        status: "APPLIED",
                        action: "RETURN_TO_STOCK",
                        quantity: 1,
                        targetRentalItemId: null,
                        reason: "Лишняя мебель",
                        createdAt: "2026-07-12T08:40:00.000Z",
                        createdBy: "Пётр Приёмщик",
                        taskExternalId: null,
                      },
                      {
                        id: "allocation-chair-2",
                        idempotencyKey: "allocation-key-2",
                        status: "PENDING_TASK",
                        action: "WRITE_OFF",
                        quantity: 1,
                        targetRentalItemId: null,
                        reason: "Сломан",
                        createdAt: "2026-07-12T08:45:00.000Z",
                        createdBy: "Пётр Приёмщик",
                        taskExternalId: null,
                      },
                    ],
                    action: "WRITE_OFF",
                    targetRentalItemId: null,
                    reason: "Сломан",
                    resolvedAt: "2026-07-12T08:45:00.000Z",
                    resolvedBy: "Пётр Приёмщик",
                  },
                ],
                contentsMode: "FACTUAL",
                expectedContents: [{ name: "Стул", quantity: 2 }],
                returnedContents: [],
                technicalState: "PENDING_INSPECTION",
                acceptedAt: null,
                media: [],
                sourceEstimateId: null,
                pendingEstimateId: null,
                estimateClaimId: null,
                estimateClaimedAt: null,
              },
            ],
          },
        ],
      })
    )

    const dossier = await new BrowserRentalItemDossierAdapter().get(
      "spb",
      "spb-cabin-1"
    )

    expect(dossier?.activities).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "return:receipt-conflict:return-item-conflict:audit:return-audit-conflict",
          type: "RETURN_CONFLICT_REGISTERED",
          actor: { id: null, displayName: "Анна Логист" },
        }),
        expect.objectContaining({
          id: "return:receipt-conflict:return-item-conflict:resolution:ACCOUNTING_CORRECTION:correction-return-1",
          type: "RETURN_CONFLICT_RESOLVED",
          links: expect.arrayContaining([
            expect.objectContaining({
              href: "/logistics/transfers?correctionId=correction-return-1",
            }),
          ]),
        }),
        expect.objectContaining({
          id: "return:receipt-conflict:return-item-conflict:furniture-allocation:allocation-chair-1",
          type: "RETURN_FURNITURE_DISPOSITION_RECORDED",
          sourceLabel: "Возвращено на склад: Стул",
        }),
      ])
    )
    expect(
      dossier?.activities.some((activity) =>
        activity.id.includes("allocation-chair-2")
      )
    ).toBe(false)
    expect(
      dossier?.activities.some((activity) => activity.type === "RETURNED")
    ).toBe(false)
    expect(dossier?.inspections).toEqual([])
  })
})
