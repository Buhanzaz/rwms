import type {
  RentalItemContentsItemDto,
  RentalItemPhotoVariantDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

export const NEW_RENTAL_ITEM_CATEGORY = "Новая"
export const NEW_RENTAL_ITEM_STATUS = "NEW" satisfies RentalItemStatus
export const INVENTORY_RENTAL_ITEM_CATEGORIES = [
  "Новая",
  "ИТР",
  "Обычная",
] as const
export const SANBLOCK_RENTAL_ITEM_TYPE = "БК-Санблок"
export const PLASTIC_WINDOW_CHARACTERISTIC = "Пластиковое окно"

export const RENTAL_ITEM_TYPE_OPTIONS = [
  "БК-1",
  "БК-2",
  "БК-3",
  "БК-4",
  "БК-5",
  "БК-6",
  "БК-Склад",
  SANBLOCK_RENTAL_ITEM_TYPE,
  "БК-Модуль из 2х",
  "БК-Модуль из 3",
  "БК-Пост охраны",
] as const

export type RentalItemCreationType = (typeof RENTAL_ITEM_TYPE_OPTIONS)[number]

export const RENTAL_ITEM_DIMENSION_OPTIONS = [
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
] as const

export const RENTAL_ITEM_DIMENSIONS_BY_TYPE: Record<
  RentalItemCreationType,
  readonly (typeof RENTAL_ITEM_DIMENSION_OPTIONS)[number][]
> = {
  "БК-1": ["2.4x6"],
  "БК-2": ["2.4x6"],
  "БК-3": ["2.4x6"],
  "БК-4": ["2.4x6"],
  "БК-5": ["2.4x6"],
  "БК-6": ["2.4x6"],
  "БК-Склад": ["2.4x6"],
  [SANBLOCK_RENTAL_ITEM_TYPE]: ["2.4x6"],
  "БК-Модуль из 2х": ["4.8x6"],
  "БК-Модуль из 3": ["7.2x6"],
  "БК-Пост охраны": [
    "2x2",
    "2.4x2",
    "2.4x2.4",
    "2.4x3",
    "2.4x4",
    "2.4x5",
    "3x3",
  ],
}

export const RENTAL_ITEM_FINISHING_OPTIONS = [
  "ДВП",
  "ЛДСП",
  "ПВХ",
  "ОСБ",
  "Вагонка",
  "СМЛО",
  "Сэндвич",
] as const

export type RentalItemFinishing = (typeof RENTAL_ITEM_FINISHING_OPTIONS)[number]

export const RENTAL_ITEM_CHARACTERISTIC_OPTIONS = [
  PLASTIC_WINDOW_CHARACTERISTIC,
  "Электрика КК",
  "Электрика КК + УЗО",
  "Электрика КК + УЗО + счётчик",
  "Электрика КК + счётчик",
  "Металлическая дверь",
  "Кондиционер",
  "Две лампы",
  "Мама-папа",
] as const

export type RentalItemCharacteristic =
  (typeof RENTAL_ITEM_CHARACTERISTIC_OPTIONS)[number]

export const DEFAULT_RENTAL_ITEM_CHARACTERISTICS = [
  PLASTIC_WINDOW_CHARACTERISTIC,
] satisfies RentalItemCharacteristic[]

export type SanblockSettings = {
  toilets: number
  sinks: number
  showers: number
}

export type RentalItemCreationPhoto = {
  id: string
  name: string
  sourceDataUrl: string
  url: string
  rotation: 0 | 90 | 180 | 270
  variants: {
    small: RentalItemPhotoVariantDto
    largeWebp: RentalItemPhotoVariantDto
  }
  createdAt: string
}

export type CreateRentalItemPayload = {
  warehouseId: string
  number: string
  type: RentalItemCreationType
  dimensions: string
  finishing: RentalItemFinishing
  category: string
  characteristics: string[]
  photos: RentalItemCreationPhoto[]
  linoleum: boolean
  status: RentalItemStatus
  contentsItems?: RentalItemContentsItemDto[]
  shipmentDate?: string | null
  tenant?: string | null
}

export type CreateInventoryRentalItemPayload = {
  warehouseId: string
  inventoryId: string
  findingId: string
  condition: "NEW" | "USED"
  number: string
  type: RentalItemCreationType
  dimensions: string
  finishing: RentalItemFinishing
  category: (typeof INVENTORY_RENTAL_ITEM_CATEGORIES)[number]
  characteristics: string[]
  linoleum: boolean
}

export function getRentalItemDimensionsForType(
  type: RentalItemCreationType | ""
) {
  if (!type) {
    return []
  }

  return RENTAL_ITEM_DIMENSIONS_BY_TYPE[type]
}

export function getDefaultRentalItemDimensions(
  type: RentalItemCreationType | ""
) {
  return getRentalItemDimensionsForType(type)[0] ?? ""
}

export function getDefaultRentalItemFinishing(
  type: RentalItemCreationType | "",
  currentFinishing: RentalItemFinishing | ""
): RentalItemFinishing | "" {
  if (type === SANBLOCK_RENTAL_ITEM_TYPE) {
    return "ПВХ"
  }

  return currentFinishing
}

export function getDefaultRentalItemCharacteristics(
  currentCharacteristics: RentalItemCharacteristic[]
): RentalItemCharacteristic[] {
  return Array.from(
    new Set([...currentCharacteristics, ...DEFAULT_RENTAL_ITEM_CHARACTERISTICS])
  )
}

export function isSanblockRentalItemType(type: RentalItemCreationType | "") {
  return type === SANBLOCK_RENTAL_ITEM_TYPE
}

export function buildRentalItemCharacteristics(params: {
  selectedCharacteristics: RentalItemCharacteristic[]
  type: RentalItemCreationType
  sanblockSettings: SanblockSettings
}) {
  const characteristics: string[] = [...params.selectedCharacteristics]

  if (isSanblockRentalItemType(params.type)) {
    characteristics.push(`Туалеты: ${params.sanblockSettings.toilets}`)
    characteristics.push(`Раковины: ${params.sanblockSettings.sinks}`)
    characteristics.push(`Душевые: ${params.sanblockSettings.showers}`)
  }

  return characteristics
}
