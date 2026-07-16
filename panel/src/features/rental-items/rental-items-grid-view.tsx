/* eslint-disable react-hooks/incompatible-library -- TanStack Virtual returns imperative helpers that React Compiler intentionally skips. */
import { useEffect, useLayoutEffect, useRef, useState } from "react"
import { useVirtualizer } from "@tanstack/react-virtual"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { cn } from "@/lib/utils"

type RentalItemsGridViewProps = {
  items: RentalItemDto[]
  gridFormat: {
    columns: number
    rows: number
  }
  onOpenPhotos: (item: RentalItemDto) => void
  onOpenItem: (item: RentalItemDto) => void
}

const DEFAULT_GRID_WIDTH = 1200
const DEFAULT_GRID_HEIGHT = 640
const MIN_CARD_HEIGHT = 48
const MIN_ADAPTIVE_CARD_WIDTH = 150
const MIN_ADAPTIVE_CARD_HEIGHT = 176
const CARD_PHOTO_RATIO = 0.75

function getCharacteristics(value: string | null) {
  if (!value?.trim()) return []

  return value
    .split(/[,;\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function getItemPhotos(item: RentalItemDto) {
  if (item.previewPhotoUrls && item.previewPhotoUrls.length > 0) {
    return item.previewPhotoUrls
  }

  if (item.mainPhotoUrl) {
    return [item.mainPhotoUrl]
  }

  return []
}

function getGridGap(size: number) {
  return size >= 5 ? 8 : 12
}

function limitCountBySize(
  requestedCount: number,
  availableSize: number,
  minItemSize: number,
  gap: number
) {
  return Math.max(
    1,
    Math.min(
      requestedCount,
      Math.floor((availableSize + gap) / (minItemSize + gap))
    )
  )
}

export function RentalItemsGridView({
  items,
  gridFormat,
  onOpenPhotos,
  onOpenItem,
}: RentalItemsGridViewProps) {
  const parentRef = useRef<HTMLDivElement | null>(null)
  const [dimensions, setDimensions] = useState({
    width: DEFAULT_GRID_WIDTH,
    height: DEFAULT_GRID_HEIGHT,
  })

  useEffect(() => {
    if (!parentRef.current) {
      return
    }

    const observer = new ResizeObserver(([entry]) => {
      setDimensions({
        width: entry.contentRect.width,
        height: entry.contentRect.height,
      })
    })

    observer.observe(parentRef.current)

    return () => observer.disconnect()
  }, [])

  const requestedColumnCount = Math.max(1, Math.round(gridFormat.columns))
  const requestedVisibleRowCount = Math.max(1, Math.round(gridFormat.rows))
  let gridGap = getGridGap(
    Math.max(requestedColumnCount, requestedVisibleRowCount)
  )
  let columnCount = limitCountBySize(
    requestedColumnCount,
    dimensions.width,
    MIN_ADAPTIVE_CARD_WIDTH,
    gridGap
  )
  let visibleRowCount = limitCountBySize(
    requestedVisibleRowCount,
    dimensions.height,
    MIN_ADAPTIVE_CARD_HEIGHT,
    gridGap
  )
  gridGap = getGridGap(Math.max(columnCount, visibleRowCount))
  columnCount = limitCountBySize(
    requestedColumnCount,
    dimensions.width,
    MIN_ADAPTIVE_CARD_WIDTH,
    gridGap
  )
  visibleRowCount = limitCountBySize(
    requestedVisibleRowCount,
    dimensions.height,
    MIN_ADAPTIVE_CARD_HEIGHT,
    gridGap
  )
  const compactness = Math.max(columnCount, visibleRowCount)
  const columnWidth = dimensions.width / columnCount
  const rowHeight = Math.max(
    MIN_CARD_HEIGHT,
    Math.floor(
      (dimensions.height - gridGap * (visibleRowCount - 1)) / visibleRowCount
    )
  )
  const virtualRowHeight = rowHeight + gridGap
  const detailedDescription = columnWidth >= 220 && compactness <= 3
  const minimumDescriptionHeight = detailedDescription
    ? 92
    : columnWidth >= 176
      ? 48
      : 32
  const photoHeight = Math.max(
    0,
    Math.min(
      Math.floor(rowHeight * CARD_PHOTO_RATIO),
      rowHeight - minimumDescriptionHeight
    )
  )
  const descriptionHeight = rowHeight - photoHeight
  const showStatus = descriptionHeight >= 44 && columnWidth >= 176
  const showDescription = descriptionHeight >= 32
  const showExtraDescription = descriptionHeight >= 72 && detailedDescription
  const showCharacteristics = descriptionHeight >= 84 && detailedDescription
  const rowCount = Math.ceil(items.length / columnCount)

  const rowVirtualizer = useVirtualizer({
    count: rowCount,
    getScrollElement: () => parentRef.current,
    estimateSize: () => virtualRowHeight,
    overscan: 4,
  })

  useLayoutEffect(() => {
    rowVirtualizer.measure()
  }, [columnCount, rowCount, rowVirtualizer, virtualRowHeight])

  return (
    <div
      ref={parentRef}
      data-grid-format={`${columnCount}x${visibleRowCount}`}
      className="min-h-0 flex-1 overflow-auto"
    >
      <div
        className="relative"
        style={{ height: `${rowVirtualizer.getTotalSize()}px` }}
      >
        {rowVirtualizer.getVirtualItems().map((virtualRow) => {
          const startIndex = virtualRow.index * columnCount
          const rowItems = items.slice(startIndex, startIndex + columnCount)

          return (
            <div
              key={virtualRow.key}
              className="absolute left-0 grid w-full"
              style={{
                gridTemplateColumns: `repeat(${columnCount}, minmax(0, 1fr))`,
                gap: `${gridGap}px`,
                height: `${rowHeight}px`,
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              {rowItems.map((item) => {
                const photos = getItemPhotos(item)
                const characteristics = getCharacteristics(item.characteristics)

                return (
                  <div
                    key={item.id}
                    className="flex h-full min-h-0 flex-col overflow-hidden rounded-lg border bg-card"
                  >
                    <div
                      className="shrink-0 overflow-hidden"
                      style={{ height: `${photoHeight}px` }}
                    >
                      <PhotoCarousel
                        photos={photos}
                        photoCount={item.photoCount}
                        showPhotoCount={item.hasPhotos}
                        photoCountClassName="hidden sm:flex"
                        className="h-full w-full"
                        fit="cover"
                        controlsVisibility="mobile-visible"
                        onCenterClick={
                          item.hasPhotos ? () => onOpenPhotos(item) : undefined
                        }
                      />
                    </div>

                    <button
                      type="button"
                      className={cn(
                        "flex min-h-0 w-full min-w-0 flex-1 flex-col justify-center gap-0.5 text-left hover:bg-muted/40",
                        descriptionHeight >= 56 ? "p-2" : "px-2 py-1",
                        compactness >= 5 && "px-1.5"
                      )}
                      onClick={() => onOpenItem(item)}
                    >
                      <div className="flex min-w-0 items-center justify-between gap-2">
                        <div
                          className={cn(
                            "min-w-0 truncate font-semibold",
                            compactness >= 5 && "text-xs"
                          )}
                        >
                          {item.number}
                        </div>

                        {showStatus && (
                          <RentalItemStatusBadge status={item.status} />
                        )}
                      </div>

                      {showDescription ? (
                        <div
                          className={cn(
                            "min-w-0",
                            showCharacteristics && characteristics.length > 0
                              ? "grid grid-cols-[minmax(0,1fr)_minmax(8rem,1fr)] gap-2"
                              : "flex flex-col gap-0.5"
                          )}
                        >
                          <div className="min-w-0">
                            <div className="truncate text-xs text-muted-foreground">
                              {[item.type, item.dimensions, item.category]
                                .filter(Boolean)
                                .join(", ")}
                            </div>
                            {showExtraDescription ? (
                              <div className="truncate text-xs text-muted-foreground">
                                {item.finishing ?? "—"}
                              </div>
                            ) : null}
                          </div>
                          {showCharacteristics && characteristics.length > 0 ? (
                            <div
                              className="flex max-h-11 min-w-0 flex-wrap content-start justify-end gap-1 overflow-hidden"
                              aria-label={`Характеристики: ${characteristics.join(", ")}`}
                            >
                              {characteristics.map((characteristic) => (
                                <span
                                  key={characteristic}
                                  className="max-w-full truncate rounded-full bg-muted px-1.5 py-0.5 text-[10px] leading-3 text-muted-foreground"
                                >
                                  {characteristic}
                                </span>
                              ))}
                            </div>
                          ) : null}
                        </div>
                      ) : null}
                    </button>
                  </div>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}
