import {
  buildRentalItemsTableSchema,
  formatRentalItemContents,
  getRentalItemFieldLabel,
  getRentalItemSearchText,
  getRentalItemSortValue,
  getVisibleRentalItemFilterDefinitions,
  type MoveRentalItemContentToStockPayload,
  type PageResponse,
  type RentalItemContentsItemDto,
  type RentalItemDto,
  type RentalItemPhotoDto,
  type RentalItemPhotoVariantDto,
  type RentalItemsColumnConfig,
  type RentalItemsFilterKey,
  type RentalItemsFilterOptionSet,
  type RentalItemsQueryParams,
  type RentalItemsTableSchema,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import type {
  CreateInventoryRentalItemPayload,
  CreateRentalItemPayload,
  RentalItemCreationPhoto,
} from "@/features/rental-items/model/rental-item-create"
import { hasUnresolvedReturnEquipmentDispositionLowLevel } from "@/features/equipment/return-equipment-disposition-guard"
import { hasActiveWarehouseTransferLowLevel } from "@/features/logistics/warehouse-transfers/active-transfer-guard"

export function canonicalizeRentalItemNumber(value: string) {
  return value
    .trim()
    .replace(/[^\p{L}\p{N}]+/gu, "")
    .toLocaleUpperCase("ru-RU")
}

export const RENTAL_ITEMS_MOCK_STORAGE_KEY = "wms:mock-rental-items"
export const RENTAL_ITEM_PHOTOS_MOCK_STORAGE_KEY = "wms:mock-rental-item-photos"
export const RENTAL_ITEMS_MOCK_UPDATED_EVENT = "wms:mock-rental-items-updated"
export const RENTAL_ITEM_MUTATION_LOCK_NAME = "rwms:rental-item:mutation"
const RENTAL_ITEM_FALLBACK_LOCK_KEY = "rwms:rental-item:mutation:lease"
export const RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES: RentalItemStatus[] = [
  "NEW",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "CAPITAL_REPAIR",
  "AFTER_RENT",
  "SALE",
  "USED_SALE",
  "RESERVED",
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
]

function assertNoActiveWarehouseTransfer(rentalItemId: string) {
  if (hasActiveWarehouseTransferLowLevel(rentalItemId)) {
    throw new Error(
      "Бытовка участвует в межскладском перемещении. Сначала завершите или отмените перемещение"
    )
  }
}

let rentalItemMutationQueue: Promise<void> = Promise.resolve()

/** Shared mock-only boundary for commands that mutate rental-item lifecycle state. */
export function runRentalItemMutation<T>(operation: () => Promise<T>) {
  const withOriginLock = () =>
    typeof navigator !== "undefined" && navigator.locks
      ? navigator.locks.request(RENTAL_ITEM_MUTATION_LOCK_NAME, operation)
      : runRentalItemFallbackLease(operation)
  const result = rentalItemMutationQueue.then(withOriginLock, withOriginLock)
  rentalItemMutationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

async function runRentalItemFallbackLease<T>(operation: () => Promise<T>) {
  if (typeof window === "undefined") return operation()
  const token = `rental-lease-${Date.now()}-${Math.random().toString(36).slice(2)}`
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const now = Date.now()
    let lease: { token: string; expiresAt: number } | null = null
    try {
      lease = JSON.parse(
        window.localStorage.getItem(RENTAL_ITEM_FALLBACK_LOCK_KEY) ?? "null"
      ) as { token: string; expiresAt: number } | null
    } catch {
      // A corrupt lease is treated as expired and may be replaced below.
    }
    if (!lease || lease.expiresAt <= now) {
      window.localStorage.setItem(
        RENTAL_ITEM_FALLBACK_LOCK_KEY,
        JSON.stringify({ token, expiresAt: now + 5_000 })
      )
      if (
        window.localStorage
          .getItem(RENTAL_ITEM_FALLBACK_LOCK_KEY)
          ?.includes(token)
      ) {
        try {
          return await operation()
        } finally {
          if (
            window.localStorage
              .getItem(RENTAL_ITEM_FALLBACK_LOCK_KEY)
              ?.includes(token)
          ) {
            window.localStorage.removeItem(RENTAL_ITEM_FALLBACK_LOCK_KEY)
          }
        }
      }
    }
    await new Promise((resolve) => window.setTimeout(resolve, 10))
  }
  throw new Error("Реестр бытовок занят в другой вкладке")
}

export type MoveRentalItemContentsToRentalItemResult = {
  sourceItem: RentalItemDto
  targetItem: RentalItemDto
}

const mockPhotoUrls = [
  "https://images.unsplash.com/photo-1494526585095-c41746248156?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1518780664697-55e3ad937233?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1564013799919-ab600027ffc6?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1570129477492-45c003edd2be?q=80&w=900&auto=format&fit=crop",
]

function buildMockPhotoVariantUrl(url: string, variant: "small" | "largeWebp") {
  try {
    const nextUrl = new URL(url)

    nextUrl.searchParams.set("auto", "format")
    nextUrl.searchParams.set("fm", "webp")
    nextUrl.searchParams.set("q", variant === "small" ? "70" : "90")
    nextUrl.searchParams.set("w", variant === "small" ? "360" : "1800")

    return nextUrl.toString()
  } catch {
    return url
  }
}

function createMockPhotoDto(params: {
  id: string
  rentalItemId: string
  url: string
  createdAt?: string
}): RentalItemPhotoDto {
  return {
    id: params.id,
    rentalItemId: params.rentalItemId,
    url: params.url,
    variants: {
      small: {
        url: buildMockPhotoVariantUrl(params.url, "small"),
        width: 360,
        mimeType: "image/webp",
      },
      largeWebp: {
        url: buildMockPhotoVariantUrl(params.url, "largeWebp"),
        width: 1800,
        mimeType: "image/webp",
      },
    },
    createdAt: params.createdAt ?? new Date().toISOString(),
    capturedAtKnown: params.createdAt !== undefined,
  }
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()

    reader.onload = () => {
      if (typeof reader.result === "string") {
        resolve(reader.result)
        return
      }

      reject(new Error("Не удалось прочитать файл изображения."))
    }

    reader.onerror = () => {
      reject(new Error("Не удалось прочитать файл изображения."))
    }

    reader.readAsDataURL(file)
  })
}

