import * as React from "react"
import {
  cleanup,
  createEvent,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

afterEach(cleanup)

vi.mock("@/components/ui/carousel", async () => {
  type Listener = () => void
  type FakeApi = {
    selectedScrollSnap: () => number
    scrollSnapList: () => number[]
    scrollTo: (index: number) => void
    scrollPrev: () => void
    scrollNext: () => void
    on: (event: string, listener: Listener) => void
    off: (event: string, listener: Listener) => void
  }

  function Carousel({
    setApi,
    children,
    opts: _opts,
    ...props
  }: React.ComponentProps<"div"> & {
    opts?: unknown
    setApi?: (api: FakeApi) => void
  }) {
    void _opts
    const listeners = React.useRef(new Set<Listener>())
    const index = React.useRef(0)
    const count = React.Children.count(children)
    const api = React.useMemo<FakeApi>(
      () => ({
        selectedScrollSnap: () => index.current,
        scrollSnapList: () =>
          Array.from({ length: count }, (_, value) => value),
        scrollTo: (nextIndex) => {
          index.current = nextIndex
          listeners.current.forEach((listener) => listener())
        },
        scrollPrev: () => {
          index.current = index.current === 0 ? 1 : index.current - 1
          listeners.current.forEach((listener) => listener())
        },
        scrollNext: () => {
          index.current += 1
          listeners.current.forEach((listener) => listener())
        },
        on: (_event, listener) => {
          listeners.current.add(listener)
        },
        off: (_event, listener) => {
          listeners.current.delete(listener)
        },
      }),
      [count]
    )
    React.useEffect(() => setApi?.(api), [api, setApi])
    return <div {...props}>{children}</div>
  }

  return {
    Carousel,
    CarouselContent: (props: React.ComponentProps<"div">) => <div {...props} />,
    CarouselItem: (props: React.ComponentProps<"div">) => <div {...props} />,
  }
})

import { PhotoCarousel } from "@/components/media/photo-carousel"

const photos = [
  {
    id: "photo-1",
    url: "/fallback-1.jpg",
    variants: {
      small: { url: "/thumb-1.jpg" },
      largeWebp: { url: "/preview-1.webp" },
      original: { url: "/original-1.jpg" },
    },
  },
  {
    id: "photo-2",
    url: "/fallback-2.jpg",
    variants: {
      small: { url: "/thumb-2.jpg" },
      largeWebp: { url: "/preview-2.webp" },
      original: { url: "/original-2.jpg" },
    },
  },
]

function movePointer(
  element: HTMLElement,
  clientX: number,
  pointerType = "mouse"
) {
  const event = createEvent.pointerMove(element)
  Object.defineProperties(event, {
    clientX: { value: clientX },
    pointerType: { value: pointerType },
  })
  fireEvent(element, event)
}

function measurePhoto(element: HTMLElement) {
  vi.spyOn(element, "getBoundingClientRect").mockReturnValue({
    left: 100,
    right: 600,
    top: 0,
    bottom: 400,
    width: 500,
    height: 400,
    x: 100,
    y: 0,
    toJSON: () => ({}),
  })
}

describe("PhotoCarousel", () => {
  it("shows a fullscreen load failure instead of an endless spinner and exposes an explicit retry", async () => {
    const onRequestFullscreen = vi.fn()
    const photo = {
      id: "failed-photo",
      url: "/small.jpg",
      fullscreenError: "Временная ошибка сервиса",
    }
    render(
      <PhotoCarousel
        photos={[photo]}
        onRequestFullscreen={onRequestFullscreen}
      />
    )
    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    await screen.findByRole("dialog")
    expect(screen.getByRole("alert").textContent).toContain(
      "Не удалось загрузить фотографию"
    )
    expect(
      screen.queryByText("Загрузка полноэкранной фотографии...")
    ).toBeNull()
    onRequestFullscreen.mockClear()
    fireEvent.click(
      screen.getByRole("button", { name: "Повторить загрузку фото" })
    )
    expect(onRequestFullscreen).toHaveBeenCalledOnce()
    expect(onRequestFullscreen).toHaveBeenCalledWith(photo, { retry: true })
  })

  it("progressively reveals only the nearest edge and clears it on leave", () => {
    render(
      <PhotoCarousel photos={photos} controlsVisibility="mobile-visible" />
    )
    const carousel = screen.getByLabelText("Фотографии")
    measurePhoto(carousel)
    const opacity = (edge: string) =>
      Number(carousel.style.getPropertyValue(`--photo-edge-${edge}`))

    movePointer(carousel, 350)
    expect(opacity("left")).toBe(0)
    expect(opacity("right")).toBe(0)
    movePointer(carousel, 175)
    expect(opacity("left")).toBeCloseTo(0.25)
    expect(opacity("right")).toBe(0)
    movePointer(carousel, 125)
    expect(opacity("left")).toBeCloseTo(0.75)
    movePointer(carousel, 100)
    expect(opacity("left")).toBe(1)
    movePointer(carousel, 575)
    expect(opacity("left")).toBe(0)
    expect(opacity("right")).toBeCloseTo(0.75)
    fireEvent.pointerLeave(carousel)
    expect(opacity("left")).toBe(0)
    expect(opacity("right")).toBe(0)
  })

  it("does not apply mouse shading to touch swipes and keeps keyboard controls focusable", () => {
    render(
      <PhotoCarousel photos={photos} controlsVisibility="mobile-visible" />
    )
    const carousel = screen.getByLabelText("Фотографии")
    measurePhoto(carousel)
    movePointer(carousel, 100, "touch")
    expect(carousel.style.getPropertyValue("--photo-edge-left")).toBe("")

    const next = screen.getByRole("button", { name: "Следующее фото" })
    next.focus()
    expect(document.activeElement).toBe(next)
    expect(next.dataset.visibility).toBe("mobile-visible")
  })

  it("uses the same progressive edges in the blue fullscreen viewer", async () => {
    render(<PhotoCarousel photos={photos} title="Бытовка 42" />)
    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    const dialog = await screen.findByRole("dialog")
    expect(dialog.className).toContain("bg-primary")
    const carousel = dialog.querySelector<HTMLElement>(
      '[aria-label="Бытовка 42"]'
    )!
    measurePhoto(carousel)
    movePointer(carousel, 350)
    expect(carousel.style.getPropertyValue("--photo-edge-right")).toBe("0")
    movePointer(carousel, 600)
    expect(carousel.style.getPropertyValue("--photo-edge-right")).toBe("1")
    expect(carousel.style.getPropertyValue("--photo-edge-left")).toBe("0")
  })

  it("uses arrows for navigation without opening fullscreen", async () => {
    const onActiveIndexChange = vi.fn()
    const onCenterClick = vi.fn()
    render(
      <PhotoCarousel
        photos={photos}
        controlsVisibility="always"
        onActiveIndexChange={onActiveIndexChange}
        onCenterClick={onCenterClick}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Следующее фото" }))

    await waitFor(() => expect(onActiveIndexChange).toHaveBeenCalledWith(1))
    expect(onCenterClick).not.toHaveBeenCalled()
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("opens from the center and keeps keyboard navigation in sync", async () => {
    render(
      <PhotoCarousel photos={photos} title="Фото бытовки" showViewerToolbar />
    )

    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    expect(await screen.findByRole("dialog")).toBeTruthy()
    fireEvent.keyDown(await screen.findByRole("dialog"), { key: "ArrowRight" })

    await waitFor(() => expect(screen.getByText("2 / 2")).toBeTruthy())
    fireEvent.click(screen.getByRole("button", { name: "Закрыть" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
  })

  it("shows shaded edge controls, supports zoom, and does not expose rotation", async () => {
    render(<PhotoCarousel photos={photos} title="Фото до" />)

    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    const dialog = await screen.findByRole("dialog")
    const nextControl = dialog.querySelector<HTMLButtonElement>(
      '[data-slot="photo-fullscreen-next"]'
    )
    const previousControl = dialog.querySelector<HTMLButtonElement>(
      '[data-slot="photo-fullscreen-previous"]'
    )

    expect(nextControl?.className).toContain("bg-gradient-to-l")
    expect(nextControl?.className).toContain("photo-edge-control")
    expect(nextControl?.dataset.edge).toBe("right")
    expect(nextControl?.dataset.visibility).toBe("mobile-visible")
    expect(previousControl?.className).toContain("bg-gradient-to-r")

    fireEvent.click(nextControl!)
    await waitFor(() => expect(screen.getByText("2 / 2")).toBeTruthy())

    const image = screen.getByRole("img", {
      name: "Фото до, фото 2 из 2",
    })
    expect(screen.queryByRole("button", { name: "Повернуть фото" })).toBeNull()
    expect(image.style.transform).not.toContain("rotate(")
    fireEvent.click(image.closest("button")!)

    await waitFor(() => expect(image.style.transform).toContain("scale(2)"))
  })

  it("requests LARGE for the initial fullscreen slide and every navigation", async () => {
    const onRequestFullscreen = vi.fn()
    const lazyPhotos = photos.map((photo, index) => ({
      id: photo.id,
      url: `/medium-${index + 1}.webp`,
      variants: { medium: { url: `/medium-${index + 1}.webp` } },
    }))
    render(
      <PhotoCarousel
        photos={lazyPhotos}
        title="Ленивая загрузка"
        onRequestFullscreen={onRequestFullscreen}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    const dialog = await screen.findByRole("dialog")
    await waitFor(() =>
      expect(onRequestFullscreen).toHaveBeenCalledWith(lazyPhotos[0])
    )
    expect(
      screen.getAllByText("Загрузка полноэкранной фотографии...").length
    ).toBeGreaterThan(0)
    expect(
      screen.queryByRole("img", {
        name: "Ленивая загрузка, фото 1 из 2",
      })
    ).toBeNull()

    fireEvent.keyDown(dialog, { key: "ArrowRight" })
    await waitFor(() =>
      expect(onRequestFullscreen).toHaveBeenCalledWith(lazyPhotos[1])
    )
  })

  it("waits for the original when fullscreen quality requires it", async () => {
    const onRequestFullscreen = vi.fn()
    const lazyPhoto = {
      id: "photo-1",
      url: "/small-1.webp",
      variants: { small: { url: "/small-1.webp" } },
    }
    const { rerender } = render(
      <PhotoCarousel
        photos={[lazyPhoto]}
        title="Полный размер"
        fullscreenQuality="original"
        onRequestFullscreen={onRequestFullscreen}
      />
    )

    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    await waitFor(() =>
      expect(onRequestFullscreen).toHaveBeenCalledWith(lazyPhoto)
    )
    expect(
      screen.getByText("Загрузка полноэкранной фотографии...")
    ).toBeTruthy()

    rerender(
      <PhotoCarousel
        photos={[
          {
            ...lazyPhoto,
            variants: {
              ...lazyPhoto.variants,
              original: { url: "/original-1.jpg" },
            },
          },
        ]}
        title="Полный размер"
        fullscreenQuality="original"
        onRequestFullscreen={onRequestFullscreen}
      />
    )

    expect(
      (
        await screen.findByRole("img", {
          name: "Полный размер, фото 1 из 1",
        })
      ).getAttribute("src")
    ).toBe("/original-1.jpg")
  })

  it("uses preview by default and original only when explicitly requested", async () => {
    const { rerender } = render(
      <PhotoCarousel photos={[photos[0]]} title="Проверка качества" />
    )
    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    expect(
      (
        await screen.findByRole("img", {
          name: "Проверка качества, фото 1 из 1",
        })
      ).getAttribute("src")
    ).toBe("/preview-1.webp")

    fireEvent.click(screen.getByRole("button", { name: "Закрыть" }))
    rerender(
      <PhotoCarousel
        photos={[photos[0]]}
        title="Проверка качества"
        fullscreenQuality="original"
      />
    )
    fireEvent.click(screen.getByRole("button", { name: "Открыть фото 1" }))
    expect(
      (
        await screen.findByRole("img", {
          name: "Проверка качества, фото 1 из 1",
        })
      ).getAttribute("src")
    ).toBe("/original-1.jpg")
  })

  it("does not expose a dead center button when fullscreen is disabled", () => {
    render(<PhotoCarousel photos={[photos[0]]} disableFullscreenViewer />)

    expect(screen.queryByRole("button", { name: "Открыть фото 1" })).toBeNull()
    expect(screen.getByRole("img", { name: "Фото 1 из 1" })).toBeTruthy()
  })
})
