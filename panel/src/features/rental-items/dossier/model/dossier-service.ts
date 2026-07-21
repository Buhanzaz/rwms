export const DOSSIER_ACTIVITY_CODES = [
  "CABIN_CREATED",
  "CABIN_PASSPORT_CHANGED",
  "CABIN_STATUS_CHANGED",
  "CABIN_WAREHOUSE_CHANGED",
  "CABIN_LOGISTICS_EFFECT_APPLIED",
  "CABIN_COMMENT_REVISION_CHANGED",
  "CABIN_MANUAL_NOTE_ADDED",
  "ESTIMATE_CREATED",
  "ESTIMATE_DRAFT_CHANGED",
  "ESTIMATE_COMPLETED",
  "ESTIMATE_AMENDED",
  "REPAIR_CREATED",
  "REPAIR_PLAN_CHANGED",
  "REPAIR_QUEUED",
  "REPAIR_STAGE_COMPLETED",
  "REPAIR_PENDING_ACCEPTANCE",
  "REPAIR_REWORK_CREATED",
  "REPAIR_ACCEPTED",
  "REPAIR_WRITTEN_OFF",
  "INVENTORY_FINDING_ADDED",
  "INVENTORY_INSPECTION_SAVED",
  "INVENTORY_PUBLICATION_READY",
  "INVENTORY_PUBLICATION_REQUESTED",
  "INVENTORY_PUBLICATION_SUCCEEDED",
  "INVENTORY_PUBLICATION_TRANSIENT_FAILED",
  "INVENTORY_PUBLICATION_BLOCKED",
  "INVENTORY_PUBLICATION_CLOSED_BLOCKED",
  "MEDIA_READY",
  "MEDIA_FAILED",
  "MEDIA_ROTATED",
  "MEDIA_DELETED",
] as const

export const DOSSIER_SOURCE_TYPES = [
  "ASSET",
  "MAINTENANCE",
  "INVENTORY",
  "MEDIA",
  "LOGISTICS",
  "TASK_BOARD",
] as const

export const DOSSIER_SOURCE_PRODUCERS = [
  "asset-service",
  "maintenance-service",
  "inventory-service",
  "media-service",
] as const

export const DOSSIER_MEDIA_STATES = [
  "PROCESSING",
  "READY",
  "FAILED",
  "DELETED",
] as const

export type DossierActivityCode = (typeof DOSSIER_ACTIVITY_CODES)[number]
export type DossierSourceType = (typeof DOSSIER_SOURCE_TYPES)[number]
export type DossierSourceProducer = (typeof DOSSIER_SOURCE_PRODUCERS)[number]
export type DossierMediaState = (typeof DOSSIER_MEDIA_STATES)[number]
export type DossierVisibility = "COMPLETE" | "PARTIAL"

export type DossierActorReference = {
  subjectId: string
  principalType: string
  profileRevision: string | null
}

export type DossierSourceReference = {
  producer: DossierSourceProducer
  aggregateType: string
  aggregateId: string
  secondaryId?: string
}

export type DossierMediaProjection = {
  mediaId: string
  folderId: string
  findingId: string
  generation: number
  state: DossierMediaState
}

export type DossierActivity = {
  activityId: string
  cabinId: string
  warehouseId: string
  activityCode: DossierActivityCode
  occurredAt: string | null
  recordedAt: string
  actorRef: DossierActorReference | null
  sourceRef: DossierSourceReference
  media: DossierMediaProjection[]
}

export type CabinDossierPage = {
  cabinId: string
  activities: DossierActivity[]
  nextCursor: string | null
  visibility: DossierVisibility
}

export type DossierActivityFilters = {
  occurredFrom?: string
  occurredBefore?: string
  activityCodes?: DossierActivityCode[]
  sourceTypes?: DossierSourceType[]
  actorSubjectId?: string
}

export type GetCabinDossierQuery = DossierActivityFilters & {
  limit?: number
  after?: string
}
