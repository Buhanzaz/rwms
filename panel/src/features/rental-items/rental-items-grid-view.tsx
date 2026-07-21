/* eslint-disable react-hooks/incompatible-library -- TanStack Virtual returns imperative helpers that React Compiler intentionally skips. */
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { useVirtualizer } from "@tanstack/react-virtual"

import {
  PhotoCarousel,
  type PhotoCarouselPhoto,
} from "@/components/media/photo-carousel"
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
  renderItemActions?: (item: RentalItemDto) => ReactNode
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

function getItemPhotos(
  item: RentalItemDto,
  servicePhotos: readonly PhotoCarouselPhoto[]
) {
  const photos: Array<string | PhotoCarouselPhoto> = [...servicePhotos]
  if (item.legacyPhotos && item.legacyPhotos.length > 0) {
    return [
      ...photos,
      ...item.legacyPhotos.slice(0, 4).map((photo) => ({
        id: photo.id,
        url: photo.url,
        variants: {
          small: { url: photo.variants?.small?.url ?? photo.url },
          medium: { url: photo.url },
          large: { url: photo.variants?.largeWebp?.url ?? photo.url },
        },
        createdAt: photo.capturedAt ?? undefined,
      })),
    ]
  }
  if (item.previewPhotoUrls && item.previewPhotoUrls.length > 0) {
    return [...photos, ...item.previewPhotoUrls]
  }

  if (item.mainPhotoUrl) {
    return [...photos, item.mainPhotoUrl]
  }

  return photos
}

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
  const photos = getItemPhotos(item, servicePhotos)
  const photoCount = item.photoCount + (projection?.photoCount ?? 0)
  const effectiveAvailability =
    coverAvailability === "unavailable" ||
    servicePhotoResult.availability === "unavailable"
      ? "unavailable"
      : coverAvailability === "loading" ||
          servicePhotoResult.availability === "loading"
        ? "loading"
        : "available"

  if (photos.length === 0) {
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
      photos={photos}
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

export function RentalItemsGridView({
  items,
  gridFormat,
  onOpenPhotos,
  onOpenItem,
  accessToken,
  mediaCovers,
  coverAvailability = "available",
  renderItemActions,
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
                const characteristics = getCharacteristics(item.characteristics)
                const actions = renderItemActions?.(item)

                return (
                  <div
                    key={item.id}
                    className="flex h-full min-h-0 flex-col overflow-hidden rounded-lg border bg-card"
                  >
                    <div
                      className="shrink-0 overflow-hidden"
                      style={{ height: `${photoHeight}px` }}
                    >
                      <RentalItemCardPhoto
                        item={item}
                        accessToken={accessToken}
                        projection={mediaCovers.get(item.id)}
                        coverAvailability={coverAvailability}
                        onOpenPhotos={onOpenPhotos}
                      />
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
