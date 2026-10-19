import * as React from "react"
import { ArrowLeft01Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import useEmblaCarousel, {
  type UseEmblaCarouselType,
} from "embla-carousel-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

type CarouselApi = UseEmblaCarouselType[1]
type UseCarouselParameters = Parameters<typeof useEmblaCarousel>
type CarouselOptions = UseCarouselParameters[0]
type CarouselPlugin = UseCarouselParameters[1]

type CarouselProps = {
  opts?: CarouselOptions
  plugins?: CarouselPlugin
  orientation?: "horizontal" | "vertical"
  setApi?: (api: CarouselApi) => void
}

type CarouselDotsProps = Omit<React.ComponentProps<"div">, "children"> & {
  getDotLabel?: (index: number, count: number) => string
}

type CarouselContextProps = {
  carouselRef: ReturnType<typeof useEmblaCarousel>[0]
  api: CarouselApi
  scrollPrev: () => void
  scrollNext: () => void
  canScrollPrev: boolean
  canScrollNext: boolean
} & CarouselProps

const CarouselContext = React.createContext<CarouselContextProps | null>(null)

function useCarousel() {
  const context = React.useContext(CarouselContext)

  if (!context) {
    throw new Error("useCarousel must be used within a <Carousel />")
  }

  return context
}

function Carousel({
  orientation = "horizontal",
  opts,
  setApi,
  plugins,
  className,
  children,
  ...props
}: React.ComponentProps<"div"> & CarouselProps) {
  const [carouselRef, api] = useEmblaCarousel(
    {
      ...opts,
      axis: orientation === "horizontal" ? "x" : "y",
    },
    plugins
  )
  const [canScrollPrev, setCanScrollPrev] = React.useState(false)
  const [canScrollNext, setCanScrollNext] = React.useState(false)

  const onSelect = React.useCallback((nextApi: CarouselApi) => {
    if (!nextApi) return
    setCanScrollPrev(nextApi.canScrollPrev())
    setCanScrollNext(nextApi.canScrollNext())
  }, [])

  const scrollPrev = React.useCallback(() => api?.scrollPrev(), [api])
  const scrollNext = React.useCallback(() => api?.scrollNext(), [api])

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (event.key === "ArrowLeft") {
        event.preventDefault()
        scrollPrev()
      } else if (event.key === "ArrowRight") {
        event.preventDefault()
        scrollNext()
      }
    },
    [scrollNext, scrollPrev]
  )

  React.useEffect(() => {
    if (api && setApi) setApi(api)
  }, [api, setApi])

  React.useEffect(() => {
    if (!api) return
    let active = true
    queueMicrotask(() => {
      if (active) onSelect(api)
    })
    api.on("reInit", onSelect)
    api.on("select", onSelect)

    return () => {
      active = false
      api.off("reInit", onSelect)
      api.off("select", onSelect)
    }
  }, [api, onSelect])

  return (
    <CarouselContext.Provider
      value={{
        carouselRef,
        api,
        opts,
        orientation,
        plugins,
        scrollPrev,
        scrollNext,
        canScrollPrev,
        canScrollNext,
      }}
    >
      <div
        role="region"
        aria-roledescription="carousel"
        data-slot="carousel"
        className={cn("relative", className)}
        onKeyDownCapture={handleKeyDown}
        {...props}
      >
        {children}
      </div>
    </CarouselContext.Provider>
  )
}

function CarouselContent({ className, ...props }: React.ComponentProps<"div">) {
  const { carouselRef, orientation } = useCarousel()

  return (
    <div
      ref={carouselRef}
      className="h-full overflow-hidden"
      data-slot="carousel-content"
    >
      <div
        className={cn(
          "flex touch-pan-y",
          orientation === "horizontal" ? "-ml-4" : "-mt-4 flex-col",
          className
        )}
        {...props}
      />
    </div>
  )
}

function CarouselItem({ className, ...props }: React.ComponentProps<"div">) {
  const { orientation } = useCarousel()

  return (
    <div
      role="group"
      aria-roledescription="slide"
      data-slot="carousel-item"
      className={cn(
        "min-w-0 shrink-0 grow-0 basis-full",
        orientation === "horizontal" ? "pl-4" : "pt-4",
        className
      )}
      {...props}
    />
  )
}

