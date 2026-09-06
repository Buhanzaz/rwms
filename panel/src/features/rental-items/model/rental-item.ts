export type RentalItemStatus =
  | "RENTED"
  | "BOOKED"
  | "REPAIR"
  | "WAITING_REPAIR_CHECK"
  | "WRITTEN_OFF"
  | "LOST"
  | "CAPITAL_REPAIR"
  | "AFTER_RENT"
  | "WAITING_ESTIMATE_CONFIRMATION"
  | "SALE"
  | "USED_SALE"
  | "RESERVED"
  | "FREE"
  | "WAREHOUSE"
  | "OWN_NEEDS"
  | "IN_TRANSFER"

export const RENTAL_ITEM_STATUS_LABEL: Record<RentalItemStatus, string> = {
  RENTED: "Аренда",
  BOOKED: "Бронь",
  REPAIR: "В ремонте",
  WAITING_REPAIR_CHECK: "В ремонте",
  WRITTEN_OFF: "Списана",
  LOST: "Утеряна",
  CAPITAL_REPAIR: "Капремонт",
  AFTER_RENT: "Ожидает осмотра",
  WAITING_ESTIMATE_CONFIRMATION: "Ожидает подтверждения сметы",
  SALE: "Продажа Б/У",
  USED_SALE: "Продажа Б/У",
  RESERVED: "Бронь",
  FREE: "Свободна",
  WAREHOUSE: "Склад",
  OWN_NEEDS: "Собственные нужды",
  IN_TRANSFER: "В перемещении",
}

export type RentalItemContentsItemDto = {
  name: string
  quantity: number
  /** Canonical asset-service identity when the public cabin response provides it. */
  equipmentId?: string
  /** Human-readable equipment name from the asset catalog. */
  equipmentName?: string
  /** Asset-service ledger location kind for this cabin content row. */
  locationKind?: string
}

export type RentalItemActiveOrderReservationDto = {
  reservationId: string
  orderId: string
  clientId: string | null
  tenantSnapshot: string | null
  reservedAt: string
}

/** A public cabin-composition value. The UUID remains an internal command key. */
export type RentalItemCharacteristicDto = {
  id: string
  name: string
}

type RentalItemCoreDto = {
  id: string
  /** Optimistic concurrency token for cabin-level commands. */
  version: number
  warehouseId: string

  number: string
  /**
   * UUIDs are retained only for strict passport edits and never rendered as
   * labels. An incomplete HTML import is represented by an empty value, which
   * keeps the required composition form invalid until the user selects one.
   */
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  type: string
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: RentalItemCharacteristicDto[]
  linoleum: boolean | null

  status: RentalItemStatus
  comment: string | null

  mediaAvailability?: "AVAILABLE"
  contents: string | null
  contentsItems: RentalItemContentsItemDto[]

  shipmentDate: string | null
  tenant: string | null
  price: number | null
  activeOrderReservation?: RentalItemActiveOrderReservationDto | null
  /** Preserved only so a composition-only passport edit does not erase it. */
  passport: Record<string, unknown>
  tags: string[]
}

export type RentalItemDto = RentalItemCoreDto & Record<string, unknown>

export type RentalItemsViewMode = "table" | "grid"
export type RentalItemsColumnKey = string
export type RentalItemsFilterKey = string

export type RentalItemFieldDataType =
  | "text"
  | "number"
  | "boolean"
  | "date"
  | "status"
  | "photos"
  | "contents"
  | "currency"

export type RentalItemFieldDefinition = {
  id: RentalItemsColumnKey
  label: string
  dataType: RentalItemFieldDataType
  column?: boolean
  filterable?: boolean
  searchable?: boolean
  locked?: boolean
  visibleByDefault?: boolean
  alwaysShow?: boolean
  size?: number
  minSize?: number
  maxSize?: number
}

export type RentalItemsColumnConfig = {
  id: RentalItemsColumnKey
  label: string
  visible: boolean
  locked?: boolean
  filterable?: boolean
  searchable?: boolean
  dataType: RentalItemFieldDataType
  size?: number
  minSize?: number
  maxSize?: number
}

export type RentalItemsFilterDefinition = {
  id: RentalItemsFilterKey
  label: string
  dataType: RentalItemFieldDataType
}

