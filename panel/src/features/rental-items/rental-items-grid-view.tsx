/* eslint-disable react-hooks/incompatible-library -- TanStack Virtual returns imperative helpers that React Compiler intentionally skips. */
import {
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { useVirtualizer } from "@tanstack/react-virtual"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Button } from "@/components/ui/button"
import type { CabinCoverProjection } from "@/features/media/media-service"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  useRentalItemCardPhotos,
  type RentalItemCoverAvailability,
} from "@/features/rental-items/use-rental-item-covers"
import { cn } from "@/lib/utils"

export type RentalItemsGridViewProps = {
  items: RentalItemDto[]
  gridFormat: {
    columns: number
    rows: number
  }
  onOpenPhotos: (item: RentalItemDto) => void
  onOpenItem: (item: RentalItemDto) => void
  accessToken: string
  mediaCovers: ReadonlyMap<string, CabinCoverProjection>
  coverAvailability?: RentalItemCoverAvailability
  /** Fits the viewport to the configured visible rows instead of its flex parent. */
  autoHeight?: boolean
  renderPhotoOverlay?: (item: RentalItemDto) => ReactNode
  renderItemActions?: (item: RentalItemDto) => ReactNode
}

const DEFAULT_GRID_WIDTH = 1200
const DEFAULT_GRID_HEIGHT = 640
const MIN_CARD_HEIGHT = 48
const MIN_ADAPTIVE_CARD_WIDTH = 150

