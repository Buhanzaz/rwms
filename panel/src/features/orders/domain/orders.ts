import {
  formatRentalItemContents,
  type RentalItemDto,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import {
  CLIENT_TYPES,
  CLIENT_TYPE_LABELS,
  normalizeClientDisplayName,
  normalizeClientSearch,
  type ClientType,
  type RentalClient,
} from "@/features/clients/domain/clients"

export const ORDER_CLIENT_TYPES = CLIENT_TYPES

export type OrderClientType = ClientType

export const ORDER_CLIENT_TYPE_LABELS = CLIENT_TYPE_LABELS

export const ORDER_STATUSES = [
  "DRAFT",
  "SAVED",
  "FULFILLED",
  "CLOSED",
  "CANCELLED",
] as const
export type OrderStatus = (typeof ORDER_STATUSES)[number]

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  DRAFT: "Черновик",
  SAVED: "Сохранён",
  FULFILLED: "Исполнен",
  CLOSED: "Закрыт",
  CANCELLED: "Отменён",
}

export type OrderClient = RentalClient

export type OrderClientSearchItem = RentalClient

export type OrderEquipmentContent = {
  equipmentId: string
  equipmentName: string
  quantity: number
  locationKind: string
}

export type OrderDesiredEquipment = {
  equipmentId: string
  equipmentName: string
  quantity: number
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
  desiredContents: OrderDesiredEquipment[]
  /** Nullable on the wire; optional keeps older read-only fixtures compatible. */
  rentalTerm?: OrderRentalTerm | null
}

export type OrderRentalTerm = {
  rentalMonths: number
  shipmentDate: string | null
  returnDate: string | null
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
  deliveryAddress: string | null
  latitude: number | null
  longitude: number | null
  contactPhone: string | null
  comment: string | null
  acceptableDeliveryDates: string[]
  unitCount: number
  createdAt: string
  updatedAt: string
}

export type OrderDetailPermissions = {
  canEdit: boolean
  canViewOtherManagers: boolean
}

export type OrderMovementCabin = {
  rentalItemId: string
  lineState: string
}

export type OrderMovement = {
  documentId: string
  documentType: "SHIPMENT" | "RETURN"
  state: string
  /** Null while an automatically created return has not been planned yet. */
  scheduledDate: string | null
  actualAt: string | null
  rentalShipmentId: string | null
  createdAt: string
  updatedAt: string
  cabins: OrderMovementCabin[]
}

export type OrderDetail = OrderSummary & {
  units: OrderUnitCandidate[]
  movements: OrderMovement[]
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
  "ORDER_SAVED",
  "ORDER_FULFILLED",
  "ORDER_CLOSED",
  "ORDER_CANCELLED",
] as const

export type OrderAuditEventType = (typeof ORDER_AUDIT_EVENT_TYPES)[number]

export const ORDER_AUDIT_EVENT_LABELS: Record<OrderAuditEventType, string> = {
  ORDER_CREATED: "Бронирование создано",
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
  ORDER_CHANGED: "Бронирование изменено",
  ORDER_SAVED: "Бронирование сохранено",
  ORDER_FULFILLED: "Бронирование исполнено",
  ORDER_CLOSED: "Бронирование закрыто",
  ORDER_CANCELLED: "Бронирование отменено",
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

export { normalizeClientDisplayName, normalizeClientSearch }

export function orderUnitToRentalItem(unit: OrderRentalUnit): RentalItemDto {
  const contentsItems = unit.contents.map((content) => ({
    equipmentId: content.equipmentId,
    equipmentName: content.equipmentName,
    name: content.equipmentName,
    quantity: content.quantity,
    locationKind: content.locationKind,
  }))
  const characteristics = (unit.characteristics ?? "")
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name !== "")
    .map((name, index) => ({
      // The logistics order snapshot still provides display names rather than
      // catalog UUIDs. This adapter is read-only: the synthetic key is never
      // rendered or sent in a command.
      id: `${unit.id}:characteristic:${index}`,
      name,
    }))

  return {
    id: unit.id,
    version: unit.version,
    warehouseId: unit.warehouseId,
    number: unit.number,
    // An order candidate is a read-only logistics snapshot. It has no
    // composition UUIDs and cannot be opened in the passport editor.
    rentalTypeId: "",
    dimensionId: "",
    finishingId: "",
    type: unit.rentalType ?? "—",
    dimensions: unit.dimensions,
    finishing: unit.finishing,
    category: unit.category,
    characteristics,
    linoleum: unit.linoleum,
    status: unit.status,
    comment: null,
    contents: formatRentalItemContents(contentsItems),
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
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
