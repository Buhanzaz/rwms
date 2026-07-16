import type {
  PendingEstimateMediaUpload,
  RepairEstimateMediaRefDto,
  RepairEstimateMediaRotationDegrees,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RepairEstimateMediaClient } from "@/features/repair-estimates/ports/repair-estimate-media-client"
import {
  canUseOriginalMedia,
  mediaKindFromMimeType,
  type OriginalMediaViewerContext,
} from "@/features/media/model/media"

const DATABASE_NAME = "rwms-repair-estimate-media"
const DATABASE_VERSION = 1
const MEDIA_STORE_NAME = "media"
const LEGACY_STORAGE_KEY = "rwms:repair-estimate-media:v1"
const MEDIA_MUTATION_LOCK_NAME = "rwms:repair-estimate-media:mutation"
const MEDIA_INVALIDATION_CHANNEL_NAME = "rwms:repair-estimate-media:changes"
const MAX_OBJECT_URL_CACHE_SIZE = 24
const MAX_FILE_BYTES = 25 * 1024 * 1024
const MAX_UPLOAD_BATCH_BYTES = 250 * 1024 * 1024

type StoredMediaRecord = {
  id: string
  fileName: string
  mimeType: string
  createdAt: string
  blob: Blob
  contentVersion: string
  rotationDegrees: RepairEstimateMediaRotationDegrees
}

type LegacyStoredMediaRecord = {
  id: string
  fileName: string
  mimeType: string
  createdAt: string
  binaryDataUrl: string
  rotationDegrees?: RepairEstimateMediaRotationDegrees
}

type LegacyMediaStorageEnvelope = {
  service: "repair-estimate-media"
  schemaVersion: 1
  records: LegacyStoredMediaRecord[]
}

type InFlightObjectUrl = {
  contentVersion: string
  promise: Promise<string>
}

type MediaRuntimeState = {
  objectUrlByMediaId: Map<string, string>
  objectUrlVersionByMediaId: Map<string, string>
  inFlightObjectUrlByMediaId: Map<string, InFlightObjectUrl>
  mutationQueue: Promise<void>
  cacheGeneration: number
  dbPromise?: Promise<IDBDatabase>
  migrationPromise?: Promise<void>
  broadcastChannel: BroadcastChannel | null
  listenersRegistered: boolean
}

const RUNTIME_STATE_KEY = "__rwmsRepairEstimateIndexedDbRuntimeV1__"
const runtimeGlobal = globalThis as typeof globalThis & {
  [RUNTIME_STATE_KEY]?: MediaRuntimeState
}
const runtimeState: MediaRuntimeState = runtimeGlobal[RUNTIME_STATE_KEY] ?? {
  objectUrlByMediaId: new Map(),
  objectUrlVersionByMediaId: new Map(),
  inFlightObjectUrlByMediaId: new Map(),
  mutationQueue: Promise.resolve(),
  cacheGeneration: 0,
  broadcastChannel: null,
  listenersRegistered: false,
}
runtimeGlobal[RUNTIME_STATE_KEY] = runtimeState

