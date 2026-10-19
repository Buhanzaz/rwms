import { useMemo, useSyncExternalStore } from "react"

const TABLET_GRID_FORMAT_MAX = 3
const DESKTOP_GRID_FORMAT_MAX = 5
const TABLET_BREAKPOINT = 768
const DESKTOP_BREAKPOINT = 1280
const LANDSCAPE_PHONE_WIDTH_MAX = 1024
const LANDSCAPE_PHONE_HEIGHT_MAX = 500

export type RentalItemsGridFormat = {
  columns: number
  rows: number
}

export type RentalItemsViewportSnapshot = {
  width: number
  height: number
}

function getViewportSnapshotKey() {
  return `${window.innerWidth}:${window.innerHeight}`
}

function parseViewportSnapshot(
  snapshotKey: string
): RentalItemsViewportSnapshot {
  const [width, height] = snapshotKey.split(":").map(Number)

  return { width, height }
}

function subscribeViewport(callback: () => void) {
  window.addEventListener("resize", callback)
  window.addEventListener("orientationchange", callback)

  return () => {
    window.removeEventListener("resize", callback)
    window.removeEventListener("orientationchange", callback)
  }
}

function isLandscapePhoneViewport(viewport: RentalItemsViewportSnapshot) {
  return (
    viewport.width >= TABLET_BREAKPOINT &&
    viewport.width < LANDSCAPE_PHONE_WIDTH_MAX &&
    viewport.height <= LANDSCAPE_PHONE_HEIGHT_MAX
  )
}

export function isRentalItemsMobileViewport(
  viewport: RentalItemsViewportSnapshot
) {
  return (
    viewport.width < TABLET_BREAKPOINT || isLandscapePhoneViewport(viewport)
  )
}

export function getRentalItemsDefaultGridSize(
  viewport: RentalItemsViewportSnapshot
) {
  if (viewport.width >= DESKTOP_BREAKPOINT) {
    return 5
  }

  if (!isRentalItemsMobileViewport(viewport)) {
    return 3
  }

  return 1
}

export function getRentalItemsGridFormatMax(
  viewport: RentalItemsViewportSnapshot
) {
  if (isRentalItemsMobileViewport(viewport)) {
    return 1
  }

  if (viewport.width >= DESKTOP_BREAKPOINT) {
    return DESKTOP_GRID_FORMAT_MAX
  }

  return TABLET_GRID_FORMAT_MAX
}

export function normalizeRentalItemsGridSize(value: unknown, maxSize: number) {
  if (value === null || value === undefined || value === "") {
    return null
  }

  const numericValue = Number(value)

  if (!Number.isFinite(numericValue)) {
    return null
  }

  return Math.max(1, Math.min(maxSize, Math.round(numericValue)))
}

export function getEffectiveRentalItemsGridFormat(
  savedGridSize: number | null,
  viewport: RentalItemsViewportSnapshot
): RentalItemsGridFormat {
  if (isRentalItemsMobileViewport(viewport)) {
    return { columns: 1, rows: 1 }
  }

  const maxSize = getRentalItemsGridFormatMax(viewport)
  const size =
    normalizeRentalItemsGridSize(savedGridSize, maxSize) ??
    getRentalItemsDefaultGridSize(viewport)

  return {
    columns: size,
    rows: size,
  }
}

export function useRentalItemsGridViewport() {
  const viewportSnapshotKey = useSyncExternalStore(
    subscribeViewport,
    getViewportSnapshotKey,
    getViewportSnapshotKey
  )

  return useMemo(
    () => parseViewportSnapshot(viewportSnapshotKey),
    [viewportSnapshotKey]
  )
}
