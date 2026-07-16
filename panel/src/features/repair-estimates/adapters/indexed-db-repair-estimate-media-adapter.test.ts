import "fake-indexeddb/auto"

import { afterAll, beforeAll, describe, expect, it, vi } from "vitest"

import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import type { OriginalMediaViewerContext } from "@/features/media/model/media"
import type { RepairEstimateMediaRefDto } from "@/features/repair-estimates/model/repair-estimate"

const createObjectUrl = vi.fn(() => "blob:test-object")

beforeAll(() => {
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: createObjectUrl,
  })
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  })
})

afterAll(() => {
  Reflect.deleteProperty(URL, "createObjectURL")
  Reflect.deleteProperty(URL, "revokeObjectURL")
})

describe("IndexedDbRepairEstimateMediaAdapter original access", () => {
  it("hydrates preview only and resolves the original lazily in a work context", async () => {
    const adapter = new IndexedDbRepairEstimateMediaAdapter()
    const mediaId = `media-${crypto.randomUUID()}`
    const [stored] = await adapter.upload([
      {
        id: mediaId,
        file: new File(["original-image"], "cabin.jpg", {
          type: "image/jpeg",
        }),
        previewUrl: "blob:pending",
        rotationDegrees: 0,
      },
    ])

    expect(stored.variants.original?.url).toContain("/original")
    const [hydrated] = await adapter.hydrate([stored])
    const hydratedRef: RepairEstimateMediaRefDto = hydrated
    expect(hydrated.originalAvailable).toBe(true)
    expect(hydratedRef.variants.original).toBeUndefined()
    expect(hydrated.variants.largeWebp.url).toBe("blob:test-object")

    const durableAgain = adapter.dehydrate([hydrated])[0]
    expect(durableAgain.variants.original?.storageRef).toBe(mediaId)
    await expect(adapter.resolveOriginalUrl(mediaId, "WORK")).resolves.toBe(
      "blob:test-object"
    )
  })

  it("rejects a generic gallery context at runtime", async () => {
    const adapter = new IndexedDbRepairEstimateMediaAdapter()

    await expect(
      adapter.resolveOriginalUrl(
        "unknown-media",
        "WAREHOUSE" as OriginalMediaViewerContext
      )
    ).rejects.toThrow("Оригинал недоступен в этом контексте")
  })
})
