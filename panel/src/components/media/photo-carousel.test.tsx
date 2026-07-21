import * as React from "react"
import {
  cleanup,
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

describe("PhotoCarousel", () => {
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
