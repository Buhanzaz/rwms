export type DriverTaskKind =
  | "DELIVER_TO_REPAIR"
  | "REMOVE_FROM_REPAIR"
  | "CAPITAL_TO_PRODUCTION"
  | "MOVE_TO_SHIPMENT"

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
  workflowState: DriverTaskWorkflowState | null
  taskStatus: "ACTIVE" | "DONE" | "CANCELLED"
  entryStatus: DriverBoardEntryStatus
  scheduledDate: string
  lane: "SCHEDULED" | "CURRENT"
  priority: number
  pinned: boolean
  position: number
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

export type DriverBoard = {
  warehouseId: string
  queueId: string
  queueVersion: number
  repairPlaceCount: number
  occupiedRepairPlaceCount: number
  availableRepairPlaceCount: number
  repairPlacesOverCapacity: boolean
  current: DriverBoardCard[]
  dates: DriverBoardDateColumn[]
  capitalRepairs: CapitalRepairCard[]
}

export type MoveDriverBoardTask = {
  warehouseId: string
  expectedTaskVersion: number
  expectedEntryVersion: number
  targetDate: string
  targetIndex: number
}
