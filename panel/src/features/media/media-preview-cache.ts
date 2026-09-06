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
  loader: () => Promise<DisposableMediaObjectUrl>
): Promise<MediaPreviewLease> {
  const acquisitionRevision = cacheRevision
  sweep()
  while (true) {
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
        pendingEntry = { promise: loader(), invalidated: false }
        pending.set(key, pendingEntry)
      }
      try {
        const objectUrl = await pendingEntry.promise
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
        if (pending.get(key) === pendingEntry) pending.delete(key)
        throw error
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
  for (const pendingEntry of pending.values()) pendingEntry.invalidated = true
  pending.clear()
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
