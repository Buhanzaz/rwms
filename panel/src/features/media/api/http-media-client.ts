import { ApiError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import {
  cabinMediaOwner,
  type CabinCoverPage,
  type DisposableMediaObjectUrl,
  type MediaAsset,
  type MediaPage,
  type MediaRotationDegrees,
  type MediaUploadResult,
  type MediaVariant,
  type PlaybackMediaVariant,
  type ServiceMediaOwner,
  type UploadedObject,
  type UploadSession,
} from "@/features/media/model/service-media"

type FetchFunction = (
  input: string | URL | Request,
  init?: RequestInit
) => Promise<Response>

export type MediaUploadProgress = Readonly<{
  loadedBytes: number
  totalBytes: number
  percentage: number
}>

export type MediaUploadProgressListener = (
  progress: MediaUploadProgress
) => void

export type ProgressUploadFunction = (
  input: string | URL,
  init: RequestInit,
  onProgress: MediaUploadProgressListener
) => Promise<Response>

type ObjectUrlFactory = Readonly<{
  create: (blob: Blob) => string
  revoke: (url: string) => void
}>

export type HttpMediaClientOptions = Readonly<{
  baseUrl?: string
  fetch?: FetchFunction
  progressUpload?: ProgressUploadFunction
  randomUUID?: () => string
  sha256?: (blob: Blob) => Promise<string>
  objectUrls?: ObjectUrlFactory
}>

export type CreateUploadSessionInput = Readonly<{
  folderId?: string
  fileName: string
  contentType: string
  contentLength: number
  checksumSha256: string
  sortOrder?: number
}>

export type ListOwnerMediaOptions = Readonly<{
  limit?: number
  cursor?: string
}>

export type MediaUploadCommandKeys = Readonly<{
  createSession: string
  uploadAndFinalize: string
}>

export interface MediaClient {
  createUploadSession(
    accessToken: string,
    owner: ServiceMediaOwner,
    input: CreateUploadSessionInput,
    idempotencyKey: string
  ): Promise<UploadSession>
  uploadSessionContent(
    accessToken: string,
    session: UploadSession,
    file: Blob,
    idempotencyKey: string,
    onProgress?: MediaUploadProgressListener
  ): Promise<UploadedObject>
  finalizeUploadSession(
    accessToken: string,
    sessionId: string,
    uploadedObject: UploadedObject,
    idempotencyKey: string
  ): Promise<MediaAsset>
  uploadFile(
    accessToken: string,
    owner: ServiceMediaOwner,
    file: File,
    sortOrder?: number,
    folderId?: string,
    commandKeys?: MediaUploadCommandKeys,
    onProgress?: MediaUploadProgressListener
  ): Promise<MediaUploadResult>
  listOwnerMedia(
    accessToken: string,
    owner: ServiceMediaOwner,
    options?: ListOwnerMediaOptions
  ): Promise<MediaPage>
  listCabinCovers(
    accessToken: string,
    warehouseId: string,
    cabinIds: readonly string[]
  ): Promise<CabinCoverPage>
  createOriginalObjectUrl(
    accessToken: string,
    owner: ServiceMediaOwner,
    mediaId: string
  ): Promise<DisposableMediaObjectUrl>
  createVariantObjectUrl(
    accessToken: string,
    owner: ServiceMediaOwner,
    variant: MediaVariant | PlaybackMediaVariant
  ): Promise<DisposableMediaObjectUrl>
  deleteAsset(
    accessToken: string,
    owner: ServiceMediaOwner,
    mediaId: string,
    expectedVersion: number,
    idempotencyKey: string
  ): Promise<MediaAsset>
}

export type InventoryFindingMediaClient = MediaClient

const UUID_PATH_PART = "[0-9a-fA-F-]{36}"
const UPLOAD_CONTENT_PATH = new RegExp(
  `^/api/media/v1/upload-sessions/${UUID_PATH_PART}/content$`
)
const VARIANT_CONTENT_PATH = new RegExp(
  `^/api/media/v1/assets/${UUID_PATH_PART}/variants/(SMALL|MEDIUM|LARGE|PLAYBACK)/content$`
)
const SHA256 = /^[0-9a-f]{64}$/
// Service-owned IDs include deterministic UUID-shaped identifiers imported
// from the old panel (for example, warehouse IDs with zero version bits).
// Match the canonical UUID text form here and leave UUID generation rules to
// the owning service.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MEDIA_CONTENT_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "video/mp4",
  "video/webm",
])
const MEDIA_KINDS = new Set(["IMAGE", "VIDEO"])
const MEDIA_STATUSES = new Set([
  "UPLOADING",
  "PROCESSING",
  "READY",
  "FAILED",
  "DELETED",
])
const DERIVED_VARIANTS = new Set(["SMALL", "MEDIUM", "LARGE", "PLAYBACK"])
const ROTATIONS = new Set([0, 90, 180, 270])

