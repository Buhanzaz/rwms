import { afterEach, expect, it, vi } from "vitest"

import {
  acquireMediaPreview,
  clearMediaPreviewCache,
  evictMediaPreview,
  getCachedMediaPreviewUrl,
  mediaPreviewCacheKey,
} from "@/features/media/media-preview-cache"

afterEach(() => {
  clearMediaPreviewCache()
})

it("reuses a preview URL after a card unmounts and releases it on invalidation", async () => {
  const dispose = vi.fn()
  const loader = vi.fn().mockResolvedValue({
    url: "blob:preview",
    contentType: "image/webp",
    size: 42,
    dispose,
  })
  const key = mediaPreviewCacheKey({
    warehouseId: "warehouse-id",
    cabinId: "cabin-id",
    mediaId: "media-id",
    generation: 2,
    variant: "SMALL",
  })

  const first = await acquireMediaPreview(key, loader)
  first.release()
  const second = await acquireMediaPreview(key, loader)

  expect(second.url).toBe("blob:preview")
  expect(loader).toHaveBeenCalledOnce()
  second.release()

  evictMediaPreview("media-id")
  expect(dispose).toHaveBeenCalledOnce()
})

it("exposes a retained URL synchronously when a warehouse card remounts", async () => {
  const key = mediaPreviewCacheKey({
    warehouseId: "warehouse-id",
    cabinId: "cabin-id",
    mediaId: "media-id",
    generation: 1,
    variant: "SMALL",
  })
  const lease = await acquireMediaPreview(key, async () => ({
    url: "blob:cached",
    contentType: "image/webp",
    size: 42,
    dispose: vi.fn(),
  }))
  lease.release()

  expect(getCachedMediaPreviewUrl(key)).toBe("blob:cached")
  evictMediaPreview("media-id")
  expect(getCachedMediaPreviewUrl(key)).toBeUndefined()
})

it("keeps an active preview valid until its card releases the lease", async () => {
  const dispose = vi.fn()
  const key = mediaPreviewCacheKey({
    warehouseId: "warehouse-id",
    cabinId: "cabin-id",
    mediaId: "media-id",
    generation: 1,
    variant: "SMALL",
  })
  const lease = await acquireMediaPreview(key, async () => ({
    url: "blob:preview",
    contentType: "image/webp",
    size: 42,
    dispose,
  }))

  evictMediaPreview("media-id")
  expect(dispose).not.toHaveBeenCalled()

  lease.release()
  expect(dispose).toHaveBeenCalledOnce()
})

it("retries a preview that was invalidated while its prior request was in flight", async () => {
  type Preview = {
    url: string
    contentType: string
    size: number
    dispose: () => void
  }
  let resolveFirst!: (value: Preview) => void
  const firstDispose = vi.fn()
  const secondDispose = vi.fn()
  const loader = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Preview>((resolve) => {
          resolveFirst = resolve
        })
    )
    .mockResolvedValueOnce({
      url: "blob:fresh-preview",
      contentType: "image/webp",
      size: 42,
      dispose: secondDispose,
    })
  const key = mediaPreviewCacheKey({
    warehouseId: "warehouse-id",
    cabinId: "cabin-id",
    mediaId: "media-id",
    generation: 1,
    variant: "SMALL",
  })

  const leasePromise = acquireMediaPreview(key, loader)
  evictMediaPreview("media-id")
  resolveFirst({
    url: "blob:stale-preview",
    contentType: "image/webp",
    size: 42,
    dispose: firstDispose,
  })

  const lease = await leasePromise
  expect(lease.url).toBe("blob:fresh-preview")
  expect(loader).toHaveBeenCalledTimes(2)
  expect(firstDispose).toHaveBeenCalledOnce()
  lease.release()
  clearMediaPreviewCache()
  expect(secondDispose).toHaveBeenCalledOnce()
})

it("does not recreate a preview after a principal-wide clear races its loader", async () => {
  type Preview = {
    url: string
    contentType: string
    size: number
    dispose: () => void
  }
  let resolvePreview!: (value: Preview) => void
  const dispose = vi.fn()
  const key = mediaPreviewCacheKey({
    warehouseId: "warehouse-id",
    cabinId: "cabin-id",
    mediaId: "media-id",
    generation: 1,
    variant: "SMALL",
  })
  const pendingLease = acquireMediaPreview(
    key,
    () =>
      new Promise<Preview>((resolve) => {
        resolvePreview = resolve
      })
  )

  clearMediaPreviewCache()
  resolvePreview({
    url: "blob:late-preview",
    contentType: "image/webp",
    size: 42,
    dispose,
  })

  await expect(pendingLease).rejects.toThrow("Media preview cache was cleared")
  expect(dispose).toHaveBeenCalledOnce()
  expect(getCachedMediaPreviewUrl(key)).toBeUndefined()
})
