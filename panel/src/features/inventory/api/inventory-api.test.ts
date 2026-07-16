import { beforeEach, describe, expect, it } from "vitest"
import {
  addInventoryRentalItem,
  completeInventory,
  getInventory,
  previewInventoryCompletion,
  publishInventoryWorks,
  resolveInventoryNumber,
  saveInventoryFinding,
  startInventory,
} from "@/features/inventory/api/inventory-api"
import {
  inventoryCompletionRiskSignature,
  toInventoryRepairPlanSnapshot,
} from "@/features/inventory/domain/inventory-domain"
import { INVENTORY_STORAGE_KEY } from "@/features/inventory/adapters/local-storage-inventory-adapter"
import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { buildRepairEstimateTaskPlans } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import type {
  InventoryActorSnapshot,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"
import {
  readRentalItems,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import {
  getRepairTaskSnapshotByInventoryFinding,
  upsertRepairTaskByInventoryFinding,
} from "@/features/repair-tasks/api/repair-tasks-api"

const actor: InventoryActorSnapshot = {
  id: "manager-1",
  displayName: "Кладовщик",
  permissions: ["MANAGE"],
  authorizedWarehouseIds: null,
}

async function completeWithCurrentRisks(session: InventorySessionDto) {
  const preview = await previewInventoryCompletion({
    inventoryId: session.id,
    expectedVersion: session.version,
    actor,
  })
  return completeInventory({
    inventoryId: session.id,
    expectedVersion: session.version,
    actor,
    acknowledgedRiskSignature: inventoryCompletionRiskSignature(
      preview.findings
    ),
  })
}

function globallyUniqueFinding(session: InventorySessionDto) {
  const registry = readRentalItems()
  const finding = session.findings.find(
    (candidate) =>
      registry.filter(
        (item) =>
          item.number.trim().replace(/\s+/g, " ").toLocaleUpperCase("ru-RU") ===
          candidate.canonicalNumber
      ).length === 1
  )
  if (!finding) throw new Error("Нет finding с глобально уникальным номером")
  return finding
}

async function automaticCatalogWorkLine(id: string) {
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  const node = snapshot.nodes.find(
    (candidate) =>
      candidate.active &&
      candidate.nodeType === "WORK" &&
      candidate.includeInEstimate &&
      catalog.getEffectiveQueueBinding(candidate.id)
  )
  if (!node) throw new Error("Не найдена работа каталога с маршрутом")
  return {
    line: {
      id,
      sourceLineKey: id,
      lineType: "WORK",
      description: node.name,
      lineComment: "Автоматический план",
      unit: node.unit ?? "шт",
      quantity: 1,
      unitPrice: node.unitPrice ?? "0.00",
      lineTotal: node.unitPrice ?? "0.00",
      catalogSnapshot: {
        nodeId: node.id,
        code: node.code,
        name: node.name,
        nodeType: "WORK" as const,
      },
    } satisfies RepairEstimateLineDto,
    catalog,
    photoRequired: node.photoRequired,
  }
}

describe("inventory publication saga", () => {
  beforeEach(() => window.localStorage.clear())

  it("retries a frozen conflict against live registry state without duplicating a task", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const finding = started.findings.find(
      (item) => item.expectedSnapshot?.status === "FREE"
    )!
    const inspected = await saveInventoryFinding({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      findingId: finding.id,
      comment: "Требуется работа",
      media: [],
      lines: [
        {
          id: "work-1",
          sourceLineKey: "work-1",
          lineType: "WORK",
          description: "Замена окна",
          lineComment: "",
          unit: "шт",
          quantity: 1,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: null,
        },
      ],
      repairPlans: [],
    })
    expect(
      inspected.findings.find((item) => item.id === finding.id)?.repairPlans
    ).toEqual([
      expect.objectContaining({
        includedLineIds: ["work-1"],
        primaryLineId: "work-1",
      }),
    ])
    writeRentalItems(
      readRentalItems().map((item) =>
        item.id === finding.rentalItemId ? { ...item, status: "RENTED" } : item
      )
    )
    const preview = await previewInventoryCompletion({
      inventoryId: inspected.id,
      expectedVersion: inspected.version,
      actor,
    })
    expect(preview.statistics).toMatchObject({
      workTotal: "100.00",
      materialTotal: "0.00",
    })
    expect(
      preview.findings.find((item) => item.id === finding.id)
    ).toMatchObject({
      conflicts: expect.arrayContaining([
        expect.objectContaining({ code: "RENTED" }),
      ]),
    })
    await expect(
      completeInventory({
        inventoryId: inspected.id,
        expectedVersion: inspected.version,
        actor,
        acknowledgedRiskSignature: null,
      })
    ).rejects.toThrow("Проверьте ненайденные")
    const completed = await completeWithCurrentRisks(inspected)
    const blocked = await publishInventoryWorks({
      inventoryId: completed.id,
      actor,
    })
    expect(
      blocked.findings.find((item) => item.id === finding.id)
    ).toMatchObject({ publicationStatus: "BLOCKED" })

    writeRentalItems(
      readRentalItems().map((item) =>
        item.id === finding.rentalItemId ? { ...item, status: "FREE" } : item
      )
    )
    const published = await publishInventoryWorks({
      inventoryId: completed.id,
      actor,
    })
    const result = published.findings.find((item) => item.id === finding.id)!
    expect(result.publicationStatus).toBe("PUBLISHED")
    const firstTaskId = result.publishedRepairTaskId
    const retried = await publishInventoryWorks({
      inventoryId: completed.id,
      actor,
    })
    expect(
      retried.findings.find((item) => item.id === finding.id)
        ?.publishedRepairTaskId
    ).toBe(firstTaskId)
    expect(
      await getRepairTaskSnapshotByInventoryFinding(
        completed.id,
        finding.id,
        completed.warehouseId
      )
    ).toMatchObject({ id: firstTaskId, origin: "INVENTORY" })
  })

  it.each(["PUBLISHING", "FAILED"] as const)(
    "recovers a task from %s before live-conflict gating",
    async (publicationStatus) => {
      const started = await startInventory({
        warehouse: {
          id: "spb",
          code: "СПБ",
          name: "Склад СПБ",
          timeZone: "Europe/Moscow",
        },
        actor,
        businessDate: "2026-07-11",
      })
      const finding = started.findings.find(
        (item) => item.expectedSnapshot?.status === "FREE"
      )!
      const line = {
        id: "crash-work-1",
        sourceLineKey: "crash-work-1",
        lineType: "WORK" as const,
        description: "Замена двери",
        lineComment: "",
        unit: "шт",
        quantity: 1,
        unitPrice: "200.00",
        lineTotal: "200.00",
        catalogSnapshot: null,
      }
      const inspected = await saveInventoryFinding({
        inventoryId: started.id,
        expectedVersion: started.version,
        actor,
        findingId: finding.id,
        comment: "Требуется работа",
        media: [],
        lines: [line],
        repairPlans: [],
      })
      const completed = await completeWithCurrentRisks(inspected)
      const raw = window.localStorage.getItem(INVENTORY_STORAGE_KEY)
      if (!raw) throw new Error("Тестовая инвентаризация не сохранена")
      const envelope = JSON.parse(raw) as {
        revision: number
        sessions: InventorySessionDto[]
      }
      const operationKey = `${completed.id}:${finding.id}`
      window.localStorage.setItem(
        INVENTORY_STORAGE_KEY,
        JSON.stringify({
          ...envelope,
          revision: envelope.revision + 1,
          sessions: envelope.sessions.map((session) =>
            session.id === completed.id
              ? {
                  ...session,
                  version: session.version + 1,
                  findings: session.findings.map((item) =>
                    item.id === finding.id
                      ? {
                          ...item,
                          publicationStatus,
                          publicationOperationKey: operationKey,
                        }
                      : item
                  ),
                }
              : session
          ),
        })
      )
      const task = await upsertRepairTaskByInventoryFinding({
        warehouseId: completed.warehouseId,
        rentalItemId: finding.rentalItemId!,
        cabinNumber: finding.cabinNumber,
        authorName: actor.displayName,
        sourceInventoryId: completed.id,
        sourceInventoryFindingId: finding.id,
        reason: "Инвентаризация",
        dispatchDate: completed.businessDate,
        comment: "Требуется работа",
        media: [],
        subtasks: [
          {
            id: "crash-subtask-1",
            kind: "REPAIR_WORK",
            status: "WAITING",
            workLines: [line],
            materialLines: [],
            groupComment: "",
            queueCode: null,
            routeQueueKind: null,
            sortOrder: 10,
            queuePosition: 0,
            plannedDurationMinutes: null,
            photoRequired: false,
            startedAt: null,
            completedAt: null,
            activeStartedAt: null,
            activeWorkSeconds: 0,
            workerGroup: null,
            assignments: [],
            resultMedia: [],
            assigneeName: null,
          },
        ],
      })

      const recovered = await publishInventoryWorks({
        inventoryId: completed.id,
        actor,
      })
      expect(
        recovered.findings.find((item) => item.id === finding.id)
      ).toMatchObject({
        publicationStatus: "PUBLISHED",
        publishedRepairTaskId: task.id,
      })
    }
  )

  it("keeps uninspected expected items missing in completion preview", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    expect(started.findings.length).toBeGreaterThan(1)
    const finding = globallyUniqueFinding(started)
    const inspected = await saveInventoryFinding({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      findingId: finding.id,
      comment: "Осмотрена",
      media: [],
      lines: [],
      repairPlans: [],
    })

    const preview = await previewInventoryCompletion({
      inventoryId: inspected.id,
      expectedVersion: inspected.version,
      actor,
    })

    expect(preview.statistics).toMatchObject({
      expectedCount: started.findings.length,
      inspectedCount: 1,
      missingCount: started.findings.length - 1,
    })
    expect(inventoryCompletionRiskSignature(preview.findings)).not.toBe("")
    await expect(
      completeInventory({
        inventoryId: inspected.id,
        expectedVersion: inspected.version,
        actor,
        acknowledgedRiskSignature: null,
      })
    ).rejects.toThrow("Проверьте ненайденные")
  })

  it("refreshes a deleted existing finding and prevents inspection save", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const finding = globallyUniqueFinding(started)
    writeRentalItems(
      readRentalItems().filter((item) => item.id !== finding.rentalItemId)
    )
    const resolution = await resolveInventoryNumber({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      number: finding.cabinNumber,
    })
    expect(resolution).toMatchObject({
      kind: "CONFLICT",
      conflict: "RENTAL_ITEM_MISSING",
      item: null,
      finding: {
        reconciliationStatus: "MISSING",
        currentSnapshot: null,
        conflicts: [expect.objectContaining({ code: "RENTAL_ITEM_MISSING" })],
      },
    })
    if (resolution.kind !== "CONFLICT") throw new Error("Ожидался конфликт")
    await expect(
      saveInventoryFinding({
        inventoryId: resolution.session.id,
        expectedVersion: resolution.session.version,
        actor,
        findingId: finding.id,
        comment: "Проверка после удаления",
        media: [],
        lines: [],
        repairPlans: [],
      })
    ).rejects.toThrow("Нельзя сохранить осмотр")
  })

  it.each([
    {
      conflict: "OTHER_WAREHOUSE" as const,
      update: { warehouseId: "msk" },
      snapshot: { warehouseId: "msk" },
    },
    {
      conflict: "WRITTEN_OFF" as const,
      update: { status: "WRITTEN_OFF" as const },
      snapshot: { status: "WRITTEN_OFF" },
    },
  ])(
    "refreshes an existing finding as $conflict and prevents inspection save",
    async ({ conflict, update, snapshot }) => {
      const started = await startInventory({
        warehouse: {
          id: "spb",
          code: "СПБ",
          name: "Склад СПБ",
          timeZone: "Europe/Moscow",
        },
        actor,
        businessDate: "2026-07-11",
      })
      const finding = globallyUniqueFinding(started)
      writeRentalItems(
        readRentalItems().map((item) =>
          item.id === finding.rentalItemId ? { ...item, ...update } : item
        )
      )

      const resolution = await resolveInventoryNumber({
        inventoryId: started.id,
        expectedVersion: started.version,
        actor,
        number: finding.cabinNumber,
      })

      expect(resolution).toMatchObject({
        kind: "CONFLICT",
        conflict,
        finding: {
          currentSnapshot: snapshot,
          reconciliationStatus: "MISSING",
          conflicts: expect.arrayContaining([
            expect.objectContaining({ code: conflict }),
          ]),
        },
      })
      if (resolution.kind !== "CONFLICT") throw new Error("Ожидался конфликт")
      await expect(
        saveInventoryFinding({
          inventoryId: resolution.session.id,
          expectedVersion: resolution.session.version,
          actor,
          findingId: finding.id,
          comment: "Недопустимый осмотр",
          media: [],
          lines: [],
          repairPlans: [],
        })
      ).rejects.toThrow("Нельзя сохранить осмотр")
    }
  )

  it("rejects a guessed foreign session before registry lookup or creation", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const foreignActor: InventoryActorSnapshot = {
      ...actor,
      id: "manager-msk",
      authorizedWarehouseIds: ["msk"],
    }
    const countBefore = readRentalItems().length
    await expect(
      resolveInventoryNumber({
        inventoryId: started.id,
        expectedVersion: started.version,
        actor: foreignActor,
        number: "ЧУЖАЯ-1",
      })
    ).rejects.toThrow("Нет доступа")
    await expect(
      addInventoryRentalItem({
        inventoryId: started.id,
        expectedVersion: started.version,
        actor: foreignActor,
        findingId: "foreign-finding",
        condition: "NEW",
        rentalItem: {
          number: "ЧУЖАЯ-1",
          type: "БК-1",
          dimensions: "2.4x6",
          finishing: "ДВП",
          category: "Новая",
          characteristics: [],
          linoleum: false,
        },
      })
    ).rejects.toThrow("Нет доступа")
    expect(readRentalItems()).toHaveLength(countBefore)
  })

  it("keeps AUTO routing, movement and photo requirements after reload and publication", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const finding = started.findings.find(
      (item) => item.expectedSnapshot?.status === "FREE"
    )!
    const { line, catalog, photoRequired } = await automaticCatalogWorkLine(
      "inventory-auto-work"
    )
    const selectedPlans = buildRepairEstimateTaskPlans([line], catalog)

    const saved = await saveInventoryFinding({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      findingId: finding.id,
      comment: "Автоматический маршрут",
      media: [],
      lines: [line],
      repairCompletionMode: "AUTO",
      movementRequired: true,
      repairPlans: selectedPlans.map(toInventoryRepairPlanSnapshot),
    })
    const persisted = saved.findings.find((item) => item.id === finding.id)!
    expect(persisted).toMatchObject({
      repairCompletionMode: "AUTO",
      movementRequired: true,
      repairPlans: [
        expect.objectContaining({ kind: "MOVE_TO_REPAIR", sortOrder: 10 }),
        expect.objectContaining({ kind: "REPAIR_WORK", sortOrder: 20 }),
        expect.objectContaining({ kind: "MOVE_FROM_REPAIR", sortOrder: 30 }),
      ],
    })
    expect(
      persisted.repairPlans.find((plan) => plan.kind === "REPAIR_WORK")
        ?.photoRequired
    ).toBe(photoRequired)

    const reopened = await getInventory(saved.id, actor)
    expect(
      reopened?.findings.find((item) => item.id === finding.id)
    ).toMatchObject({
      repairCompletionMode: "AUTO",
      movementRequired: true,
      repairPlans: persisted.repairPlans,
    })

    const completed = await completeWithCurrentRisks(reopened!)
    const published = await publishInventoryWorks({
      inventoryId: completed.id,
      actor,
    })
    const taskId = published.findings.find(
      (item) => item.id === finding.id
    )?.publishedRepairTaskId
    const task = await getRepairTaskSnapshotByInventoryFinding(
      completed.id,
      finding.id,
      completed.warehouseId
    )
    expect(task).toMatchObject({ id: taskId, origin: "INVENTORY" })
    expect(task?.subtasks.map((subtask) => subtask.kind)).toEqual([
      "MOVE_TO_REPAIR",
      "REPAIR_WORK",
      "MOVE_FROM_REPAIR",
    ])
    expect(task?.subtasks[1]?.photoRequired).toBe(photoRequired)
  })

  it("rebuilds AUTO routes instead of retaining a previous MANUAL route", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const finding = started.findings.find(
      (item) => item.expectedSnapshot?.status === "FREE"
    )!
    const { line, catalog } = await automaticCatalogWorkLine(
      "inventory-auto-overrides-manual"
    )
    const manual = await saveInventoryFinding({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      findingId: finding.id,
      comment: "Ручной маршрут перед автоматическим",
      media: [],
      lines: [line],
      repairCompletionMode: "MANUAL",
      movementRequired: false,
      repairPlans: [
        {
          id: "manual-route",
          kind: "REPAIR_WORK",
          includedLineIds: [line.id],
          primaryLineId: line.id,
          groupComment: "Ручная очередь",
          queueCode: "manual-queue",
          routeQueueKind: null,
          sortOrder: 10,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
      ],
    })
    const automatic = await saveInventoryFinding({
      inventoryId: manual.id,
      expectedVersion: manual.version,
      actor,
      findingId: finding.id,
      comment: "Автоматический маршрут",
      media: [],
      lines: [line],
      repairCompletionMode: "AUTO",
      movementRequired: false,
      repairPlans: manual.findings.find((item) => item.id === finding.id)!
        .repairPlans,
    })
    const expectedPlan = buildRepairEstimateTaskPlans([line], catalog)[0]!
    const savedPlan = automatic.findings
      .find((item) => item.id === finding.id)!
      .repairPlans.find((plan) => plan.kind === "REPAIR_WORK")!
    expect(savedPlan).toMatchObject({
      queueCode: expectedPlan.queueCode,
      routeQueueKind: expectedPlan.routeQueueKind,
    })
    expect(savedPlan.queueCode).not.toBe("manual-queue")
  })

  it("keeps MANUAL ordering and an unassigned fallback through reload and publication", async () => {
    const started = await startInventory({
      warehouse: {
        id: "spb",
        code: "СПБ",
        name: "Склад СПБ",
        timeZone: "Europe/Moscow",
      },
      actor,
      businessDate: "2026-07-11",
    })
    const finding = started.findings.find(
      (item) => item.expectedSnapshot?.status === "FREE"
    )!
    const workA: RepairEstimateLineDto = {
      id: "inventory-manual-work-a",
      sourceLineKey: "inventory-manual-work-a",
      lineType: "WORK",
      description: "Первая работа",
      lineComment: "",
      unit: "шт",
      quantity: 1,
      unitPrice: "100.00",
      lineTotal: "100.00",
      catalogSnapshot: null,
    }
    const workB: RepairEstimateLineDto = {
      ...workA,
      id: "inventory-manual-work-b",
      sourceLineKey: "inventory-manual-work-b",
      description: "Вторая работа",
    }
    const saved = await saveInventoryFinding({
      inventoryId: started.id,
      expectedVersion: started.version,
      actor,
      findingId: finding.id,
      comment: "Ручной маршрут",
      media: [],
      lines: [workA, workB],
      repairCompletionMode: "MANUAL",
      movementRequired: true,
      repairPlans: [
        {
          id: "manual-move-to",
          kind: "MOVE_TO_REPAIR",
          includedLineIds: [],
          primaryLineId: null,
          groupComment: "",
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          sortOrder: 10,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
        {
          id: "manual-work-b",
          kind: "REPAIR_WORK",
          includedLineIds: [workB.id],
          primaryLineId: workB.id,
          groupComment: "Сначала вторая работа",
          queueCode: null,
          routeQueueKind: "HOLDING",
          sortOrder: 20,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
        {
          id: "manual-move-from",
          kind: "MOVE_FROM_REPAIR",
          includedLineIds: [],
          primaryLineId: null,
          groupComment: "",
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          sortOrder: 30,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
      ],
    })
    const persisted = saved.findings.find((item) => item.id === finding.id)!
    expect(persisted).toMatchObject({
      repairCompletionMode: "MANUAL",
      movementRequired: true,
    })
    expect(
      persisted.repairPlans.map((plan) => [
        plan.kind,
        plan.includedLineIds,
        plan.routeQueueKind,
      ])
    ).toEqual([
      ["MOVE_TO_REPAIR", [], "MOVEMENT"],
      ["REPAIR_WORK", [workB.id], "HOLDING"],
      ["REPAIR_WORK", [workA.id], null],
      ["MOVE_FROM_REPAIR", [], "MOVEMENT"],
    ])

    const reopened = await getInventory(saved.id, actor)
    expect(
      reopened?.findings.find((item) => item.id === finding.id)
    ).toMatchObject({
      repairCompletionMode: "MANUAL",
      movementRequired: true,
      repairPlans: persisted.repairPlans,
    })

    const completed = await completeWithCurrentRisks(reopened!)
    await publishInventoryWorks({ inventoryId: completed.id, actor })
    const task = await getRepairTaskSnapshotByInventoryFinding(
      completed.id,
      finding.id,
      completed.warehouseId
    )
    expect(task?.subtasks.map((subtask) => subtask.kind)).toEqual([
      "MOVE_TO_REPAIR",
      "REPAIR_WORK",
      "REPAIR_WORK",
      "MOVE_FROM_REPAIR",
    ])
    expect(
      task?.subtasks
        .filter((subtask) => subtask.kind === "REPAIR_WORK")
        .map((subtask) => subtask.workLines.map((line) => line.id))
    ).toEqual([[workB.id], [workA.id]])
  })
})