export class HttpMediaClient implements MediaClient {
  readonly #baseUrl: URL
  readonly #fetch: FetchFunction
  readonly #progressUpload: ProgressUploadFunction
  readonly #randomUUID: () => string
  readonly #sha256: (blob: Blob) => Promise<string>
  readonly #objectUrls: ObjectUrlFactory

  constructor(options: HttpMediaClientOptions = {}) {
    this.#baseUrl = normalizeBaseUrl(
      options.baseUrl ?? getGatewayRuntimeConfig().mediaApiBaseUrl
    )
    this.#fetch = options.fetch ?? ((input, init) => fetch(input, init))
    this.#progressUpload = options.progressUpload ?? browserProgressUpload
    this.#randomUUID = options.randomUUID ?? (() => crypto.randomUUID())
    this.#sha256 = options.sha256 ?? browserSha256
    this.#objectUrls =
      options.objectUrls ??
      ({
        create: (blob) => URL.createObjectURL(blob),
        revoke: (url) => URL.revokeObjectURL(url),
      } satisfies ObjectUrlFactory)
  }

  async createUploadSession(
    accessToken: string,
    owner: ServiceMediaOwner,
    input: CreateUploadSessionInput,
    idempotencyKey: string
  ) {
    const session = await this.#requestJson<unknown>(
      accessToken,
      this.#apiUrl("v1/upload-sessions"),
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify({
          ...owner,
          ...input,
          sortOrder: input.sortOrder ?? 0,
        }),
      }
    )
    return parseUploadSession(session, this.#baseUrl.origin)
  }

  async uploadSessionContent(
    accessToken: string,
    session: UploadSession,
    file: Blob,
    idempotencyKey: string,
    onProgress?: MediaUploadProgressListener
  ) {
    const contentUrl = requireSameOriginPath(
      session.contentUploadUrl,
      this.#baseUrl.origin,
      UPLOAD_CONTENT_PATH
    )
    if (
      contentUrl.pathname !==
      `/api/media/v1/upload-sessions/${session.uploadSessionId}/content`
    ) {
      throw new Error("Media content path does not match its upload session")
    }
    const init: RequestInit = {
      method: "PUT",
      headers: {
        "Content-Type": file.type,
        "Idempotency-Key": idempotencyKey,
      },
      body: file,
    }
    return parseUploadedObject(
      onProgress
        ? await this.#requestJsonWithProgress<unknown>(
            accessToken,
            contentUrl,
            init,
            onProgress
          )
        : await this.#requestJson<unknown>(accessToken, contentUrl, init)
    )
  }

  async finalizeUploadSession(
    accessToken: string,
    sessionId: string,
    uploadedObject: UploadedObject,
    idempotencyKey: string
  ) {
    return parseMediaAsset(
      await this.#requestJson<unknown>(
        accessToken,
        this.#apiUrl(
          `v1/upload-sessions/${encodeURIComponent(sessionId)}/complete`
        ),
        {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(uploadedObject),
        }
      ),
      this.#baseUrl.origin
    )
  }

  async uploadFile(
    accessToken: string,
    owner: ServiceMediaOwner,
    file: File,
    sortOrder = 0,
    folderId?: string,
    commandKeys?: MediaUploadCommandKeys,
    onProgress?: MediaUploadProgressListener
  ): Promise<MediaUploadResult> {
    if (
      !file.name.trim() ||
      file.size <= 0 ||
      !MEDIA_CONTENT_TYPES.has(file.type)
    ) {
      throw new Error("Media file metadata is invalid")
    }
    if (folderId !== undefined && !UUID.test(folderId)) {
      throw new Error("Некорректная папка медиафайлов.")
    }
    onProgress?.({ loadedBytes: 0, totalBytes: file.size, percentage: 0 })
    const checksumSha256 = await this.#sha256(file)
    if (!SHA256.test(checksumSha256)) {
      throw new Error("SHA-256 media checksum is invalid")
    }
    const session = await this.createUploadSession(
      accessToken,
      owner,
      {
        folderId,
        fileName: file.name,
        contentType: file.type,
        contentLength: file.size,
        checksumSha256,
        sortOrder,
      },
      commandKeys?.createSession ?? this.#randomUUID()
    )
    const finalizeKey = commandKeys?.uploadAndFinalize ?? this.#randomUUID()
    const uploadedObject = await this.uploadSessionContent(
      accessToken,
      session,
      file,
      finalizeKey,
      onProgress
    )
    const asset = await this.finalizeUploadSession(
      accessToken,
      session.uploadSessionId,
      uploadedObject,
      finalizeKey
    )
    if (asset.id !== session.mediaId) {
      throw new Error("Media service returned a mismatched asset")
    }
    return { session, uploadedObject, asset }
  }

  async listOwnerMedia(
    accessToken: string,
    owner: ServiceMediaOwner,
    options: ListOwnerMediaOptions = {}
  ) {
    const url = this.#apiUrl("v1/assets")
    setOwnerQuery(url, owner)
    if (options.limit !== undefined)
      url.searchParams.set("limit", `${options.limit}`)
    if (options.cursor) url.searchParams.set("cursor", options.cursor)
    return parseMediaPage(
      await this.#requestJson<unknown>(accessToken, url),
      this.#baseUrl.origin,
      owner
    )
  }

  async listCabinCovers(
    accessToken: string,
    warehouseId: string,
    cabinIds: readonly string[]
  ) {
    if (
      !UUID.test(warehouseId) ||
      cabinIds.length < 1 ||
      cabinIds.length > 200 ||
      new Set(cabinIds).size !== cabinIds.length ||
      cabinIds.some((cabinId) => !UUID.test(cabinId))
    ) {
      throw new Error("Invalid cabin cover request")
    }
    return parseCabinCoverPage(
      await this.#requestJson<unknown>(
        accessToken,
        this.#apiUrl("v1/cabin-covers"),
        {
          method: "POST",
          body: JSON.stringify({ warehouseId, cabinIds }),
        }
      ),
      this.#baseUrl.origin,
      warehouseId
    )
  }

  async createOriginalObjectUrl(
    accessToken: string,
    owner: ServiceMediaOwner,
    mediaId: string
  ) {
    const url = this.#apiUrl(
      `v1/assets/${encodeURIComponent(mediaId)}/original`
    )
    setOwnerQuery(url, owner)
    return this.#createObjectUrl(accessToken, url)
  }

  async createVariantObjectUrl(
    accessToken: string,
    owner: ServiceMediaOwner,
    variant: MediaVariant | PlaybackMediaVariant
  ) {
    const url = requireSameOriginPath(
      variant.contentPath,
      this.#baseUrl.origin,
      VARIANT_CONTENT_PATH,
      true
    )
    requireExactOwnerQuery(url, owner)
    return this.#createObjectUrl(accessToken, url, variant.contentType)
  }

  async deleteAsset(
    accessToken: string,
    owner: ServiceMediaOwner,
    mediaId: string,
    expectedVersion: number,
    idempotencyKey: string
  ) {
    const url = this.#apiUrl(
      `v1/assets/${encodeURIComponent(mediaId)}/deletion`
    )
    setOwnerQuery(url, owner)
    return parseMediaAsset(
      await this.#requestJson<unknown>(accessToken, url, {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify({ expectedVersion }),
      }),
      this.#baseUrl.origin
    )
  }

  #apiUrl(path: string) {
    return new URL(
      `${this.#baseUrl.pathname.replace(/\/$/, "")}/${path}`,
      this.#baseUrl.origin
    )
  }

  async #requestJson<T>(
    accessToken: string,
    input: URL,
    init: RequestInit = {}
  ) {
    const response = await this.#request(
      accessToken,
      input,
      init,
      "application/json"
    )
    return (await response.json()) as T
  }

  async #requestJsonWithProgress<T>(
    accessToken: string,
    input: URL,
    init: RequestInit,
    onProgress: MediaUploadProgressListener
  ) {
    if (!accessToken.trim()) throw new Error("Не получен токен доступа.")
    if (input.origin !== this.#baseUrl.origin) {
      throw new Error("Media request must stay on the panel origin")
    }
    const headers = new Headers(init.headers)
    headers.set("Authorization", `Bearer ${accessToken}`)
    headers.set("Accept", "application/json")
    const response = await this.#progressUpload(
      input,
      { ...init, headers },
      onProgress
    )
    if (!response.ok) {
      const problem = await readProblemDetail(response)
      throw new ApiError(problem.message, response.status, problem.code)
    }
    return (await response.json()) as T
  }

  async #request(
    accessToken: string,
    input: URL,
    init: RequestInit,
    accept: string
  ) {
    if (!accessToken.trim()) throw new Error("Не получен токен доступа.")
    if (input.origin !== this.#baseUrl.origin) {
      throw new Error("Media request must stay on the panel origin")
    }
    const headers = new Headers(init.headers)
    headers.set("Authorization", `Bearer ${accessToken}`)
    headers.set("Accept", accept)
    if (
      init.body !== undefined &&
      init.body !== null &&
      !headers.has("Content-Type")
    ) {
      headers.set("Content-Type", "application/json")
    }
    const response = await this.#fetch(input, { ...init, headers })
    if (!response.ok) {
      const problem = await readProblemDetail(response)
      throw new ApiError(problem.message, response.status, problem.code)
    }
    return response
  }

  async #createObjectUrl(
    accessToken: string,
    input: URL,
    expectedContentType?: string
  ): Promise<DisposableMediaObjectUrl> {
    const response = await this.#request(
      accessToken,
      input,
      { method: "GET", cache: "no-store" },
      expectedContentType ?? "image/*,video/*"
    )
    const blob = await response.blob()
    const contentType = response.headers.get("Content-Type") ?? blob.type
    if (
      !contentType ||
      (expectedContentType && contentType !== expectedContentType)
    ) {
      throw new Error("Media service returned an unexpected content type")
    }
    const url = this.#objectUrls.create(blob)
    let disposed = false
    return {
      url,
      contentType,
      size: blob.size,
      dispose: () => {
        if (disposed) return
        disposed = true
        this.#objectUrls.revoke(url)
      },
    }
  }
}

