export const INVENTORY_FINDING_MEDIA_OWNER_TYPE = "INVENTORY_FINDING" as const
export const INVENTORY_FINDING_MEDIA_CONTEXT = "INSPECTION" as const
export const CABIN_MEDIA_OWNER_TYPE = "CABIN" as const
export const CABIN_MEDIA_CONTEXT = "WAREHOUSE" as const
export const MAINTENANCE_ESTIMATE_MEDIA_OWNER_TYPE =
  "MAINTENANCE_ESTIMATE" as const
export const MAINTENANCE_ESTIMATE_MEDIA_CONTEXT = "ESTIMATE" as const
export const MAINTENANCE_REPAIR_MEDIA_OWNER_TYPE = "MAINTENANCE_REPAIR" as const
export const MAINTENANCE_REPAIR_MEDIA_CONTEXT = "REPAIR" as const
export const MAINTENANCE_ACCEPTANCE_MEDIA_OWNER_TYPE =
  "MAINTENANCE_ACCEPTANCE" as const
export const MAINTENANCE_ACCEPTANCE_MEDIA_CONTEXT = "ACCEPTANCE" as const
export const LOGISTICS_RETURN_MEDIA_OWNER_TYPE = "LOGISTICS_RETURN" as const
export const LOGISTICS_RETURN_MEDIA_CONTEXT = "RETURN_INSPECTION" as const
export const LOGISTICS_SHIPMENT_MEDIA_OWNER_TYPE = "LOGISTICS_SHIPMENT" as const
export const LOGISTICS_SHIPMENT_MEDIA_CONTEXT = "SHIPMENT" as const
export const LOGISTICS_TRANSFER_MEDIA_OWNER_TYPE = "LOGISTICS_TRANSFER" as const
export const LOGISTICS_TRANSFER_MEDIA_CONTEXT = "TRANSFER" as const

export type InventoryFindingMediaOwner = Readonly<{
  ownerType: typeof INVENTORY_FINDING_MEDIA_OWNER_TYPE
  ownerId: string
  warehouseId: string
  context: typeof INVENTORY_FINDING_MEDIA_CONTEXT
}>

export type CabinMediaOwner = Readonly<{
  ownerType: typeof CABIN_MEDIA_OWNER_TYPE
  ownerId: string
  warehouseId: string
  context: typeof CABIN_MEDIA_CONTEXT
}>

type MaintenanceOwnerScope<
  OwnerType extends string,
  Context extends string,
> = Readonly<{
  ownerType: OwnerType
  ownerId: string
  warehouseId: string
  context: Context
}>

export type MaintenanceMediaOwner =
  | MaintenanceOwnerScope<
      typeof MAINTENANCE_ESTIMATE_MEDIA_OWNER_TYPE,
      typeof MAINTENANCE_ESTIMATE_MEDIA_CONTEXT
    >
  | MaintenanceOwnerScope<
      typeof MAINTENANCE_REPAIR_MEDIA_OWNER_TYPE,
      typeof MAINTENANCE_REPAIR_MEDIA_CONTEXT
    >
  | MaintenanceOwnerScope<
      typeof MAINTENANCE_ACCEPTANCE_MEDIA_OWNER_TYPE,
      typeof MAINTENANCE_ACCEPTANCE_MEDIA_CONTEXT
    >

type LogisticsOwnerScope<
  OwnerType extends string,
  Context extends string,
> = Readonly<{
  ownerType: OwnerType
  documentId: string
  lineId: string
  warehouseId: string
  context: Context
}>

export type LogisticsMediaOwner =
  | LogisticsOwnerScope<
      typeof LOGISTICS_RETURN_MEDIA_OWNER_TYPE,
      typeof LOGISTICS_RETURN_MEDIA_CONTEXT
    >
  | LogisticsOwnerScope<
      typeof LOGISTICS_SHIPMENT_MEDIA_OWNER_TYPE,
      typeof LOGISTICS_SHIPMENT_MEDIA_CONTEXT
    >
  | LogisticsOwnerScope<
      typeof LOGISTICS_TRANSFER_MEDIA_OWNER_TYPE,
      typeof LOGISTICS_TRANSFER_MEDIA_CONTEXT
    >

export type ServiceMediaOwner =
  | InventoryFindingMediaOwner
  | CabinMediaOwner
  | MaintenanceMediaOwner
  | LogisticsMediaOwner

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

export function cabinMediaOwner(
  ownerId: string,
  warehouseId: string
): CabinMediaOwner {
  return {
    ownerType: CABIN_MEDIA_OWNER_TYPE,
    ownerId,
    warehouseId,
    context: CABIN_MEDIA_CONTEXT,
  }
}

export function maintenanceEstimateMediaOwner(
  ownerId: string,
  warehouseId: string
): MaintenanceMediaOwner {
  return {
    ownerType: MAINTENANCE_ESTIMATE_MEDIA_OWNER_TYPE,
    ownerId,
    warehouseId,
    context: MAINTENANCE_ESTIMATE_MEDIA_CONTEXT,
  }
}

export function maintenanceRepairMediaOwner(
  ownerId: string,
  warehouseId: string
): MaintenanceMediaOwner {
  return {
    ownerType: MAINTENANCE_REPAIR_MEDIA_OWNER_TYPE,
    ownerId,
    warehouseId,
    context: MAINTENANCE_REPAIR_MEDIA_CONTEXT,
  }
}

export function maintenanceAcceptanceMediaOwner(
  ownerId: string,
  warehouseId: string
): MaintenanceMediaOwner {
  return {
    ownerType: MAINTENANCE_ACCEPTANCE_MEDIA_OWNER_TYPE,
    ownerId,
    warehouseId,
    context: MAINTENANCE_ACCEPTANCE_MEDIA_CONTEXT,
  }
}

export function logisticsReturnMediaOwner(
  documentId: string,
  lineId: string,
  warehouseId: string
): LogisticsMediaOwner {
  return {
    ownerType: LOGISTICS_RETURN_MEDIA_OWNER_TYPE,
    documentId,
    lineId,
    warehouseId,
    context: LOGISTICS_RETURN_MEDIA_CONTEXT,
  }
}

export function logisticsShipmentMediaOwner(
  documentId: string,
  lineId: string,
  warehouseId: string
): LogisticsMediaOwner {
  return {
    ownerType: LOGISTICS_SHIPMENT_MEDIA_OWNER_TYPE,
    documentId,
    lineId,
    warehouseId,
    context: LOGISTICS_SHIPMENT_MEDIA_CONTEXT,
  }
}

export function logisticsTransferMediaOwner(
  documentId: string,
  lineId: string,
  warehouseId: string
): LogisticsMediaOwner {
  return {
    ownerType: LOGISTICS_TRANSFER_MEDIA_OWNER_TYPE,
    documentId,
    lineId,
    warehouseId,
    context: LOGISTICS_TRANSFER_MEDIA_CONTEXT,
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
  folderId: string
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

export type CabinCoverVariant = MediaVariant &
  Readonly<{
    mediaId: string
    generation: number
  }>

export type CabinCoverProjection = Readonly<{
  cabinId: string
  photoCount: number
  cover: CabinCoverVariant | null
  previews: readonly CabinCoverVariant[]
}>

export type CabinCoverPage = Readonly<{
  items: readonly CabinCoverProjection[]
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
