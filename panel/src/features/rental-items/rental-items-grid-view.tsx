/* eslint-disable react-hooks/incompatible-library -- TanStack Virtual returns imperative helpers that React Compiler intentionally skips. */
import { useEffect, useRef, useState } from "react"
import { useVirtualizer } from "@tanstack/react-virtual"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

type RentalItemsGridViewProps = {
  items: RentalItemDto[]
  onOpenPhotos: (item: RentalItemDto) => void
  onOpenItem: (item: RentalItemDto) => void
}

const CARD_WIDTH = 320
const GRID_GAP = 16
const GRID_ROW_HEIGHT = 380

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
  onOpenPhotos,
  onOpenItem,
}: RentalItemsGridViewProps) {
  const parentRef = useRef<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(1200)

  useEffect(() => {
    if (!parentRef.current) {
      return
    }

    const observer = new ResizeObserver(([entry]) => {
      setWidth(entry.contentRect.width)
    })

    observer.observe(parentRef.current)

    return () => observer.disconnect()
  }, [])

  const columnCount = Math.max(1, Math.floor((width + GRID_GAP) / CARD_WIDTH))
  const rowCount = Math.ceil(items.length / columnCount)

  const rowVirtualizer = useVirtualizer({
    count: rowCount,
    getScrollElement: () => parentRef.current,
    estimateSize: () => GRID_ROW_HEIGHT,
    overscan: 4,
  })

  return (
    <div ref={parentRef} className="min-h-0 flex-1 overflow-auto">
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
              className="absolute left-0 grid w-full gap-4"
              style={{
                gridTemplateColumns: `repeat(${columnCount}, minmax(0, 1fr))`,
                height: `${GRID_ROW_HEIGHT - GRID_GAP}px`,
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              {rowItems.map((item) => {
                const photos = getItemPhotos(item)

                return (
                  <div
                    key={item.id}
                    className="h-[364px] overflow-hidden rounded-lg border bg-card"
                  >
                    <PhotoCarousel
                      photos={photos}
                      photoCount={item.photoCount}
                      showPhotoCount={item.hasPhotos}
                      photoCountClassName="hidden sm:flex"
                      className="h-36 w-full"
                      fit="cover"
                      controlsVisibility="mobile-visible"
                      onCenterClick={
                        item.hasPhotos ? () => onOpenPhotos(item) : undefined
                      }
                    />

                    <button
                      type="button"
                      className="block w-full p-3 text-left hover:bg-muted/40"
                      onClick={() => onOpenItem(item)}
                    >
                      <div className="mb-3 flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate font-semibold">
                            {item.number}
                          </div>
                          <div className="truncate text-xs text-muted-foreground">
                            {item.type}
                          </div>
                        </div>

                        <RentalItemStatusBadge status={item.status} />
                      </div>

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
                          <span className="truncate">{item.tenant ?? "—"}</span>
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
