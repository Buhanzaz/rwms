export const INVENTORY_FINDING_MEDIA_OWNER_TYPE = "INVENTORY_FINDING" as const
export const INVENTORY_FINDING_MEDIA_CONTEXT = "INSPECTION" as const

export type InventoryFindingMediaOwner = Readonly<{
  ownerType: typeof INVENTORY_FINDING_MEDIA_OWNER_TYPE
  ownerId: string
  warehouseId: string
  context: typeof INVENTORY_FINDING_MEDIA_CONTEXT
}>

export function inventoryFindingMediaOwner(
  ownerId: string,
  warehouseId: string
): InventoryFindingMediaOwner {
  return {
    ownerType: INVENTORY_FINDING_MEDIA_OWNER_TYPE,
    ownerId,
    warehouseId,
    context: INVENTORY_FINDING_MEDIA_CONTEXT,
  }
}

export type MediaKind = "IMAGE" | "VIDEO"
export type ServiceMediaStatus =
  "UPLOADING" | "PROCESSING" | "READY" | "FAILED" | "DELETED"
export type DerivedMediaVariantKind = "SMALL" | "MEDIUM" | "LARGE"
export type MediaRotationDegrees = 0 | 90 | 180 | 270

export type MediaVariant = Readonly<{
  kind: DerivedMediaVariantKind
  contentType: string
  contentPath: string
  width: number | null
  height: number | null
}>

export type MediaAsset = Readonly<{
  id: string
  fileName: string
  contentType: string
  kind: MediaKind
  status: ServiceMediaStatus
  version: number
  generation: number
  rotationDegrees: MediaRotationDegrees
  sortOrder: number
  sizeBytes: number | null
  createdAt: string
  variants: readonly MediaVariant[]
}>

export type ReadyMediaReference = Readonly<{
  mediaId: string
  generation: number
}>

export function readyMediaReference(
  asset: MediaAsset
): ReadyMediaReference | null {
  return asset.status === "READY" && asset.generation > 0
    ? { mediaId: asset.id, generation: asset.generation }
    : null
}

export type MediaPage = Readonly<{
  items: readonly MediaAsset[]
  next: string | null
}>

export type UploadSession = Readonly<{
  uploadSessionId: string
  mediaId: string
  expiresAt: string
  contentUploadUrl: string
}>

export type UploadedObject = Readonly<{
  objectVersionId: string
  etag: string
  checksumSha256: string
}>

export type MediaUploadResult = Readonly<{
  session: UploadSession
  uploadedObject: UploadedObject
  asset: MediaAsset
}>

export type DisposableMediaObjectUrl = Readonly<{
  url: string
  contentType: string
  size: number
  dispose: () => void
}>