function CarouselPrevious({
  className,
  variant = "outline",
  size = "icon-sm",
  onClick,
  ...props
}: React.ComponentProps<typeof Button>) {
  const { orientation, scrollPrev, canScrollPrev } = useCarousel()

  return (
    <Button
      data-slot="carousel-previous"
      variant={variant}
      size={size}
      className={cn(
        "absolute touch-manipulation rounded-full",
        orientation === "horizontal"
          ? "inset-y-0 -left-12 my-auto"
          : "-top-12 left-1/2 -translate-x-1/2 rotate-90",
        className
      )}
      disabled={!canScrollPrev}
      onClick={(event) => {
        event.stopPropagation()
        scrollPrev()
        onClick?.(event)
      }}
      {...props}
    >
      <HugeiconsIcon icon={ArrowLeft01Icon} strokeWidth={2} />
      <span className="sr-only">Предыдущий слайд</span>
    </Button>
  )
}

function CarouselNext({
  className,
  variant = "outline",
  size = "icon-sm",
  onClick,
  ...props
}: React.ComponentProps<typeof Button>) {
  const { orientation, scrollNext, canScrollNext } = useCarousel()

  return (
    <Button
      data-slot="carousel-next"
      variant={variant}
      size={size}
      className={cn(
        "absolute touch-manipulation rounded-full",
        orientation === "horizontal"
          ? "inset-y-0 -right-12 my-auto"
          : "-bottom-12 left-1/2 -translate-x-1/2 rotate-90",
        className
      )}
      disabled={!canScrollNext}
      onClick={(event) => {
        event.stopPropagation()
        scrollNext()
        onClick?.(event)
      }}
      {...props}
    >
      <HugeiconsIcon icon={ArrowRight01Icon} strokeWidth={2} />
      <span className="sr-only">Следующий слайд</span>
    </Button>
  )
}

function CarouselDots({
  className,
  getDotLabel,
  "aria-label": ariaLabel = "Навигация по слайдам",
  ...props
}: CarouselDotsProps) {
  const { api } = useCarousel()
  const [current, setCurrent] = React.useState(0)
  const [count, setCount] = React.useState(0)
  const viewportRef = React.useRef<HTMLDivElement>(null)
  const dotRefs = React.useRef<Array<HTMLButtonElement | null>>([])

  const update = React.useCallback((nextApi: CarouselApi) => {
    if (!nextApi) return
    setCurrent(nextApi.selectedScrollSnap())
    setCount(nextApi.scrollSnapList().length)
  }, [])

  React.useEffect(() => {
    if (!api) return
    let active = true
    const updateIfActive = () => {
      if (active) update(api)
    }

    queueMicrotask(updateIfActive)
    api.on("reInit", updateIfActive)
    api.on("select", updateIfActive)

    return () => {
      active = false
      api.off("reInit", updateIfActive)
      api.off("select", updateIfActive)
    }
  }, [api, update])

  React.useEffect(() => {
    const viewport = viewportRef.current
    const dot = dotRefs.current[current]
    if (!viewport || !dot || viewport.clientWidth === 0) return

    const dotStart = dot.offsetLeft
    const dotEnd = dotStart + dot.offsetWidth
    const viewportStart = viewport.scrollLeft
    const viewportEnd = viewportStart + viewport.clientWidth
    if (dotStart >= viewportStart && dotEnd <= viewportEnd) return

    viewport.scrollTo({
      left: Math.max(
        0,
        dotStart - (viewport.clientWidth - dot.offsetWidth) / 2
      ),
      behavior: "smooth",
    })
  }, [count, current])

  if (count <= 1) return null

  return (
    <div
      ref={viewportRef}
      role="group"
      aria-label={ariaLabel}
      data-slot="carousel-dots"
      className={cn(
        "[scrollbar-width:none] overflow-x-auto overscroll-x-contain py-1 [&::-webkit-scrollbar]:hidden",
        className
      )}
      {...props}
    >
      <div className="mx-auto flex w-max min-w-full items-center justify-center gap-1 px-2">
        {Array.from({ length: count }, (_, index) => {
          const active = index === current
          return (
            <Button
              key={index}
              ref={(node) => {
                dotRefs.current[index] = node
              }}
              type="button"
              size="icon-xs"
              variant="ghost"
              aria-label={
                getDotLabel?.(index, count) ??
                `Перейти к слайду ${index + 1} из ${count}`
              }
              aria-current={active ? "true" : undefined}
              className="rounded-full hover:bg-transparent"
              onClick={(event) => {
                event.stopPropagation()
                api?.scrollTo(index)
              }}
            >
              <span
                className={cn(
                  "size-1.5 rounded-full bg-muted-foreground/40 transition-[width,height,background-color]",
                  active && "size-2 bg-primary"
                )}
              />
            </Button>
          )
        })}
      </div>
    </div>
  )
}

export {
  type CarouselApi,
  Carousel,
  CarouselContent,
  CarouselDots,
  CarouselItem,
  CarouselNext,
  CarouselPrevious,
  useCarousel,
}
