import {
  formatRentalItemContents,
  type RentalItemDto,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

export const ORDER_CLIENT_TYPES = ["INDIVIDUAL", "LEGAL_ENTITY"] as const

export type OrderClientType = (typeof ORDER_CLIENT_TYPES)[number]

export const ORDER_CLIENT_TYPE_LABELS: Record<OrderClientType, string> = {
  INDIVIDUAL: "Физическое лицо",
  LEGAL_ENTITY: "Юридическое лицо",
}

export const ORDER_STATUSES = ["DRAFT", "CANCELLED"] as const
export type OrderStatus = (typeof ORDER_STATUSES)[number]

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  DRAFT: "Черновик",
  CANCELLED: "Отменён",
}

export type OrderClient = {
  id: string
  type: OrderClientType
  displayName: string
}

export type OrderClientSearchItem = OrderClient & {
  version?: number
  createdAt?: string
  updatedAt?: string
}

export type OrderEquipmentContent = {
  equipmentId: string
  equipmentCode: string
  equipmentName: string
  quantity: number
  locationKind: string
}

export type OrderRentalUnit = {
  id: string
  version: number
  warehouseId: string
  number: string
  status: RentalItemStatus
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  tags: string[]
  contents: OrderEquipmentContent[]
  createdAt: string
  updatedAt: string
}

export type OrderUnitCandidate = {
  reservationId: string | null
  added: boolean
  unit: OrderRentalUnit
}

export type OrderSummary = {
  id: string
  version: number
  number: string
  status: OrderStatus
  client: OrderClient
  managerId: string
  managerDisplayName: string
  createdBy: string
  createdByDisplayName: string
  warehouseId: string | null
  unitCount: number
  createdAt: string
  updatedAt: string
}

export type OrderDetailPermissions = {
  canEdit: boolean
  canViewOtherManagers: boolean
}

export type OrderDetail = OrderSummary & {
  units: OrderUnitCandidate[]
  permissions: OrderDetailPermissions
}

export type OrderPage<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const ORDER_AUDIT_EVENT_TYPES = [
  "ORDER_CREATED",
  "CLIENT_SELECTED",
  "CLIENT_CREATED",
  "WAREHOUSE_SELECTED",
  "UNIT_ADDED",
  "UNIT_ADD_CONFLICT",
  "UNIT_REMOVED",
  "RESERVATION_CREATED",
  "RESERVATION_RELEASED",
  "EQUIPMENT_ADDED",
  "EQUIPMENT_INCREASED",
  "EQUIPMENT_DECREASED",
  "WAREHOUSE_OPERATION_CREATED",
  "ORDER_CHANGED",
  "ORDER_CANCELLED",
] as const

export type OrderAuditEventType = (typeof ORDER_AUDIT_EVENT_TYPES)[number]

export const ORDER_AUDIT_EVENT_LABELS: Record<OrderAuditEventType, string> = {
  ORDER_CREATED: "Заказ создан",
  CLIENT_SELECTED: "Клиент выбран",
  CLIENT_CREATED: "Клиент создан",
  WAREHOUSE_SELECTED: "Склад выбран",
  UNIT_ADDED: "Бытовка добавлена",
  UNIT_ADD_CONFLICT: "Конфликт резервирования бытовки",
  UNIT_REMOVED: "Бытовка удалена",
  RESERVATION_CREATED: "Резервирование создано",
  RESERVATION_RELEASED: "Резервирование освобождено",
  EQUIPMENT_ADDED: "Наполнение добавлено",
  EQUIPMENT_INCREASED: "Количество наполнения увеличено",
  EQUIPMENT_DECREASED: "Количество наполнения уменьшено",
  WAREHOUSE_OPERATION_CREATED: "Складская операция создана",
  ORDER_CHANGED: "Заказ изменён",
  ORDER_CANCELLED: "Заказ отменён",
}

export type OrderAuditEvent = {
  id: string
  orderId: string
  eventType: OrderAuditEventType
  actorSubjectId: string
  actorRole: string
  subjectType: string
  subjectId: string
  previousValues: Record<string, unknown> | null
  newValues: Record<string, unknown> | null
  occurredAt: string
}

export function normalizeClientDisplayName(value: string) {
  return value.trim().replace(/\s+/g, " ")
}

export function normalizeClientSearch(value: string) {
  return normalizeClientDisplayName(value).toLocaleLowerCase("ru-RU")
}

export function orderUnitToRentalItem(unit: OrderRentalUnit): RentalItemDto {
  const contentsItems = unit.contents.map((content) => ({
    equipmentId: content.equipmentId,
    equipmentCode: content.equipmentCode,
    equipmentName: content.equipmentName,
    name: content.equipmentName,
    quantity: content.quantity,
    locationKind: content.locationKind,
  }))

  return {
    id: unit.id,
    version: unit.version,
    warehouseId: unit.warehouseId,
    number: unit.number,
    type: unit.rentalType ?? "—",
    dimensions: unit.dimensions,
    finishing: unit.finishing,
    category: unit.category,
    characteristics: unit.characteristics,
    linoleum: unit.linoleum,
    status: unit.status,
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    previewPhotoUrls: [],
    locationNodeId: null,
    contents: formatRentalItemContents(contentsItems),
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
    tags: unit.tags,
    createdAt: unit.createdAt,
    updatedAt: unit.updatedAt,
  }
}

export function formatOrderDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}
