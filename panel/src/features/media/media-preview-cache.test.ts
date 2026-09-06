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

it("pins a newly loaded preview before trimming and trims again on release", async () => {
  const firstDispose = vi.fn()
  const secondDispose = vi.fn()
  const first = await acquireMediaPreview("first", async () => ({
    url: "blob:first",
    contentType: "image/webp",
    size: 80 * 1024 * 1024,
    dispose: firstDispose,
  }))
  const second = await acquireMediaPreview("second", async () => ({
    url: "blob:second",
    contentType: "image/webp",
    size: 80 * 1024 * 1024,
    dispose: secondDispose,
  }))

  expect(firstDispose).not.toHaveBeenCalled()
  expect(secondDispose).not.toHaveBeenCalled()
  expect(getCachedMediaPreviewUrl("second")).toBe(second.url)
  first.release()
  expect(firstDispose).toHaveBeenCalledOnce()
  expect(secondDispose).not.toHaveBeenCalled()
  second.release()
})

it("retains a long warehouse scroll beyond 512 small photos without downloading them again", async () => {
  const loader = vi.fn(async () => ({
    url: "blob:small",
    contentType: "image/webp",
    size: 16 * 1024,
    dispose: vi.fn(),
  }))
  for (let index = 0; index < 700; index += 1) {
    const lease = await acquireMediaPreview(`small-${index}`, loader)
    lease.release()
  }
  const revisited = await acquireMediaPreview("small-0", loader)
  expect(loader).toHaveBeenCalledTimes(700)
  expect(revisited.url).toBe("blob:small")
  revisited.release()
})

it("deduplicates concurrent preview requests", async () => {
  const dispose = vi.fn()
  const loader = vi.fn(async () => ({
    url: "blob:shared",
    contentType: "image/webp",
    size: 42,
    dispose,
  }))
  const leases = await Promise.all([
    acquireMediaPreview("same", loader),
    acquireMediaPreview("same", loader),
  ])
  expect(loader).toHaveBeenCalledOnce()
  leases.forEach((lease) => lease.release())
  expect(dispose).not.toHaveBeenCalled()
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
  await vi.waitFor(() => expect(resolveFirst).toBeTypeOf("function"))
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

  await vi.waitFor(() => expect(resolvePreview).toBeTypeOf("function"))
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

it("limits all cards to four downloads, prioritizes covers, and drops abandoned queued work", async () => {
  const controllers = Array.from({ length: 8 }, () => new AbortController())
  const started: number[] = []
  const loaders = controllers.map((_, index) =>
    vi.fn((signal: AbortSignal) => {
      started.push(index)
      return new Promise<never>((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(signal.reason), {
          once: true,
        })
      })
    })
  )
  const requests = controllers.map((controller, index) =>
    acquireMediaPreview(`queued-${index}`, loaders[index], {
      signal: controller.signal,
      priority: index < 4 ? 1 : 0,
    })
  )
  const settled = Promise.allSettled(requests)
  await vi.waitFor(() => expect(started).toHaveLength(4))
  expect(started).toEqual([4, 5, 6, 7])
  controllers.forEach((controller) => controller.abort())
  expect((await settled).every((result) => result.status === "rejected")).toBe(
    true
  )
  expect(started).toHaveLength(4)
})

it("cancels only the departing consumer of a shared pending preview", async () => {
  const controller = new AbortController()
  let finish!: (value: {
    url: string
    contentType: string
    size: number
    dispose: () => void
  }) => void
  let downloadSignal!: AbortSignal
  const loader = vi.fn((signal: AbortSignal) => {
    downloadSignal = signal
    return new Promise<{
      url: string
      contentType: string
      size: number
      dispose: () => void
    }>((resolve) => {
      finish = resolve
    })
  })
  const first = acquireMediaPreview("shared-cancel", loader, {
    signal: controller.signal,
  })
  const firstOutcome = expect(first).rejects.toMatchObject({
    name: "AbortError",
  })
  const second = acquireMediaPreview("shared-cancel", loader)
  await vi.waitFor(() => expect(loader).toHaveBeenCalledOnce())
  controller.abort()
  await firstOutcome
  expect(downloadSignal.aborted).toBe(false)
  finish({
    url: "blob:retained",
    contentType: "image/webp",
    size: 10,
    dispose: vi.fn(),
  })
  const lease = await second
  expect(lease.url).toBe("blob:retained")
  lease.release()
})

it("disposes a late result if its last consumer left despite an uncancellable loader", async () => {
  const controller = new AbortController()
  const dispose = vi.fn()
  let finish!: (value: {
    url: string
    contentType: string
    size: number
    dispose: () => void
  }) => void
  const request = acquireMediaPreview(
    "late-cancel",
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
    { signal: controller.signal }
  )
  const outcome = expect(request).rejects.toMatchObject({ name: "AbortError" })
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"))
  controller.abort()
  await outcome
  finish({ url: "blob:late", contentType: "image/webp", size: 10, dispose })
  await vi.waitFor(() => expect(dispose).toHaveBeenCalledOnce())
  expect(getCachedMediaPreviewUrl("late-cancel")).toBeUndefined()
})