function browserProgressUpload(
  input: string | URL,
  init: RequestInit,
  onProgress: MediaUploadProgressListener
) {
  return new Promise<Response>((resolve, reject) => {
    if (!(init.body instanceof Blob)) {
      reject(new Error("Progress media upload requires a Blob body"))
      return
    }
    const request = new XMLHttpRequest()
    request.open(init.method ?? "PUT", input.toString(), true)
    new Headers(init.headers).forEach((value, name) =>
      request.setRequestHeader(name, value)
    )
    request.upload.onprogress = (event) => {
      const totalBytes = event.lengthComputable
        ? event.total
        : init.body instanceof Blob
          ? init.body.size
          : 0
      if (totalBytes <= 0) return
      const loadedBytes = Math.min(event.loaded, totalBytes)
      onProgress({
        loadedBytes,
        totalBytes,
        percentage: Math.round((loadedBytes / totalBytes) * 100),
      })
    }
    request.onerror = () => reject(new Error("Не удалось загрузить медиафайл"))
    request.onabort = () => reject(new Error("Загрузка медиафайла отменена"))
    request.onload = () => {
      const headers = new Headers()
      request
        .getAllResponseHeaders()
        .trim()
        .split(/[\r\n]+/)
        .filter(Boolean)
        .forEach((line) => {
          const separator = line.indexOf(":")
          if (separator > 0) {
            headers.append(
              line.slice(0, separator).trim(),
              line.slice(separator + 1).trim()
            )
          }
        })
      const body = [204, 205, 304].includes(request.status)
        ? null
        : request.responseText
      resolve(
        new Response(body, {
          status: request.status,
          statusText: request.statusText,
          headers,
        })
      )
    }
    request.send(init.body)
  })
}