function loadImage(dataUrl: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()

    image.onload = () => resolve(image)
    image.onerror = () =>
      reject(new Error("Не удалось обработать изображение."))
    image.src = dataUrl
  })
}

async function createWebpVariant(params: {
  sourceDataUrl: string
  rotation: RentalItemCreationPhoto["rotation"]
  maxWidth: number
  quality: number
}): Promise<RentalItemPhotoVariantDto> {
  const image = await loadImage(params.sourceDataUrl)
  const baseWidth = image.naturalWidth || image.width
  const baseHeight = image.naturalHeight || image.height
  const scale = Math.min(1, params.maxWidth / baseWidth)
  const targetWidth = Math.max(1, Math.round(baseWidth * scale))
  const targetHeight = Math.max(1, Math.round(baseHeight * scale))
  const rotated = params.rotation === 90 || params.rotation === 270
  const canvas = document.createElement("canvas")
  const context = canvas.getContext("2d")

  if (!context) {
    throw new Error("Браузер не поддерживает обработку изображения.")
  }

  canvas.width = rotated ? targetHeight : targetWidth
  canvas.height = rotated ? targetWidth : targetHeight

  context.translate(canvas.width / 2, canvas.height / 2)
  context.rotate((params.rotation * Math.PI) / 180)
  context.drawImage(
    image,
    -targetWidth / 2,
    -targetHeight / 2,
    targetWidth,
    targetHeight
  )

  return {
    url: canvas.toDataURL("image/webp", params.quality),
    width: canvas.width,
    height: canvas.height,
    mimeType: "image/webp",
  }
}

async function createRentalItemPhotoUpload(params: {
  id: string
  name: string
  sourceDataUrl: string
  rotation: RentalItemCreationPhoto["rotation"]
  createdAt: string
}): Promise<RentalItemCreationPhoto> {
  const [small, largeWebp] = await Promise.all([
    createWebpVariant({
      sourceDataUrl: params.sourceDataUrl,
      rotation: params.rotation,
      maxWidth: 360,
      quality: 0.72,
    }),
    createWebpVariant({
      sourceDataUrl: params.sourceDataUrl,
      rotation: params.rotation,
      maxWidth: 1800,
      quality: 0.9,
    }),
  ])

  return {
    id: params.id,
    name: params.name,
    sourceDataUrl: params.sourceDataUrl,
    url: largeWebp.url,
    rotation: params.rotation,
    variants: {
      small,
      largeWebp,
    },
    createdAt: params.createdAt,
  }
}

const statuses: RentalItemStatus[] = [
  "FREE",
  "RENTED",
  "AFTER_RENT",
  "WAITING_ESTIMATE_CONFIRMATION",
  "BOOKED",
  "REPAIR",
  "CAPITAL_REPAIR",
  "USED_SALE",
  "WAREHOUSE",
  "OWN_NEEDS",
]

const types = [
  "БК-1",
  "БК-2",
  "БК-3",
  "БК-4",
  "БК-5",
  "БК-6",
  "БК-Склад",
  "БК-Санблок",
  "БК-Модуль из 2х",
  "БК-Модуль из 3",
  "БК-Пост охраны",
]

const finishings = ["ДВП", "ЛДСП", "ПВХ", "ОСБ", "Вагонка", "СМЛО", "Сэндвич"]

const categories = ["Обычная", "ИТР", "Новая", "Санблок"]

const characteristics = [
  "Пластиковое окно",
  "Электрика КК",
  "Электрика КК + УЗО",
  "Электрика КК + УЗО + счётчик",
  "Электрика КК + счётчик",
  "Металлическая дверь, кондиционер",
  "Две лампы",
  "Мама-папа",
]

const dimensions = [
  "2x2",
  "2.4x2",
  "2.4x2.4",
  "2.4x3",
  "2.4x4",
  "2.4x5",
  "2.4x6",
  "3x3",
  "4.8x6",
  "7.2x6",
]

let rentalItemsCache: RentalItemDto[] | null = null
let rentalItemPhotosCache: Record<string, RentalItemPhotoDto[]> | null = null

