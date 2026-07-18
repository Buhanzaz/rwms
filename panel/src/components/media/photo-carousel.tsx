import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent,
  type ReactNode,
} from "react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Camera01Icon,
  Cancel01Icon,
  Home01Icon,
  RotateLeft01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Carousel,
  CarouselContent,
  CarouselItem,
  type CarouselApi,
} from "@/components/ui/carousel"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { cn } from "@/lib/utils"

type PhotoCarouselControlsVisibility = "hover" | "always" | "mobile-visible"
export type PhotoCarouselPhoto = {
  id: string
  url: string
  variants?: {
    small?: { url: string }
    largeWebp?: { url: string }
    original?: { url: string }
  }
  createdAt?: string
}

type PhotoSource = string | PhotoCarouselPhoto
type Orientation = 0 | 90 | 180 | 270

type PhotoCarouselProps = {
  photos: PhotoSource[]
  item?: RentalItemDto
  title?: string
  loading?: boolean
  isLoading?: boolean
  photoCount?: number
  showPhotoCount?: boolean
  photoCountClassName?: string
  className?: string
  imageClassName?: string
  imageVariant?: "preview" | "fullscreen"
  fullscreenQuality?: "preview" | "original"
  placeholder?: ReactNode
  onCenterClick?: () => void
  activeIndex?: number
  onActiveIndexChange?: (index: number) => void
  onSwipeUp?: () => void
  fit?: "cover" | "contain"
  controlsVisibility?: PhotoCarouselControlsVisibility
  hideEdgeControlsOnMobile?: boolean
  showViewerToolbar?: boolean
  disableFullscreenViewer?: boolean
}

type SwipeStartPosition = {
  pointerId: number
  x: number
  y: number
}

const SWIPE_MIN_DISTANCE = 56
const SWIPE_AXIS_LOCK_RATIO = 1.15

const MOCK_FALLBACK_PHOTO_URLS = [
  "https://images.unsplash.com/photo-1494526585095-c41746248156?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1518780664697-55e3ad937233?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1564013799919-ab600027ffc6?q=80&w=900&auto=format&fit=crop",
  "https://images.unsplash.com/photo-1570129477492-45c003edd2be?q=80&w=900&auto=format&fit=crop",
]

function getControlsVisibilityClass(
  controlsVisibility: PhotoCarouselControlsVisibility
) {
  if (controlsVisibility === "always") return "opacity-100"
  if (controlsVisibility === "mobile-visible") {
    return "opacity-100 lg:opacity-0 lg:group-hover/carousel:opacity-100 lg:group-focus-within/carousel:opacity-100"
  }
  return "opacity-0 group-hover/carousel:opacity-100 group-focus-within/carousel:opacity-100"
}

function buildVariantUrl(url: string, variant: "small" | "largeWebp") {
  if (url.startsWith("blob:") || url.startsWith("data:")) return url

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

function normalizePhotoSource(
  photo: PhotoSource,
  index: number,
  item?: RentalItemDto
): PhotoCarouselPhoto | null {
  if (typeof photo === "string") {
    const url = photo.trim()
    if (!url) return null
    return {
      id: `${item?.id ?? "photo"}-${index}-${url}`,
      url,
      variants: {
        small: { url: buildVariantUrl(url, "small") },
        largeWebp: { url: buildVariantUrl(url, "largeWebp") },
      },
    }
  }

  return photo.url ? photo : null
}

function getPhotoPreviewUrl(photo: PhotoCarouselPhoto) {
  return photo.variants?.small?.url ?? photo.url
}

function getPhotoFullscreenUrl(
  photo: PhotoCarouselPhoto,
  quality: "preview" | "original"
) {
  if (quality === "original" && photo.variants?.original?.url) {
    return photo.variants.original.url
  }
  return photo.variants?.largeWebp?.url ?? photo.url
}

function buildItemFallbackPhotos(item?: RentalItemDto): PhotoCarouselPhoto[] {
  if (!item) return []

  const urls = [...(item.previewPhotoUrls ?? []), item.mainPhotoUrl].filter(
    (url): url is string => Boolean(url)
  )
  if (urls.length > 0) {
    return urls.map((url, index) => ({
      id: `${item.id}-preview-${index}`,
      url,
      variants: {
        small: { url: buildVariantUrl(url, "small") },
        largeWebp: { url: buildVariantUrl(url, "largeWebp") },
      },
    }))
  }

  if (!item.hasPhotos || item.photoCount <= 0) return []

  return Array.from(
    { length: Math.min(item.photoCount, MOCK_FALLBACK_PHOTO_URLS.length) },
    (_, index) => {
      const url =
        MOCK_FALLBACK_PHOTO_URLS[index % MOCK_FALLBACK_PHOTO_URLS.length]
      return {
        id: `${item.id}-fallback-${index}`,
        url,
        variants: {
          small: { url: buildVariantUrl(url, "small") },
          largeWebp: { url: buildVariantUrl(url, "largeWebp") },
        },
      }
    }
  )
}

function useCarouselIndex(
  api: CarouselApi,
  requestedIndex: number,
  onIndexChange: (index: number) => void
) {
  useEffect(() => {
    if (!api) return
    const onSelect = () => onIndexChange(api.selectedScrollSnap())
    onSelect()
    api.on("select", onSelect)
    api.on("reInit", onSelect)
    return () => {
      api.off("select", onSelect)
      api.off("reInit", onSelect)
    }
  }, [api, onIndexChange])

  useEffect(() => {
    if (api && api.selectedScrollSnap() !== requestedIndex) {
      api.scrollTo(requestedIndex, true)
    }
  }, [api, requestedIndex])
}

function useVerticalSwipe(onSwipeUp?: () => void) {
  const startRef = useRef<SwipeStartPosition | null>(null)

  const onPointerDown = useCallback((event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "mouse" && event.button !== 0) return
    startRef.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
    }
  }, [])

  const onPointerUp = useCallback(
    (event: PointerEvent<HTMLDivElement>) => {
      const start = startRef.current
      startRef.current = null
      if (!start || start.pointerId !== event.pointerId || !onSwipeUp) return

      const deltaX = event.clientX - start.x
      const deltaY = event.clientY - start.y
      if (
        deltaY <= -SWIPE_MIN_DISTANCE &&
        Math.abs(deltaY) > Math.abs(deltaX) * SWIPE_AXIS_LOCK_RATIO
      ) {
        onSwipeUp()
      }
    },
    [onSwipeUp]
  )

  const cancel = useCallback(() => {
    startRef.current = null
  }, [])

  return { onPointerDown, onPointerUp, onPointerCancel: cancel }
}

