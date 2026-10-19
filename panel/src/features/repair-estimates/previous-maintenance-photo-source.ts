import {
  cabinMediaOwner,
  maintenanceEstimateMediaOwner,
  maintenanceRepairMediaOwner,
  type MediaAsset,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import type {
  MaintenanceEstimate,
  MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"

type MaintenancePhotoOwnerType = "MAINTENANCE_ESTIMATE" | "MAINTENANCE_REPAIR"

export type CurrentMaintenancePhotoOwner = Readonly<{
  ownerType: MaintenancePhotoOwnerType
  ownerId: string
}> | null

export type PreviousMaintenancePhotoSource = Readonly<{
  owner: ServiceMediaOwner
  mediaIds: readonly string[]
  capturedAt: string
  label: "Фото бытовки" | "Смета" | "Ремонт"
}>

function instant(value: string) {
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : Number.NEGATIVE_INFINITY
}

function latestEstimateTimestamp(estimate: MaintenanceEstimate) {
  return estimate.revisions.reduce(
    (latest, revision) =>
      instant(revision.recordedAt) > instant(latest)
        ? revision.recordedAt
        : latest,
    estimate.completedAt ?? estimate.createdAt
  )
}

function uniqueReferenceIds(
  references: readonly { mediaId: string; generation: number }[]
) {
  return [...new Set(references.map((reference) => reference.mediaId))]
}

function latestCabinBatch(
  assets: readonly MediaAsset[],
  notAfter: number
): { mediaIds: string[]; capturedAt: string } | null {
  const folders = new Map<string, MediaAsset[]>()
  for (const asset of assets) {
    if (
      asset.kind !== "IMAGE" ||
      asset.status !== "READY" ||
      instant(asset.createdAt) > notAfter
    ) {
      continue
    }
    const folder = folders.get(asset.folderId) ?? []
    folder.push(asset)
    folders.set(asset.folderId, folder)
  }

  return (
    [...folders.values()]
      .map((folder) => {
        const ordered = folder
          .slice()
          .sort(
            (left, right) =>
              left.sortOrder - right.sortOrder ||
              instant(left.createdAt) - instant(right.createdAt) ||
              left.id.localeCompare(right.id)
          )
        return {
          mediaIds: ordered.map((asset) => asset.id),
          capturedAt: ordered.reduce(
            (latest, asset) =>
              instant(asset.createdAt) > instant(latest)
                ? asset.createdAt
                : latest,
            ordered[0]!.createdAt
          ),
        }
      })
      .sort(
        (left, right) => instant(right.capturedAt) - instant(left.capturedAt)
      )[0] ?? null
  )
}

export function selectLatestPreviousMaintenancePhotoSource({
  warehouseId,
  rentalItemId,
  estimates,
  repairs,
  cabinAssets,
  currentOwner,
  currentCreatedAt,
}: {
  warehouseId: string
  rentalItemId: string
  estimates: readonly MaintenanceEstimate[]
  repairs: readonly MaintenanceRepair[]
  cabinAssets: readonly MediaAsset[]
  currentOwner: CurrentMaintenancePhotoOwner
  currentCreatedAt: string | null
}): PreviousMaintenancePhotoSource | null {
  const notAfter =
    currentCreatedAt === null
      ? Number.POSITIVE_INFINITY
      : instant(currentCreatedAt)
  const candidates: PreviousMaintenancePhotoSource[] = []

  for (const estimate of estimates) {
    const capturedAt = latestEstimateTimestamp(estimate)
    const mediaIds = uniqueReferenceIds(estimate.mediaReferences)
    if (
      mediaIds.length === 0 ||
      instant(capturedAt) > notAfter ||
      (currentOwner?.ownerType === "MAINTENANCE_ESTIMATE" &&
        currentOwner.ownerId === estimate.id)
    ) {
      continue
    }
    candidates.push({
      owner: maintenanceEstimateMediaOwner(estimate.id, warehouseId),
      mediaIds,
      capturedAt,
      label: "Смета",
    })
  }

  for (const repair of repairs) {
    const mediaIds = uniqueReferenceIds(repair.mediaReferences)
    if (
      mediaIds.length === 0 ||
      instant(repair.updatedAt) > notAfter ||
      (currentOwner?.ownerType === "MAINTENANCE_REPAIR" &&
        currentOwner.ownerId === repair.id)
    ) {
      continue
    }
    candidates.push({
      owner: maintenanceRepairMediaOwner(repair.id, warehouseId),
      mediaIds,
      capturedAt: repair.updatedAt,
      label: "Ремонт",
    })
  }

  const cabinBatch = latestCabinBatch(cabinAssets, notAfter)
  if (cabinBatch) {
    candidates.push({
      owner: cabinMediaOwner(rentalItemId, warehouseId),
      mediaIds: cabinBatch.mediaIds,
      capturedAt: cabinBatch.capturedAt,
      label: "Фото бытовки",
    })
  }

  return (
    candidates.sort(
      (left, right) =>
        instant(right.capturedAt) - instant(left.capturedAt) ||
        left.label.localeCompare(right.label)
    )[0] ?? null
  )
}
