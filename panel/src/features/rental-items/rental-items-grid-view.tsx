/* eslint-disable react-hooks/incompatible-library -- TanStack Virtual returns imperative helpers that React Compiler intentionally skips. */
import { useEffect, useRef, useState } from "react"
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

function getItemPhotos(item: RentalItemDto) {
  if (item.previewPhotoUrls && item.previewPhotoUrls.length > 0) {
    return item.previewPhotoUrls
  }

  if (item.mainPhotoUrl) {
    return [item.mainPhotoUrl]
  }

  return []
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

  const columnCount = Math.max(1, Math.round(gridFormat.columns))
  const visibleRowCount = Math.max(1, Math.round(gridFormat.rows))
  const compactness = Math.max(columnCount, visibleRowCount)
  const columnWidth = dimensions.width / columnCount
  const gridGap = compactness >= 7 ? 6 : compactness >= 5 ? 8 : 12
  const rowHeight = Math.max(
    MIN_CARD_HEIGHT,
    Math.floor(
      (dimensions.height - gridGap * (visibleRowCount - 1)) / visibleRowCount
    )
  )
  const virtualRowHeight = rowHeight + gridGap
  const showImage = rowHeight >= 112 && compactness <= 5
  const showStatus = rowHeight >= 92 && columnWidth >= 150
  const showDetails = rowHeight >= 240 && columnWidth >= 220 && compactness <= 2
  const photoHeight = showImage
    ? Math.min(compactness <= 2 ? 144 : 92, Math.floor(rowHeight * 0.46))
    : 0
  const rowCount = Math.ceil(items.length / columnCount)

  const rowVirtualizer = useVirtualizer({
    count: rowCount,
    getScrollElement: () => parentRef.current,
    estimateSize: () => virtualRowHeight,
    overscan: 4,
  })

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

                return (
                  <div
                    key={item.id}
                    className="h-full min-h-0 overflow-hidden rounded-lg border bg-card"
                  >
                    {showImage && (
                      <div style={{ height: `${photoHeight}px` }}>
                        <PhotoCarousel
                          photos={photos}
                          photoCount={item.photoCount}
                          showPhotoCount={item.hasPhotos}
                          photoCountClassName="hidden sm:flex"
                          className="h-full w-full"
                          fit="cover"
                          controlsVisibility="mobile-visible"
                          onCenterClick={
                            item.hasPhotos
                              ? () => onOpenPhotos(item)
                              : undefined
                          }
                        />
                      </div>
                    )}

                    <button
                      type="button"
                      className={cn(
                        "block w-full min-w-0 text-left hover:bg-muted/40",
                        showImage ? "p-2" : "h-full p-2",
                        compactness >= 7 && "p-1.5"
                      )}
                      onClick={() => onOpenItem(item)}
                    >
                      <div
                        className={cn(
                          "flex min-w-0 items-start justify-between gap-2",
                          showDetails && "mb-3"
                        )}
                      >
                        <div className="min-w-0">
                          <div
                            className={cn(
                              "truncate font-semibold",
                              compactness >= 7 && "text-[0.6875rem]"
                            )}
                          >
                            {item.number}
                          </div>
                          {rowHeight >= 76 && (
                            <div className="truncate text-xs text-muted-foreground">
                              {item.type}
                            </div>
                          )}
                        </div>

                        {showStatus && (
                          <RentalItemStatusBadge status={item.status} />
                        )}
                      </div>

                      {showDetails && (
                        <div className="grid gap-1 text-xs">
                          <div className="flex justify-between gap-2">
                            <span className="text-muted-foreground">
                              Отделка:
                            </span>
                            <span className="truncate">
                              {item.finishing ?? "—"}
                            </span>
                          </div>

                          <div className="flex justify-between gap-2">
                            <span className="text-muted-foreground">
                              Категория:
                            </span>
                            <span className="truncate">
                              {item.category ?? "—"}
                            </span>
                          </div>

                          <div className="flex justify-between gap-2">
                            <span className="text-muted-foreground">
                              Арендатор:
                            </span>
                            <span className="truncate">
                              {item.tenant ?? "—"}
                            </span>
                          </div>

                          <div className="flex justify-between gap-2">
                            <span className="text-muted-foreground">
                              Дата отгрузки:
                            </span>
                            <span className="truncate">
                              {item.shipmentDate ?? "—"}
                            </span>
                          </div>

                          <div className="flex justify-between gap-2">
                            <span className="text-muted-foreground">
                              Наполнение:
                            </span>
                            <span className="truncate">
                              {item.contents ?? "—"}
                            </span>
                          </div>
                        </div>
                      )}
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
