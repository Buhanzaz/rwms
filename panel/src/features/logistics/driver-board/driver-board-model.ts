import type { AdditionalContact } from "@/features/clients/domain/clients"
import type { DesiredDeliveryWindow } from "@/features/orders/domain/orders"

export type DriverTaskKind =
  | "DELIVER_TO_REPAIR"
  | "REMOVE_FROM_REPAIR"
  | "CAPITAL_TO_PRODUCTION"
  | "GENERAL_MOVEMENT"
  | "SHIPMENT"
  | "RETURN"
  | "TRANSFER"

export type DriverTaskAudienceMode =
  "UNASSIGNED" | "ASSIGNED_DRIVER" | "WAREHOUSE_DRIVERS"

export type DriverTaskAudience = {
  mode: DriverTaskAudienceMode
  workerId: string | null
  workerName: string | null
}

export type DriverTaskSourceType =
  | "REPAIR"
  | "ESTIMATE"
  | "INVENTORY"
  | "REPAIR_PLACE"
  | "CAPITAL_REPAIR"
  | "MANUAL"
  | "LOGISTICS_DOCUMENT"
  | "LOGISTICS_DOCUMENT_LINE"

export type DriverTaskPlanningMode = "AUTO" | "FIXED_DATE"

export type DriverTaskWorkflowState =
  | "REGISTERING"
  | "SCHEDULED"
  | "CURRENT"
  | "FINALIZING"
  | "COMPLETED"
  | "CANCELLED"
  | "RECONCILIATION_REQUIRED"

export type DriverBoardEntryStatus =
  "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" | "CANCELLED"

export type DriverTripDesiredEquipment = {
  equipmentId: string
  equipmentName: string
  quantity: number
}

export type DriverTripActualEquipment = {
  equipmentId: string
  equipmentName: string | null
  quantity: number
  locationKind: string
}

export type DriverTripCabin = {
  cabinId: string
  unitNumber: string
  desiredContents: DriverTripDesiredEquipment[]
  actualContents: DriverTripActualEquipment[] | null
  movementTaskCreated: boolean
  movementTaskCompleted: boolean
  contentReady: boolean | null
}

/** Logistics-owned projection shown identically to managers and the driver. */
export type DriverTripDetails = {
  taskNumber: string
  tripNumber: number
  operationType: string
  clientName: string
  address: string | null
  latitude: number | null
  longitude: number | null
  primaryContactName: string | null
  primaryContactPhone: string | null
  additionalContacts: AdditionalContact[]
  comment: string | null
  desiredDeliveryWindows: DesiredDeliveryWindow[]
  scheduledDate: string
  cabins: DriverTripCabin[]
}

/** Full logistics-owned driver-task response used by the live detail dialog. */
export type DriverTask = {
  id: string
  version: number
  warehouseId: string
  cabinId: string
  repairId: string | null
  sourceType: DriverTaskSourceType
  sourceId: string
  kind: DriverTaskKind
  planningMode: DriverTaskPlanningMode
  scheduledDate: string
  priority: number
  comment: string | null
  unitNumber: string
  driverQueueDefinitionId: string
  driverAudienceMode: DriverTaskAudienceMode
  plannedDriverWorkerId: string | null
  plannedDriverNameSnapshot: string | null
  externalTaskId: string
  taskBoardTaskId: string | null
  taskBoardTaskVersion: number | null
  taskBoardEntryId: string | null
  taskBoardEntryStatus: string | null
  taskBoardDoneAt: string | null
  state: DriverTaskWorkflowState
  repairPlaceAllocationId: string | null
  repairPlaceAllocationVersion: number | null
  completionMediaId: string | null
  completionMediaGeneration: number | null
  coverApplied: boolean
  repairPlaceEffectApplied: boolean
  failureCode: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
  tripDetails: DriverTripDetails | null
}

export type DriverBoardCard = {
  driverTaskId: string | null
  externalTaskId: string
  taskBoardTaskId: string
  taskBoardTaskVersion: number
  taskBoardEntryId: string
  taskBoardEntryVersion: number
  title: string
  taskText: string | null
  unitNumber: string | null
  kind: DriverTaskKind | null
  driverAudience: DriverTaskAudience
  workflowState: DriverTaskWorkflowState | null
  taskStatus: "ACTIVE" | "DONE" | "CANCELLED"
  entryStatus: DriverBoardEntryStatus
  scheduledDate: string
  lane: "SCHEDULED" | "CURRENT"
  priority: number
  pinned: boolean
  position: number
  tripDetails: DriverTripDetails | null
}

export type DriverBoardDateColumn = {
  date: string
  tasks: DriverBoardCard[]
}

export type CapitalRepairCard = {
  repairId: string
  repairVersion: number
  cabinId: string
  unitNumber: string
  priority: number
  complexityName: "Капитальный ремонт"
  complexityColor: string
  plannedMinutes: string
  forcedCapital: boolean
}

export type RepairPlaceCard = {
  repairId: string
  cabinId: string
  unitNumber: string
  allocationState: "OCCUPIED" | "READY_TO_RELEASE"
  repairStageName: string | null
  repairStageState:
    "PLANNED" | "QUEUED" | "IN_PROGRESS" | "DONE" | "CANCELLED" | null
  priority: number
}

export type DriverBoard = {
  warehouseId: string
  currentDate: string
  queueId: string
  queueVersion: number
  repairPlaceCount: number
  occupiedRepairPlaceCount: number
  usedRepairPlaceCount: number
  availableRepairPlaceCount: number
  inboundRepairPlaceAvailable: boolean
  automaticRefillDelayMinutes: number
  repairPlacesOverCapacity: boolean
  repairPlaces: RepairPlaceCard[]
  current: DriverBoardCard[]
  dates: DriverBoardDateColumn[]
  capitalRepairs: CapitalRepairCard[]
}

export type MoveDriverBoardTask = {
  warehouseId: string
  expectedTaskVersion: number
  expectedEntryVersion: number
  targetLane: "SCHEDULED" | "CURRENT"
  targetDate: string
  targetIndex: number
}

export type CreateManualMovement = {
  warehouseId: string
  cabinId: string
  comment: string
  priority: number
  idempotencyKey: string
}
