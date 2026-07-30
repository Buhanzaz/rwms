export type QueueType = "MOVEMENT" | "REPAIR" | "HOLDING" | "FURNITURE_MOVEMENT"
export type CredentialStatus =
  "NOT_CONFIGURED" | "PENDING" | "ACTIVE" | "DISABLED" | "ERROR"
export type OperationalAvailability = "AVAILABLE" | "DISABLED"

export type WorkerClassDto = {
  id: string
  version: number
  name: string
  description: string | null
  comment: string | null
  sortOrder: number
  active: boolean
}

export type WorkerClassRequest = Omit<WorkerClassDto, "id">

export type QueueBindingDto = {
  id: string
  version: number
  workerClass: WorkerClassDto
  order: number
  primary: boolean
  stopTaskOnTake: boolean
  notifyUrgent: boolean
}

export type QueueBindingRequest = {
  workerClassId: string
  order: number
  stopTaskOnTake: boolean
  notifyUrgent: boolean
}

export type QueueDefinitionDto = {
  id: string
  version: number
  name: string
  description: string | null
  type: QueueType
}

export type QueueDefinitionRequest = Omit<QueueDefinitionDto, "id">

export type WorkQueueDto = {
  id: string
  version: number
  warehouseId: string
  definitionId: string
  definitionVersion: number
  name: string
  description: string | null
  type: QueueType
  sortOrder: number
  active: boolean
  hidden: boolean
  collapsed: boolean
  holdingPeriodMinutes: number | null
  notificationThreshold: number | null
  notifyWhenThresholdReached: boolean
  resultPhotoMinCount: number
  bindings: QueueBindingDto[]
}

export type WorkQueueRequest = {
  version: number
  definitionId: string
  active: boolean
  hidden: boolean
  collapsed: boolean
  holdingPeriodMinutes: number | null
  notificationThreshold: number | null
  notifyWhenThresholdReached: boolean
  resultPhotoMinCount: number | null
  bindings: QueueBindingRequest[]
}

export type QualificationDto = {
  id: string
  version: number
  workerClass: WorkerClassDto
  active: boolean
  comment: string | null
}

export type QualificationRequest = {
  workerClassId: string
  active: boolean
  comment: string | null
}

export type WorkerDto = {
  id: string
  version: number
  warehouseId: string
  displayName: string
  firstName: string | null
  lastName: string | null
  middleName: string | null
  active: boolean
  comment: string | null
  appLogin: string | null
  credentialStatus: CredentialStatus
  credentialError: string | null
  currentGroupId: string | null
  currentGroupName: string | null
  operationalAvailability: OperationalAvailability
  qualifications: QualificationDto[]
}

export type WorkerRequest = {
  version: number
  displayName: string
  firstName: string | null
  lastName: string | null
  middleName: string | null
  active: boolean
  comment: string | null
  appLogin: string | null
  password: string | null
  qualifications: QualificationRequest[]
}

export type GroupMemberDto = {
  id: string
  version: number
  workerId: string
  workerName: string
  active: boolean
}

export type GroupMemberRequest = {
  workerId: string
  active: boolean
}

export type WorkerGroupDto = {
  id: string
  version: number
  warehouseId: string
  workerClass: WorkerClassDto
  name: string
  description: string | null
  active: boolean
  operationalStatus: OperationalAvailability
  unavailableSince: string | null
  unavailabilityReason: string | null
  members: GroupMemberDto[]
}

export type WorkerGroupRequest = {
  version: number
  workerClassId: string
  name: string
  description: string | null
  active: boolean
  members: GroupMemberRequest[]
}

export const queueTypeLabels: Record<QueueType, string> = {
  MOVEMENT: "Перемещение",
  REPAIR: "Ремонт",
  HOLDING: "Удержание",
  FURNITURE_MOVEMENT: "Перемещение мебели",
}

export const credentialStatusLabels: Record<CredentialStatus, string> = {
  NOT_CONFIGURED: "Не настроены",
  PENDING: "Обновляются",
  ACTIVE: "Активны",
  DISABLED: "Отключены",
  ERROR: "Ошибка",
}

export const operationalAvailabilityLabels: Record<
  OperationalAvailability,
  string
> = {
  AVAILABLE: "Доступна",
  DISABLED: "Недоступна",
}
