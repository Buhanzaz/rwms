import type { DisposableMediaObjectUrl } from "@/features/media/media-service"

type CacheEntry = {
  key: string
  objectUrl: DisposableMediaObjectUrl
  references: number
  lastUsedAt: number
  evicted: boolean
}

type PendingEntry = {
  promise: Promise<DisposableMediaObjectUrl>
  invalidated: boolean
  controller: AbortController
  consumers: number
  settled: boolean
  priority: number
}

export type MediaPreviewLease = Readonly<{
  url: string
  release: () => void
}>

const MAX_ENTRIES = 4096
const MAX_BYTES = 128 * 1024 * 1024
const entries = new Map<string, CacheEntry>()
const pending = new Map<string, PendingEntry>()
let cacheRevision = 0
let cachedBytes = 0

/** Keeps decoded preview Blob URLs until an invalidation, logout, or size cap. */
export async function acquireMediaPreview(
  key: string,
  loader: (signal: AbortSignal) => Promise<DisposableMediaObjectUrl>,
  options: { signal?: AbortSignal; priority?: number } = {}
): Promise<MediaPreviewLease> {
  const acquisitionRevision = cacheRevision
  sweep()
  while (true) {
    options.signal?.throwIfAborted()
    if (acquisitionRevision !== cacheRevision) {
      throw new MediaPreviewCacheClearedError()
    }
    let entry = entries.get(key)
    if (entry?.evicted) {
      if (entry.references === 0) {
        disposeEntry(entry)
      } else {
        // Existing cards keep a lease until React replaces their projection,
        // but a newly mounted card must never receive an invalidated URL.
        entries.delete(key)
        cachedBytes -= entry.objectUrl.size
      }
      entry = undefined
    }
    if (!entry) {
      let pendingEntry = pending.get(key)
      if (!pendingEntry || pendingEntry.invalidated) {
        const controller = new AbortController()
        const next: PendingEntry = {
          promise: undefined!,
          invalidated: false,
          controller,
          consumers: 0,
          settled: false,
          priority: options.priority ?? 0,
        }
        next.promise = schedulePreviewLoad(
          () => loader(controller.signal),
          controller.signal,
          () => next.priority
        )
          .then((objectUrl) => {
            if (controller.signal.aborted) {
              objectUrl.dispose()
              throw controller.signal.reason
            }
            return objectUrl
          })
          .finally(() => {
            next.settled = true
          })
        pendingEntry = next
        pending.set(key, pendingEntry)
      }
      pendingEntry.priority = Math.min(
        pendingEntry.priority,
        options.priority ?? 0
      )
      pendingEntry.consumers += 1
      try {
        const objectUrl = await waitForPreview(
          pendingEntry.promise,
          options.signal
        )
        if (acquisitionRevision !== cacheRevision) {
          objectUrl.dispose()
          throw new MediaPreviewCacheClearedError()
        }
        if (pendingEntry.invalidated) {
          objectUrl.dispose()
          if (pending.get(key) === pendingEntry) pending.delete(key)
          continue
        }
        if (pending.get(key) === pendingEntry) pending.delete(key)
        entry = entries.get(key)
        if (!entry) {
          entry = {
            key,
            objectUrl,
            references: 0,
            lastUsedAt: Date.now(),
            evicted: false,
          }
          entries.set(key, entry)
          cachedBytes += objectUrl.size
        } else if (entry.objectUrl !== objectUrl) {
          objectUrl.dispose()
        }
      } catch (error) {
        if (pendingEntry.settled && pending.get(key) === pendingEntry)
          pending.delete(key)
        if (acquisitionRevision !== cacheRevision)
          throw new MediaPreviewCacheClearedError()
        throw error
      } finally {
        pendingEntry.consumers -= 1
        if (pendingEntry.consumers === 0 && !pendingEntry.settled) {
          if (pending.get(key) === pendingEntry) pending.delete(key)
          pendingEntry.controller.abort()
        }
      }
    }

    entry.references += 1
    entry.lastUsedAt = Date.now()
    // Pin the new URL before enforcing the budget: visible cards may exceed
    // the idle-cache limit, but must never receive an already revoked URL.
    trim()
    let released = false
    return {
      url: entry.objectUrl.url,
      release: () => {
        if (released) return
        released = true
        entry!.references = Math.max(0, entry!.references - 1)
        entry!.lastUsedAt = Date.now()
        if (entry!.evicted && entry!.references === 0) disposeEntry(entry!)
        trim()
      },
    }
  }
}