function createOpaqueId(prefix: string) {
  const suffix =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${suffix}`
}

function normalizeRotationDegrees(
  value: unknown
): RepairEstimateMediaRotationDegrees {
  return value === 90 || value === 180 || value === 270 ? value : 0
}

function requestToPromise<T>(request: IDBRequest<T>) {
  return new Promise<T>((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () =>
      reject(request.error ?? new Error("Ошибка IndexedDB"))
  })
}

function transactionToPromise(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () =>
      reject(transaction.error ?? new Error("Ошибка транзакции IndexedDB"))
    transaction.onabort = () =>
      reject(transaction.error ?? new Error("Транзакция IndexedDB отменена"))
  })
}

function openDatabase() {
  if (runtimeState.dbPromise) {
    return runtimeState.dbPromise
  }
  if (typeof indexedDB === "undefined") {
    return Promise.reject(
      new Error("Хранилище фотографий недоступно в этом браузере")
    )
  }

  const pending = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(MEDIA_STORE_NAME)) {
        database.createObjectStore(MEDIA_STORE_NAME, { keyPath: "id" })
      }
    }
    request.onsuccess = () => {
      const database = request.result
      database.onversionchange = () => {
        database.close()
        if (runtimeState.dbPromise === pending) {
          runtimeState.dbPromise = undefined
        }
      }
      resolve(database)
    }
    request.onerror = () =>
      reject(
        request.error ?? new Error("Не удалось открыть хранилище фотографий")
      )
    request.onblocked = () =>
      reject(new Error("Закройте другие вкладки WMS и повторите загрузку фото"))
  })
  runtimeState.dbPromise = pending
  void pending.catch(() => {
    if (runtimeState.dbPromise === pending) {
      runtimeState.dbPromise = undefined
    }
  })
  return pending
}

function runWithOriginMutationLock<T>(operation: () => Promise<T>) {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(MEDIA_MUTATION_LOCK_NAME, operation)
  }
  return operation()
}

function runSerializedMutation<T>(operation: () => Promise<T>) {
  const run = () => runWithOriginMutationLock(operation)
  const result = runtimeState.mutationQueue.then(run, run)
  runtimeState.mutationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function dataUrlToBlob(dataUrl: string, fallbackMimeType: string) {
  const separator = dataUrl.indexOf(",")
  if (separator < 0) {
    throw new Error("Некорректные legacy-данные фотографии")
  }
  const header = dataUrl.slice(0, separator)
  const payload = dataUrl.slice(separator + 1)
  const mimeType = /^data:([^;,]+)/.exec(header)?.[1] || fallbackMimeType
  const binary = header.includes(";base64")
    ? atob(payload)
    : decodeURIComponent(payload)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return new Blob([bytes], { type: mimeType })
}

async function migrateLegacyLocalStorage(database: IDBDatabase) {
  if (typeof window === "undefined") {
    return
  }
  const raw = window.localStorage.getItem(LEGACY_STORAGE_KEY)
  if (!raw) {
    return
  }

  let legacyRecords: LegacyStoredMediaRecord[]
  try {
    const parsed = JSON.parse(raw) as Partial<LegacyMediaStorageEnvelope>
    if (
      parsed.service !== "repair-estimate-media" ||
      parsed.schemaVersion !== 1 ||
      !Array.isArray(parsed.records)
    ) {
      window.localStorage.removeItem(LEGACY_STORAGE_KEY)
      return
    }
    legacyRecords = parsed.records
  } catch {
    window.localStorage.removeItem(LEGACY_STORAGE_KEY)
    return
  }

  try {
    const converted = legacyRecords.map((record) => ({
      id: record.id,
      fileName: record.fileName,
      mimeType: record.mimeType,
      createdAt: record.createdAt,
      blob: dataUrlToBlob(record.binaryDataUrl, record.mimeType),
      contentVersion: createOpaqueId("legacy-media-content"),
      rotationDegrees: normalizeRotationDegrees(record.rotationDegrees),
    })) satisfies StoredMediaRecord[]
    const keysTransaction = database.transaction(MEDIA_STORE_NAME, "readonly")
    const existingKeysRequest = keysTransaction
      .objectStore(MEDIA_STORE_NAME)
      .getAllKeys()
    const existingKeys = new Set(
      (await requestToPromise(existingKeysRequest)).map(String)
    )
    await transactionToPromise(keysTransaction)

    const recordsToInsert = converted.filter(
      (record) => !existingKeys.has(record.id)
    )
    if (recordsToInsert.length > 0) {
      const transaction = database.transaction(MEDIA_STORE_NAME, "readwrite")
      const store = transaction.objectStore(MEDIA_STORE_NAME)
      recordsToInsert.forEach((record) => store.put(record))
      await transactionToPromise(transaction)
    }
    window.localStorage.removeItem(LEGACY_STORAGE_KEY)
  } catch {
    // Keep v1 data for another migration attempt; IndexedDB remains usable.
  }
}

async function getDatabase() {
  const database = await openDatabase()
  runtimeState.migrationPromise ??= runSerializedMutation(() =>
    migrateLegacyLocalStorage(database)
  )
  await runtimeState.migrationPromise
  return database
}

function storageUrl(
  mediaId: string,
  variant: "small" | "largeWebp" | "original"
) {
  return `mock-estimate-media://${mediaId}/${variant}`
}

