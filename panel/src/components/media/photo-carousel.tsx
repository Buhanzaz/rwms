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
  Camera,
  ChevronLeft,
  ChevronRight,
  Home,
  RotateCcw,
  X,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import type {
  RentalItemDto,
  RentalItemPhotoDto,
} from "@/features/rental-items/model/rental-item"

type PhotoCarouselControlsVisibility = "hover" | "always" | "mobile-visible"
type PhotoSource = string | RentalItemPhotoDto
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

function joinClasses(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(" ")
}

function getControlsVisibilityClass(
  controlsVisibility: PhotoCarouselControlsVisibility
) {
  if (controlsVisibility === "always") {
    return "opacity-100"
  }

  if (controlsVisibility === "mobile-visible") {
    return "opacity-100 lg:opacity-0 lg:hover:opacity-100 lg:focus-visible:opacity-100"
  }

  return "opacity-0 hover:opacity-100 focus-visible:opacity-100"
}

function buildVariantUrl(url: string, variant: "small" | "largeWebp") {
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
): RentalItemPhotoDto | null {
  if (typeof photo === "string") {
    const url = photo.trim()

    if (!url) {
      return null
    }

    return {
      id: `${item?.id ?? "photo"}-${index}-${url}`,
      rentalItemId: item?.id ?? "unknown",
      url,
      variants: {
        small: {
          url: buildVariantUrl(url, "small"),
        },
        largeWebp: {
          url: buildVariantUrl(url, "largeWebp"),
        },
      },
      createdAt: new Date().toISOString(),
    }
  }

  if (!photo.url) {
    return null
  }

  return photo
}

function getPhotoPreviewUrl(photo: RentalItemPhotoDto) {
  return photo.variants?.small?.url ?? photo.url
}

function getPhotoFullscreenUrl(photo: RentalItemPhotoDto) {
  return photo.variants?.largeWebp?.url ?? photo.url
}