function RentalItemCardPhoto({
  item,
  accessToken,
  projection,
  coverAvailability = "available",
  onOpenPhotos,
}: {
  item: RentalItemDto
  accessToken: string
  projection: CabinCoverProjection | undefined
  coverAvailability: RentalItemCoverAvailability
  onOpenPhotos: (item: RentalItemDto) => void
}) {
  const servicePhotoResult = useRentalItemCardPhotos({
    accessToken,
    cabinId: item.id,
    warehouseId: item.warehouseId,
    projection,
    coverAvailability,
  })
  const servicePhotos = servicePhotoResult.photos.map((photo) => ({
    id: photo.id,
    url: photo.url,
    variants: { small: { url: photo.url } },
  }))
  const photoCount = Math.max(projection?.photoCount ?? 0, servicePhotos.length)
  const effectiveAvailability =
    coverAvailability === "unavailable" ||
    servicePhotoResult.availability === "unavailable"
      ? "unavailable"
      : coverAvailability === "loading" ||
          servicePhotoResult.availability === "loading"
        ? "loading"
        : "available"

  if (servicePhotos.length === 0) {
    const placeholder =
      effectiveAvailability === "unavailable"
        ? "Сервис фото недоступен"
        : effectiveAvailability === "loading"
          ? "Загрузка фото..."
          : photoCount > 0
            ? "Фото обрабатываются"
            : "Нет фото"

    return (
      <Button
        type="button"
        variant="secondary"
        className="h-full w-full flex-col"
        aria-label={`${placeholder}. Открыть фотографии ${item.number}`}
        onClick={() => onOpenPhotos(item)}
      >
        {placeholder}
      </Button>
    )
  }

  return (
    <PhotoCarousel
      photos={servicePhotos}
      item={item}
      imageVariant="thumbnail"
      photoCount={photoCount}
      showPhotoCount={photoCount > 0}
      photoCountClassName="hidden sm:flex"
      className="h-full w-full"
      fit="cover"
      controlsVisibility="always"
      onCenterClick={() => onOpenPhotos(item)}
    />
  )
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

function getAutoGridHeight(
  rowCount: number,
  requestedVisibleRowCount: number,
  gap: number,
  cardHeight: number
) {
  const visibleRowCount = Math.max(
    1,
    Math.min(Math.max(rowCount, 1), requestedVisibleRowCount)
  )
  return Math.min(
    DEFAULT_GRID_HEIGHT,
    Math.max(
      cardHeight,
      visibleRowCount * cardHeight + (visibleRowCount - 1) * gap
    )
  )
}

export function RentalItemsGridView({
  items,
  gridFormat,
  onOpenPhotos,
  onOpenItem,
  accessToken,
  mediaCovers,
  coverAvailability = "available",
  autoHeight = false,
  renderPhotoOverlay,
  renderItemActions,
}: RentalItemsGridViewProps) {
  const parentRef = useRef<HTMLDivElement | null>(null)
  const [dimensions, setDimensions] = useState({
    width: DEFAULT_GRID_WIDTH,
    height: DEFAULT_GRID_HEIGHT,
  })

  useLayoutEffect(() => {
    const element = parentRef.current

    if (!element) {
      return
    }

    const updateDimensions = () => {
      const width = element.clientWidth
      const height = element.clientHeight

      setDimensions((current) => {
        if (current.width === width && current.height === height) {
          return current
        }

        return { width, height }
      })
    }

    updateDimensions()

    const observer = new ResizeObserver(updateDimensions)

    observer.observe(element)

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
  const getCardMetrics = (
    nextColumnCount: number,
    nextGap: number,
    visibleRows: number
  ) => {
    const columnWidth = Math.max(
      0,
      (dimensions.width - nextGap * (nextColumnCount - 1)) / nextColumnCount
    )
    const compactness = Math.max(nextColumnCount, visibleRows)
    const detailedDescription = columnWidth >= 220 && compactness <= 3
    const minimumDescriptionHeight = detailedDescription
      ? 92
      : columnWidth >= 176
        ? 48
        : 32
    const photoHeight = Math.ceil(columnWidth)
    const rowHeight = Math.max(
      MIN_CARD_HEIGHT,
      photoHeight + minimumDescriptionHeight
    )

    return {
      columnWidth,
      compactness,
      detailedDescription,
      photoHeight,
      rowHeight,
    }
  }

  let rowCount = Math.ceil(items.length / columnCount)
  let cardMetrics = getCardMetrics(
    columnCount,
    gridGap,
    requestedVisibleRowCount
  )
  let gridHeight = autoHeight
    ? getAutoGridHeight(
        rowCount,
        requestedVisibleRowCount,
        gridGap,
        cardMetrics.rowHeight
      )
    : dimensions.height
  let visibleRowCount = limitCountBySize(
    autoHeight
      ? Math.min(Math.max(rowCount, 1), requestedVisibleRowCount)
      : requestedVisibleRowCount,
    gridHeight,
    cardMetrics.rowHeight,
    gridGap
  )
  gridGap = getGridGap(Math.max(columnCount, visibleRowCount))
  columnCount = limitCountBySize(
    requestedColumnCount,
    dimensions.width,
    MIN_ADAPTIVE_CARD_WIDTH,
    gridGap
  )
  rowCount = Math.ceil(items.length / columnCount)
  cardMetrics = getCardMetrics(columnCount, gridGap, visibleRowCount)
  gridHeight = autoHeight
    ? getAutoGridHeight(
        rowCount,
        requestedVisibleRowCount,
        gridGap,
        cardMetrics.rowHeight
      )
    : dimensions.height
  visibleRowCount = limitCountBySize(
    autoHeight
      ? Math.min(Math.max(rowCount, 1), requestedVisibleRowCount)
      : requestedVisibleRowCount,
    gridHeight,
    cardMetrics.rowHeight,
    gridGap
  )
  cardMetrics = getCardMetrics(columnCount, gridGap, visibleRowCount)
  const {
    columnWidth,
    compactness,
    detailedDescription,
    photoHeight,
    rowHeight,
  } = cardMetrics
  const virtualRowHeight = rowHeight + gridGap
  const descriptionHeight = rowHeight - photoHeight
  const showStatus = descriptionHeight >= 44 && columnWidth >= 176
  const showDescription = descriptionHeight >= 32
  const showExtraDescription = descriptionHeight >= 72 && detailedDescription
  const showCharacteristics = descriptionHeight >= 84 && detailedDescription

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
      className={cn(
        "min-w-0 overflow-auto [scrollbar-gutter:stable]",
        autoHeight ? "" : "min-h-0 flex-1"
      )}
      style={autoHeight ? { height: `${gridHeight}px` } : undefined}
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
                const characteristics = item.characteristics
                const actions = renderItemActions?.(item)

                return (
                  <div
                    key={item.id}
                    className="flex h-full min-h-0 flex-col overflow-hidden rounded-lg border bg-card"
                  >
                    <div className="relative aspect-square w-full shrink-0 overflow-hidden">
                      <RentalItemCardPhoto
                        item={item}
                        accessToken={accessToken}
                        projection={mediaCovers.get(item.id)}
                        coverAvailability={coverAvailability}
                        onOpenPhotos={onOpenPhotos}
                      />
                      {renderPhotoOverlay?.(item)}
                    </div>

                    <div className="flex min-h-0 min-w-0 flex-1 items-stretch">
                      <button
                        type="button"
                        className={cn(
                          "flex min-h-0 min-w-0 flex-1 flex-col justify-center gap-0.5 text-left hover:bg-muted/40",
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
                            {showCharacteristics &&
                            characteristics.length > 0 ? (
                              <div
                                className="flex max-h-11 min-w-0 flex-wrap content-start justify-end gap-1 overflow-hidden"
                                aria-label="Характеристики бытовки"
                              >
                                {characteristics.map((characteristic) => (
                                  <span
                                    key={characteristic.id}
                                    className="max-w-full truncate rounded-full bg-muted px-1.5 py-0.5 text-[10px] leading-3 text-muted-foreground"
                                  >
                                    {characteristic.name}
                                  </span>
                                ))}
                              </div>
                            ) : null}
                          </div>
                        ) : null}
                      </button>
                      {actions ? (
                        <div className="flex shrink-0 items-center gap-1 p-2 pl-0">
                          {actions}
                        </div>
                      ) : null}
                    </div>
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