export function PhotoCarousel({
  photos,
  item,
  title,
  loading,
  isLoading,
  photoCount,
  showPhotoCount = false,
  photoCountClassName,
  className,
  imageClassName,
  imageVariant = "preview",
  fullscreenQuality = "preview",
  placeholder,
  onCenterClick,
  activeIndex: controlledActiveIndex,
  onActiveIndexChange,
  onSwipeUp,
  fit = "cover",
  controlsVisibility = "hover",
  hideEdgeControlsOnMobile = false,
  showViewerToolbar = true,
  disableFullscreenViewer = false,
}: PhotoCarouselProps) {
  const [api, setApi] = useState<CarouselApi>()
  const [internalActiveIndex, setInternalActiveIndex] = useState(0)
  const [viewerOpen, setViewerOpen] = useState(false)
  const verticalSwipe = useVerticalSwipe(onSwipeUp)
  const safePhotos = useMemo(() => {
    const normalized = photos
      .map((photo, index) => normalizePhotoSource(photo, index, item))
      .filter((photo): photo is PhotoCarouselPhoto => photo !== null)
    return normalized.length > 0 ? normalized : buildItemFallbackPhotos(item)
  }, [item, photos])
  const requestedIndex = controlledActiveIndex ?? internalActiveIndex
  const safeActiveIndex =
    requestedIndex >= 0 && requestedIndex < safePhotos.length
      ? requestedIndex
      : 0
  const hasPhotos = safePhotos.length > 0
  const hasMultiplePhotos = safePhotos.length > 1

  const changeIndex = useCallback(
    (index: number) => {
      if (controlledActiveIndex === undefined) setInternalActiveIndex(index)
      onActiveIndexChange?.(index)
    },
    [controlledActiveIndex, onActiveIndexChange]
  )
  useCarouselIndex(api, safeActiveIndex, changeIndex)

  function openViewer() {
    if (!hasPhotos || disableFullscreenViewer) return
    setViewerOpen(true)
  }

  function handleCenterClick() {
    if (onCenterClick) onCenterClick()
    else openViewer()
  }

  if (loading ?? isLoading ?? false) {
    return (
      <div
        className={cn(
          "flex items-center justify-center rounded-lg border bg-muted/30 text-sm text-muted-foreground",
          className ?? "h-[320px]"
        )}
      >
        Загрузка фотографий...
      </div>
    )
  }

  if (!hasPhotos) {
    return (
      <div
        className={cn(
          "flex flex-col items-center justify-center gap-2 rounded-lg border bg-muted text-sm text-muted-foreground",
          className ?? "h-[320px]"
        )}
      >
        {placeholder ?? (
          <HugeiconsIcon icon={Home01Icon} className="size-10 opacity-50" />
        )}
        <span>Фото не загружены.</span>
      </div>
    )
  }

  const controlsClass = getControlsVisibilityClass(controlsVisibility)

  return (
    <>
      <Carousel
        setApi={setApi}
        opts={{ loop: hasMultiplePhotos, watchDrag: hasMultiplePhotos }}
        className={cn(
          "group/carousel overflow-hidden bg-muted outline-none",
          className ?? "h-[320px] rounded-lg border"
        )}
        aria-label={title ?? "Фотографии"}
        {...verticalSwipe}
      >
        <CarouselContent className="-ml-0 h-full">
          {safePhotos.map((photo, index) => {
            const image = (
              <img
                src={
                  imageVariant === "fullscreen"
                    ? getPhotoFullscreenUrl(photo, fullscreenQuality)
                    : getPhotoPreviewUrl(photo)
                }
                alt={`Фото ${index + 1} из ${safePhotos.length}`}
                draggable={false}
                className={cn(
                  "h-full w-full select-none",
                  fit === "cover" ? "object-cover" : "object-contain",
                  imageClassName
                )}
              />
            )
            const interactive =
              !disableFullscreenViewer || onCenterClick !== undefined

            return (
              <CarouselItem
                key={photo.id}
                aria-label={`${index + 1} из ${safePhotos.length}`}
                className="h-full pl-0"
              >
                {interactive ? (
                  <button
                    type="button"
                    aria-label={`Открыть фото ${index + 1}`}
                    className="block h-full w-full cursor-zoom-in touch-pan-y"
                    onClick={handleCenterClick}
                  >
                    {image}
                  </button>
                ) : (
                  <div className="h-full w-full touch-pan-y">{image}</div>
                )}
              </CarouselItem>
            )
          })}
        </CarouselContent>

        {showPhotoCount ? (
          <Badge
            className={cn("absolute top-2 right-2 gap-1", photoCountClassName)}
          >
            <HugeiconsIcon icon={Camera01Icon} />
            {photoCount ?? safePhotos.length}
          </Badge>
        ) : null}

        {hasMultiplePhotos ? (
          <>
            <button
              type="button"
              aria-label="Предыдущее фото"
              className={cn(
                "absolute inset-y-0 left-0 flex w-1/5 items-center justify-start bg-gradient-to-r from-black/60 via-black/25 to-transparent pl-3 text-white transition-opacity",
                hideEdgeControlsOnMobile && "hidden lg:flex",
                controlsClass
              )}
              onClick={(event) => {
                event.stopPropagation()
                api?.scrollPrev()
              }}
            >
              <HugeiconsIcon icon={ArrowLeft01Icon} className="size-8" />
            </button>
            <button
              type="button"
              aria-label="Следующее фото"
              className={cn(
                "absolute inset-y-0 right-0 flex w-1/5 items-center justify-end bg-gradient-to-l from-black/60 via-black/25 to-transparent pr-3 text-white transition-opacity",
                hideEdgeControlsOnMobile && "hidden lg:flex",
                controlsClass
              )}
              onClick={(event) => {
                event.stopPropagation()
                api?.scrollNext()
              }}
            >
              <HugeiconsIcon icon={ArrowRight01Icon} className="size-8" />
            </button>
            <div className="pointer-events-none absolute bottom-2 left-1/2 flex -translate-x-1/2 gap-1">
              {safePhotos.map((photo, index) => (
                <span
                  key={photo.id}
                  className={cn(
                    "size-1.5 rounded-full bg-white/80 shadow",
                    index === safeActiveIndex ? "opacity-100" : "opacity-40"
                  )}
                />
              ))}
            </div>
          </>
        ) : null}
      </Carousel>

      <PhotoFullscreenViewer
        photos={safePhotos}
        open={viewerOpen}
        activeIndex={safeActiveIndex}
        title={title ?? `Фото${item ? ` — ${item.number}` : ""}`}
        quality={fullscreenQuality}
        showToolbar={showViewerToolbar}
        onActiveIndexChange={changeIndex}
        onOpenChange={setViewerOpen}
      />
    </>
  )
}