function buildItemFallbackPhotos(item?: RentalItemDto): RentalItemPhotoDto[] {
  if (!item) {
    return []
  }

  const urls = [...(item.previewPhotoUrls ?? []), item.mainPhotoUrl].filter(
    (url): url is string => Boolean(url)
  )

  if (urls.length > 0) {
    return urls.map((url, index) => ({
      id: `${item.id}-preview-${index}`,
      rentalItemId: item.id,
      url,
      variants: {
        small: {
          url: buildVariantUrl(url, "small"),
        },
        largeWebp: {
          url: buildVariantUrl(url, "largeWebp"),
        },
      },
      createdAt: new Date().toISOString(),
    }))
  }

  if (!item.hasPhotos || item.photoCount <= 0) {
    return []
  }

  return Array.from(
    {
      length: Math.min(item.photoCount, MOCK_FALLBACK_PHOTO_URLS.length),
    },
    (_, index) => ({
      id: `${item.id}-fallback-${index}`,
      rentalItemId: item.id,
      url: MOCK_FALLBACK_PHOTO_URLS[index % MOCK_FALLBACK_PHOTO_URLS.length],
      variants: {
        small: {
          url: buildVariantUrl(
            MOCK_FALLBACK_PHOTO_URLS[index % MOCK_FALLBACK_PHOTO_URLS.length],
            "small"
          ),
        },
        largeWebp: {
          url: buildVariantUrl(
            MOCK_FALLBACK_PHOTO_URLS[index % MOCK_FALLBACK_PHOTO_URLS.length],
            "largeWebp"
          ),
        },
      },
      createdAt: new Date().toISOString(),
    })
  )
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
  placeholder,
  onCenterClick,
  activeIndex: controlledActiveIndex,
  onActiveIndexChange,
  onSwipeUp,
  fit = "cover",
  controlsVisibility = "hover",
  hideEdgeControlsOnMobile = false,
  disableFullscreenViewer = false,
}: PhotoCarouselProps) {
  const showLoading = loading ?? isLoading ?? false

  const swipeStartRef = useRef<SwipeStartPosition | null>(null)
  const suppressNextClickRef = useRef(false)
  const [internalActiveIndex, setInternalActiveIndex] = useState(0)
  const [viewerOpen, setViewerOpen] = useState(false)
  const [viewerInitialIndex, setViewerInitialIndex] = useState(0)

  const safePhotos = useMemo(() => {
    const normalizedPhotos = photos
      .map((photo, index) => normalizePhotoSource(photo, index, item))
      .filter((photo): photo is RentalItemPhotoDto => photo !== null)

    if (normalizedPhotos.length > 0) {
      return normalizedPhotos
    }

    return buildItemFallbackPhotos(item)
  }, [photos, item])

  const activeIndex = controlledActiveIndex ?? internalActiveIndex

  const safeActiveIndex =
    activeIndex >= 0 && activeIndex < safePhotos.length ? activeIndex : 0

  const activePhoto = safePhotos[safeActiveIndex] ?? null
  const hasPhotos = safePhotos.length > 0
  const hasMultiplePhotos = safePhotos.length > 1
  const controlsClass = getControlsVisibilityClass(controlsVisibility)
  const fullscreenLike = imageVariant === "fullscreen" || onSwipeUp !== undefined

  function openViewer(index: number) {
    if (!hasPhotos || disableFullscreenViewer) {
      return
    }

    const safeIndex = index >= 0 && index < safePhotos.length ? index : 0

    setViewerInitialIndex(safeIndex)
    setViewerOpen(true)
  }

  function setActivePhotoIndex(updater: (currentIndex: number) => number) {
    const nextIndex = updater(safeActiveIndex)

    if (controlledActiveIndex === undefined) {
      setInternalActiveIndex(nextIndex)
    }

    onActiveIndexChange?.(nextIndex)
  }

  function showPreviousPhoto() {
    if (!hasMultiplePhotos) {
      return
    }

    setActivePhotoIndex((currentIndex) => {
      const currentSafeIndex =
        currentIndex >= 0 && currentIndex < safePhotos.length ? currentIndex : 0

      return currentSafeIndex === 0
        ? safePhotos.length - 1
        : currentSafeIndex - 1
    })
  }

  function showNextPhoto() {
    if (!hasMultiplePhotos) {
      return
    }

    setActivePhotoIndex((currentIndex) => {
      const currentSafeIndex =
        currentIndex >= 0 && currentIndex < safePhotos.length ? currentIndex : 0

      return currentSafeIndex === safePhotos.length - 1
        ? 0
        : currentSafeIndex + 1
    })
  }

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) {
      return
    }

    event.currentTarget.setPointerCapture(event.pointerId)
    suppressNextClickRef.current = false
    swipeStartRef.current = {
      x: event.clientX,
      y: event.clientY,
    }
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    const start = swipeStartRef.current

    swipeStartRef.current = null

    if (!start) {
      return
    }

    const deltaX = event.clientX - start.x
    const deltaY = event.clientY - start.y
    const absX = Math.abs(deltaX)
    const absY = Math.abs(deltaY)
    const isHorizontalSwipe =
      absX >= SWIPE_MIN_DISTANCE && absX > absY * SWIPE_AXIS_LOCK_RATIO
    const isSwipeUp =
      deltaY <= -SWIPE_MIN_DISTANCE && absY > absX * SWIPE_AXIS_LOCK_RATIO

    if (isHorizontalSwipe) {
      event.preventDefault()
      suppressNextClickRef.current = true

      if (deltaX < 0) {
        showNextPhoto()
      } else {
        showPreviousPhoto()
      }

      return
    }

    if (isSwipeUp && onSwipeUp) {
      event.preventDefault()
      suppressNextClickRef.current = true
      onSwipeUp()
    }
  }

  if (showLoading) {
    return (
      <div
        className={joinClasses(
          "flex items-center justify-center rounded-lg border bg-muted/30 text-sm text-muted-foreground",
          className ?? "h-[320px]"
        )}
      >
        Загрузка фотографий...
      </div>
    )
  }

  return (
    <>
      <div
        className={joinClasses(
          "relative overflow-hidden bg-muted outline-none",
          fullscreenLike && "touch-none",
          className ?? "h-[320px] rounded-lg border"
        )}
        tabIndex={0}
        onPointerDown={handlePointerDown}
        onPointerUp={handlePointerUp}
        onPointerCancel={() => {
          swipeStartRef.current = null
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowLeft") {
            showPreviousPhoto()
          }

          if (event.key === "ArrowRight") {
            showNextPhoto()
          }

          if (event.key === "Enter") {
            openViewer(safeActiveIndex)
          }
        }}
      >
        {activePhoto ? (
          <button
            type="button"
            className="block h-full w-full cursor-zoom-in"
            onClick={() => {
              if (suppressNextClickRef.current) {
                suppressNextClickRef.current = false
                return
              }

              if (onCenterClick) {
                onCenterClick()
                return
              }

              if (!disableFullscreenViewer) {
                openViewer(safeActiveIndex)
              }
            }}
          >
            <img
              src={
                imageVariant === "fullscreen"
                  ? getPhotoFullscreenUrl(activePhoto)
                  : getPhotoPreviewUrl(activePhoto)
              }
              alt={`Фото ${safeActiveIndex + 1}`}
              draggable={false}
              className={joinClasses(
                "h-full w-full select-none",
                fit === "cover" ? "object-cover" : "object-contain",
                imageClassName
              )}
            />
          </button>
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
            {placeholder ?? <Home className="size-10 opacity-50" />}
            <span>Фото не загружены.</span>
          </div>
        )}

        {showPhotoCount && hasPhotos ? (
          <Badge
            className={joinClasses(
              "absolute top-2 right-2 z-30 gap-1",
              photoCountClassName
            )}
          >
            <Camera className="size-3" />
            {photoCount ?? safePhotos.length}
          </Badge>
        ) : null}

        {hasMultiplePhotos ? (
          <>
            <button
              type="button"
              aria-label="Предыдущее фото"
              className={joinClasses(
                "absolute top-0 left-0 z-20 h-full w-1/5 items-center justify-start pl-3",
                "bg-gradient-to-r from-black/60 via-black/25 to-transparent",
                "text-white transition",
                hideEdgeControlsOnMobile ? "hidden lg:flex" : "flex",
                controlsClass
              )}
              onClick={(event) => {
                event.stopPropagation()
                showPreviousPhoto()
              }}
            >
              <ChevronLeft className="size-8 drop-shadow" />
            </button>

            <button
              type="button"
              aria-label="Следующее фото"
              className={joinClasses(
                "absolute top-0 right-0 z-20 h-full w-1/5 items-center justify-end pr-3",
                "bg-gradient-to-l from-black/60 via-black/25 to-transparent",
                "text-white transition",
                hideEdgeControlsOnMobile ? "hidden lg:flex" : "flex",
                controlsClass
              )}
              onClick={(event) => {
                event.stopPropagation()
                showNextPhoto()
              }}
            >
              <ChevronRight className="size-8 drop-shadow" />
            </button>

            <div className="pointer-events-none absolute bottom-2 left-1/2 z-30 flex -translate-x-1/2 gap-1">
              {safePhotos.map((photo, index) => (
                <span
                  key={photo.id}
                  className={joinClasses(
                    "size-1.5 rounded-full bg-white/80 shadow",
                    index === safeActiveIndex ? "opacity-100" : "opacity-40"
                  )}
                />
              ))}
            </div>
          </>
        ) : null}
      </div>

      {viewerOpen ? (
        <PhotoFullscreenViewer
          key={`${viewerInitialIndex}:${safePhotos.length}`}
          photos={safePhotos}
          initialIndex={viewerInitialIndex}
          title={title ?? `Фото${item ? ` — ${item.number}` : ""}`}
          onOpenChange={setViewerOpen}
        />
      ) : null}
    </>
  )
}