export type RentalItemsFilterOptionSet = RentalItemsFilterDefinition & {
  values: string[]
}

export type RentalItemsFiltersState = Partial<
  Record<RentalItemsFilterKey, string[]>
>

export type RentalItemsTableSchema = {
  columns: RentalItemsColumnConfig[]
  filters: RentalItemsFilterDefinition[]
  searchableFieldIds: RentalItemsColumnKey[]
}

export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

const EMPTY_VALUE = "—"
const DEFAULT_VISIBLE_RENTAL_ITEM_COLUMN_IDS = new Set([
  "number",
  "type",
  "dimensions",
  "finishing",
  "category",
  "characteristics",
  "linoleum",
  "status",
])

const INTERNAL_RENTAL_ITEM_FIELD_IDS = new Set([
  "id",
  "warehouseId",
  "warehouseName",
  "categoryId",
  "subcategoryId",
  "typeId",
  "rentalTypeId",
  "dimensionId",
  "finishingId",
  "subcategory",
  "mediaAvailability",
  "contentsItems",
  "passport",
  "tags",
  "lastModifiedDate",
  "lastModifiedBy",
  "createdDate",
  "createdBy",
  "updatedAt",
  "updatedBy",
])

const RENTAL_ITEM_FIELD_DEFINITIONS: RentalItemFieldDefinition[] = [
  {
    id: "number",
    label: "Номер",
    dataType: "text",
    locked: true,
    alwaysShow: true,
    searchable: true,
    size: 120,
    minSize: 90,
    maxSize: 400,
  },
  {
    id: "type",
    label: "Тип",
    dataType: "text",
    searchable: true,
    size: 120,
    minSize: 90,
    maxSize: 400,
  },
  {
    id: "dimensions",
    label: "Габариты",
    dataType: "text",
    searchable: true,
    size: 120,
    minSize: 90,
    maxSize: 400,
  },
  {
    id: "finishing",
    label: "Отделка",
    dataType: "text",
    searchable: true,
    size: 120,
    minSize: 100,
    maxSize: 500,
  },
  {
    id: "category",
    label: "Категория",
    dataType: "text",
    searchable: true,
    size: 140,
    minSize: 110,
    maxSize: 500,
  },
  {
    id: "characteristics",
    label: "Характеристики",
    dataType: "text",
    // Characteristics are represented by an array of UUID/name values. They
    // cannot be discovered by the scalar-field inference below, so the
    // warehouse must always keep this default-visible column in its schema.
    alwaysShow: true,
    searchable: true,
    size: 260,
    minSize: 160,
    maxSize: 700,
  },
  {
    id: "linoleum",
    label: "Линолеум",
    dataType: "boolean",
    size: 120,
    minSize: 90,
    maxSize: 300,
  },
  {
    id: "status",
    label: "Статус",
    dataType: "status",
    size: 160,
    minSize: 130,
    maxSize: 500,
  },
  {
    id: "comment",
    label: "Комментарий",
    dataType: "text",
    searchable: true,
    size: 260,
    minSize: 160,
    maxSize: 800,
  },
  {
    id: "hasPhotos",
    label: "Фото",
    dataType: "photos",
    filterable: false,
    alwaysShow: true,
    size: 110,
    minSize: 90,
    maxSize: 300,
  },
  {
    id: "contents",
    label: "Наполнение",
    dataType: "contents",
    searchable: true,
    size: 320,
    minSize: 220,
    maxSize: 900,
  },
  {
    id: "shipmentDate",
    label: "Дата отгрузки",
    dataType: "date",
    size: 150,
    minSize: 120,
    maxSize: 400,
  },
  {
    id: "tenant",
    label: "Арендатор",
    dataType: "text",
    searchable: true,
    size: 180,
    minSize: 140,
    maxSize: 700,
  },
  {
    id: "price",
    label: "Цена",
    dataType: "currency",
    size: 140,
    minSize: 100,
    maxSize: 400,
  },
  {
    id: "contentsItems",
    label: "Наполнение",
    dataType: "contents",
    column: false,
  },
]

