import { beforeEach, describe, expect, it, vi } from "vitest"

import { DEV_AUTH_BYPASS_TOKEN } from "@/features/auth/auth-config"
import {
  addInventoryFromWarehouseStock,
  getRentalItemInventoryAddOptions,
} from "@/api/rental-item-inventory-api"
import {
  getRentalItemsForContentsMove,
  moveRentalItemContentsToRentalItem,
  moveRentalItemContentsToStock,
  readRentalItems,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import { HttpContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/http-contents-transfer-task-client"
import { LocalStorageContentsTransferStore } from "@/features/rental-items/contents-transfer/adapters/local-storage-contents-transfer-store"
import { BrowserContentsTransferClient } from "@/features/rental-items/contents-transfer/browser-contents-transfer-client"
import type { CreateContentsTransferCommand } from "@/features/rental-items/contents-transfer/model/contents-transfer"
import type { ContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/ports/contents-transfer-task-client"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

function cabin(overrides: Partial<RentalItemDto>): RentalItemDto {
  return {
    id: "source",
    version: 3,
    warehouseId: "spb",
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: null,
    linoleum: null,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems: [{ name: "Стул", quantity: 4 }],
    shipmentDate: null,
    tenant: null,
    price: null,
    ...overrides,
  }
}

function seed() {
  writeRentalItems(
    [
      cabin({}),
      cabin({
        id: "target",
        version: 7,
        number: "БЫТ-002",
        contentsItems: [{ name: "Стол", quantity: 1 }],
      }),
      cabin({ id: "empty", number: "БЫТ-003", contentsItems: [] }),
      cabin({ id: "rented", number: "БЫТ-004", status: "RENTED" }),
      cabin({ id: "other", number: "БЫТ-005", warehouseId: "msk" }),
    ],
    false
  )
}

function command(): CreateContentsTransferCommand {
  return {
    externalTaskId: "00000000-0000-0000-0000-000000000777",
    warehouseId: "spb",
    serviceWarehouseId: "00000000-0000-0000-0000-000000000001",
    accessToken: "token",
    actor: { id: "user-1", displayName: "Иван Петров" },
    source: {
      rentalItemId: "source",
      number: "БЫТ-001",
      expectedVersion: 3,
    },
    target: {
      rentalItemId: "target",
      number: "БЫТ-002",
      expectedVersion: 7,
    },
    items: [{ name: "Стул", quantity: 2 }],
  }
}

describe("rental item contents move boundary", () => {
  beforeEach(() => {
    window.localStorage.clear()
    seed()
  })

  it("lists only same-warehouse active filled source candidates", async () => {
    const candidates = await getRentalItemsForContentsMove({
      warehouseId: "spb",
      sourceRentalItemId: "target",
      requireContents: true,
    })
    expect(candidates.map((item) => item.id)).toEqual(["source"])
  })

  it("caps by validation, rejects stale versions and increments both versions", async () => {
    await expect(
      moveRentalItemContentsToRentalItem({
        sourceRentalItemId: "source",
        targetRentalItemId: "source",
        payload: [{ name: "Стул", quantity: 1 }],
      })
    ).rejects.toThrow("ту же бытовку")
    await expect(
      moveRentalItemContentsToRentalItem({
        sourceRentalItemId: "source",
        targetRentalItemId: "target",
        expectedSourceVersion: 3,
        expectedTargetVersion: 7,
        payload: [{ name: "Стул", quantity: 5 }],
      })
    ).rejects.toThrow("Недоступное количество")
    await expect(
      moveRentalItemContentsToRentalItem({
        sourceRentalItemId: "source",
        targetRentalItemId: "target",
        expectedSourceVersion: 2,
        expectedTargetVersion: 7,
        payload: [{ name: "Стул", quantity: 2 }],
      })
    ).rejects.toThrow("Данные бытовки изменились")

    const moved = await moveRentalItemContentsToRentalItem({
      sourceRentalItemId: "source",
      targetRentalItemId: "target",
      expectedSourceVersion: 3,
      expectedTargetVersion: 7,
      payload: [{ name: "Стул", quantity: 2 }],
    })
    expect(moved?.sourceItem).toMatchObject({
      version: 4,
      contentsItems: [{ name: "Стул", quantity: 2 }],
    })
    expect(moved?.targetItem).toMatchObject({
      version: 8,
      contentsItems: [
        { name: "Стол", quantity: 1 },
        { name: "Стул", quantity: 2 },
      ],
    })
  })

  it("increments the cabin version for stock removal and stock addition", async () => {
    const removed = await moveRentalItemContentsToStock("source", [
      { name: "Стул", quantity: 1 },
    ])
    expect(removed?.version).toBe(4)

    const options = await getRentalItemInventoryAddOptions("target")
    const available = options.warehouseStock.find(
      (item) => item.availableQuantity > 0
    )
    expect(available).toBeDefined()
    const added = await addInventoryFromWarehouseStock({
      targetRentalItemId: "target",
      payload: { items: [{ name: available!.name, quantity: 1 }] },
    })
    expect(added.version).toBe(8)
  })
})

describe("BrowserContentsTransferClient", () => {
  beforeEach(() => {
    window.localStorage.clear()
    seed()
  })

  it("does not mutate cabins when task registration fails", async () => {
    const taskClient: ContentsTransferTaskClient = {
      dispatch: vi.fn().mockRejectedValue(new Error("task-board offline")),
    }
    const client = new BrowserContentsTransferClient(
      taskClient,
      new LocalStorageContentsTransferStore()
    )
    const before = readRentalItems()
    await expect(client.transfer(command())).rejects.toThrow(
      "task-board offline"
    )
    expect(readRentalItems()).toEqual(before)
    expect(
      new LocalStorageContentsTransferStore().findByExternalTaskId(
        command().externalTaskId
      )
    ).toMatchObject({ phase: "PENDING_DISPATCH", error: "task-board offline" })
  })

  it("recovers a post-apply record failure after a dialog reload", async () => {
    const durableStore = new LocalStorageContentsTransferStore()
    let failAppliedOnce = true
    const flakyStore = {
      findByExternalTaskId:
        durableStore.findByExternalTaskId.bind(durableStore),
      findMatching: durableStore.findMatching.bind(durableStore),
      listForRentalItem: durableStore.listForRentalItem.bind(durableStore),
      saveAttempt: async (
        attempt: Parameters<typeof durableStore.saveAttempt>[0]
      ) => {
        if (attempt.phase === "APPLIED" && failAppliedOnce) {
          failAppliedOnce = false
          throw new Error("record write failed")
        }
        return durableStore.saveAttempt(attempt)
      },
    }
    const dispatch = vi.fn().mockResolvedValue({
      boardTaskId: "task-1",
      queueId: "move-queue",
      queueCode: "MOVE",
    })
    await expect(
      new BrowserContentsTransferClient({ dispatch }, flakyStore).transfer(
        command()
      )
    ).rejects.toThrow("record write failed")
    expect(durableStore.listForRentalItem("source")).toEqual([])

    const recovered = await new BrowserContentsTransferClient(
      { dispatch },
      durableStore
    ).transfer({
      ...command(),
      externalTaskId: "00000000-0000-0000-0000-000000000778",
    })
    expect(recovered.record.status).toBe("APPLIED")
    expect(dispatch).toHaveBeenCalledTimes(2)
    expect(recovered.sourceItem.version).toBe(4)
    expect(durableStore.listForRentalItem("source")).toHaveLength(1)
  })

  it("does not reuse a stale conflicted attempt after both cabin versions change", async () => {
    const store = new LocalStorageContentsTransferStore()
    const original = command()
    const stored = {
      externalTaskId: original.externalTaskId,
      warehouseId: original.warehouseId,
      serviceWarehouseId: original.serviceWarehouseId,
      actor: original.actor,
      source: original.source,
      target: original.target,
      items: original.items,
    }
    await store.saveAttempt({
      externalTaskId: stored.externalTaskId,
      phase: "CONFLICT",
      command: stored,
      task: {
        boardTaskId: "task-old",
        queueId: "queue-move",
        queueCode: "MOVE",
      },
      record: null,
      error: "Данные бытовки изменились",
      createdAt: "2026-07-12T10:00:00.000Z",
      updatedAt: "2026-07-12T10:01:00.000Z",
    })

    expect(
      store.findMatching({
        ...stored,
        externalTaskId: "00000000-0000-0000-0000-000000000779",
        source: { ...stored.source, expectedVersion: 4 },
        target: { ...stored.target, expectedVersion: 8 },
      })
    ).toBeNull()
  })

  it("applies once and recovers an idempotent retry by externalTaskId", async () => {
    const dispatch = vi.fn().mockResolvedValue({
      boardTaskId: "task-1",
      queueId: "move-queue",
      queueCode: "MOVE",
    })
    const client = new BrowserContentsTransferClient(
      { dispatch },
      new LocalStorageContentsTransferStore()
    )
    const first = await client.transfer(command())
    const repeated = await client.transfer(command())

    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(first.record.status).toBe("APPLIED")
    expect(repeated.record).toEqual(first.record)
    expect(repeated.sourceItem.version).toBe(4)
    expect(repeated.targetItem.version).toBe(8)
    expect(
      new LocalStorageContentsTransferStore().listForRentalItem("source")
    ).toHaveLength(1)
    await expect(
      client.transfer({
        ...command(),
        items: [{ name: "Стул", quantity: 1 }],
      })
    ).rejects.toThrow("externalTaskId уже связан")
  })
})

describe("HttpContentsTransferTaskClient", () => {
  beforeEach(() => vi.restoreAllMocks())

  it("selects the first visible MOVEMENT queue and sends source to target", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse([
          queue("repair", "REPAIR", 0, true, false),
          queue("hidden-move", "MOVEMENT", 0, true, true),
          queue("move-2", "MOVEMENT", 2, true, false),
          queue("move-1", "MOVEMENT", 1, true, false),
        ])
      )
      .mockResolvedValueOnce(
        jsonResponse({
          columns: [
            {
              entries: [
                {
                  taskId: "task-1",
                  externalTaskId: command().externalTaskId,
                  queueId: "move-1",
                  queueCode: "MOVE-1",
                },
              ],
            },
          ],
        })
      )

    const result = await new HttpContentsTransferTaskClient().dispatch(
      DEV_AUTH_BYPASS_TOKEN,
      command()
    )
    expect(result).toEqual({
      boardTaskId: "task-1",
      queueId: "move-1",
      queueCode: "MOVE-1",
    })
    expect(
      fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)
    ).toEqual([
      "/api/task-board/warehouses/00000000-0000-0000-0000-000000000001/work-queues",
      "/api/task-board/warehouses/00000000-0000-0000-0000-000000000001/task-board/tasks",
    ])
    const post = fetchMock.mock.calls[1]!
    const headers = (post[1] as RequestInit).headers as Headers
    expect(headers.has("Authorization")).toBe(false)
    const body = JSON.parse(String((post[1] as RequestInit).body))
    expect(body).toMatchObject({
      externalTaskId: command().externalTaskId,
      title: "БЫТ-001 → БЫТ-002",
      unitNumber: "БЫТ-002",
      route: [{ queueId: "move-1", queueCode: "MOVE-1" }],
    })
  })

  it("recovers a 409 through the board snapshot", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse([queue("move-1", "MOVEMENT", 1, true, false)])
      )
      .mockResolvedValueOnce(jsonResponse({ message: "duplicate" }, 409))
      .mockResolvedValueOnce(
        jsonResponse({
          columns: [
            {
              entries: [
                {
                  taskId: "task-existing",
                  externalTaskId: command().externalTaskId,
                  queueId: "move-1",
                  queueCode: "MOVE-1",
                },
              ],
            },
          ],
        })
      )
    await expect(
      new HttpContentsTransferTaskClient().dispatch("token", command())
    ).resolves.toMatchObject({ boardTaskId: "task-existing" })
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

function queue(
  id: string,
  type: "MOVEMENT" | "REPAIR" | "HOLDING",
  sortOrder: number,
  active: boolean,
  hidden: boolean
) {
  return {
    id,
    version: 0,
    warehouseId: "service-warehouse",
    code: id.toLocaleUpperCase("ru-RU"),
    name: id,
    description: null,
    type,
    sortOrder,
    active,
    hidden,
    collapsed: false,
    holdingPeriodMinutes: null,
    notificationThreshold: null,
    notifyWhenThresholdReached: false,
    bindings: [],
  }
}