export function createHttpMediaClient(options: HttpMediaClientOptions = {}) {
  return new HttpMediaClient(options)
}

function normalizeBaseUrl(value: string) {
  const url = new URL(value)
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname.replace(/\/$/, "") !== "/api/media"
  ) {
    throw new Error(
      "Media API base URL must be the same-origin /api/media endpoint"
    )
  }
  if (typeof window !== "undefined" && url.origin !== window.location.origin) {
    throw new Error("Media API base URL must use the panel origin")
  }
  url.pathname = "/api/media"
  return url
}

function setOwnerQuery(url: URL, owner: ServiceMediaOwner) {
  for (const [name, value] of ownerQueryEntries(owner)) {
    url.searchParams.set(name, value)
  }
}

function requireExactOwnerQuery(url: URL, owner: ServiceMediaOwner) {
  const expected = new Map<string, string>(ownerQueryEntries(owner))
  for (const [name, value] of expected) {
    if (
      url.searchParams.get(name) !== value ||
      url.searchParams.getAll(name).length !== 1
    ) {
      throw new Error("Media content path has a mismatched owner scope")
    }
  }
  const generation = Number(url.searchParams.get("generation"))
  if (!Number.isSafeInteger(generation) || generation <= 0) {
    throw new Error("Media content path has an invalid generation")
  }
  if (url.searchParams.getAll("generation").length !== 1) {
    throw new Error("Media content path has an invalid generation")
  }
  const allowed = new Set([...expected.keys(), "generation"])
  for (const name of url.searchParams.keys()) {
    if (!allowed.has(name)) {
      throw new Error("Media content path has an unexpected query parameter")
    }
  }
}

