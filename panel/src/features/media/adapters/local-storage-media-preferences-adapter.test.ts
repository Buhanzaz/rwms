import { beforeEach, describe, expect, it } from "vitest"

import {
  LocalStorageMediaPreferencesAdapter,
  MEDIA_PREFERENCES_STORAGE_KEY,
} from "@/features/media/adapters/local-storage-media-preferences-adapter"
import { canUseOriginalMedia } from "@/features/media/model/media"

describe("LocalStorageMediaPreferencesAdapter", () => {
  beforeEach(() => window.localStorage.clear())

  it("keeps original quality disabled by default and scopes it per user", async () => {
    const adapter = new LocalStorageMediaPreferencesAdapter()

    await expect(adapter.get("user-a")).resolves.toEqual({
      showOriginalPhotos: false,
    })
    await adapter.update("user-a", { showOriginalPhotos: true })

    await expect(adapter.get("user-a")).resolves.toEqual({
      showOriginalPhotos: true,
    })
    await expect(adapter.get("user-b")).resolves.toEqual({
      showOriginalPhotos: false,
    })
    expect(
      window.localStorage.getItem(MEDIA_PREFERENCES_STORAGE_KEY)
    ).toContain('"schemaVersion":1')
  })

  it("allows originals only in estimate, inspection and work contexts", () => {
    expect(canUseOriginalMedia("ESTIMATE")).toBe(true)
    expect(canUseOriginalMedia("INSPECTION")).toBe(true)
    expect(canUseOriginalMedia("WORK")).toBe(true)
    expect(canUseOriginalMedia("WAREHOUSE")).toBe(false)
    expect(canUseOriginalMedia("HISTORY")).toBe(false)
  })

  it("recovers from malformed browser data", async () => {
    window.localStorage.setItem(MEDIA_PREFERENCES_STORAGE_KEY, "{broken")
    const adapter = new LocalStorageMediaPreferencesAdapter()

    await expect(adapter.get("user-a")).resolves.toEqual({
      showOriginalPhotos: false,
    })
  })
})