function toStorageRef(record: StoredMediaRecord): RepairEstimateMediaRefDto {
  return {
    id: record.id,
    fileName: record.fileName,
    mimeType: record.mimeType,
    kind: mediaKindFromMimeType(record.mimeType),
    createdAt: record.createdAt,
    rotationDegrees: normalizeRotationDegrees(record.rotationDegrees),
    processingStatus: "READY",
    originalAvailable: true,
    provenance: {
      source: "BROWSER_MOCK",
      ingestService: "IO",
      derivativeService: "GO",
    },
    variants: {
      small: {
        url: storageUrl(record.id, "small"),
        storageRef: record.id,
        mimeType: record.mimeType,
        width: null,
        height: null,
      },
      largeWebp: {
        url: storageUrl(record.id, "largeWebp"),
        storageRef: record.id,
        mimeType: record.mimeType,
        width: null,
        height: null,
      },
      original: {
        url: storageUrl(record.id, "original"),
        storageRef: record.id,
        mimeType: record.mimeType,
        width: null,
        height: null,
      },
    },
  }
}

function releaseObjectUrl(mediaId: string) {
  const objectUrl = runtimeState.objectUrlByMediaId.get(mediaId)
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
  }
  runtimeState.objectUrlByMediaId.delete(mediaId)
  runtimeState.objectUrlVersionByMediaId.delete(mediaId)
}

function releaseAllObjectUrls() {
  runtimeState.cacheGeneration += 1
  for (const mediaId of runtimeState.objectUrlByMediaId.keys()) {
    releaseObjectUrl(mediaId)
  }
}

function cacheObjectUrl(record: StoredMediaRecord, objectUrl: string) {
  releaseObjectUrl(record.id)
  runtimeState.objectUrlByMediaId.set(record.id, objectUrl)
  runtimeState.objectUrlVersionByMediaId.set(record.id, record.contentVersion)
  while (runtimeState.objectUrlByMediaId.size > MAX_OBJECT_URL_CACHE_SIZE) {
    const oldestMediaId = runtimeState.objectUrlByMediaId.keys().next().value
    if (oldestMediaId === undefined) {
      break
    }
    releaseObjectUrl(oldestMediaId)
  }
}

function invalidateMediaIds(mediaIds: readonly string[]) {
  mediaIds.forEach(releaseObjectUrl)
  runtimeState.broadcastChannel?.postMessage({
    type: "invalidate",
    mediaIds,
  })
}

if (!runtimeState.listenersRegistered) {
  runtimeState.listenersRegistered = true
  if (typeof BroadcastChannel !== "undefined") {
    runtimeState.broadcastChannel = new BroadcastChannel(
      MEDIA_INVALIDATION_CHANNEL_NAME
    )
    runtimeState.broadcastChannel.addEventListener("message", (event) => {
      const message = event.data as { type?: unknown; mediaIds?: unknown }
      if (message.type === "invalidate" && Array.isArray(message.mediaIds)) {
        message.mediaIds
          .filter((mediaId): mediaId is string => typeof mediaId === "string")
          .forEach(releaseObjectUrl)
      }
    })
  }
  if (typeof window !== "undefined") {
    window.addEventListener("pagehide", (event) => {
      if (!event.persisted) {
        releaseAllObjectUrls()
      }
    })
  }
}

async function getStoredMediaRecord(database: IDBDatabase, mediaId: string) {
  const transaction = database.transaction(MEDIA_STORE_NAME, "readonly")
  const request = transaction
    .objectStore(MEDIA_STORE_NAME)
    .get(mediaId) as IDBRequest<StoredMediaRecord | undefined>
  const record = await requestToPromise(request)
  await transactionToPromise(transaction)
  return record
}