type PhotoFullscreenViewerProps = {
  photos: RentalItemPhotoDto[]
  initialIndex: number
  title: string
  onOpenChange: (open: boolean) => void
}

function PhotoFullscreenViewer({
  photos,
  initialIndex,
  title,
  onOpenChange,
}: PhotoFullscreenViewerProps) {
  const safeInitialIndex =
    initialIndex >= 0 && initialIndex < photos.length ? initialIndex : 0

  const [currentIndex, setCurrentIndex] = useState(safeInitialIndex)
  const [orientationById, setOrientationById] = useState<
    Record<string, Orientation>
  >({})
  const swipeStartRef = useRef<SwipeStartPosition | null>(null)

  const safeCurrentIndex =
    currentIndex >= 0 && currentIndex < photos.length ? currentIndex : 0

  const currentPhoto = photos[safeCurrentIndex] ?? null

  const goPrev = useCallback(() => {
    if (photos.length === 0) {
      return
    }

    setCurrentIndex((currentIndex) => {
      const currentSafeIndex =
        currentIndex >= 0 && currentIndex < photos.length ? currentIndex : 0

      return currentSafeIndex === 0 ? photos.length - 1 : currentSafeIndex - 1
    })
  }, [photos.length])

  const goNext = useCallback(() => {
    if (photos.length === 0) {
      return
    }

    setCurrentIndex((currentIndex) => {
      const currentSafeIndex =
        currentIndex >= 0 && currentIndex < photos.length ? currentIndex : 0

      return currentSafeIndex === photos.length - 1 ? 0 : currentSafeIndex + 1
    })
  }, [photos.length])

  function changeOrientation() {
    if (!currentPhoto) {
      return
    }

    setOrientationById((current) => {
      const currentOrientation = current[currentPhoto.id] ?? 0

      const nextOrientation: Orientation =
        currentOrientation === 0
          ? 90
          : currentOrientation === 90
            ? 180
            : currentOrientation === 180
              ? 270
              : 0

      return {
        ...current,
        [currentPhoto.id]: nextOrientation,
      }
    })
  }

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onOpenChange(false)
        return
      }

      if (event.key === "ArrowLeft") {
        goPrev()
        return
      }

      if (event.key === "ArrowRight") {
        goNext()
      }
    }

    window.addEventListener("keydown", handleKeyDown)

    return () => {
      window.removeEventListener("keydown", handleKeyDown)
    }
  }, [goNext, goPrev, onOpenChange])

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) {
      return
    }

    event.currentTarget.setPointerCapture(event.pointerId)
    swipeStartRef.current = {
      x: event.clientX,
      y: event.clientY,
    }
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    const start = swipeStartRef.current

    swipeStartRef.current = null

    if (!start) {
      return
    }

    const deltaX = event.clientX - start.x
    const deltaY = event.clientY - start.y
    const absX = Math.abs(deltaX)
    const absY = Math.abs(deltaY)
    const isHorizontalSwipe =
      absX >= SWIPE_MIN_DISTANCE && absX > absY * SWIPE_AXIS_LOCK_RATIO
    const isSwipeUp =
      deltaY <= -SWIPE_MIN_DISTANCE && absY > absX * SWIPE_AXIS_LOCK_RATIO

    if (isHorizontalSwipe) {
      event.preventDefault()

      if (deltaX < 0) {
        goNext()
      } else {
        goPrev()
      }

      return
    }

    if (isSwipeUp) {
      event.preventDefault()
      onOpenChange(false)
    }
  }

  const orientation = currentPhoto ? (orientationById[currentPhoto.id] ?? 0) : 0

  return (
    <div
      className="fixed inset-0 z-[100] touch-none bg-black"
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerUp}
      onPointerCancel={() => {
        swipeStartRef.current = null
      }}
    >
      <div className="absolute top-4 left-4 z-40 rounded-full bg-white/15 px-4 py-2 text-sm font-medium text-white backdrop-blur">
        {title}
      </div>

      <Button
        type="button"
        size="icon"
        variant="ghost"
        className="absolute top-4 right-4 z-40 rounded-full bg-white/15 text-white hover:bg-white/25 hover:text-white"
        onClick={() => onOpenChange(false)}
      >
        <X className="size-5" />
      </Button>

      {currentPhoto ? (
        <>
          <button
            type="button"
            aria-label="Предыдущее фото"
            className="absolute top-0 left-0 z-20 h-full w-1/3 cursor-w-resize"
            onClick={goPrev}
          />

          <button
            type="button"
            aria-label="Следующее фото"
            className="absolute top-0 right-0 z-20 h-full w-1/3 cursor-e-resize"
            onClick={goNext}
          />

          <div className="relative z-10 flex h-full w-full items-center justify-center p-12">
            <img
              src={getPhotoFullscreenUrl(currentPhoto)}
              alt={title}
              draggable={false}
              className="max-h-full max-w-full object-contain select-none"
              style={{
                transform: `rotate(${orientation}deg)`,
                transformOrigin: "center center",
              }}
            />
          </div>

          <div className="absolute bottom-8 left-1/2 z-40 flex -translate-x-1/2 items-center gap-1 rounded-full bg-white/15 px-3 py-2 text-white backdrop-blur">
            <Button
              type="button"
              size="icon"
              variant="ghost"
              className="size-9 rounded-full text-white hover:bg-white/20 hover:text-white"
              onClick={goPrev}
            >
              <ChevronLeft className="size-5" />
            </Button>

            <Button
              type="button"
              size="icon"
              variant="ghost"
              className="size-9 rounded-full text-white hover:bg-white/20 hover:text-white"
              onClick={goNext}
            >
              <ChevronRight className="size-5" />
            </Button>

            <Button
              type="button"
              size="icon"
              variant="ghost"
              className="size-9 rounded-full text-white hover:bg-white/20 hover:text-white"
              onClick={changeOrientation}
            >
              <RotateCcw className="size-5" />
            </Button>
          </div>

          <div className="absolute bottom-3 left-1/2 z-40 -translate-x-1/2 rounded-full bg-white/15 px-3 py-1 text-xs text-white backdrop-blur">
            {safeCurrentIndex + 1} / {photos.length}
          </div>
        </>
      ) : (
        <div className="flex h-full items-center justify-center text-sm text-white/70">
          Фото не найдены.
        </div>
      )}
    </div>
  )
}
