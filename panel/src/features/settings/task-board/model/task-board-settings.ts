export type QueueType = "MOVEMENT" | "REPAIR" | "HOLDING"
export type CredentialStatus = "NOT_CONFIGURED" | "PENDING" | "ACTIVE" | "ERROR"
export type RestPeriodType = "SMOKE_BREAK" | "LUNCH"

export type ReviewedTaskBoardBootstrapResult = {
  warehouseId: string
  sourceSha256: string
  created: number
  reused: number
  conflicts: number
  counts: {
    workerClasses: number
    workQueues: number
    queueBindings: number
    workers: number
    qualifications: number
    workerGroups: number
    memberships: number
  }
}

export type WorkerClassDto = {
  id: string
  version: number
  code: string
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
  stopTaskOnTake: boolean
}

export type QueueBindingRequest = {
  workerClassId: string
  stopTaskOnTake: boolean
}

export type WorkQueueDto = {
  id: string
  version: number
  warehouseId: string
  code: string
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
  bindings: QueueBindingDto[]
}

export type WorkQueueRequest = {
  version: number
  code: string
  name: string
  description: string | null
  type: QueueType
  active: boolean
  hidden: boolean
  collapsed: boolean
  holdingPeriodMinutes: number | null
  notificationThreshold: number | null
  notifyWhenThresholdReached: boolean
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
  roleInGroup: string | null
  active: boolean
}

export type GroupMemberRequest = {
  workerId: string
  roleInGroup: string | null
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

/** @deprecated Retained only for deferred browser logistics fixtures. */
export type RestPeriodDto = {
  id: string
  version: number
  type: RestPeriodType
  startsAt: string
  endsAt: string
  warningMinutes: number
  autoPause: boolean
}

/** @deprecated Retained only for deferred browser logistics fixtures. */
export type ScheduleDayDto = {
  dayOfWeek: 1 | 2 | 3 | 4 | 5 | 6 | 7
  enabled: boolean
  linkedToTemplate: boolean
  shiftStartsAt: string
  shiftEndsAt: string
  restPeriods: RestPeriodDto[]
}

/** @deprecated Retained only for deferred browser logistics fixtures. */
export type GroupScheduleDto = {
  id: string
  version: number
  warehouseId: string
  workerGroupId: string
  timezone: string
  returnGraceMinutes: number
  days: ScheduleDayDto[]
}

/** @deprecated Retained only for deferred browser logistics fixtures. */
export type GroupScheduleRequest = Omit<
  GroupScheduleDto,
  "id" | "warehouseId" | "workerGroupId"
>

/** @deprecated Retained only for deferred browser logistics fixtures. */
export type CopyGroupScheduleRequest = {
  sourceGroupId: string
  sourceExpectedVersion: number
  targets: { groupId: string; expectedVersion: number }[]
}

export const queueTypeLabels: Record<QueueType, string> = {
  MOVEMENT: "Перемещение",
  REPAIR: "Ремонт",
  HOLDING: "Удержание",
}

export const credentialStatusLabels: Record<CredentialStatus, string> = {
  NOT_CONFIGURED: "Не настроены",
  PENDING: "Обновляются",
  ACTIVE: "Активны",
  ERROR: "Ошибка",
}