function recordObjectUrl(database: IDBDatabase, record: StoredMediaRecord) {
  const existing = runtimeState.objectUrlByMediaId.get(record.id)
  const existingVersion = runtimeState.objectUrlVersionByMediaId.get(record.id)
  if (existing && existingVersion === record.contentVersion) {
    runtimeState.objectUrlByMediaId.delete(record.id)
    runtimeState.objectUrlByMediaId.set(record.id, existing)
    return Promise.resolve(existing)
  }
  if (existing) {
    releaseObjectUrl(record.id)
  }

  const inFlight = runtimeState.inFlightObjectUrlByMediaId.get(record.id)
  if (inFlight?.contentVersion === record.contentVersion) {
    return inFlight.promise
  }

  const cacheGeneration = runtimeState.cacheGeneration
  const promise = (async () => {
    const objectUrl = URL.createObjectURL(record.blob)
    const currentRecord = await getStoredMediaRecord(database, record.id)
    if (
      runtimeState.cacheGeneration !== cacheGeneration ||
      currentRecord?.contentVersion !== record.contentVersion
    ) {
      URL.revokeObjectURL(objectUrl)
      throw new Error("Фотография изменилась во время загрузки")
    }
    cacheObjectUrl(record, objectUrl)
    return objectUrl
  })()
  const entry: InFlightObjectUrl = {
    contentVersion: record.contentVersion,
    promise,
  }
  runtimeState.inFlightObjectUrlByMediaId.set(record.id, entry)
  const clearInFlight = () => {
    if (runtimeState.inFlightObjectUrlByMediaId.get(record.id) === entry) {
      runtimeState.inFlightObjectUrlByMediaId.delete(record.id)
    }
  }
  void promise.then(clearInFlight, clearInFlight)
  return promise
}

function validateUploads(uploads: PendingEstimateMediaUpload[]) {
  const oversized = uploads.find((upload) => upload.file.size > MAX_FILE_BYTES)
  if (oversized) {
    throw new Error(
      `Файл «${oversized.file.name}» больше 25 МБ. Уменьшите размер изображения.`
    )
  }
  const totalBytes = uploads.reduce(
    (total, upload) => total + upload.file.size,
    0
  )
  if (totalBytes > MAX_UPLOAD_BATCH_BYTES) {
    throw new Error("Общий размер выбранных фотографий превышает 250 МБ")
  }
  return totalBytes
}

async function assertStorageCapacity(requiredBytes: number) {
  if (
    requiredBytes === 0 ||
    typeof navigator === "undefined" ||
    !navigator.storage?.estimate
  ) {
    return
  }
  const estimate = await navigator.storage.estimate()
  if (
    estimate.quota !== undefined &&
    estimate.usage !== undefined &&
    estimate.quota - estimate.usage < requiredBytes
  ) {
    throw new Error(
      "Недостаточно места в браузере для выбранных фотографий. Освободите хранилище или уменьшите размер файлов."
    )
  }
}

function translateStorageError(error: unknown): never {
  if (error instanceof DOMException && error.name === "QuotaExceededError") {
    throw new Error(
      "Недостаточно места в браузере для сохранения фотографий. Уменьшите размер файлов."
    )
  }
  throw error
}

export class IndexedDbRepairEstimateMediaAdapter implements RepairEstimateMediaClient {
  async upload(uploads: PendingEstimateMediaUpload[]) {
    if (uploads.length > 20) {
      throw new Error("Можно прикрепить не более 20 фотографий")
    }
    if (uploads.length === 0) {
      return []
    }
    const totalBytes = validateUploads(uploads)
    await assertStorageCapacity(totalBytes)
    const database = await getDatabase()
    const records: StoredMediaRecord[] = uploads.map((upload) => ({
      id: upload.id,
      fileName: upload.file.name,
      mimeType: upload.file.type || "application/octet-stream",
      createdAt: new Date().toISOString(),
      blob: upload.file,
      contentVersion: createOpaqueId("media-content"),
      rotationDegrees: normalizeRotationDegrees(upload.rotationDegrees),
    }))

    try {
      return await runSerializedMutation(async () => {
        const transaction = database.transaction(MEDIA_STORE_NAME, "readwrite")
        const store = transaction.objectStore(MEDIA_STORE_NAME)
        records.forEach((record) => store.put(record))
        await transactionToPromise(transaction)
        invalidateMediaIds(records.map((record) => record.id))
        return records.map(toStorageRef)
      })
    } catch (error) {
      return translateStorageError(error)
    }
  }