function ownerQueryEntries(owner: ServiceMediaOwner) {
  return [
    ["ownerType", owner.ownerType],
    [
      "ownerId" in owner ? "ownerId" : "documentId",
      "ownerId" in owner ? owner.ownerId : owner.documentId,
    ],
    ...("ownerId" in owner ? [] : [["lineId", owner.lineId]]),
    ["warehouseId", owner.warehouseId],
    ["context", owner.context],
  ] as Array<[string, string]>
}

function requireSameOriginPath(
  path: string,
  origin: string,
  pattern: RegExp,
  allowQuery = false
) {
  if (!path.startsWith("/"))
    throw new Error("Media content path must be relative")
  const url = new URL(path, origin)
  if (
    url.origin !== origin ||
    url.username ||
    url.password ||
    url.hash ||
    (!allowQuery && url.search) ||
    !pattern.test(url.pathname)
  ) {
    throw new Error("Media content path is outside the public media API")
  }
  return url
}

function parseUploadSession(value: unknown, origin: string): UploadSession {
  const record = requireRecord(value, "upload session")
  const session: UploadSession = {
    uploadSessionId: requireUUID(record.uploadSessionId, "uploadSessionId"),
    mediaId: requireUUID(record.mediaId, "mediaId"),
    expiresAt: requireDateTime(record.expiresAt, "expiresAt"),
    contentUploadUrl: requireString(
      record.contentUploadUrl,
      "contentUploadUrl"
    ),
  }
  const contentUrl = requireSameOriginPath(
    session.contentUploadUrl,
    origin,
    UPLOAD_CONTENT_PATH
  )
  if (
    contentUrl.pathname !==
    `/api/media/v1/upload-sessions/${session.uploadSessionId}/content`
  ) {
    throw new Error("Media content path does not match its upload session")
  }
  return session
}

function parseUploadedObject(value: unknown): UploadedObject {
  const record = requireRecord(value, "uploaded object")
  const checksumSha256 = requireString(record.checksumSha256, "checksumSha256")
  if (!SHA256.test(checksumSha256))
    throw new Error("Invalid uploaded object checksum")
  return {
    objectVersionId: requireBoundedString(
      record.objectVersionId,
      "objectVersionId",
      255
    ),
    etag: requireBoundedString(record.etag, "etag", 255),
    checksumSha256,
  }
}

function parseMediaPage(
  value: unknown,
  origin: string,
  owner: ServiceMediaOwner
): MediaPage {
  const record = requireRecord(value, "media page")
  if (!Array.isArray(record.items) || record.items.length > 100) {
    throw new Error("Invalid media page items")
  }
  if (
    record.next !== null &&
    (typeof record.next !== "string" || !record.next || record.next.length > 64)
  ) {
    throw new Error("Invalid media page cursor")
  }
  return {
    items: record.items.map((item) => parseMediaAsset(item, origin, owner)),
    next: record.next,
  }
}

