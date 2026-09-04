import {
  useCallback,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type WheelEvent as ReactWheelEvent,
} from "react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Cancel01Icon,
  ZoomInAreaIcon,
  ZoomOutAreaIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"

export type FullscreenPhoto = {
  id: string
  src: string
  alt: string
}

type Offset = { x: number; y: number }

type DragGesture = {
  mode: "drag"
  pointerId: number
  startX: number
  startY: number
  startOffset: Offset
}

type PinchGesture = {
  mode: "pinch"
  startDistance: number
  startScale: number
}

type DismissGesture = {
  mode: "dismiss"
  pointerId: number
  startX: number
  startY: number
}

type Gesture = DragGesture | PinchGesture | DismissGesture

const MIN_SCALE = 1
const MAX_SCALE = 5
const SCALE_STEP = 0.5
const KEYBOARD_PAN_STEP = 56
const DISMISS_DISTANCE = 56
const DISMISS_AXIS_RATIO = 1.15

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}

function pointerDistance(points: readonly { x: number; y: number }[]) {
  const [first, second] = points
  if (!first || !second) return 0
  return Math.hypot(second.x - first.x, second.y - first.y)
}

export function FullscreenPhotoViewer({
  photos,
  open,
  activeIndex = 0,
  title,
  loading = false,
  emptyLabel = "Нет фотографий",
  onActiveIndexChange,
  onSwipeUp,
  onOpenChange,
}: {
  photos: readonly FullscreenPhoto[]
  open: boolean
  activeIndex?: number
  title: string
  loading?: boolean
  emptyLabel?: string
  onActiveIndexChange?: (index: number) => void
  onSwipeUp?: () => void
  onOpenChange: (open: boolean) => void
}) {
  const safeIndex =
    activeIndex >= 0 && activeIndex < photos.length ? activeIndex : 0
  const activePhoto = photos[safeIndex] ?? null
  const stageRef = useRef<HTMLDivElement>(null)
  const pointersRef = useRef(new Map<number, { x: number; y: number }>())
  const gestureRef = useRef<Gesture | null>(null)
  const scaleRef = useRef(MIN_SCALE)
  const offsetRef = useRef<Offset>({ x: 0, y: 0 })
  const [scale, setScale] = useState(MIN_SCALE)
  const [offset, setOffset] = useState<Offset>({ x: 0, y: 0 })
  const [dragging, setDragging] = useState(false)

  const applyView = useCallback((nextScale: number, nextOffset: Offset) => {
    const normalizedScale = clamp(nextScale, MIN_SCALE, MAX_SCALE)
    const stage = stageRef.current
    const maxX = stage ? (stage.clientWidth * (normalizedScale - 1)) / 2 : 0
    const maxY = stage ? (stage.clientHeight * (normalizedScale - 1)) / 2 : 0
    const normalizedOffset =
      normalizedScale === MIN_SCALE
        ? { x: 0, y: 0 }
        : {
            x: clamp(nextOffset.x, -maxX, maxX),
            y: clamp(nextOffset.y, -maxY, maxY),
          }
    scaleRef.current = normalizedScale
    offsetRef.current = normalizedOffset
    setScale(normalizedScale)
    setOffset(normalizedOffset)
  }, [])

  const resetView = useCallback(() => {
    pointersRef.current.clear()
    gestureRef.current = null
    setDragging(false)
    applyView(MIN_SCALE, { x: 0, y: 0 })
  }, [applyView])

  const changePhoto = useCallback(
    (direction: -1 | 1) => {
      if (photos.length < 2) return
      const nextIndex = (safeIndex + direction + photos.length) % photos.length
      resetView()
      onActiveIndexChange?.(nextIndex)
    },
    [onActiveIndexChange, photos.length, resetView, safeIndex]
  )

  const changeScale = useCallback(
    (delta: number) => {
      applyView(scaleRef.current + delta, offsetRef.current)
    },
    [applyView]
  )

  const panBy = useCallback(
    (deltaX: number, deltaY: number) => {
      applyView(scaleRef.current, {
        x: offsetRef.current.x + deltaX,
        y: offsetRef.current.y + deltaY,
      })
    },
    [applyView]
  )

  function handlePointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    pointersRef.current.set(event.pointerId, {
      x: event.clientX,
      y: event.clientY,
    })
    const points = [...pointersRef.current.values()]
    if (points.length >= 2) {
      gestureRef.current = {
        mode: "pinch",
        startDistance: pointerDistance(points),
        startScale: scaleRef.current,
      }
      setDragging(true)
      return
    }
    if (scaleRef.current > MIN_SCALE) {
      gestureRef.current = {
        mode: "drag",
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        startOffset: offsetRef.current,
      }
      setDragging(true)
      return
    }
    if (event.pointerType !== "mouse" && onSwipeUp) {
      gestureRef.current = {
        mode: "dismiss",
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
      }
    }
  }

  function handlePointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (!pointersRef.current.has(event.pointerId)) return
    pointersRef.current.set(event.pointerId, {
      x: event.clientX,
      y: event.clientY,
    })
    const gesture = gestureRef.current
    if (!gesture) return
    if (gesture.mode === "dismiss") return
    if (gesture.mode === "pinch") {
      const distance = pointerDistance([...pointersRef.current.values()])
      if (gesture.startDistance > 0) {
        applyView(
          gesture.startScale * (distance / gesture.startDistance),
          offsetRef.current
        )
      }
      return
    }
    if (gesture.pointerId === event.pointerId) {
      applyView(scaleRef.current, {
        x: gesture.startOffset.x + event.clientX - gesture.startX,
        y: gesture.startOffset.y + event.clientY - gesture.startY,
      })
    }
  }

  function finishPointer(event: ReactPointerEvent<HTMLDivElement>) {
    const gesture = gestureRef.current
    if (gesture?.mode === "dismiss" && gesture.pointerId === event.pointerId) {
      const deltaX = event.clientX - gesture.startX
      const deltaY = event.clientY - gesture.startY
      if (
        deltaY <= -DISMISS_DISTANCE &&
        Math.abs(deltaY) > Math.abs(deltaX) * DISMISS_AXIS_RATIO
      ) {
        onSwipeUp?.()
      }
    }
    pointersRef.current.delete(event.pointerId)
    const remaining = [...pointersRef.current.entries()]
    if (remaining.length === 1 && scaleRef.current > MIN_SCALE) {
      const [pointerId, point] = remaining[0]!
      gestureRef.current = {
        mode: "drag",
        pointerId,
        startX: point.x,
        startY: point.y,
        startOffset: offsetRef.current,
      }
      return
    }
    gestureRef.current = null
    setDragging(false)
  }

  function handleWheel(event: ReactWheelEvent<HTMLDivElement>) {
    event.preventDefault()
    changeScale(event.deltaY < 0 ? SCALE_STEP : -SCALE_STEP)
  }

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) resetView()
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="!fixed !inset-0 !top-0 !left-0 !h-svh !max-h-none !w-screen !max-w-none !translate-x-0 !translate-y-0 overflow-hidden overscroll-contain rounded-none border-0 bg-primary p-0 text-primary-foreground"
        onKeyDown={(event) => {
          if (event.defaultPrevented) return
          if (event.key === "+" || event.key === "=") {
            event.preventDefault()
            changeScale(SCALE_STEP)
            return
          }
          if (event.key === "-") {
            event.preventDefault()
            changeScale(-SCALE_STEP)
            return
          }
          if (event.key === "0") {
            event.preventDefault()
            resetView()
            return
          }
          if (event.key === "ArrowLeft") {
            event.preventDefault()
            changePhoto(-1)
            return
          }
          if (event.key === "ArrowRight") {
            event.preventDefault()
            changePhoto(1)
            return
          }
          if (event.key === "ArrowUp" && scaleRef.current > MIN_SCALE) {
            event.preventDefault()
            panBy(0, KEYBOARD_PAN_STEP)
          }
          if (event.key === "ArrowDown" && scaleRef.current > MIN_SCALE) {
            event.preventDefault()
            panBy(0, -KEYBOARD_PAN_STEP)
          }
        }}
      >
        <DialogHeader className="sr-only">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Полноэкранный просмотр. Масштабируйте кнопками, колесом или жестом,
            затем перетаскивайте фотографию для просмотра деталей.
          </DialogDescription>
        </DialogHeader>

        <div className="pointer-events-none absolute inset-x-0 top-0 z-20 flex items-start justify-between gap-3 p-4 [padding-top:max(1rem,env(safe-area-inset-top))]">
          <div className="max-w-[calc(100vw-5rem)] truncate rounded-full bg-primary/55 px-4 py-2 text-sm font-medium backdrop-blur">
            {title}
          </div>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Закрыть просмотр"
            className="pointer-events-auto shrink-0 rounded-full bg-primary/55 text-primary-foreground hover:bg-primary-foreground/20 hover:text-primary-foreground"
            onClick={() => handleOpenChange(false)}
          >
            <HugeiconsIcon icon={Cancel01Icon} aria-hidden="true" />
          </Button>
        </div>

        <div
          ref={stageRef}
          data-slot="fullscreen-photo-stage"
          aria-label="Область масштабирования фотографии"
          className={cn(
            "flex h-svh w-screen touch-none items-center justify-center overflow-hidden bg-primary select-none",
            scale > MIN_SCALE && (dragging ? "cursor-grabbing" : "cursor-grab")
          )}
          onDoubleClick={() =>
            scaleRef.current === MIN_SCALE
              ? applyView(2, offsetRef.current)
              : resetView()
          }
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={finishPointer}
          onPointerCancel={finishPointer}
          onWheel={handleWheel}
        >
          {loading ? (
            <p className="text-sm text-primary-foreground/70">Загрузка фотографий...</p>
          ) : activePhoto ? (
            <img
              src={activePhoto.src}
              alt={activePhoto.alt}
              width={2400}
              height={1800}
              draggable={false}
              className={cn(
                "h-full w-full object-contain select-none",
                !dragging &&
                  "transition-transform duration-150 motion-reduce:transition-none"
              )}
              style={{
                transform: `translate3d(${offset.x}px, ${offset.y}px, 0) scale(${scale})`,
              }}
            />
          ) : (
            <p className="px-6 text-center text-sm text-primary-foreground/70">
              {emptyLabel}
            </p>
          )}
        </div>

        <div className="pointer-events-none absolute inset-x-0 bottom-0 z-20 flex justify-center p-4 [padding-bottom:max(1rem,env(safe-area-inset-bottom))]">
          <div className="pointer-events-auto flex flex-wrap items-center justify-center gap-1 rounded-full bg-primary/60 p-2 backdrop-blur">
            <Button
              type="button"
              size="icon"
              variant="ghost"
              aria-label="Предыдущее фото"
              className="rounded-full text-primary-foreground hover:bg-primary-foreground/20 hover:text-primary-foreground"
              disabled={photos.length < 2}
              onClick={() => changePhoto(-1)}
            >
              <HugeiconsIcon icon={ArrowLeft01Icon} aria-hidden="true" />
            </Button>
            <span
              aria-live="polite"
              aria-atomic="true"
              className="min-w-16 px-2 text-center text-xs tabular-nums"
            >
              {activePhoto ? `${safeIndex + 1} / ${photos.length}` : "0 / 0"}
            </span>
            <Button
              type="button"
              size="icon"
              variant="ghost"
              aria-label="Следующее фото"
              className="rounded-full text-primary-foreground hover:bg-primary-foreground/20 hover:text-primary-foreground"
              disabled={photos.length < 2}
              onClick={() => changePhoto(1)}
            >
              <HugeiconsIcon icon={ArrowRight01Icon} aria-hidden="true" />
            </Button>
            <span className="mx-1 h-6 w-px bg-primary-foreground/20" aria-hidden="true" />
            <Button
              type="button"
              size="icon"
              variant="ghost"
              aria-label="Уменьшить фото"
              className="rounded-full text-primary-foreground hover:bg-primary-foreground/20 hover:text-primary-foreground"
              disabled={scale <= MIN_SCALE}
              onClick={() => changeScale(-SCALE_STEP)}
            >
              <HugeiconsIcon icon={ZoomOutAreaIcon} aria-hidden="true" />
            </Button>
            <button
              type="button"
              aria-label="Сбросить масштаб"
              className="min-w-14 rounded-full px-2 py-2 text-xs tabular-nums outline-none hover:bg-primary-foreground/20 focus-visible:ring-2 focus-visible:ring-primary-foreground"
              onClick={resetView}
            >
              {Math.round(scale * 100)}%
            </button>
            <Button
              type="button"
              size="icon"
              variant="ghost"
              aria-label="Увеличить фото"
              className="rounded-full text-primary-foreground hover:bg-primary-foreground/20 hover:text-primary-foreground"
              disabled={scale >= MAX_SCALE}
              onClick={() => changeScale(SCALE_STEP)}
            >
              <HugeiconsIcon icon={ZoomInAreaIcon} aria-hidden="true" />
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