const FIELD_DEFINITION_BY_ID = new Map(
  RENTAL_ITEM_FIELD_DEFINITIONS.map((definition) => [definition.id, definition])
)

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function hasColumnValue(value: unknown) {
  return (
    value === null ||
    value === undefined ||
    typeof value === "string" ||
    typeof value === "number" ||
    typeof value === "boolean"
  )
}

function inferDataType(value: unknown): RentalItemFieldDataType {
  if (typeof value === "number") {
    return "number"
  }

  if (typeof value === "boolean") {
    return "boolean"
  }

  return "text"
}

function humanizeDtoField(fieldId: string) {
  return fieldId
    .replace(/([a-zа-яё])([A-ZА-ЯЁ])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim()
    .replace(/^./, (value) => value.toUpperCase())
}

function toColumnConfig(
  definition: RentalItemFieldDefinition
): RentalItemsColumnConfig {
  const locked = definition.locked === true
  const visible =
    definition.visibleByDefault ??
    DEFAULT_VISIBLE_RENTAL_ITEM_COLUMN_IDS.has(definition.id)

  return {
    id: definition.id,
    label: definition.label,
    visible: locked ? true : visible,
    locked: locked || undefined,
    filterable: definition.filterable !== false,
    searchable: definition.searchable === true,
    dataType: definition.dataType,
    size: definition.size,
    minSize: definition.minSize,
    maxSize: definition.maxSize,
  }
}

function createInferredDefinition(
  fieldId: string,
  value: unknown
): RentalItemFieldDefinition {
  return {
    id: fieldId,
    label: humanizeDtoField(fieldId),
    dataType: inferDataType(value),
    filterable: true,
    searchable: typeof value === "string",
    visibleByDefault: false,
    size: 160,
    minSize: 100,
    maxSize: 700,
  }
}

function canInferRentalItemColumn(fieldId: string) {
  return (
    !INTERNAL_RENTAL_ITEM_FIELD_IDS.has(fieldId) && !/^legacy/i.test(fieldId)
  )
}

function getObservedFieldValues(items: RentalItemDto[]) {
  const valuesByField = new Map<string, unknown>()

  items.forEach((item) => {
    Object.entries(item).forEach(([fieldId, value]) => {
      if (!valuesByField.has(fieldId) && hasColumnValue(value)) {
        valuesByField.set(fieldId, value)
      }
    })
  })

  return valuesByField
}

export function buildRentalItemsTableSchema(
  items: RentalItemDto[]
): RentalItemsTableSchema {
  const observedFieldValues = getObservedFieldValues(items)
  const columns: RentalItemsColumnConfig[] = []
  const knownColumnIds = new Set<string>()

  RENTAL_ITEM_FIELD_DEFINITIONS.forEach((definition) => {
    knownColumnIds.add(definition.id)

    if (definition.column === false) {
      return
    }

    if (
      items.length > 0 &&
      !definition.alwaysShow &&
      !observedFieldValues.has(definition.id)
    ) {
      return
    }

    columns.push(toColumnConfig(definition))
  })

  observedFieldValues.forEach((value, fieldId) => {
    if (knownColumnIds.has(fieldId)) {
      return
    }

    if (!canInferRentalItemColumn(fieldId)) {
      return
    }

    columns.push(toColumnConfig(createInferredDefinition(fieldId, value)))
  })

  const filters = columns
    .filter((column) => column.filterable !== false)
    .map<RentalItemsFilterDefinition>((column) => ({
      id: column.id,
      label: column.label,
      dataType: column.dataType,
    }))

  const searchableFieldIds = columns
    .filter((column) => column.searchable)
    .map((column) => column.id)

  return {
    columns,
    filters,
    searchableFieldIds,
  }
}

export function normalizeRentalItemsColumnConfig(
  schemaColumns: RentalItemsColumnConfig[],
  value: unknown
): RentalItemsColumnConfig[] {
  const savedColumns = Array.isArray(value)
    ? (value as RentalItemsColumnConfig[])
    : []

  const schemaColumnById = new Map(
    schemaColumns.map((column) => [column.id, column])
  )

  const normalizedColumns: RentalItemsColumnConfig[] = []

  savedColumns.forEach((savedColumn) => {
    const schemaColumn = schemaColumnById.get(savedColumn.id)

    if (!schemaColumn) {
      return
    }

    if (normalizedColumns.some((column) => column.id === schemaColumn.id)) {
      return
    }

    const locked = schemaColumn.locked === true

    normalizedColumns.push({
      ...schemaColumn,
      visible: locked ? true : savedColumn.visible !== false,
    })
  })

  schemaColumns.forEach((schemaColumn) => {
    if (normalizedColumns.some((column) => column.id === schemaColumn.id)) {
      return
    }

    normalizedColumns.push({
      ...schemaColumn,
      visible: schemaColumn.locked ? true : schemaColumn.visible,
    })
  })

  const lockedColumns = schemaColumns
    .filter((schemaColumn) => schemaColumn.locked)
    .map((schemaColumn) => {
      const normalizedColumn = normalizedColumns.find(
        (column) => column.id === schemaColumn.id
      )

      return {
        ...schemaColumn,
        ...normalizedColumn,
        visible: true,
        locked: true,
      }
    })

  const movableColumns = normalizedColumns.filter((column) => !column.locked)

  return [...lockedColumns, ...movableColumns]
}

export function getVisibleRentalItemFilterDefinitions(
  schema: RentalItemsTableSchema,
  columnsConfig: RentalItemsColumnConfig[]
): RentalItemsFilterDefinition[] {
  const filterById = new Map(
    schema.filters.map((filter) => [filter.id, filter])
  )

  return columnsConfig
    .filter((column) => column.visible && column.filterable !== false)
    .map((column) => filterById.get(column.id))
    .filter((filter): filter is RentalItemsFilterDefinition => Boolean(filter))
}

export function getRentalItemFieldDefinition(
  fieldId: string
): RentalItemFieldDefinition | undefined {
  return FIELD_DEFINITION_BY_ID.get(fieldId)
}

export function getRentalItemFieldValue(
  item: RentalItemDto,
  key: RentalItemsFilterKey
): unknown {
  if (key === "contents") {
    return formatRentalItemContents(item.contentsItems, item.contents)
  }

  return item[key]
}

export function formatRentalItemFieldValue(
  item: RentalItemDto,
  key: RentalItemsFilterKey
): string {
  const value = getRentalItemFieldValue(item, key)
  const definition = getRentalItemFieldDefinition(key)
  const dataType = definition?.dataType

  if (value === null || value === undefined || value === "") {
    return EMPTY_VALUE
  }

  if (dataType === "status" && typeof value === "string") {
    return RENTAL_ITEM_STATUS_LABEL[value as RentalItemStatus] ?? value
  }

  if (dataType === "boolean" || typeof value === "boolean") {
    return value ? "Да" : "Нет"
  }

  if (dataType === "contents") {
    return formatRentalItemContents(item.contentsItems, item.contents)
  }

  if (dataType === "currency" && typeof value === "number") {
    return new Intl.NumberFormat("ru-RU").format(value)
  }

  if (key === "characteristics" && Array.isArray(value)) {
    return value[0]?.name ?? EMPTY_VALUE
  }

  if (Array.isArray(value) || isRecord(value)) {
    return EMPTY_VALUE
  }

  return String(value)
}

export function getRentalItemFieldLabel(
  item: RentalItemDto,
  key: RentalItemsFilterKey
): string {
  return formatRentalItemFieldValue(item, key)
}

export function getRentalItemFieldLabels(
  item: RentalItemDto,
  key: RentalItemsFilterKey
): string[] {
  if (key === "characteristics") {
    return item.characteristics.map((characteristic) => characteristic.name)
  }

  return [getRentalItemFieldLabel(item, key)]
}

export function getRentalItemSortValue(
  item: RentalItemDto,
  key: RentalItemsColumnKey
): string | number {
  const value = getRentalItemFieldValue(item, key)

  if (typeof value === "number") {
    return value
  }

  return formatRentalItemFieldValue(item, key)
}

export function formatRentalItemContents(
  items: RentalItemContentsItemDto[] | null | undefined,
  fallback?: string | null
): string {
  if (items && items.length > 0) {
    return items.map((item) => `${item.name} ${item.quantity} шт.`).join(", ")
  }

  return fallback ?? EMPTY_VALUE
}