function parseCabinCoverPage(
  value: unknown,
  origin: string,
  warehouseId: string
): CabinCoverPage {
  const record = requireRecord(value, "cabin cover page")
  if (!Array.isArray(record.items) || record.items.length > 200) {
    throw new Error("Invalid cabin cover page items")
  }
  const cabinIds = new Set<string>()
  return {
    items: record.items.map((value) => {
      const item = requireRecord(value, "cabin cover")
      const cabinId = requireUUID(item.cabinId, "cabinId")
      if (cabinIds.has(cabinId)) {
        throw new Error("Duplicate cabin cover projection")
      }
      cabinIds.add(cabinId)
      const photoCount = requirePositiveInteger(item.photoCount, "photoCount")
      if (photoCount > 100) throw new Error("Invalid cabin photo count")
      const owner = cabinMediaOwner(cabinId, warehouseId)
      if (!Array.isArray(item.previews) || item.previews.length > 100) {
        throw new Error("Invalid cabin preview list")
      }
      const mediaIds = new Set<string>()
      const parsePreview = (preview: unknown) => {
        const previewRecord = requireRecord(preview, "cabin cover variant")
        const mediaId = requireUUID(previewRecord.mediaId, "mediaId")
        if (mediaIds.has(mediaId)) {
          throw new Error("Duplicate cabin preview")
        }
        mediaIds.add(mediaId)
        const generation = requirePositiveInteger(
          previewRecord.generation,
          "generation"
        )
        const variant = parseMediaVariant(
          previewRecord,
          origin,
          owner,
          mediaId,
          "IMAGE"
        )
        if (variant.kind !== "SMALL") {
          throw new Error("Cabin preview is not SMALL")
        }
        return { mediaId, generation, ...variant }
      }
      const previews = item.previews.map(parsePreview)
      if (previews.length > photoCount) {
        throw new Error("Cabin preview count exceeds photo count")
      }
      if (item.cover === null) {
        return { cabinId, photoCount, cover: null, previews }
      }

      const coverRecord = requireRecord(item.cover, "cabin cover variant")
      const coverMediaId = requireUUID(coverRecord.mediaId, "mediaId")
      const coverGeneration = requirePositiveInteger(
        coverRecord.generation,
        "generation"
      )
      const coverVariant = parseMediaVariant(
        coverRecord,
        origin,
        owner,
        coverMediaId,
        "IMAGE"
      )
      if (coverVariant.kind !== "SMALL") {
        throw new Error("Cabin cover is not SMALL")
      }
      const cover = {
        mediaId: coverMediaId,
        generation: coverGeneration,
        ...coverVariant,
      }
      const matchingPreview = previews.some(
        (preview) =>
          preview.mediaId === cover.mediaId &&
          preview.generation === cover.generation &&
          preview.kind === cover.kind &&
          preview.contentType === cover.contentType &&
          preview.contentPath === cover.contentPath &&
          preview.width === cover.width &&
          preview.height === cover.height
      )
      if (!matchingPreview) {
        throw new Error("Cabin cover does not match previews")
      }
      return {
        cabinId,
        photoCount,
        cover,
        previews,
      }
    }),
  }
}

function parseMediaAsset(
  value: unknown,
  origin: string,
  owner?: ServiceMediaOwner
): MediaAsset {
  const record = requireRecord(value, "media asset")
  if (!Array.isArray(record.variants)) throw new Error("Invalid media variants")
  const kind = requireEnum(record.kind, "kind", MEDIA_KINDS)
  const status = requireEnum(record.status, "status", MEDIA_STATUSES)
  const version = requirePositiveInteger(record.version, "version")
  const generation = requireNonNegativeInteger(record.generation, "generation")
  const rotationDegrees = requireNumericEnum(
    record.rotationDegrees,
    "rotationDegrees",
    ROTATIONS
  )
  if (status === "READY" && generation === 0) {
    throw new Error("Invalid READY media generation")
  }
  if (record.variants.length > 0 && !owner) {
    throw new Error("Media variants require an owner scope")
  }
  const mediaId = requireUUID(record.id, "id")
  const parsedVariants = record.variants.map((variant) => {
    if (!owner) throw new Error("Media variants require an owner scope")
    return parseMediaVariant(
      variant,
      origin,
      owner,
      mediaId,
      kind as MediaAsset["kind"]
    )
  })
  const imageVariants = parsedVariants.filter(
    (variant): variant is MediaVariant => variant.kind !== "PLAYBACK"
  )
  const playbackVariants = parsedVariants.filter(
    (variant): variant is PlaybackMediaVariant => variant.kind === "PLAYBACK"
  )
  if (playbackVariants.length > 1) {
    throw new Error("Video asset contains duplicate PLAYBACK variants")
  }
  return {
    id: mediaId,
    folderId: requireUUID(record.folderId, "folderId"),
    fileName: requireBoundedString(record.fileName, "fileName", 512),
    contentType: requireMediaContentType(record.contentType, "contentType"),
    kind: kind as MediaAsset["kind"],
    status: status as MediaAsset["status"],
    version,
    generation,
    rotationDegrees: rotationDegrees as MediaRotationDegrees,
    sortOrder: requireNonNegativeInteger(record.sortOrder, "sortOrder"),
    sizeBytes:
      record.sizeBytes === null
        ? null
        : requirePositiveInteger(record.sizeBytes, "sizeBytes"),
    createdAt: requireDateTime(record.createdAt, "createdAt"),
    variants: imageVariants,
    ...(playbackVariants[0] ? { playbackVariant: playbackVariants[0] } : {}),
  }
}