type PhotoFullscreenViewerProps = {
  photos: PhotoCarouselPhoto[]
  open: boolean
  activeIndex: number
  title: string
  quality: "preview" | "original"
  showToolbar: boolean
  onActiveIndexChange: (index: number) => void
  onOpenChange: (open: boolean) => void
}

function PhotoFullscreenViewer({
  photos,
  open,
  activeIndex,
  title,
  quality,
  showToolbar,
  onActiveIndexChange,
  onOpenChange,
}: PhotoFullscreenViewerProps) {
  const [api, setApi] = useState<CarouselApi>()
  const [orientationById, setOrientationById] = useState<
    Record<string, Orientation>
  >({})
  const safeIndex =
    activeIndex >= 0 && activeIndex < photos.length ? activeIndex : 0
  useCarouselIndex(api, safeIndex, onActiveIndexChange)

  const goPrev = useCallback(() => api?.scrollPrev(), [api])
  const goNext = useCallback(() => api?.scrollNext(), [api])

  function rotateCurrentPhoto() {
    const photo = photos[safeIndex]
    if (!photo) return
    setOrientationById((current) => {
      const orientation = current[photo.id] ?? 0
      return {
        ...current,
        [photo.id]: ((orientation + 270) % 360) as Orientation,
      }
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="!fixed !inset-0 !top-0 !left-0 !h-dvh !max-h-dvh !w-screen !max-w-none !translate-x-0 !translate-y-0 overflow-hidden rounded-none border-0 bg-black p-0 text-white"
        onKeyDown={(event) => {
          if (event.defaultPrevented) return
          if (event.key === "ArrowLeft") {
            event.preventDefault()
            goPrev()
          }
          if (event.key === "ArrowRight") {
            event.preventDefault()
            goNext()
          }
        }}
      >
        <DialogHeader className="sr-only">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Полноэкранный просмотр фотографий. Используйте стрелки для
            перелистывания.
          </DialogDescription>
        </DialogHeader>
        <div className="absolute top-4 left-4 z-40 rounded-full bg-white/15 px-4 py-2 text-sm font-medium text-white backdrop-blur">
          {title}
        </div>
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label="Закрыть"
          className="absolute top-4 right-4 z-40 rounded-full bg-white/15 text-white hover:bg-white/25 hover:text-white"
          onClick={() => onOpenChange(false)}
        >
          <HugeiconsIcon icon={Cancel01Icon} />
        </Button>

        <Carousel
          setApi={setApi}
          opts={{ loop: photos.length > 1, watchDrag: photos.length > 1 }}
          className="h-dvh w-screen"
          aria-label={title}
        >
          <CarouselContent className="-ml-0 h-dvh">
            {photos.map((photo, index) => (
              <CarouselItem
                key={photo.id}
                aria-label={`${index + 1} из ${photos.length}`}
                className="flex h-dvh items-center justify-center p-12"
              >
                <img
                  src={getPhotoFullscreenUrl(photo, quality)}
                  alt={`${title}, фото ${index + 1} из ${photos.length}`}
                  draggable={false}
                  className="max-h-full max-w-full object-contain select-none"
                  style={{
                    transform: `rotate(${orientationById[photo.id] ?? 0}deg)`,
                    transformOrigin: "center center",
                  }}
                />
              </CarouselItem>
            ))}
          </CarouselContent>

          {photos.length > 1 ? (
            <>
              <button
                type="button"
                aria-label="Предыдущее фото"
                aria-hidden="true"
                tabIndex={-1}
                className="absolute inset-y-0 left-0 w-1/4 cursor-w-resize"
                onClick={(event) => {
                  event.stopPropagation()
                  goPrev()
                }}
              />
              <button
                type="button"
                aria-label="Следующее фото"
                aria-hidden="true"
                tabIndex={-1}
                className="absolute inset-y-0 right-0 w-1/4 cursor-e-resize"
                onClick={(event) => {
                  event.stopPropagation()
                  goNext()
                }}
              />
            </>
          ) : null}
        </Carousel>

        <div className="absolute bottom-8 left-1/2 z-40 flex -translate-x-1/2 flex-col items-center gap-2 text-white">
          <div
            aria-live="polite"
            aria-atomic="true"
            className="rounded-full bg-white/15 px-3 py-1 text-xs backdrop-blur"
          >
            {safeIndex + 1} / {photos.length}
          </div>
          {showToolbar ? (
            <div className="flex items-center gap-1 rounded-full bg-white/15 px-3 py-2 backdrop-blur">
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Предыдущее фото"
                className="rounded-full text-white hover:bg-white/20 hover:text-white"
                disabled={photos.length < 2}
                onClick={(event) => {
                  event.stopPropagation()
                  goPrev()
                }}
              >
                <HugeiconsIcon icon={ArrowLeft01Icon} />
              </Button>
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Следующее фото"
                className="rounded-full text-white hover:bg-white/20 hover:text-white"
                disabled={photos.length < 2}
                onClick={(event) => {
                  event.stopPropagation()
                  goNext()
                }}
              >
                <HugeiconsIcon icon={ArrowRight01Icon} />
              </Button>
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Повернуть фото"
                className="rounded-full text-white hover:bg-white/20 hover:text-white"
                onClick={rotateCurrentPhoto}
              >
                <HugeiconsIcon icon={RotateLeft01Icon} />
              </Button>
            </div>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  )
}
