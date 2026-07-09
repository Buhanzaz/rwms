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
  CreateRentalItemPayload,
  RentalItemCreationPhoto,
} from "@/features/rental-items/model/rental-item-create"

export const RENTAL_ITEMS_MOCK_STORAGE_KEY = "wms:mock-rental-items"
export const RENTAL_ITEM_PHOTOS_MOCK_STORAGE_KEY = "wms:mock-rental-item-photos"
export const RENTAL_ITEMS_MOCK_UPDATED_EVENT = "wms:mock-rental-items-updated"

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
  const id = createMockId(payload.warehouseId)
  const photoDtos = payload.photos.map((photo) =>
    mapCreationPhotoToDto(photo, id)
  )

  const nextItem: RentalItemDto = normalizeRentalItem({
    id,
    warehouseId: payload.warehouseId,
    locationNodeId: null,

    number: payload.number.trim(),
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

    contents: null,
    contentsItems: [],

    shipmentDate: null,
    tenant: null,
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
    return item.warehouseId === params.warehouseId
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
  const actualMovePayload = getActualMovePayload(currentItem, payload)

  if (actualMovePayload.length === 0) {
    return delay(currentItem)
  }

  const nextItem: RentalItemDto = normalizeRentalItem({
    ...currentItem,
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
  payload: MoveRentalItemContentToStockPayload[]
}): Promise<MoveRentalItemContentsToRentalItemResult | null> {
  const items = readRentalItems()

  if (params.sourceRentalItemId === params.targetRentalItemId) {
    const item = items.find((rentalItem) => {
      return rentalItem.id === params.sourceRentalItemId
    })

    return delay(
      item
        ? {
            sourceItem: item,
            targetItem: item,
          }
        : null
    )
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

  const actualMovePayload = getActualMovePayload(sourceItem, params.payload)

  if (actualMovePayload.length === 0) {
    return delay({
      sourceItem,
      targetItem,
    })
  }

  const nextSourceItem: RentalItemDto = normalizeRentalItem({
    ...sourceItem,
    contentsItems: subtractContentsItems(
      sourceItem.contentsItems,
      actualMovePayload
    ),
  })

  const nextTargetItem: RentalItemDto = normalizeRentalItem({
    ...targetItem,
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
}

export async function getRentalItemsForContentsMove(params: {
  warehouseId: string
  sourceRentalItemId: string
}): Promise<RentalItemDto[]> {
  const items = readRentalItems()
    .filter((item) => {
      return (
        item.warehouseId === params.warehouseId &&
        item.id !== params.sourceRentalItemId
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