function parseMediaVariant(
  value: unknown,
  origin: string,
  owner: ServiceMediaOwner,
  mediaId: string,
  mediaKind: MediaAsset["kind"]
): MediaVariant | PlaybackMediaVariant {
  const record = requireRecord(value, "media variant")
  const kind = requireEnum(record.kind, "kind", DERIVED_VARIANTS)
  const contentPath = requireString(record.contentPath, "contentPath")
  const contentUrl = requireSameOriginPath(
    contentPath,
    origin,
    VARIANT_CONTENT_PATH,
    true
  )
  requireExactOwnerQuery(contentUrl, owner)
  if (
    contentUrl.pathname !==
    `/api/media/v1/assets/${mediaId}/variants/${kind}/content`
  ) {
    throw new Error("Media variant path does not match its kind")
  }
  if (
    (mediaKind === "IMAGE" && kind === "PLAYBACK") ||
    (mediaKind === "VIDEO" && kind !== "PLAYBACK")
  ) {
    throw new Error("Media variant does not match the asset kind")
  }
  return {
    kind: kind as MediaVariant["kind"] | PlaybackMediaVariant["kind"],
    contentType: requireExactContentType(
      record.contentType,
      kind === "PLAYBACK" ? "video/mp4" : "image/webp"
    ),
    contentPath,
    width:
      record.width === null
        ? null
        : requirePositiveInteger(record.width, "width"),
    height:
      record.height === null
        ? null
        : requirePositiveInteger(record.height, "height"),
  }
}

function requireRecord(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`Invalid ${name} response`)
  }
  return value as Record<string, unknown>
}

function requireString(value: unknown, name: string) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return value
}

function requireBoundedString(value: unknown, name: string, maxLength: number) {
  const result = requireString(value, name)
  if (result.length > maxLength) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return result
}

function requireUUID(value: unknown, name: string) {
  const result = requireString(value, name)
  if (!UUID.test(result)) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return result
}

function requireDateTime(value: unknown, name: string) {
  const result = requireString(value, name)
  if (!Number.isFinite(Date.parse(result))) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return result
}

function requirePositiveInteger(value: unknown, name: string) {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return value as number
}

function requireNonNegativeInteger(value: unknown, name: string) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return value as number
}

function requireEnum(
  value: unknown,
  name: string,
  allowed: ReadonlySet<string>
) {
  if (typeof value !== "string" || !allowed.has(value)) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return value
}

function requireNumericEnum(
  value: unknown,
  name: string,
  allowed: ReadonlySet<number>
) {
  if (typeof value !== "number" || !allowed.has(value)) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return value
}

function requireMediaContentType(value: unknown, name: string) {
  const result = requireString(value, name)
  if (!MEDIA_CONTENT_TYPES.has(result)) {
    throw new Error(`Invalid media response field: ${name}`)
  }
  return result
}

function requireExactContentType(value: unknown, expected: string) {
  const result = requireString(value, "contentType")
  if (result !== expected) {
    throw new Error("Invalid media response field: contentType")
  }
  return result
}

async function readProblemDetail(response: Response) {
  const fallback = `Запрос завершился с ошибкой ${response.status}`
  const contentType = response.headers.get("Content-Type") ?? ""
  if (!contentType.includes("json")) {
    return { message: (await response.text()).trim() || fallback, code: null }
  }
  const body = (await response.json()) as {
    detail?: unknown
    message?: unknown
    code?: unknown
  }
  const detail = body.detail ?? body.message
  return {
    message: typeof detail === "string" && detail.trim() ? detail : fallback,
    code: typeof body.code === "string" && body.code.trim() ? body.code : null,
  }
}

async function browserSha256(blob: Blob) {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("")
}