function createMockId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID()}`
  }

  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function safeParseRentalItemPhotos(
  value: string | null
): Record<string, RentalItemPhotoDto[]> | null {
  if (!value) {
    return null
  }

  try {
    const parsed = JSON.parse(value)

    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null
    }

    return parsed as Record<string, RentalItemPhotoDto[]>
  } catch {
    return null
  }
}

function readRentalItemPhotosMap(): Record<string, RentalItemPhotoDto[]> {
  if (typeof window === "undefined") {
    return rentalItemPhotosCache ?? {}
  }

  const storedPhotos = safeParseRentalItemPhotos(
    window.localStorage.getItem(RENTAL_ITEM_PHOTOS_MOCK_STORAGE_KEY)
  )

  rentalItemPhotosCache = storedPhotos ?? {}

  return rentalItemPhotosCache
}

function writeRentalItemPhotosMap(
  photosByItemId: Record<string, RentalItemPhotoDto[]>
) {
  rentalItemPhotosCache = photosByItemId

  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(
    RENTAL_ITEM_PHOTOS_MOCK_STORAGE_KEY,
    JSON.stringify(photosByItemId)
  )
}

function mapCreationPhotoToDto(
  photo: RentalItemCreationPhoto,
  rentalItemId: string
): RentalItemPhotoDto {
  return {
    id: photo.id,
    rentalItemId,
    url: photo.url,
    variants: photo.variants,
    createdAt: photo.createdAt,
    capturedAtKnown: true,
  }
}

function createMockContentsItems(index: number): RentalItemContentsItemDto[] {
  if (index % 4 === 0) {
    return [
      {
        name: "Стол",
        quantity: 2,
      },
      {
        name: "Стул",
        quantity: 4,
      },
      {
        name: "Шкаф",
        quantity: 1,
      },
    ]
  }

  if (index % 6 === 0) {
    return [
      {
        name: "Кровать 2-ярусная",
        quantity: 3,
      },
      {
        name: "Стол офисный",
        quantity: 2,
      },
    ]
  }

  if (index % 9 === 0) {
    return [
      {
        name: "Кровать",
        quantity: 2,
      },
      {
        name: "Стол офисный",
        quantity: 2,
      },
      {
        name: "Конвектор",
        quantity: 1,
      },
    ]
  }

  return []
}

function createMockItems(warehouseId: string): RentalItemDto[] {
  return Array.from(
    {
      length: warehouseId === "spb" ? 120 : 75,
    },
    (_, index) => {
      const numberIndex = index + 1
      const hasPhotos = index % 3 !== 0
      const status = statuses[index % statuses.length]
      const photoCount = hasPhotos ? (index % 15) + 1 : 0
      const contentsItems = createMockContentsItems(index)

      const previewPhotoUrls = hasPhotos
        ? Array.from(
            {
              length: Math.min(photoCount, 4),
            },
            (_, photoIndex) =>
              mockPhotoUrls[(index + photoIndex) % mockPhotoUrls.length]
          )
        : []

      return {
        id: `${warehouseId}-${numberIndex}`,
        version: 0,
        warehouseId,
        locationNodeId: null,

        number: `БЫТ-${String(numberIndex).padStart(3, "0")}`,
        type: types[index % types.length],
        dimensions: dimensions[index % dimensions.length],
        finishing: finishings[index % finishings.length],
        category: categories[index % categories.length],
        characteristics: characteristics[index % characteristics.length],
        linoleum: index % 2 === 0,

        status,
        comment:
          index % 5 === 0
            ? "Нужна проверка перед выдачей клиенту"
            : index % 7 === 0
              ? "Есть замечания по внутренней отделке"
              : null,

        hasPhotos,
        photoCount,
        mainPhotoUrl: previewPhotoUrls[0] ?? null,
        previewPhotoUrls,

        contents:
          contentsItems.length > 0
            ? formatRentalItemContents(contentsItems)
            : null,
        contentsItems,

        shipmentDate:
          status === "RENTED" || status === "RESERVED"
            ? `2026-05-${String((index % 27) + 1).padStart(2, "0")}`
            : null,

        tenant:
          status === "RENTED" || status === "RESERVED"
            ? index % 2 === 0
              ? "ООО СтройПроект"
              : "ИП Петров А.В."
            : null,

        price: index % 3 === 0 ? 30000 + index * 250 : null,
      }
    }
  )
}

function createInitialRentalItems(): RentalItemDto[] {
  return [...createMockItems("spb"), ...createMockItems("msk")]
}

function delay<T>(data: T, timeout = 250): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(data), timeout)
  })
}

function safeParseRentalItems(value: string | null): RentalItemDto[] | null {
  if (!value) {
    return null
  }

  try {
    const parsed = JSON.parse(value)

    if (!Array.isArray(parsed)) {
      return null
    }

    return parsed as RentalItemDto[]
  } catch {
    return null
  }
}

function normalizeRentalItem(item: RentalItemDto): RentalItemDto {
  const contentsItems = item.contentsItems ?? []

  return {
    ...item,
    version:
      typeof item.version === "number" && Number.isSafeInteger(item.version)
        ? Math.max(0, item.version)
        : 0,
    locationNodeId: item.locationNodeId ?? null,
    contentsItems,
    contents:
      contentsItems.length > 0 ? formatRentalItemContents(contentsItems) : null,
  }
}

function normalizeContentName(value: string) {
  return value.trim().toLowerCase()
}

function normalizeMovePayload(
  payload: MoveRentalItemContentToStockPayload[]
): MoveRentalItemContentToStockPayload[] {
  const quantityByName = new Map<string, MoveRentalItemContentToStockPayload>()

  payload.forEach((item) => {
    const normalizedName = normalizeContentName(item.name)
    const currentItem = quantityByName.get(normalizedName)

    if (!currentItem) {
      quantityByName.set(normalizedName, {
        name: item.name.trim(),
        quantity: Math.max(0, item.quantity),
      })
      return
    }

    quantityByName.set(normalizedName, {
      ...currentItem,
      quantity: currentItem.quantity + Math.max(0, item.quantity),
    })
  })

  return Array.from(quantityByName.values())
}

function getActualMovePayload(
  sourceItem: RentalItemDto,
  payload: MoveRentalItemContentToStockPayload[]
): MoveRentalItemContentToStockPayload[] {
  const normalizedPayload = normalizeMovePayload(payload)

  return sourceItem.contentsItems
    .map((contentItem) => {
      const moveItem = normalizedPayload.find((item) => {
        return (
          normalizeContentName(item.name) ===
          normalizeContentName(contentItem.name)
        )
      })

      if (!moveItem) {
        return null
      }

      const quantity = Math.min(
        Math.max(0, moveItem.quantity),
        contentItem.quantity
      )

      if (quantity <= 0) {
        return null
      }

      return {
        name: contentItem.name,
        quantity,
      }
    })
    .filter(
      (item): item is MoveRentalItemContentToStockPayload => item !== null
    )
}

function subtractContentsItems(
  contentsItems: RentalItemContentsItemDto[],
  payload: MoveRentalItemContentToStockPayload[]
): RentalItemContentsItemDto[] {
  return contentsItems
    .map((contentItem) => {
      const moveItem = payload.find((item) => {
        return (
          normalizeContentName(item.name) ===
          normalizeContentName(contentItem.name)
        )
      })

      if (!moveItem) {
        return contentItem
      }

      return {
        ...contentItem,
        quantity: Math.max(0, contentItem.quantity - moveItem.quantity),
      }
    })
    .filter((contentItem) => contentItem.quantity > 0)
}

function addContentsItems(
  contentsItems: RentalItemContentsItemDto[],
  payload: MoveRentalItemContentToStockPayload[]
): RentalItemContentsItemDto[] {
  let nextContentsItems = [...contentsItems]

  payload.forEach((moveItem) => {
    const targetIndex = nextContentsItems.findIndex((contentItem) => {
      return (
        normalizeContentName(contentItem.name) ===
        normalizeContentName(moveItem.name)
      )
    })

    if (targetIndex === -1) {
      nextContentsItems = [
        ...nextContentsItems,
        {
          name: moveItem.name,
          quantity: moveItem.quantity,
        },
      ]

      return
    }

    nextContentsItems = nextContentsItems.map((contentItem, index) => {
      if (index !== targetIndex) {
        return contentItem
      }

      return {
        ...contentItem,
        quantity: contentItem.quantity + moveItem.quantity,
      }
    })
  })

  return nextContentsItems.filter((contentItem) => contentItem.quantity > 0)
}

export function readRentalItems(): RentalItemDto[] {
  if (typeof window === "undefined") {
    return rentalItemsCache ?? createInitialRentalItems()
  }

  const storedItems = safeParseRentalItems(
    window.localStorage.getItem(RENTAL_ITEMS_MOCK_STORAGE_KEY)
  )

  if (storedItems) {
    const normalizedItems = storedItems.map(normalizeRentalItem)
    rentalItemsCache = normalizedItems

    return normalizedItems
  }

  const initialItems = createInitialRentalItems()
  writeRentalItems(initialItems, false)

  return initialItems
}

export function writeRentalItems(
  items: RentalItemDto[],
  emitEvent = true
): void {
  const normalizedItems = items.map(normalizeRentalItem)

  rentalItemsCache = normalizedItems

  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(
    RENTAL_ITEMS_MOCK_STORAGE_KEY,
    JSON.stringify(normalizedItems)
  )

  if (emitEvent) {
    window.dispatchEvent(new Event(RENTAL_ITEMS_MOCK_UPDATED_EVENT))
  }
}

export async function prepareRentalItemPhotoUpload(
  file: File
): Promise<RentalItemCreationPhoto> {
  const sourceDataUrl = await readFileAsDataUrl(file)

  return createRentalItemPhotoUpload({
    id: createMockId("rental-photo"),
    name: file.name,
    sourceDataUrl,
    rotation: 0,
    createdAt: new Date().toISOString(),
  })
}

export async function rotateRentalItemCreationPhoto(
  photo: RentalItemCreationPhoto
): Promise<RentalItemCreationPhoto> {
  const nextRotation: RentalItemCreationPhoto["rotation"] =
    photo.rotation === 0
      ? 90
      : photo.rotation === 90
        ? 180
        : photo.rotation === 180
          ? 270
          : 0

  return delay(
    await createRentalItemPhotoUpload({
      id: photo.id,
      name: photo.name,
      sourceDataUrl: photo.sourceDataUrl,
      rotation: nextRotation,
      createdAt: photo.createdAt,
    })
  )
}

export async function createRentalItem(
  payload: CreateRentalItemPayload
): Promise<RentalItemDto> {
  const items = readRentalItems()
  const number = payload.number.trim().replace(/\s+/g, " ")
  if (!number) throw new Error("Укажите номер бытовки")
  if (
    items.some(
      (item) =>
        canonicalizeRentalItemNumber(item.number) ===
        canonicalizeRentalItemNumber(number)
    )
  ) {
    throw new Error("Бытовка с таким номером уже существует")
  }
  const tenant = payload.tenant?.trim() || null
  const shipmentDate = payload.shipmentDate ?? null
  if (payload.status === "RENTED" && (!tenant || !shipmentDate)) {
    throw new Error("Для бытовки в аренде укажите контрагента и дату отгрузки")
  }
  const contentsItems = (payload.contentsItems ?? [])
    .filter((item) => item.name.trim() && item.quantity > 0)
    .map((item) => ({
      name: item.name.trim(),
      quantity: Math.floor(item.quantity),
    }))
  const id = createMockId(payload.warehouseId)
  const photoDtos = payload.photos.map((photo) =>
    mapCreationPhotoToDto(photo, id)
  )

  const nextItem: RentalItemDto = normalizeRentalItem({
    id,
    version: 0,
    warehouseId: payload.warehouseId,
    locationNodeId: null,

    number,
    type: payload.type,
    dimensions: payload.dimensions,
    finishing: payload.finishing,
    category: payload.category,
    characteristics:
      payload.characteristics.length > 0
        ? payload.characteristics.join(", ")
        : null,
    linoleum: payload.linoleum,

    status: payload.status,
    comment: null,

    hasPhotos: photoDtos.length > 0,
    photoCount: photoDtos.length,
    mainPhotoUrl:
      photoDtos[0]?.variants?.small?.url ?? photoDtos[0]?.url ?? null,
    previewPhotoUrls: photoDtos.map(
      (photo) => photo.variants?.small?.url ?? photo.url
    ),

    contents:
      contentsItems.length > 0
        ? contentsItems
            .map((item) => `${item.name}: ${item.quantity}`)
            .join(", ")
        : null,
    contentsItems,

    shipmentDate,
    tenant,
    price: null,
  })

  writeRentalItems([nextItem, ...items])

  if (photoDtos.length > 0) {
    writeRentalItemPhotosMap({
      ...readRentalItemPhotosMap(),
      [id]: photoDtos,
    })
  }

  return delay(nextItem)
}

export type RentalItemNumberLookupResult =
  | { kind: "NOT_FOUND"; item: null }
  | {
      kind:
        | "CURRENT_WAREHOUSE"
        | "RENTED_CURRENT_WAREHOUSE"
        | "OTHER_WAREHOUSE"
        | "WRITTEN_OFF"
      item: RentalItemDto
    }

/** @deprecated Use RentalItemNumberLookupResult for new consumers. */
export type InventoryRentalItemLookupResult = RentalItemNumberLookupResult

type InventoryRentalItemSource = {
  sourceInventoryId?: unknown
  sourceInventoryFindingId?: unknown
}

/**
 * Rechecks the global mock registry under the rental-item mutation lock. The
 * DTO has no entity version, so inventory conflict detection compares fields.
 */
export function lookupRentalItemForInventory(params: {
  warehouseId: string
  number: string
}): Promise<RentalItemNumberLookupResult> {
  return runRentalItemMutation(async () => {
    const canonicalNumber = canonicalizeRentalItemNumber(params.number)
    const item = readRentalItems().find(
      (candidate) =>
        canonicalizeRentalItemNumber(candidate.number) === canonicalNumber
    )
    if (!item) return { kind: "NOT_FOUND", item: null }
    if (item.status === "WRITTEN_OFF") return { kind: "WRITTEN_OFF", item }
    if (item.warehouseId !== params.warehouseId)
      return { kind: "OTHER_WAREHOUSE", item }
    if (item.status === "RENTED")
      return { kind: "RENTED_CURRENT_WAREHOUSE", item }
    return { kind: "CURRENT_WAREHOUSE", item }
  })
}

/** Global number lookup shared by intake, inventory and warehouse transfers. */
export function lookupRentalItemByNumber(params: {
  warehouseId: string
  number: string
}): Promise<RentalItemNumberLookupResult> {
  return lookupRentalItemForInventory(params)
}

/** Idempotent inventory source command; no creation photos are persisted here. */
export function createRentalItemForInventory(
  payload: CreateInventoryRentalItemPayload
): Promise<RentalItemDto> {
  return runRentalItemMutation(async () => {
    if (payload.condition === "NEW" && payload.category !== "Новая") {
      throw new Error("Новая бытовка должна иметь категорию «Новая»")
    }
    const items = readRentalItems()
    const bySource = items.find((item) => {
      const source = item as RentalItemDto & InventoryRentalItemSource
      return (
        source.sourceInventoryId === payload.inventoryId &&
        source.sourceInventoryFindingId === payload.findingId
      )
    })
    if (bySource) return bySource
    const canonicalNumber = canonicalizeRentalItemNumber(payload.number)
    const duplicate = items.find(
      (item) => canonicalizeRentalItemNumber(item.number) === canonicalNumber
    )
    if (duplicate) {
      throw new Error("Бытовка с таким номером уже существует")
    }
    const item = normalizeRentalItem({
      id: createMockId(payload.warehouseId),
      version: 0,
      warehouseId: payload.warehouseId,
      locationNodeId: null,
      number: payload.number.trim().replace(/\s+/g, " "),
      type: payload.type,
      dimensions: payload.dimensions,
      finishing: payload.finishing,
      category: payload.category,
      characteristics:
        payload.characteristics.length > 0
          ? payload.characteristics.join(", ")
          : null,
      linoleum: payload.linoleum,
      status: "FREE",
      comment: null,
      hasPhotos: false,
      photoCount: 0,
      mainPhotoUrl: null,
      previewPhotoUrls: [],
      contents: null,
      contentsItems: [],
      shipmentDate: null,
      tenant: null,
      price: null,
      sourceInventoryId: payload.inventoryId,
      sourceInventoryFindingId: payload.findingId,
    })
    writeRentalItems([item, ...items])
    return item
  })
}

export function getRentalItemsForInventoryStart(warehouseId: string) {
  return Promise.resolve(
    readRentalItems().filter(
      (item) =>
        item.warehouseId === warehouseId &&
        RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(item.status)
    )
  )
}

function compareValues(a: unknown, b: unknown) {
  const left = a ?? ""
  const right = b ?? ""

  if (typeof left === "number" && typeof right === "number") {
    return left - right
  }

  return String(left).localeCompare(String(right), "ru")
}

function applySearch(items: RentalItemDto[], search?: string) {
  const normalizedSearch = search?.trim().toLowerCase()

  if (!normalizedSearch) {
    return items
  }

  const schema = buildRentalItemsTableSchema(items)

  return items.filter((item) => {
    return getRentalItemSearchText(item, schema.searchableFieldIds).includes(
      normalizedSearch
    )
  })
}

function applyFilters(
  items: RentalItemDto[],
  filters?: RentalItemsQueryParams["filters"]
) {
  if (!filters) {
    return items
  }

  return items.filter((item) => {
    return Object.entries(filters).every(([key, values]) => {
      if (!values || values.length === 0) {
        return true
      }

      const label = getRentalItemFieldLabel(item, key as RentalItemsFilterKey)

      return values.includes(label)
    })
  })
}

function applySort(items: RentalItemDto[], params: RentalItemsQueryParams) {
  if (!params.sortBy) {
    return items
  }

  const direction = params.sortDirection === "desc" ? -1 : 1

  return [...items].sort((a, b) => {
    return (
      compareValues(
        getRentalItemSortValue(a, params.sortBy!),
        getRentalItemSortValue(b, params.sortBy!)
      ) * direction
    )
  })
}

export async function getRentalItems(
  params: RentalItemsQueryParams
): Promise<PageResponse<RentalItemDto>> {
  const page = params.page ?? 0
  const size = params.size ?? 1000

  let items = readRentalItems().filter((item) => {
    return (
      item.warehouseId === params.warehouseId &&
      !params.excludeStatuses?.includes(item.status)
    )
  })

  items = applySearch(items, params.search)
  items = applyFilters(items, params.filters)
  items = applySort(items, params)

  const start = page * size
  const end = start + size
  const content = items.slice(start, end)

  return delay({
    content,
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
  })
}

export async function getRentalItem(id: string): Promise<RentalItemDto | null> {
  const item = readRentalItems().find((rentalItem) => {
    return rentalItem.id === id
  })

  return delay(item ?? null)
}

export async function updateRentalItemStatusForRepairWorkflow(params: {
  rentalItemId: string
  warehouseId: string
  status: RentalItemStatus
  allowedSourceStatuses?: RentalItemStatus[]
}): Promise<RentalItemDto> {
  return runRentalItemMutation(async () => {
    const items = readRentalItems()
    const existing = items.find(
      (item) =>
        item.id === params.rentalItemId &&
        item.warehouseId === params.warehouseId
    )
    if (!existing) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    assertNoActiveWarehouseTransfer(existing.id)
    if (existing.status === params.status) {
      return delay(existing)
    }
    const defaultAllowedSources: Partial<
      Record<RentalItemStatus, RentalItemStatus[]>
    > = {
      REPAIR: RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
      FREE: RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
      WAITING_REPAIR_CHECK: ["REPAIR", "WAITING_REPAIR_CHECK"],
      WAITING_ESTIMATE_CONFIRMATION: [
        "AFTER_RENT",
        "WAITING_ESTIMATE_CONFIRMATION",
      ],
      WRITTEN_OFF: [...RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES, "WRITTEN_OFF"],
    }
    const allowedSources =
      params.allowedSourceStatuses ?? defaultAllowedSources[params.status]
    if (!allowedSources?.includes(existing.status)) {
      throw new Error(
        `Статус бытовки изменился: переход ${existing.status} → ${params.status} недоступен`
      )
    }
    const saved = normalizeRentalItem({
      ...existing,
      version: existing.version + 1,
      status: params.status,
    })
    writeRentalItems(items.map((item) => (item.id === saved.id ? saved : item)))
    return delay(saved)
  })
}

export const RENTAL_ITEM_MANUAL_STATUSES = [
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
  "RESERVED",
  "USED_SALE",
] as const satisfies readonly RentalItemStatus[]

export type RentalItemManualStatus =
  (typeof RENTAL_ITEM_MANUAL_STATUSES)[number]

export class RentalItemVersionConflictError extends Error {
  constructor() {
    super("Данные бытовки изменились. Обновите страницу и повторите действие.")
    this.name = "RentalItemVersionConflictError"
  }
}

function assertRentalItemVersion(item: RentalItemDto, expectedVersion: number) {
  if (item.version !== expectedVersion) {
    throw new RentalItemVersionConflictError()
  }
}

/**
 * Browser-backed cabin command boundary. The caller owns activity persistence,
 * but an activity may only be appended after this command succeeds.
 */
export async function updateRentalItemManualStatus(params: {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
  status: RentalItemManualStatus
}): Promise<RentalItemDto> {
  return runRentalItemMutation(async () => {
    const items = readRentalItems()
    const existing = items.find(
      (item) =>
        item.id === params.rentalItemId &&
        item.warehouseId === params.warehouseId
    )
    if (!existing) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    assertNoActiveWarehouseTransfer(existing.id)
    assertRentalItemVersion(existing, params.expectedVersion)
    if (existing.status === params.status) {
      throw new Error("Выберите статус, отличный от текущего")
    }

    const blockedStatuses: RentalItemStatus[] = [
      "RENTED",
      "WRITTEN_OFF",
      "REPAIR",
      "WAITING_REPAIR_CHECK",
      "CAPITAL_REPAIR",
      "WAITING_ESTIMATE_CONFIRMATION",
      "AFTER_RENT",
    ]
    if (blockedStatuses.includes(existing.status)) {
      throw new Error(
        "Статус нельзя изменить вручную во время активного рабочего процесса"
      )
    }
    if (!RENTAL_ITEM_MANUAL_STATUSES.includes(params.status)) {
      throw new Error("Выбранный статус нельзя установить вручную")
    }

    const saved = normalizeRentalItem({
      ...existing,
      version: existing.version + 1,
      status: params.status,
    })
    writeRentalItems(items.map((item) => (item.id === saved.id ? saved : item)))
    return delay(saved)
  })
}

export async function updateRentalItemGeneralComment(params: {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
  comment: string
}): Promise<RentalItemDto> {
  return runRentalItemMutation(async () => {
    const items = readRentalItems()
    const existing = items.find(
      (item) =>
        item.id === params.rentalItemId &&
        item.warehouseId === params.warehouseId
    )
    if (!existing) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    assertRentalItemVersion(existing, params.expectedVersion)
    const saved = normalizeRentalItem({
      ...existing,
      version: existing.version + 1,
      comment: params.comment.trim() || null,
    })
    writeRentalItems(items.map((item) => (item.id === saved.id ? saved : item)))
    return delay(saved)
  })
}

export async function incrementRentalItemVersion(params: {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
}): Promise<RentalItemDto> {
  return runRentalItemMutation(async () => {
    const items = readRentalItems()
    const existing = items.find(
      (item) =>
        item.id === params.rentalItemId &&
        item.warehouseId === params.warehouseId
    )
    if (!existing) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    assertRentalItemVersion(existing, params.expectedVersion)
    const saved = normalizeRentalItem({
      ...existing,
      version: existing.version + 1,
    })
    writeRentalItems(items.map((item) => (item.id === saved.id ? saved : item)))
    return delay(saved)
  })
}

export async function getRentalItemPhotos(
  rentalItemId: string
): Promise<RentalItemPhotoDto[]> {
  const storedPhotos = readRentalItemPhotosMap()[rentalItemId]

  if (storedPhotos && storedPhotos.length > 0) {
    return delay(storedPhotos)
  }

  const item = readRentalItems().find((rentalItem) => {
    return rentalItem.id === rentalItemId
  })

  if (!item?.hasPhotos) {
    return delay([])
  }

  return delay(
    Array.from({ length: item.photoCount }, (_, index) => {
      const previewPhotoUrls = item.previewPhotoUrls ?? []

      const url =
        previewPhotoUrls[index % previewPhotoUrls.length] ??
        item.mainPhotoUrl ??
        mockPhotoUrls[index % mockPhotoUrls.length]

      return createMockPhotoDto({
        id: `${rentalItemId}-photo-${index + 1}`,
        rentalItemId,
        url,
      })
    })
  )
}

export async function getRentalItemsTableSchema(
  warehouseId: string
): Promise<RentalItemsTableSchema> {
  const warehouseItems = readRentalItems().filter((item) => {
    return item.warehouseId === warehouseId
  })

  return delay(buildRentalItemsTableSchema(warehouseItems))
}

export async function getRentalItemFilterOptions(params: {
  warehouseId: string
  columnsConfig: RentalItemsColumnConfig[]
}): Promise<RentalItemsFilterOptionSet[]> {
  const warehouseItems = readRentalItems().filter((item) => {
    return item.warehouseId === params.warehouseId
  })

  const schema = buildRentalItemsTableSchema(warehouseItems)
  const columnsConfig =
    params.columnsConfig.length > 0 ? params.columnsConfig : schema.columns

  const filters = getVisibleRentalItemFilterDefinitions(schema, columnsConfig)

  const result = filters.map<RentalItemsFilterOptionSet>((filter) => ({
    ...filter,
    values: Array.from(
      new Set(
        warehouseItems.map((item) =>
          getRentalItemFieldLabel(item, filter.id as RentalItemsFilterKey)
        )
      )
    ).sort((a, b) => a.localeCompare(b, "ru")),
  }))

  return delay(result)
}

export async function moveRentalItemContentsToStock(
  rentalItemId: string,
  payload: MoveRentalItemContentToStockPayload[]
): Promise<RentalItemDto | null> {
  const items = readRentalItems()

  const itemIndex = items.findIndex((item) => {
    return item.id === rentalItemId
  })

  if (itemIndex === -1) {
    return delay(null)
  }

  const currentItem = items[itemIndex]
  assertNoActiveWarehouseTransfer(currentItem.id)
  const actualMovePayload = getActualMovePayload(currentItem, payload)

  if (actualMovePayload.length === 0) {
    return delay(currentItem)
  }

  const nextItem: RentalItemDto = normalizeRentalItem({
    ...currentItem,
    version: currentItem.version + 1,
    contentsItems: subtractContentsItems(
      currentItem.contentsItems,
      actualMovePayload
    ),
  })

  const nextItems = items.map((item, index) => {
    return index === itemIndex ? nextItem : item
  })

  writeRentalItems(nextItems)

  return delay(nextItem)
}

export async function moveRentalItemContentsToRentalItem(params: {
  sourceRentalItemId: string
  targetRentalItemId: string
  expectedSourceVersion?: number
  expectedTargetVersion?: number
  payload: MoveRentalItemContentToStockPayload[]
}): Promise<MoveRentalItemContentsToRentalItemResult | null> {
  return runRentalItemMutation(async () => {
    if (
      hasUnresolvedReturnEquipmentDispositionLowLevel(
        params.sourceRentalItemId
      ) ||
      hasUnresolvedReturnEquipmentDispositionLowLevel(params.targetRentalItemId)
    ) {
      throw new Error(
        "Оборудование выбранной бытовки ожидает решения в разделе списания"
      )
    }
    assertNoActiveWarehouseTransfer(params.sourceRentalItemId)
    assertNoActiveWarehouseTransfer(params.targetRentalItemId)
    const items = readRentalItems()

    if (params.sourceRentalItemId === params.targetRentalItemId) {
      throw new Error("Нельзя переместить наполнение в ту же бытовку")
    }

    const sourceIndex = items.findIndex((item) => {
      return item.id === params.sourceRentalItemId
    })

    const targetIndex = items.findIndex((item) => {
      return item.id === params.targetRentalItemId
    })

    if (sourceIndex === -1 || targetIndex === -1) {
      return delay(null)
    }

    const sourceItem = items[sourceIndex]
    const targetItem = items[targetIndex]

    if (sourceItem.warehouseId !== targetItem.warehouseId) {
      return delay(null)
    }
    if (
      !RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(sourceItem.status) ||
      !RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(targetItem.status)
    ) {
      throw new Error(
        "Перемещение доступно только для активных бытовок на складе"
      )
    }
    if (params.expectedSourceVersion !== undefined) {
      assertRentalItemVersion(sourceItem, params.expectedSourceVersion)
    }
    if (params.expectedTargetVersion !== undefined) {
      assertRentalItemVersion(targetItem, params.expectedTargetVersion)
    }

    const requestedPayload = normalizeMovePayload(params.payload)
    requestedPayload.forEach((requestedItem) => {
      const availableItem = sourceItem.contentsItems.find(
        (contentItem) =>
          normalizeContentName(contentItem.name) ===
          normalizeContentName(requestedItem.name)
      )
      if (
        !Number.isInteger(requestedItem.quantity) ||
        requestedItem.quantity <= 0 ||
        !availableItem ||
        requestedItem.quantity > availableItem.quantity
      ) {
        throw new Error(
          `Недоступное количество позиции «${requestedItem.name}»`
        )
      }
    })

    const actualMovePayload = getActualMovePayload(sourceItem, requestedPayload)

    if (actualMovePayload.length === 0) {
      return delay({
        sourceItem,
        targetItem,
      })
    }

    const nextSourceItem: RentalItemDto = normalizeRentalItem({
      ...sourceItem,
      version: sourceItem.version + 1,
      contentsItems: subtractContentsItems(
        sourceItem.contentsItems,
        actualMovePayload
      ),
    })

    const nextTargetItem: RentalItemDto = normalizeRentalItem({
      ...targetItem,
      version: targetItem.version + 1,
      contentsItems: addContentsItems(
        targetItem.contentsItems,
        actualMovePayload
      ),
    })

    const nextItems = items.map((item, index) => {
      if (index === sourceIndex) {
        return nextSourceItem
      }

      if (index === targetIndex) {
        return nextTargetItem
      }

      return item
    })

    writeRentalItems(nextItems)

    return delay({
      sourceItem: nextSourceItem,
      targetItem: nextTargetItem,
    })
  })
}

/** Applies a task-registered stale-ledger reconciliation to the destination only. */
export async function addReconciledContentsToRentalItem(params: {
  reconciliationKey: string
  targetRentalItemId: string
  warehouseId: string
  expectedTargetVersion: number
  payload: MoveRentalItemContentToStockPayload[]
}) {
  return runRentalItemMutation(async () => {
    const items = readRentalItems()
    const index = items.findIndex(
      (item) =>
        item.id === params.targetRentalItemId &&
        item.warehouseId === params.warehouseId
    )
    if (index < 0) throw new Error("Бытовка назначения не найдена")
    const target = items[index]
    assertNoActiveWarehouseTransfer(target.id)
    const appliedKeys = Array.isArray(target.appliedReconciliationKeys)
      ? (target.appliedReconciliationKeys as string[])
      : []
    if (appliedKeys.includes(params.reconciliationKey)) return target
    assertRentalItemVersion(target, params.expectedTargetVersion)
    if (!RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(target.status))
      throw new Error("Бытовка назначения недоступна")
    const payload = normalizeMovePayload(params.payload)
    if (!payload.length) throw new Error("Укажите наполнение для перемещения")
    const saved = normalizeRentalItem({
      ...target,
      version: target.version + 1,
      contentsItems: addContentsItems(target.contentsItems, payload),
      appliedReconciliationKeys: [...appliedKeys, params.reconciliationKey],
    })
    writeRentalItems(
      items.map((item, itemIndex) => (itemIndex === index ? saved : item))
    )
    return saved
  })
}

export async function getRentalItemsForContentsMove(params: {
  warehouseId: string
  sourceRentalItemId: string
  requireContents?: boolean
}): Promise<RentalItemDto[]> {
  const items = readRentalItems()
    .filter((item) => {
      return (
        item.warehouseId === params.warehouseId &&
        item.id !== params.sourceRentalItemId &&
        !hasActiveWarehouseTransferLowLevel(item.id) &&
        !hasUnresolvedReturnEquipmentDispositionLowLevel(item.id) &&
        RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(item.status) &&
        (!params.requireContents ||
          item.contentsItems.some((contentItem) => contentItem.quantity > 0))
      )
    })
    .sort((left, right) => {
      return left.number.localeCompare(right.number, "ru", {
        numeric: true,
      })
    })

  return delay(items)
}

export async function getRentalItemsForEquipmentInventory(
  warehouseId: string
): Promise<RentalItemDto[]> {
  const items = readRentalItems().filter((item) => {
    return item.warehouseId === warehouseId
  })

  return delay(items)
}
