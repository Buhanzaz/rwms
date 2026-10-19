export const CUSTOMER_CABIN_PROBLEM_STATUSES = [
  "OPEN",
  "IN_PROGRESS",
  "RESOLVED",
] as const

export type CustomerCabinProblemStatus =
  (typeof CUSTOMER_CABIN_PROBLEM_STATUSES)[number]

export const CUSTOMER_CABIN_PROBLEM_STATUS_LABELS: Record<
  CustomerCabinProblemStatus,
  string
> = {
  OPEN: "Требует рассмотрения",
  IN_PROGRESS: "В работе",
  RESOLVED: "Решена",
}

export const CUSTOMER_CABIN_PROBLEM_CATEGORIES = [
  "MISSING_EQUIPMENT",
  "UNSUITABLE_CABIN",
  "OTHER",
] as const

export type CustomerCabinProblemCategory =
  (typeof CUSTOMER_CABIN_PROBLEM_CATEGORIES)[number]

export const CUSTOMER_CABIN_PROBLEM_CATEGORY_LABELS: Record<
  CustomerCabinProblemCategory,
  string
> = {
  MISSING_EQUIPMENT: "Не хватает комплектации",
  UNSUITABLE_CABIN: "Бытовка не соответствует ожиданиям",
  OTHER: "Другое",
}

export const CUSTOMER_CABIN_PROBLEM_PHASES = [
  "BEFORE_ACCEPTANCE",
  "AFTER_ACCEPTANCE",
] as const

export type CustomerCabinProblemPhase =
  (typeof CUSTOMER_CABIN_PROBLEM_PHASES)[number]

export const CUSTOMER_CABIN_PROBLEM_PHASE_LABELS: Record<
  CustomerCabinProblemPhase,
  string
> = {
  BEFORE_ACCEPTANCE: "До приёмки",
  AFTER_ACCEPTANCE: "После приёмки",
}

export const CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS = [
  "DISCOUNT",
  "REPLACEMENT",
  "RETURN",
] as const

export type CustomerCabinProblemResolutionKind =
  (typeof CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS)[number]

export const CUSTOMER_CABIN_PROBLEM_RESOLUTION_LABELS: Record<
  CustomerCabinProblemResolutionKind,
  string
> = {
  DISCOUNT: "Скидка",
  REPLACEMENT: "Замена бытовки",
  RETURN: "Возврат бытовки",
}

export const CUSTOMER_CABIN_PROBLEM_CLIENT_TYPES = [
  "INDIVIDUAL",
  "SOLE_PROPRIETOR",
  "LEGAL_ENTITY",
] as const

export type CustomerCabinProblemClientType =
  (typeof CUSTOMER_CABIN_PROBLEM_CLIENT_TYPES)[number]

export const CUSTOMER_CABIN_PROBLEM_CLIENT_TYPE_LABELS: Record<
  CustomerCabinProblemClientType,
  string
> = {
  INDIVIDUAL: "Физлицо",
  SOLE_PROPRIETOR: "ИП",
  LEGAL_ENTITY: "Юрлицо",
}

export const CUSTOMER_CABIN_PROBLEM_ACTION_KINDS = [
  "STATUS_TRANSITION",
  "RESOLUTION_DECISION",
] as const

export type CustomerCabinProblemActionKind =
  (typeof CUSTOMER_CABIN_PROBLEM_ACTION_KINDS)[number]

export type CustomerCabinProblemAction = {
  id: string
  actionKind: CustomerCabinProblemActionKind
  previousStatus: CustomerCabinProblemStatus
  lifecycleStatus: CustomerCabinProblemStatus
  resolutionKind: CustomerCabinProblemResolutionKind | null
  comment: string | null
  occurredAt: string
}

/** A manager-visible customer problem; IDs remain transport-only. */
export type CustomerCabinProblemClaim = {
  id: string
  orderId: string
  warehouseId: string
  bookingId: string
  cabinUnitId: string
  orderNumber: string
  category: CustomerCabinProblemCategory
  phase: CustomerCabinProblemPhase
  description: string
  reportedAt: string
  clientDisplayName: string
  clientType: CustomerCabinProblemClientType
  clientPhone: string | null
  orderContactPhone: string | null
  deliveryAddress: string | null
  status: CustomerCabinProblemStatus
  resolutionDeadline: string
  version: number
  resolutionKind: CustomerCabinProblemResolutionKind | null
  resolutionComment: string | null
  resolvedAt: string | null
  actions: CustomerCabinProblemAction[]
}