export function mediaPreviewCacheKey(input: {
  warehouseId: string
  cabinId: string
  mediaId: string
  generation: number
  variant: string
}) {
  return [
    input.warehouseId,
    input.cabinId,
    input.mediaId,
    input.generation,
    input.variant,
  ].join(":")
}

/**
 * Returns an already decoded preview without starting a network request.
 * The owning hook acquires a lease in its effect immediately after render;
 * this synchronous peek only removes the flash when returning to a route.
 */
export function getCachedMediaPreviewUrl(key: string) {
  const entry = entries.get(key)
  if (!entry || entry.evicted) return undefined
  entry.lastUsedAt = Date.now()
  return entry.objectUrl.url
}

export function evictMediaPreview(mediaId: string | undefined) {
  if (!mediaId) return
  for (const entry of entries.values()) {
    if (entry.key.split(":").includes(mediaId)) markEvicted(entry)
  }
  for (const [key, pendingEntry] of pending) {
    if (key.split(":").includes(mediaId)) pendingEntry.invalidated = true
  }
}

export function evictWarehouseMediaPreviews(warehouseId: string | undefined) {
  if (!warehouseId) return
  const prefix = `${warehouseId}:`
  for (const entry of entries.values()) {
    if (entry.key.startsWith(prefix)) markEvicted(entry)
  }
  for (const [key, pendingEntry] of pending) {
    if (key.startsWith(prefix)) pendingEntry.invalidated = true
  }
}

export function clearMediaPreviewCache() {
  cacheRevision += 1
  for (const entry of entries.values()) disposeEntry(entry)
  entries.clear()
  cachedBytes = 0
  for (const pendingEntry of pending.values()) {
    pendingEntry.invalidated = true
    pendingEntry.controller.abort()
  }
  pending.clear()
}

const MAX_PREVIEW_DOWNLOADS = 4
const downloadQueue: Array<{ priority: () => number; run: () => void }> = []
let activeDownloads = 0

/** One shared queue gives covers priority over adjacent slides across all visible cards. */
function schedulePreviewLoad(
  loader: () => Promise<DisposableMediaObjectUrl>,
  signal: AbortSignal,
  priority: () => number
): Promise<DisposableMediaObjectUrl> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      const index = downloadQueue.indexOf(task)
      if (index >= 0) downloadQueue.splice(index, 1)
      reject(signal.reason)
    }
    const task = {
      priority,
      run: () => {
        signal.removeEventListener("abort", abort)
        activeDownloads += 1
        void Promise.resolve()
          .then(loader)
          .then(resolve, reject)
          .finally(() => {
            activeDownloads -= 1
            pumpDownloads()
          })
      },
    }
    if (signal.aborted) return reject(signal.reason)
    signal.addEventListener("abort", abort, { once: true })
    downloadQueue.push(task)
    queueMicrotask(pumpDownloads)
  })
}

function pumpDownloads() {
  downloadQueue.sort((left, right) => left.priority() - right.priority())
  while (activeDownloads < MAX_PREVIEW_DOWNLOADS && downloadQueue.length > 0) {
    downloadQueue.shift()!.run()
  }
}

function waitForPreview<T>(
  promise: Promise<T>,
  signal?: AbortSignal
): Promise<T> {
  if (!signal) return promise
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason)
    signal.addEventListener("abort", abort, { once: true })
    promise
      .then(resolve, reject)
      .finally(() => signal.removeEventListener("abort", abort))
  })
}

/** Signals that a principal-wide cache clear superseded an in-flight preview. */
class MediaPreviewCacheClearedError extends Error {
  constructor() {
    super("Media preview cache was cleared")
  }
}

function markEvicted(entry: CacheEntry) {
  entry.evicted = true
  if (entry.references === 0) disposeEntry(entry)
}

function disposeEntry(entry: CacheEntry) {
  if (entries.get(entry.key) === entry) {
    entries.delete(entry.key)
    cachedBytes -= entry.objectUrl.size
  }
  entry.objectUrl.dispose()
}

function sweep() {
  trim()
}

function trim() {
  if (entries.size <= MAX_ENTRIES && cachedBytes <= MAX_BYTES) return
  const idle = [...entries.values()]
    .filter((entry) => entry.references === 0)
    .sort((left, right) => left.lastUsedAt - right.lastUsedAt)
  while (
    (entries.size > MAX_ENTRIES || cachedBytes > MAX_BYTES) &&
    idle.length > 0
  ) {
    const entry = idle.shift()!
    disposeEntry(entry)
  }
}
