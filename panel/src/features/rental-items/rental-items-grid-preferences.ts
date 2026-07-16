import { normalizeRentalItemsGridSize } from "@/features/rental-items/rental-items-grid-format"

export const DOSSIER_PHOTO_GRID_SIZE_PREFERENCE_KEY =
  "rental-item-dossier:photo-grid-format:v1"

export function readRentalItemsGridSizePreference(
  key: string,
  maxSize: number
) {
  try {
    const value = window.localStorage.getItem(key)
    if (value === null) return null
    return normalizeRentalItemsGridSize(JSON.parse(value), maxSize)
  } catch {
    return null
  }
}

export function writeRentalItemsGridSizePreference(
  key: string,
  value: number,
  maxSize: number
) {
  const normalized = normalizeRentalItemsGridSize(value, maxSize)
  if (normalized === null) return
  window.localStorage.setItem(key, JSON.stringify(normalized))
}