  async hydrate(refs: RepairEstimateMediaRefDto[]) {
    if (refs.length === 0) {
      return []
    }
    const database = await getDatabase()
    const transaction = database.transaction(MEDIA_STORE_NAME, "readonly")
    const store = transaction.objectStore(MEDIA_STORE_NAME)
    const requests = refs.map(
      (ref) => store.get(ref.id) as IDBRequest<StoredMediaRecord | undefined>
    )
    const records = await Promise.all(requests.map(requestToPromise))
    await transactionToPromise(transaction)

    return Promise.all(
      refs.map(async (ref, index) => {
        const record = records[index]
        const previewVariants = {
          small: ref.variants.small,
          largeWebp: ref.variants.largeWebp,
        }
        if (!record) {
          return { ...ref, variants: previewVariants }
        }
        const objectUrl = await recordObjectUrl(database, record)
        return {
          ...ref,
          rotationDegrees: normalizeRotationDegrees(ref.rotationDegrees),
          processingStatus: "READY" as const,
          originalAvailable: true,
          provenance: ref.provenance ?? {
            source: "BROWSER_MOCK" as const,
            ingestService: "IO" as const,
            derivativeService: "GO" as const,
          },
          variants: {
            small: { ...ref.variants.small, url: objectUrl },
            largeWebp: { ...ref.variants.largeWebp, url: objectUrl },
          },
        }
      })
    )
  }

  async resolveOriginalUrl(
    mediaId: string,
    context: OriginalMediaViewerContext
  ) {
    if (!canUseOriginalMedia(context)) {
      throw new Error("Оригинал недоступен в этом контексте")
    }
    const database = await getDatabase()
    const record = await getStoredMediaRecord(database, mediaId)
    if (!record) {
      throw new Error("Оригинал фотографии не найден")
    }
    return recordObjectUrl(database, record)
  }

  dehydrate(refs: RepairEstimateMediaRefDto[]) {
    return refs.map((ref) => {
      const original =
        ref.variants.original ??
        (ref.originalAvailable
          ? {
              url: storageUrl(ref.id, "original"),
              storageRef: ref.id,
              mimeType: ref.mimeType,
              width: null,
              height: null,
            }
          : undefined)

      return {
        ...ref,
        rotationDegrees: normalizeRotationDegrees(ref.rotationDegrees),
        variants: {
          small: {
            ...ref.variants.small,
            url: storageUrl(ref.variants.small.storageRef, "small"),
          },
          largeWebp: {
            ...ref.variants.largeWebp,
            url: storageUrl(ref.variants.largeWebp.storageRef, "largeWebp"),
          },
          ...(original
            ? {
                original: {
                  ...original,
                  url: storageUrl(original.storageRef, "original"),
                },
              }
            : {}),
        },
      }
    })
  }

  async updateRotation(
    mediaId: string,
    rotationDegrees: RepairEstimateMediaRotationDegrees
  ) {
    const database = await getDatabase()
    return runSerializedMutation(async () => {
      const transaction = database.transaction(MEDIA_STORE_NAME, "readwrite")
      const store = transaction.objectStore(MEDIA_STORE_NAME)
      const request = store.get(mediaId) as IDBRequest<
        StoredMediaRecord | undefined
      >
      const record = await requestToPromise(request)
      if (!record) {
        transaction.abort()
        throw new Error("Фотография сметы не найдена")
      }
      const updated: StoredMediaRecord = {
        ...record,
        rotationDegrees: normalizeRotationDegrees(rotationDegrees),
      }
      store.put(updated)
      await transactionToPromise(transaction)
      return toStorageRef(updated)
    })
  }

  async discard(mediaIds: string[]) {
    if (mediaIds.length === 0) {
      return
    }
    const database = await getDatabase()
    return runSerializedMutation(async () => {
      const transaction = database.transaction(MEDIA_STORE_NAME, "readwrite")
      const store = transaction.objectStore(MEDIA_STORE_NAME)
      mediaIds.forEach((mediaId) => store.delete(mediaId))
      await transactionToPromise(transaction)
      invalidateMediaIds(mediaIds)
    })
  }
}
