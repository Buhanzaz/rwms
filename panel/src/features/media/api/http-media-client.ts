import { ApiError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type {
  DisposableMediaObjectUrl,
  InventoryFindingMediaOwner,
  MediaAsset,
  MediaPage,
  MediaRotationDegrees,
  MediaUploadResult,
  MediaVariant,
  UploadedObject,
  UploadSession,
} from "@/features/media/model/service-media"

type FetchFunction = (
  input: string | URL | Request,
  init?: RequestInit
) => Promise<Response>

type ObjectUrlFactory = Readonly<{
  create: (blob: Blob) => string
  revoke: (url: string) => void
}>

export type HttpMediaClientOptions = Readonly<{
  baseUrl?: string
  fetch?: FetchFunction
  randomUUID?: () => string
  sha256?: (blob: Blob) => Promise<string>
  objectUrls?: ObjectUrlFactory
}>

export type CreateUploadSessionInput = Readonly<{
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

export interface InventoryFindingMediaClient {
  createUploadSession(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    input: CreateUploadSessionInput,
    idempotencyKey: string
  ): Promise<UploadSession>
  uploadSessionContent(
    accessToken: string,
    session: UploadSession,
    file: Blob,
    idempotencyKey: string
  ): Promise<UploadedObject>
  finalizeUploadSession(
    accessToken: string,
    sessionId: string,
    uploadedObject: UploadedObject,
    idempotencyKey: string
  ): Promise<MediaAsset>
  uploadFile(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    file: File,
    sortOrder?: number
  ): Promise<MediaUploadResult>
  listOwnerMedia(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    options?: ListOwnerMediaOptions
  ): Promise<MediaPage>
  createOriginalObjectUrl(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    mediaId: string
  ): Promise<DisposableMediaObjectUrl>
  createVariantObjectUrl(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    variant: MediaVariant
  ): Promise<DisposableMediaObjectUrl>
  rotate(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    mediaId: string,
    rotationDegrees: MediaRotationDegrees,
    expectedVersion: number,
    idempotencyKey: string
  ): Promise<MediaAsset>
}

const UUID_PATH_PART = "[0-9a-fA-F-]{36}"
const UPLOAD_CONTENT_PATH = new RegExp(
  `^/api/media/v1/upload-sessions/${UUID_PATH_PART}/content$`
)
const VARIANT_CONTENT_PATH = new RegExp(
  `^/api/media/v1/assets/${UUID_PATH_PART}/variants/(SMALL|MEDIUM|LARGE)/content$`
)
const SHA256 = /^[0-9a-f]{64}$/
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
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
const DERIVED_VARIANTS = new Set(["SMALL", "MEDIUM", "LARGE"])
const ROTATIONS = new Set([0, 90, 180, 270])

export class HttpMediaClient implements InventoryFindingMediaClient {
  readonly #baseUrl: URL
  readonly #fetch: FetchFunction
  readonly #randomUUID: () => string
  readonly #sha256: (blob: Blob) => Promise<string>
  readonly #objectUrls: ObjectUrlFactory

  constructor(options: HttpMediaClientOptions = {}) {
    this.#baseUrl = normalizeBaseUrl(
      options.baseUrl ?? getGatewayRuntimeConfig().mediaApiBaseUrl
    )
    this.#fetch = options.fetch ?? ((input, init) => fetch(input, init))
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
    owner: InventoryFindingMediaOwner,
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
    idempotencyKey: string
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
    return parseUploadedObject(
      await this.#requestJson<unknown>(accessToken, contentUrl, {
        method: "PUT",
        headers: {
          "Content-Type": file.type,
          "Idempotency-Key": idempotencyKey,
        },
        body: file,
      })
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
    owner: InventoryFindingMediaOwner,
    file: File,
    sortOrder = 0
  ): Promise<MediaUploadResult> {
    if (
      !file.name.trim() ||
      file.size <= 0 ||
      !MEDIA_CONTENT_TYPES.has(file.type)
    ) {
      throw new Error("Media file metadata is invalid")
    }
    const checksumSha256 = await this.#sha256(file)
    if (!SHA256.test(checksumSha256)) {
      throw new Error("SHA-256 media checksum is invalid")
    }
    const session = await this.createUploadSession(
      accessToken,
      owner,
      {
        fileName: file.name,
        contentType: file.type,
        contentLength: file.size,
        checksumSha256,
        sortOrder,
      },
      this.#randomUUID()
    )
    const finalizeKey = this.#randomUUID()
    const uploadedObject = await this.uploadSessionContent(
      accessToken,
      session,
      file,
      finalizeKey
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
    owner: InventoryFindingMediaOwner,
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

  async createOriginalObjectUrl(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
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
    owner: InventoryFindingMediaOwner,
    variant: MediaVariant
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

  async rotate(
    accessToken: string,
    owner: InventoryFindingMediaOwner,
    mediaId: string,
    rotationDegrees: MediaRotationDegrees,
    expectedVersion: number,
    idempotencyKey: string
  ) {
    const url = this.#apiUrl(
      `v1/assets/${encodeURIComponent(mediaId)}/rotation`
    )
    setOwnerQuery(url, owner)
    return parseMediaAsset(
      await this.#requestJson<unknown>(accessToken, url, {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify({ rotationDegrees, expectedVersion }),
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
      throw new ApiError(await readProblemDetail(response), response.status)
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

function setOwnerQuery(url: URL, owner: InventoryFindingMediaOwner) {
  url.searchParams.set("ownerType", owner.ownerType)
  url.searchParams.set("ownerId", owner.ownerId)
  url.searchParams.set("warehouseId", owner.warehouseId)
  url.searchParams.set("context", owner.context)
}

function requireExactOwnerQuery(url: URL, owner: InventoryFindingMediaOwner) {
  const expected = new Map<string, string>([
    ["ownerType", owner.ownerType],
    ["ownerId", owner.ownerId],
    ["warehouseId", owner.warehouseId],
    ["context", owner.context],
  ])
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
  owner: InventoryFindingMediaOwner
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

function parseMediaAsset(
  value: unknown,
  origin: string,
  owner?: InventoryFindingMediaOwner
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
  return {
    id: requireUUID(record.id, "id"),
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
    variants: record.variants.map((variant) => {
      if (!owner) throw new Error("Media variants require an owner scope")
      return parseMediaVariant(
        variant,
        origin,
        owner,
        requireUUID(record.id, "id")
      )
    }),
  }
}

function parseMediaVariant(
  value: unknown,
  origin: string,
  owner: InventoryFindingMediaOwner,
  mediaId: string
): MediaVariant {
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
  return {
    kind: kind as MediaVariant["kind"],
    contentType: requireExactContentType(record.contentType, "image/webp"),
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
  if (!contentType.includes("json"))
    return (await response.text()).trim() || fallback
  const body = (await response.json()) as {
    detail?: unknown
    message?: unknown
  }
  const detail = body.detail ?? body.message
  return typeof detail === "string" && detail.trim() ? detail : fallback
}

async function browserSha256(blob: Blob) {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("")
}
