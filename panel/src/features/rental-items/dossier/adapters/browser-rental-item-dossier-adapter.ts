import {
  listInventories,
  getInventory,
} from "@/features/inventory/api/inventory-api"
import type { InventoryActorSnapshot } from "@/features/inventory/model/inventory"
import {
  listReturnReceipts,
  listShipments as listLegacyShipments,
} from "@/features/logistics/api/logistics-api"
import { listShipments as listAuditedShipments } from "@/features/logistics/shipments/api"
import type { ShipmentAuditEvent } from "@/features/logistics/shipments/model"
import {
  listWarehouseTransferEventsForRentalItem,
  listWarehouseTransfers,
} from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import type { WarehouseTransferEventType } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import {
  getRepairEstimate,
  listRepairEstimates,
} from "@/features/repair-estimates/api/repair-estimates-api"
import type {
  PendingEstimateMediaUpload,
  RepairEstimateMediaRefDto,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  getRepairTask,
  listRepairTasks,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import {
  getRentalItem,
  getRentalItemPhotos,
  incrementRentalItemVersion,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
  updateRentalItemGeneralComment,
  updateRentalItemManualStatus,
} from "@/features/rental-items/api/rental-items-api"
import { CONTENTS_TRANSFER_UPDATED_EVENT } from "@/features/rental-items/contents-transfer/adapters/local-storage-contents-transfer-store"
import { listContentsTransfersForRentalItem } from "@/features/rental-items/contents-transfer/api/contents-transfer-api"
import type {
  AddCabinCommentCommand,
  AddCabinPhotoGroupCommand,
  CabinActivityDto,
  CabinActivityLinkDto,
  CabinActorSnapshot,
  CabinCommentDto,
  CabinEstimateDto,
  CabinInspectionDto,
  CabinOriginalPhotoContext,
  CabinPhotoDto,
  CabinPhotoGroupDto,
  CabinPhotoStage,
  CabinRentalMovementDto,
  CabinRepairDto,
  RentalItemDossierDto,
  RentalItemRepairAction,
  UpdateCabinGeneralCommentCommand,
  UpdateCabinStatusCommand,
} from "@/features/rental-items/dossier/model/rental-item-dossier"
import type { RentalItemDossierClient } from "@/features/rental-items/dossier/ports/rental-item-dossier-client"

const DOSSIER_STORAGE_KEY = "rwms:rental-item-dossier:v1"
const DOSSIER_UPDATED_EVENT = "rwms:rental-item-dossier-updated"

type ManualPhotoGroupRecord = {
  group: CabinPhotoGroupDto
  media: RepairEstimateMediaRefDto[]
}

type DossierEnvelope = {
  service: "rental-item-dossier"
  schemaVersion: 1
  revision: number
  activities: CabinActivityDto[]
  photoGroups: ManualPhotoGroupRecord[]
}

function emptyEnvelope(): DossierEnvelope {
  return {
    service: "rental-item-dossier",
    schemaVersion: 1,
    revision: 0,
    activities: [],
    photoGroups: [],
  }
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function readEnvelope(): DossierEnvelope {
  if (typeof window === "undefined") return emptyEnvelope()
  const raw = window.localStorage.getItem(DOSSIER_STORAGE_KEY)
  if (!raw) return emptyEnvelope()
  try {
    const parsed = JSON.parse(raw) as Partial<DossierEnvelope>
    if (
      parsed.service !== "rental-item-dossier" ||
      parsed.schemaVersion !== 1 ||
      !Array.isArray(parsed.activities) ||
      !Array.isArray(parsed.photoGroups)
    ) {
      return emptyEnvelope()
    }
    return {
      service: "rental-item-dossier",
      schemaVersion: 1,
      revision:
        typeof parsed.revision === "number" ? Math.max(0, parsed.revision) : 0,
      activities: parsed.activities,
      photoGroups: parsed.photoGroups,
    }
  } catch {
    return emptyEnvelope()
  }
}

function writeEnvelope(envelope: DossierEnvelope) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(DOSSIER_STORAGE_KEY, JSON.stringify(envelope))
  window.dispatchEvent(new Event(DOSSIER_UPDATED_EVENT))
}

function opaqueId(prefix: string) {
  const suffix =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${suffix}`
}

function actor(
  displayName: string,
  id: string | null = null
): CabinActorSnapshot {
  return { id, displayName: displayName.trim() || "Не указан" }
}

function optionalActor(displayName: string | null | undefined) {
  return displayName?.trim() ? actor(displayName) : null
}

function mediaPhoto(
  media: RepairEstimateMediaRefDto,
  params: {
    sourceType: CabinPhotoDto["provenance"]["sourceType"]
    sourceId: string
    sourceLabel: string
    stage: CabinPhotoStage
    order: number
    occurredAt: string
  }
): CabinPhotoDto {
  return {
    id: media.id,
    occurredAt: media.createdAt || params.occurredAt,
    order: params.order,
    stage: params.stage,
    processingStatus: "READY",
    variants: {
      thumb: {
        url: media.variants.small.url,
        width: media.variants.small.width,
        height: media.variants.small.height,
        mimeType: media.variants.small.mimeType,
      },
      preview: {
        url: media.variants.largeWebp.url,
        width: media.variants.largeWebp.width,
        height: media.variants.largeWebp.height,
        mimeType: media.variants.largeWebp.mimeType,
      },
    },
    originalAvailable:
      media.originalAvailable === true && Boolean(media.variants.original),
    provenance: {
      sourceType: params.sourceType,
      sourceId: params.sourceId,
      sourceLabel: params.sourceLabel,
    },
  }
}

function mediaGroup(params: {
  id: string
  rentalItemId: string
  activityId: string | null
  occurredAt: string
  actor: CabinActorSnapshot | null
  sourceType: CabinPhotoGroupDto["sourceType"]
  sourceId: string
  sourceLabel: string
  stage: CabinPhotoStage
  media: RepairEstimateMediaRefDto[]
}): CabinPhotoGroupDto | null {
  if (params.media.length === 0) return null
  return {
    id: params.id,
    rentalItemId: params.rentalItemId,
    activityId: params.activityId,
    occurredAt: params.occurredAt,
    actor: params.actor,
    sourceType: params.sourceType,
    sourceId: params.sourceId,
    sourceLabel: params.sourceLabel,
    stage: params.stage,
    photos: params.media.map((media, order) =>
      mediaPhoto(media, { ...params, order })
    ),
  }
}

function link(
  kind: CabinActivityLinkDto["kind"],
  label: string,
  href: string
): CabinActivityLinkDto {
  return { kind, label, href }
}

function sortNewest<T extends { occurredAt: string | null }>(values: T[]) {
  return values.sort(
    (left, right) =>
      (Date.parse(right.occurredAt ?? "") || 0) -
        (Date.parse(left.occurredAt ?? "") || 0) ||
      (right.occurredAt ?? "").localeCompare(left.occurredAt ?? "")
  )
}

const shipmentAuditActivityTypes: Record<
  ShipmentAuditEvent["type"],
  CabinActivityDto["type"]
> = {
  DRAFT_SAVED: "SHIPMENT_UPDATED",
  STOCK_RESERVED: "SHIPMENT_CREATED",
  TASK_DISPATCHED: "SHIPMENT_TASK_DISPATCHED",
  PREPARATION_CONFIRMED: "SHIPMENT_PREPARED",
  PREPARATION_CONFLICT: "SHIPMENT_PREPARATION_CONFLICT",
  SHIPMENT_FINALIZED: "SHIPPED",
  SHIPMENT_CANCELLED: "SHIPMENT_CANCELLED",
}

const warehouseTransferActivityTypes: Record<
  WarehouseTransferEventType,
  CabinActivityDto["type"]
> = {
  TRANSFER_CREATED: "WAREHOUSE_TRANSFER_CREATED",
  SOURCE_TASK_REGISTERED: "WAREHOUSE_TRANSFER_TASK_REGISTERED",
  DEPARTURE_CONFIRMED: "WAREHOUSE_TRANSFER_DEPARTED",
  DESTINATION_TASK_REGISTERED: "WAREHOUSE_TRANSFER_TASK_REGISTERED",
  ARRIVAL_CONFLICT: "WAREHOUSE_TRANSFER_ARRIVAL_CONFLICT",
  ARRIVAL_CONFIRMED: "WAREHOUSE_TRANSFER_RECEIVED",
  TRANSFER_CANCELLED: "WAREHOUSE_TRANSFER_CANCELLED",
  ACCOUNTING_CORRECTION_CREATED: "WAREHOUSE_CORRECTION_CREATED",
  ACCOUNTING_CORRECTION_APPROVED: "WAREHOUSE_CORRECTION_APPROVED",
  ACCOUNTING_CORRECTION_APPLIED: "WAREHOUSE_CORRECTION_APPLIED",
  ACCOUNTING_CORRECTION_REJECTED: "WAREHOUSE_CORRECTION_REJECTED",
}

function uniqueActivities(activities: CabinActivityDto[]) {
  return Array.from(
    activities
      .reduce((byId, activity) => {
        if (!byId.has(activity.id)) byId.set(activity.id, activity)
        return byId
      }, new Map<string, CabinActivityDto>())
      .values()
  )
}

function repairAction(
  status: RentalItemDossierDto["rentalItem"]["status"],
  repairs: RepairTaskDto[]
): RentalItemRepairAction {
  const hasActiveRepair = repairs.some(
    (item) => item.status !== "COMPLETED" && item.status !== "CANCELLED"
  )
  if (hasActiveRepair) {
    return {
      type: "NONE",
      disabledReason: "У бытовки уже есть активный ремонт",
    }
  }
  if (status === "AFTER_RENT") {
    return { type: "CREATE_ESTIMATE", disabledReason: null }
  }
  if (status === "RENTED") {
    return { type: "NONE", disabledReason: "Бытовка находится в аренде" }
  }
  if (status === "WRITTEN_OFF") {
    return { type: "NONE", disabledReason: "Бытовка списана" }
  }
  if (status === "WAITING_ESTIMATE_CONFIRMATION") {
    return {
      type: "NONE",
      disabledReason: "Смета ожидает подтверждения",
    }
  }
  if (
    status === "REPAIR" ||
    status === "WAITING_REPAIR_CHECK" ||
    status === "CAPITAL_REPAIR"
  ) {
    return { type: "NONE", disabledReason: "Ремонтный процесс уже начат" }
  }
  return { type: "CREATE_REPAIR", disabledReason: null }
}

export class BrowserRentalItemDossierAdapter implements RentalItemDossierClient {
  private readonly media = new IndexedDbRepairEstimateMediaAdapter()

  async get(warehouseId: string, rentalItemId: string) {
    const rentalItem = await getRentalItem(rentalItemId)
    if (!rentalItem || rentalItem.warehouseId !== warehouseId) return null

    const inventoryReader: InventoryActorSnapshot = {
      id: "dossier-reader",
      displayName: "Досье бытовки",
      permissions: ["VIEW"],
      authorizedWarehouseIds: [warehouseId],
    }
    const [
      inventorySummaries,
      draftEstimateSummaries,
      completedEstimateSummaries,
      taskSummaries,
      returnReceipts,
      legacyShipments,
      auditedShipments,
      warehouseTransfers,
      warehouseTransferEvents,
      rentalPhotos,
    ] = await Promise.all([
      listInventories(warehouseId, inventoryReader),
      listRepairEstimates(warehouseId, "DRAFT"),
      listRepairEstimates(warehouseId, "COMPLETED"),
      listRepairTasks(warehouseId),
      listReturnReceipts(warehouseId),
      listLegacyShipments(warehouseId),
      listAuditedShipments(warehouseId),
      listWarehouseTransfers(),
      listWarehouseTransferEventsForRentalItem(rentalItemId),
      getRentalItemPhotos(rentalItemId),
    ])
    const [inventories, estimates, tasks] = await Promise.all([
      Promise.all(
        inventorySummaries.map((item) => getInventory(item.id, inventoryReader))
      ).then((items) =>
        items.filter((item): item is NonNullable<typeof item> => item !== null)
      ),
      Promise.all(
        [...draftEstimateSummaries, ...completedEstimateSummaries].map((item) =>
          getRepairEstimate(item.id, warehouseId)
        )
      ).then((items) =>
        items.filter(
          (item): item is NonNullable<typeof item> =>
            item !== null && item.rentalItemId === rentalItemId
        )
      ),
      Promise.all(
        taskSummaries
          .filter((item) => item.rentalItemId === rentalItemId)
          .map((item) => getRepairTask(item.id, warehouseId))
      ).then((items) =>
        items.filter((item): item is NonNullable<typeof item> => item !== null)
      ),
    ])

    const activities: CabinActivityDto[] = []
    const photoGroups: CabinPhotoGroupDto[] = []
    const inspections: CabinInspectionDto[] = []
    const estimateViews: CabinEstimateDto[] = []
    const repairViews: CabinRepairDto[] = []
    const movementViews: CabinRentalMovementDto[] = []
    const comments: CabinCommentDto[] = []

    if (rentalPhotos.length > 0) {
      const knownCaptureTimes = rentalPhotos
        .filter((photo) => photo.capturedAtKnown === true)
        .map((photo) => photo.createdAt)
        .sort()
      const occurredAt = knownCaptureTimes[0] ?? null
      photoGroups.push({
        id: `rental-item:${rentalItemId}:photos`,
        rentalItemId,
        activityId: null,
        occurredAt,
        actor: null,
        sourceType: "RENTAL_ITEM",
        sourceId: rentalItemId,
        sourceLabel: "Общие фотографии",
        stage: "GENERAL",
        photos: rentalPhotos.map((photo, order) => ({
          id: photo.id,
          occurredAt: photo.capturedAtKnown === true ? photo.createdAt : null,
          order,
          stage: "GENERAL",
          processingStatus: "READY",
          variants: {
            thumb: {
              url: photo.variants?.small?.url ?? photo.url,
              width: photo.variants?.small?.width ?? null,
              height: photo.variants?.small?.height ?? null,
              mimeType: photo.variants?.small?.mimeType ?? null,
            },
            preview: {
              url: photo.variants?.largeWebp?.url ?? photo.url,
              width: photo.variants?.largeWebp?.width ?? null,
              height: photo.variants?.largeWebp?.height ?? null,
              mimeType: photo.variants?.largeWebp?.mimeType ?? null,
            },
          },
          originalAvailable: false,
          provenance: {
            sourceType: "RENTAL_ITEM",
            sourceId: rentalItemId,
            sourceLabel: "Общие фотографии",
          },
        })),
      })
    }

    inventories.forEach((inventory) => {
      inventory.findings
        .filter(
          (finding) =>
            finding.rentalItemId === rentalItemId && finding.inspectedAt
        )
        .forEach((finding) => {
          const activityId = `inventory:${inventory.id}:${finding.id}`
          const photoGroupId =
            finding.media.length > 0 ? `${activityId}:photos` : null
          const inventoryLink = link(
            "INVENTORY",
            "Открыть осмотр",
            `/inventory/history/${encodeURIComponent(inventory.id)}`
          )
          const links = [inventoryLink]
          if (finding.publishedRepairTaskId) {
            links.push(
              link(
                "REPAIR",
                "Открыть ремонт",
                `/repairs?repairId=${encodeURIComponent(finding.publishedRepairTaskId)}`
              )
            )
          }
          const findingActor = finding.inspectedBy
            ? actor(finding.inspectedBy.displayName, finding.inspectedBy.id)
            : null
          activities.push({
            id: activityId,
            rentalItemId,
            occurredAt: finding.inspectedAt!,
            actor: findingActor,
            type: "INSPECTION_COMPLETED",
            sourceType: "INVENTORY",
            sourceId: inventory.id,
            sourceLabel: "Инвентаризационный осмотр",
            comment: finding.comment.trim() || null,
            statusTransition: null,
            parentActivityId: null,
            photoGroupId,
            links,
          })
          inspections.push({
            id: activityId,
            sourceType: "INVENTORY",
            inventoryId: inventory.id,
            returnReceiptId: null,
            findingId: finding.id,
            occurredAt: finding.inspectedAt!,
            actor: findingActor,
            status: finding.inspectionStatus,
            comment: finding.comment.trim() || null,
            photoGroupId,
            publishedRepairTaskId: finding.publishedRepairTaskId,
            links,
          })
          const group = mediaGroup({
            id: photoGroupId ?? `${activityId}:photos`,
            rentalItemId,
            activityId,
            occurredAt: finding.inspectedAt!,
            actor: findingActor,
            sourceType: "INVENTORY",
            sourceId: inventory.id,
            sourceLabel: "Осмотр",
            stage: "ACCEPTANCE",
            media: finding.media,
          })
          if (group) photoGroups.push(group)
          if (finding.comment.trim()) {
            comments.push({
              id: `${activityId}:comment`,
              occurredAt: finding.inspectedAt!,
              actor: findingActor,
              text: finding.comment.trim(),
              sourceType: "INVENTORY",
              sourceId: inventory.id,
              sourceLabel: "Осмотр",
              link: inventoryLink,
              editable: false,
            })
          }
        })
    })

    estimates.forEach((estimate) => {
      const activityId = `estimate:${estimate.id}:created`
      const photoGroupId =
        estimate.media.length > 0 ? `estimate:${estimate.id}:photos` : null
      const estimateLink = link(
        "ESTIMATE",
        "Открыть смету",
        `/estimates?estimateId=${encodeURIComponent(estimate.id)}`
      )
      const estimateActor = optionalActor(estimate.authorName)
      activities.push({
        id: activityId,
        rentalItemId,
        occurredAt: estimate.createdAt,
        actor: estimateActor,
        type: "ESTIMATE_CREATED",
        sourceType: "ESTIMATE",
        sourceId: estimate.id,
        sourceLabel: "Смета",
        comment: estimate.comment.trim() || null,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId,
        links: [estimateLink],
      })
      if (estimate.status === "COMPLETED") {
        activities.push({
          id: `estimate:${estimate.id}:completed`,
          rentalItemId,
          occurredAt: estimate.updatedAt,
          actor: estimateActor,
          type: "ESTIMATE_COMPLETED",
          sourceType: "ESTIMATE",
          sourceId: estimate.id,
          sourceLabel: "Смета завершена",
          comment: null,
          statusTransition: null,
          parentActivityId: activityId,
          photoGroupId: null,
          links: [estimateLink],
        })
      }
      estimateViews.push({
        id: estimate.id,
        version: estimate.version,
        status: estimate.status,
        occurredAt: estimate.createdAt,
        updatedAt: estimate.updatedAt,
        actor: estimateActor,
        comment: estimate.comment.trim() || null,
        totalAmount: estimate.totalAmount,
        photoGroupId,
        link: estimateLink,
      })
      const group = mediaGroup({
        id: photoGroupId ?? `estimate:${estimate.id}:photos`,
        rentalItemId,
        activityId,
        occurredAt: estimate.createdAt,
        actor: estimateActor,
        sourceType: "ESTIMATE",
        sourceId: estimate.id,
        sourceLabel: "Смета",
        stage: "BEFORE",
        media: estimate.media,
      })
      if (group) photoGroups.push(group)
      if (estimate.comment.trim()) {
        comments.push({
          id: `${activityId}:comment`,
          occurredAt: estimate.createdAt,
          actor: estimateActor,
          text: estimate.comment.trim(),
          sourceType: "ESTIMATE",
          sourceId: estimate.id,
          sourceLabel: "Смета",
          link: estimateLink,
          editable: false,
        })
      }
    })

    tasks.forEach((task) => {
      this.appendRepair(
        task,
        rentalItemId,
        activities,
        photoGroups,
        repairViews,
        comments
      )
    })

    const auditedShipmentIds = new Set(
      auditedShipments
        .filter((shipment) => shipment.audit.length > 0)
        .map((shipment) => shipment.id)
    )
    legacyShipments.forEach((shipment) => {
      if (shipment.status !== "SHIPPED") return
      if (auditedShipmentIds.has(shipment.id)) return
      const item = shipment.items.find(
        (entry) => entry.rentalItemId === rentalItemId
      )
      if (!item) return
      const shipmentLink = link(
        "SHIPMENT",
        "Открыть отгрузку",
        `/logistics/shipments?shipmentId=${encodeURIComponent(shipment.id)}`
      )
      movementViews.push({
        id: shipment.id,
        direction: "OUTBOUND",
        occurredAt: shipment.shipmentDate,
        party: shipment.company,
        driverName: shipment.driverName,
        actor: optionalActor(shipment.createdBy),
        contents: item.contentsPlanned,
        photoGroupId: null,
        link: shipmentLink,
      })
      activities.push({
        id: `shipment:${shipment.id}:${rentalItemId}`,
        rentalItemId,
        occurredAt: shipment.shipmentDate,
        actor: optionalActor(shipment.createdBy),
        type: "SHIPPED",
        sourceType: "SHIPMENT",
        sourceId: shipment.id,
        sourceLabel: `Отгрузка: ${shipment.company}`,
        comment: null,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId: null,
        links: [shipmentLink],
      })
    })

    auditedShipments.forEach((shipment) => {
      const item = shipment.items.find(
        (entry) => entry.rentalItemId === rentalItemId
      )
      if (!item || shipment.audit.length === 0) return
      const shipmentLink = link(
        "SHIPMENT",
        "Открыть отгрузку",
        `/logistics/shipments?shipmentId=${encodeURIComponent(shipment.id)}`
      )
      const createdAudit = shipment.audit.find(
        (event) => event.type === "STOCK_RESERVED"
      )
      const createdActivityId = createdAudit
        ? `shipment:${shipment.id}:${rentalItemId}:audit:${createdAudit.id}`
        : null
      shipment.audit.forEach((event) => {
        const isFinalized = event.type === "SHIPMENT_FINALIZED"
        const activityId = `shipment:${shipment.id}:${rentalItemId}:audit:${event.id}`
        activities.push({
          id: activityId,
          rentalItemId,
          occurredAt: event.occurredAt,
          actor: optionalActor(event.actor),
          type: shipmentAuditActivityTypes[event.type],
          sourceType: "SHIPMENT",
          sourceId: shipment.id,
          sourceLabel:
            event.type === "STOCK_RESERVED"
              ? `Отгрузка создана: ${shipment.company}`
              : event.type === "SHIPMENT_FINALIZED"
                ? `Отгрузка: ${shipment.company}`
                : "Подготовка к отгрузке",
          comment: event.details.trim() || null,
          statusTransition: null,
          parentActivityId:
            activityId === createdActivityId ? null : createdActivityId,
          photoGroupId: null,
          links: [shipmentLink],
        })
        if (!isFinalized) return
        movementViews.push({
          id: `shipment:${shipment.id}:${rentalItemId}`,
          direction: "OUTBOUND",
          occurredAt: event.occurredAt,
          party: shipment.company,
          driverName: shipment.driverName,
          actor: optionalActor(event.actor),
          contents: item.contentsPlanned,
          photoGroupId: null,
          link: shipmentLink,
        })
      })
    })

    returnReceipts.forEach((receipt) => {
      const item = receipt.items.find(
        (entry) => entry.rentalItemId === rentalItemId
      )
      if (!item) return
      const activityId = `return:${receipt.id}:${item.id}`
      const returnLink = link(
        "RETURN_RECEIPT",
        "Открыть приёмку",
        `/logistics/returns?receiptId=${encodeURIComponent(receipt.id)}&returnItemId=${encodeURIComponent(item.id)}`
      )
      item.intakeHistory.forEach((event) => {
        const auditActivityId = `${activityId}:audit:${event.id}`
        const type: CabinActivityDto["type"] =
          event.type === "INTAKE_REGISTERED"
            ? "RETURN_INTAKE_REGISTERED"
            : event.type === "CONFLICT_REGISTERED"
              ? "RETURN_CONFLICT_REGISTERED"
              : event.type === "PASSPORT_CHANGED"
                ? "RETURN_PASSPORT_CHANGED"
                : "RETURN_EXPECTED_CONTENTS_CORRECTED"
        activities.push({
          id: auditActivityId,
          rentalItemId,
          occurredAt: event.createdAt,
          actor: optionalActor(event.createdBy),
          type,
          sourceType: "RETURN_RECEIPT",
          sourceId: receipt.id,
          sourceLabel:
            event.type === "CONFLICT_REGISTERED"
              ? `Конфликт возврата: ${item.cabinNumber}`
              : `Приёмка из аренды: ${item.cabinNumber}`,
          comment: event.reason?.trim() || null,
          statusTransition: null,
          parentActivityId: null,
          photoGroupId: null,
          links: [returnLink],
        })
      })
      item.conflictResolutions.forEach((resolution) => {
        const resolutionLink = link(
          "WAREHOUSE_TRANSFER",
          resolution.kind === "WAREHOUSE_TRANSFER"
            ? "Открыть перемещение"
            : "Открыть коррекцию",
          resolution.kind === "WAREHOUSE_TRANSFER"
            ? `/logistics/transfers?transferId=${encodeURIComponent(resolution.id)}`
            : `/logistics/transfers?correctionId=${encodeURIComponent(resolution.id)}`
        )
        activities.push({
          id: `${activityId}:resolution:${resolution.kind}:${resolution.id}`,
          rentalItemId,
          occurredAt: resolution.resolvedAt,
          actor: optionalActor(resolution.resolvedBy),
          type: "RETURN_CONFLICT_RESOLVED",
          sourceType: "RETURN_RECEIPT",
          sourceId: receipt.id,
          sourceLabel: "Конфликт возврата разрешён",
          comment: null,
          statusTransition: null,
          parentActivityId: null,
          photoGroupId: null,
          links: [returnLink, resolutionLink],
        })
      })
      item.furnitureDispositions.forEach((disposition) => {
        disposition.allocations
          .filter((allocation) => allocation.status === "APPLIED")
          .forEach((allocation) => {
          const actionLabel = {
            KEEP_IN_CABIN: "Оставлено в бытовке",
            RETURN_TO_STOCK: "Возвращено на склад",
            WRITE_OFF: "Списано",
            TRANSFER_TO_CABIN: "Передано в другую бытовку",
          }[allocation.action]
          activities.push({
            id: `${activityId}:furniture-allocation:${allocation.id}`,
            rentalItemId,
            occurredAt: allocation.createdAt,
            actor: optionalActor(allocation.createdBy),
            type: "RETURN_FURNITURE_DISPOSITION_RECORDED",
            sourceType: "RETURN_RECEIPT",
            sourceId: receipt.id,
            sourceLabel: `${actionLabel}: ${disposition.name}`,
            comment:
              [
                `${disposition.name} — ${allocation.quantity} шт.`,
                allocation.reason,
              ]
                .filter(Boolean)
                .join(" · ") || null,
            statusTransition: null,
            parentActivityId: null,
            photoGroupId: null,
            links: [returnLink],
          })
          })
      })
      if (!item.acceptedAt || item.technicalState === "PENDING_INSPECTION")
        return
      const photoGroupId = item.media.length > 0 ? `${activityId}:photos` : null
      const occurredAt = item.acceptedAt
      const inspectionLinks: CabinActivityLinkDto[] = [returnLink]
      const estimateId = item.sourceEstimateId ?? item.pendingEstimateId
      if (estimateId) {
        inspectionLinks.push(
          link(
            "ESTIMATE",
            "Открыть смету",
            `/estimates?estimateId=${encodeURIComponent(estimateId)}`
          )
        )
      }
      const relatedRepair = tasks.find(
        (task) => estimateId !== null && task.sourceEstimateId === estimateId
      )
      if (relatedRepair) {
        inspectionLinks.push(
          link(
            "REPAIR",
            "Открыть ремонт",
            `/repairs?repairId=${encodeURIComponent(relatedRepair.id)}`
          )
        )
      }
      movementViews.push({
        id: activityId,
        direction: "INBOUND",
        occurredAt,
        party: receipt.fromParty,
        driverName: receipt.driverName,
        actor: optionalActor(receipt.updatedBy ?? receipt.createdBy),
        contents: item.returnedContents,
        photoGroupId,
        link: returnLink,
      })
      activities.push({
        id: activityId,
        rentalItemId,
        occurredAt,
        actor: optionalActor(receipt.updatedBy ?? receipt.createdBy),
        type: "RETURNED",
        sourceType: "RETURN_RECEIPT",
        sourceId: receipt.id,
        sourceLabel: `Возврат от ${receipt.fromParty}`,
        comment: null,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId,
        links: inspectionLinks,
      })
      inspections.push({
        id: activityId,
        sourceType: "RETURN_RECEIPT",
        inventoryId: null,
        returnReceiptId: receipt.id,
        findingId: item.id,
        occurredAt,
        actor: optionalActor(receipt.updatedBy ?? receipt.createdBy),
        status: "READY",
        comment: null,
        photoGroupId,
        publishedRepairTaskId: relatedRepair?.id ?? null,
        links: inspectionLinks,
      })
      const group = mediaGroup({
        id: photoGroupId ?? `${activityId}:photos`,
        rentalItemId,
        activityId,
        occurredAt,
        actor: optionalActor(receipt.updatedBy ?? receipt.createdBy),
        sourceType: "RETURN_RECEIPT",
        sourceId: receipt.id,
        sourceLabel: "Приёмка из аренды",
        stage: "ACCEPTANCE",
        media: item.media,
      })
      if (group) photoGroups.push(group)
    })

    const transferDocumentsById = new Map(
      warehouseTransfers.map((document) => [document.id, document])
    )
    warehouseTransfers.forEach((document) => {
      const line = document.lines.find(
        (candidate) => candidate.rentalItemId === rentalItemId
      )
      if (!line) return
      const transferLink = link(
        "WAREHOUSE_TRANSFER",
        "Открыть перемещение",
        `/logistics/transfers?transferId=${encodeURIComponent(document.id)}&lineId=${encodeURIComponent(line.id)}`
      )
      activities.push({
        id: `warehouse-transfer:${document.id}:${line.id}:created`,
        rentalItemId,
        occurredAt: document.createdAt,
        actor: document.actor,
        type: "WAREHOUSE_TRANSFER_CREATED",
        sourceType: "WAREHOUSE_TRANSFER",
        sourceId: document.id,
        sourceLabel: `Перемещение ${document.sourceWarehouse.name} → ${document.destinationWarehouse.name}`,
        comment: document.comment?.trim() || null,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId: null,
        links: [transferLink],
      })
    })
    warehouseTransferEvents.forEach((event) => {
      if (event.type === "TRANSFER_CREATED") return
      const isCorrection = event.type.startsWith("ACCOUNTING_CORRECTION_")
      const document = transferDocumentsById.get(event.documentId)
      const line = document?.lines.find(
        (candidate) => candidate.id === event.lineId
      )
      const transferLink = link(
        "WAREHOUSE_TRANSFER",
        isCorrection ? "Открыть коррекцию" : "Открыть перемещение",
        isCorrection
          ? `/logistics/transfers?correctionId=${encodeURIComponent(event.documentId)}`
          : `/logistics/transfers?transferId=${encodeURIComponent(event.documentId)}${event.lineId ? `&lineId=${encodeURIComponent(event.lineId)}` : ""}`
      )
      const sourceLabel = {
        SOURCE_TASK_REGISTERED: "Задание отправления создано",
        DEPARTURE_CONFIRMED: "Убытие со склада подтверждено",
        DESTINATION_TASK_REGISTERED: "Задание приёмки создано",
        ARRIVAL_CONFLICT: "Обнаружен конфликт приёмки",
        ARRIVAL_CONFIRMED: "Приёмка на складе подтверждена",
        TRANSFER_CANCELLED: "Перемещение отменено",
        ACCOUNTING_CORRECTION_CREATED: "Коррекция учёта создана",
        ACCOUNTING_CORRECTION_APPROVED: "Коррекция учёта подтверждена",
        ACCOUNTING_CORRECTION_APPLIED: "Коррекция учёта применена",
        ACCOUNTING_CORRECTION_REJECTED: "Коррекция учёта отклонена",
      }[event.type]
      activities.push({
        id: `warehouse-transfer:${event.id}:${rentalItemId}`,
        rentalItemId,
        occurredAt: event.occurredAt,
        actor: event.actor,
        type: warehouseTransferActivityTypes[event.type],
        sourceType: isCorrection
          ? "ACCOUNTING_CORRECTION"
          : "WAREHOUSE_TRANSFER",
        sourceId: event.documentId,
        sourceLabel,
        comment: event.comment?.trim() || null,
        statusTransition:
          event.type === "DEPARTURE_CONFIRMED" && line
            ? {
                from: line.sourceStatus,
                to: "IN_TRANSFER",
                reason: "Подтверждено отправление между складами",
              }
            : event.type === "ARRIVAL_CONFIRMED" && line
              ? {
                  from: "IN_TRANSFER",
                  to: line.sourceStatus,
                  reason: "Подтверждена приёмка на складе назначения",
                }
              : null,
        parentActivityId:
          !isCorrection && line
            ? `warehouse-transfer:${event.documentId}:${line.id}:created`
            : null,
        photoGroupId: null,
        links: [transferLink],
      })
    })

    listContentsTransfersForRentalItem(rentalItemId).forEach((transfer) => {
      const isSource = transfer.source.rentalItemId === rentalItemId
      const otherCabin = isSource ? transfer.target : transfer.source
      const items = transfer.items
        .map((item) => `${item.name} — ${item.quantity} шт.`)
        .join(", ")
      activities.push({
        id: `${transfer.id}:${isSource ? "source" : "target"}`,
        rentalItemId,
        occurredAt: transfer.occurredAt,
        actor: transfer.actor,
        type: "CONTENTS_TRANSFERRED",
        sourceType: "CONTENTS_TRANSFER",
        sourceId: transfer.id,
        sourceLabel: isSource
          ? `Передано в ${otherCabin.number}`
          : `Получено из ${otherCabin.number}`,
        comment: items,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId: null,
        links: [],
      })
    })

    const manualEnvelope = readEnvelope()
    activities.push(
      ...manualEnvelope.activities.filter(
        (item) => item.rentalItemId === rentalItemId
      )
    )
    const manualGroups = manualEnvelope.photoGroups.filter(
      (item) => item.group.rentalItemId === rentalItemId
    )
    const hydratedManualGroups = await Promise.all(
      manualGroups.map(async (record) => {
        const media = await this.media.hydrate(record.media)
        return {
          ...record.group,
          photos: media.map((item, order) =>
            mediaPhoto(item, {
              sourceType: "MANUAL",
              sourceId: record.group.sourceId,
              sourceLabel: record.group.sourceLabel,
              stage: "GENERAL",
              order,
              occurredAt: record.group.occurredAt ?? item.createdAt,
            })
          ),
        }
      })
    )
    photoGroups.push(...hydratedManualGroups)
    activities
      .filter(
        (item) =>
          (item.type === "COMMENT_ADDED" ||
            item.type === "GENERAL_COMMENT_UPDATED") &&
          item.comment?.trim()
      )
      .forEach((item) => {
        const commentId = `${item.id}:comment`
        if (comments.some((comment) => comment.id === commentId)) return
        comments.push({
          id: commentId,
          occurredAt: item.occurredAt,
          actor: item.actor,
          text: item.comment!,
          sourceType: item.sourceType,
          sourceId: item.sourceId,
          sourceLabel: item.sourceLabel,
          link: item.links[0] ?? null,
          editable: false,
        })
      })

    const activeRepairs = tasks.filter(
      (item) => item.status !== "COMPLETED" && item.status !== "CANCELLED"
    )
    const sortedActivities = sortNewest(uniqueActivities(activities))
    return clone({
      rentalItem,
      overview: {
        lastInspectionAt:
          sortNewest(inspections.slice())[0]?.occurredAt ?? null,
        activeRepairCount: activeRepairs.length,
        latestEstimateId: sortNewest(estimateViews.slice())[0]?.id ?? null,
        latestRepairId: sortNewest(repairViews.slice())[0]?.id ?? null,
      },
      repairAction: repairAction(rentalItem.status, tasks),
      activities: sortedActivities,
      photoGroups: sortNewest(photoGroups),
      inspections: sortNewest(inspections),
      estimates: sortNewest(estimateViews),
      repairs: sortNewest(repairViews),
      shipments: sortNewest(movementViews),
      comments: sortNewest(comments),
      reservations: [],
      returns: [],
    } satisfies RentalItemDossierDto)
  }

  private appendRepair(
    task: RepairTaskDto,
    rentalItemId: string,
    activities: CabinActivityDto[],
    photoGroups: CabinPhotoGroupDto[],
    repairViews: CabinRepairDto[],
    comments: CabinCommentDto[]
  ) {
    const activityId = `repair:${task.id}:created`
    const repairLink = link(
      "REPAIR",
      "Открыть ремонт",
      `/repairs?repairId=${encodeURIComponent(task.id)}`
    )
    const taskActor = optionalActor(task.authorName)
    const beforePhotoGroupId =
      task.media.length > 0 ? `repair:${task.id}:before` : null
    activities.push({
      id: activityId,
      rentalItemId,
      occurredAt: task.createdAt,
      actor: taskActor,
      type: "REPAIR_CREATED",
      sourceType: "REPAIR",
      sourceId: task.id,
      sourceLabel: "Ремонт",
      comment: task.comment.trim() || null,
      statusTransition: null,
      parentActivityId: null,
      photoGroupId: beforePhotoGroupId,
      links: [repairLink],
    })
    if (task.completedAt) {
      activities.push({
        id: `repair:${task.id}:completed`,
        rentalItemId,
        occurredAt: task.completedAt,
        actor: taskActor,
        type: "REPAIR_COMPLETED",
        sourceType: "REPAIR",
        sourceId: task.id,
        sourceLabel: "Ремонт завершён",
        comment: null,
        statusTransition: null,
        parentActivityId: activityId,
        photoGroupId: null,
        links: [repairLink],
      })
    }
    if (task.acceptanceDecidedAt && task.acceptanceStatus === "ACCEPTED") {
      activities.push({
        id: `repair:${task.id}:accepted`,
        rentalItemId,
        occurredAt: task.acceptanceDecidedAt,
        actor: optionalActor(task.acceptanceDecidedBy),
        type: "REPAIR_ACCEPTED",
        sourceType: "REPAIR",
        sourceId: task.id,
        sourceLabel: "Ремонт принят",
        comment: task.acceptanceComment?.trim() || null,
        statusTransition: null,
        parentActivityId: activityId,
        photoGroupId: null,
        links: [repairLink],
      })
    }
    if (task.acceptanceDecidedAt && task.acceptanceStatus === "WRITTEN_OFF") {
      activities.push({
        id: `repair:${task.id}:written-off`,
        rentalItemId,
        occurredAt: task.acceptanceDecidedAt,
        actor: optionalActor(task.acceptanceDecidedBy),
        type: "REPAIR_WRITTEN_OFF",
        sourceType: "REPAIR",
        sourceId: task.id,
        sourceLabel: "Бытовка списана",
        comment: task.acceptanceComment?.trim() || null,
        statusTransition: null,
        parentActivityId: activityId,
        photoGroupId: null,
        links: [repairLink],
      })
    }
    const beforeGroup = mediaGroup({
      id: beforePhotoGroupId ?? `repair:${task.id}:before`,
      rentalItemId,
      activityId,
      occurredAt: task.createdAt,
      actor: taskActor,
      sourceType: "REPAIR",
      sourceId: task.id,
      sourceLabel: "До ремонта",
      stage: "BEFORE",
      media: task.media,
    })
    if (beforeGroup) photoGroups.push(beforeGroup)
    const resultPhotoGroupIds: string[] = []
    task.subtasks.forEach((subtask, index) => {
      const groupId = `repair:${task.id}:stage:${subtask.id}`
      const stageActivityId = `repair:${task.id}:stage:${subtask.id}:completed`
      const stageIsProven = Boolean(
        subtask.completedAt || subtask.resultMedia.length > 0
      )
      const group = mediaGroup({
        id: groupId,
        rentalItemId,
        activityId: stageIsProven ? stageActivityId : null,
        occurredAt: subtask.completedAt ?? task.updatedAt,
        actor: this.subtaskActor(subtask),
        sourceType: "REPAIR",
        sourceId: task.id,
        sourceLabel: `Этап ${index + 1}`,
        stage: subtask.kind === "MOVE_FROM_REPAIR" ? "AFTER" : "WORK",
        media: subtask.resultMedia,
      })
      if (group) {
        resultPhotoGroupIds.push(groupId)
        photoGroups.push(group)
      }
      if (stageIsProven) {
        activities.push({
          id: stageActivityId,
          rentalItemId,
          occurredAt: subtask.completedAt ?? subtask.resultMedia[0]!.createdAt,
          actor: this.subtaskActor(subtask),
          type: "REPAIR_STAGE_COMPLETED",
          sourceType: "REPAIR",
          sourceId: task.id,
          sourceLabel: `Этап ${index + 1}`,
          comment: subtask.groupComment.trim() || null,
          statusTransition: null,
          parentActivityId: task.completedAt
            ? `repair:${task.id}:completed`
            : activityId,
          photoGroupId: group ? groupId : null,
          links: [repairLink],
        })
      }
    })
    repairViews.push({
      id: task.id,
      version: task.version,
      status: task.status,
      acceptanceStatus: task.acceptanceStatus,
      occurredAt: task.createdAt,
      completedAt: task.completedAt,
      actor: taskActor,
      reason: task.reason,
      comment: task.comment.trim() || null,
      beforePhotoGroupId,
      resultPhotoGroupIds,
      link: repairLink,
    })
    if (task.comment.trim()) {
      comments.push({
        id: `${activityId}:comment`,
        occurredAt: task.createdAt,
        actor: taskActor,
        text: task.comment.trim(),
        sourceType: "REPAIR",
        sourceId: task.id,
        sourceLabel: "Ремонт",
        link: repairLink,
        editable: false,
      })
    }
  }

  private subtaskActor(subtask: RepairTaskSubtaskDto) {
    const names = subtask.assignments
      .map((assignment) => assignment.worker?.name.trim())
      .filter((name): name is string => Boolean(name))
    return names.length > 0 ? actor(names.join(", ")) : null
  }

  async addPhotoGroup(command: AddCabinPhotoGroupCommand) {
    if (command.uploads.length === 0) throw new Error("Выберите фотографии")
    if (command.uploads.length > 20)
      throw new Error("Можно добавить не более 20 фотографий")
    const now = new Date().toISOString()
    const uploads: PendingEstimateMediaUpload[] = command.uploads.map(
      ({ file, rotationDegrees }) => ({
        id: opaqueId("dossier-photo"),
        file,
        previewUrl: "",
        rotationDegrees,
      })
    )
    const media = await this.media.upload(uploads)
    try {
      await incrementRentalItemVersion(command)
      const activityId = opaqueId("cabin-activity")
      const groupId = opaqueId("cabin-photo-group")
      const activity: CabinActivityDto = {
        id: activityId,
        rentalItemId: command.rentalItemId,
        occurredAt: now,
        actor: command.actor,
        type: "PHOTO_ADDED",
        sourceType: "MANUAL",
        sourceId: activityId,
        sourceLabel: "Ручная загрузка",
        comment: command.comment?.trim() || null,
        statusTransition: null,
        parentActivityId: null,
        photoGroupId: groupId,
        links: [],
      }
      const group = mediaGroup({
        id: groupId,
        rentalItemId: command.rentalItemId,
        activityId,
        occurredAt: now,
        actor: command.actor,
        sourceType: "MANUAL",
        sourceId: activityId,
        sourceLabel: "Добавленные фотографии",
        stage: "GENERAL",
        media,
      })!
      const dehydratedMedia = this.media.dehydrate(media)
      const storedGroup: CabinPhotoGroupDto = {
        ...group,
        photos: dehydratedMedia.map((item, order) =>
          mediaPhoto(item, {
            sourceType: "MANUAL",
            sourceId: activityId,
            sourceLabel: "Добавленные фотографии",
            stage: "GENERAL",
            order,
            occurredAt: now,
          })
        ),
      }
      const envelope = readEnvelope()
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        activities: [...envelope.activities, activity],
        photoGroups: [
          ...envelope.photoGroups,
          { group: storedGroup, media: dehydratedMedia },
        ],
      })
      return this.requireDossier(command.warehouseId, command.rentalItemId)
    } catch (error) {
      await this.media.discard(media.map((item) => item.id))
      throw error
    }
  }

  async addComment(command: AddCabinCommentCommand) {
    const text = command.text.trim()
    if (!text) throw new Error("Введите комментарий")
    await this.requireDossier(command.warehouseId, command.rentalItemId)
    const envelope = readEnvelope()
    const activityId = opaqueId("cabin-activity")
    writeEnvelope({
      ...envelope,
      revision: envelope.revision + 1,
      activities: [
        ...envelope.activities,
        {
          id: activityId,
          rentalItemId: command.rentalItemId,
          occurredAt: new Date().toISOString(),
          actor: command.actor,
          type: "COMMENT_ADDED",
          sourceType: "MANUAL",
          sourceId: activityId,
          sourceLabel: "Комментарий пользователя",
          comment: text,
          statusTransition: null,
          parentActivityId: null,
          photoGroupId: null,
          links: [],
        },
      ],
    })
    return this.requireDossier(command.warehouseId, command.rentalItemId)
  }

  async updateStatus(command: UpdateCabinStatusCommand) {
    const reason = command.reason.trim()
    if (!reason) throw new Error("Укажите причину изменения статуса")
    const current = await this.requireDossier(
      command.warehouseId,
      command.rentalItemId
    )
    if (
      current.repairAction.type === "NONE" &&
      current.overview.activeRepairCount > 0
    ) {
      throw new Error("Статус нельзя изменить при активном ремонте")
    }
    const from = current.rentalItem.status
    const saved = await updateRentalItemManualStatus(command)
    const envelope = readEnvelope()
    const activityId = opaqueId("cabin-activity")
    writeEnvelope({
      ...envelope,
      revision: envelope.revision + 1,
      activities: [
        ...envelope.activities,
        {
          id: activityId,
          rentalItemId: command.rentalItemId,
          occurredAt: new Date().toISOString(),
          actor: command.actor,
          type: "STATUS_CHANGED",
          sourceType: "MANUAL",
          sourceId: activityId,
          sourceLabel: "Изменение статуса",
          comment: reason,
          statusTransition: { from, to: saved.status, reason },
          parentActivityId: null,
          photoGroupId: null,
          links: [],
        },
      ],
    })
    return this.requireDossier(command.warehouseId, command.rentalItemId)
  }

  async updateGeneralComment(command: UpdateCabinGeneralCommentCommand) {
    const saved = await updateRentalItemGeneralComment(command)
    const envelope = readEnvelope()
    const activityId = opaqueId("cabin-activity")
    writeEnvelope({
      ...envelope,
      revision: envelope.revision + 1,
      activities: [
        ...envelope.activities,
        {
          id: activityId,
          rentalItemId: command.rentalItemId,
          occurredAt: new Date().toISOString(),
          actor: command.actor,
          type: "GENERAL_COMMENT_UPDATED",
          sourceType: "MANUAL",
          sourceId: activityId,
          sourceLabel: "Общий комментарий",
          comment: saved.comment,
          statusTransition: null,
          parentActivityId: null,
          photoGroupId: null,
          links: [],
        },
      ],
    })
    return this.requireDossier(command.warehouseId, command.rentalItemId)
  }

  async getOriginalPhotoUrl(
    photoId: string,
    context: CabinOriginalPhotoContext
  ) {
    const refs: RepairEstimateMediaRefDto[] = await (async () => {
      if (context.kind === "ESTIMATE") {
        const estimate = await getRepairEstimate(
          context.documentId,
          context.warehouseId
        )
        return estimate?.media ?? []
      }
      if (context.kind === "REPAIR") {
        const task = await getRepairTask(
          context.documentId,
          context.warehouseId
        )
        return context.stageId
          ? (task?.subtasks.find((item) => item.id === context.stageId)
              ?.resultMedia ?? [])
          : (task?.media ?? [])
      }
      const reader: InventoryActorSnapshot = {
        id: "dossier-reader",
        displayName: "Досье бытовки",
        permissions: ["VIEW"],
        authorizedWarehouseIds: [context.warehouseId],
      }
      const inventory = await getInventory(context.documentId, reader)
      return inventory?.findings.flatMap((item) => item.media) ?? []
    })()
    const ref = refs.find((item) => item.id === photoId)
    if (!ref)
      throw new Error("Оригинал недоступен в выбранном рабочем контексте")
    return this.media.resolveOriginalUrl(
      photoId,
      context.kind === "ESTIMATE"
        ? "ESTIMATE"
        : context.kind === "INVENTORY"
          ? "INSPECTION"
          : "WORK"
    )
  }

  private async requireDossier(warehouseId: string, rentalItemId: string) {
    const dossier = await this.get(warehouseId, rentalItemId)
    if (!dossier) throw new Error("Бытовка не найдена на выбранном складе")
    return dossier
  }

  subscribe(listener: () => void) {
    if (typeof window === "undefined") return () => undefined
    const onChange = () => listener()
    window.addEventListener(DOSSIER_UPDATED_EVENT, onChange)
    window.addEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, onChange)
    window.addEventListener(CONTENTS_TRANSFER_UPDATED_EVENT, onChange)
    window.addEventListener("storage", onChange)
    return () => {
      window.removeEventListener(DOSSIER_UPDATED_EVENT, onChange)
      window.removeEventListener(RENTAL_ITEMS_MOCK_UPDATED_EVENT, onChange)
      window.removeEventListener(CONTENTS_TRANSFER_UPDATED_EVENT, onChange)
      window.removeEventListener("storage", onChange)
    }
  }
}
