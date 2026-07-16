import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import {
  Carousel,
  CarouselContent,
  CarouselItem,
  CarouselNext,
  type CarouselApi,
} from "@/components/ui/carousel"

const originalDescriptors = new Map<string, PropertyDescriptor | undefined>()

function installDimension(name: string, get: (element: HTMLElement) => number) {
  originalDescriptors.set(
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name)
  )
  Object.defineProperty(HTMLElement.prototype, name, {
    configurable: true,
    get() {
      return get(this)
    },
  })
}

beforeAll(() => {
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  class IntersectionObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  vi.stubGlobal("IntersectionObserver", IntersectionObserverMock)
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false,
    }))
  )
  installDimension("offsetWidth", (element) => {
    if (element.parentElement?.dataset.slot === "carousel-content") {
      return element.childElementCount * 320
    }
    return 320
  })
  installDimension("offsetHeight", () => 240)
  installDimension("offsetLeft", (element) => {
    if (element.dataset.slot !== "carousel-item") return 0
    return (
      Array.from(element.parentElement?.children ?? []).indexOf(element) * 320
    )
  })
  installDimension("offsetTop", () => 0)
  originalDescriptors.set(
    "offsetParent",
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetParent")
  )
  Object.defineProperty(HTMLElement.prototype, "offsetParent", {
    configurable: true,
    get() {
      return this.parentElement
    },
  })
})

afterEach(cleanup)

afterAll(() => {
  for (const [name, descriptor] of originalDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
  vi.unstubAllGlobals()
})

function getViewport() {
  const viewport = document.querySelector<HTMLElement>(
    '[data-slot="carousel-content"]'
  )
  if (!viewport) throw new Error("Carousel viewport was not rendered")
  return viewport
}

describe("PhotoCarousel with real Embla", () => {
  function renderRealEmbla(onCenterClick = vi.fn()) {
    let api: CarouselApi
    render(
      <Carousel
        setApi={(nextApi) => (api = nextApi)}
        opts={{ containScroll: false, loop: false }}
      >
        <CarouselContent>
          <CarouselItem>
            <button type="button" onClick={onCenterClick}>
              Фото 1
            </button>
          </CarouselItem>
          <CarouselItem>Фото 2</CarouselItem>
        </CarouselContent>
      </Carousel>
    )
    return {
      getApi: () => api,
      onCenterClick,
    }
  }

  it("handles mouse drag with real Embla and suppresses its click", async () => {
    const harness = renderRealEmbla()
    await waitFor(() => expect(harness.getApi()).toBeTruthy())
    const api = harness.getApi()
    expect(api?.scrollSnapList()).toHaveLength(2)

    fireEvent.mouseDown(getViewport(), {
      button: 0,
      buttons: 1,
      clientX: 280,
      clientY: 100,
    })
    expect(api?.internalEngine().dragHandler.pointerDown()).toBe(true)
    fireEvent.mouseMove(document, {
      buttons: 1,
      clientX: 160,
      clientY: 100,
    })
    await new Promise((resolve) => setTimeout(resolve, 24))
    fireEvent.mouseMove(document, {
      buttons: 1,
      clientX: 40,
      clientY: 100,
    })
    await new Promise((resolve) => setTimeout(resolve, 24))
    fireEvent.mouseUp(document, {
      button: 0,
      clientX: 40,
      clientY: 100,
    })
    expect(api?.internalEngine().dragHandler.pointerDown()).toBe(false)
    await waitFor(() => expect(api?.selectedScrollSnap()).toBe(1))
    fireEvent.click(screen.getByRole("button", { name: "Фото 1" }))
    expect(harness.onCenterClick).not.toHaveBeenCalled()
  })

  it("handles touch swipe with real Embla", async () => {
    const harness = renderRealEmbla()
    await waitFor(() => expect(harness.getApi()).toBeTruthy())
    const api = harness.getApi()
    expect(api?.scrollSnapList()).toHaveLength(2)

    fireEvent.touchStart(getViewport(), {
      touches: [{ clientX: 280, clientY: 100 }],
    })
    expect(api?.internalEngine().dragHandler.pointerDown()).toBe(true)
    fireEvent.touchMove(getViewport(), {
      cancelable: true,
      touches: [{ clientX: 160, clientY: 100 }],
    })
    await new Promise((resolve) => setTimeout(resolve, 24))
    fireEvent.touchMove(getViewport(), {
      cancelable: true,
      touches: [{ clientX: 40, clientY: 100 }],
    })
    await new Promise((resolve) => setTimeout(resolve, 24))
    fireEvent.touchEnd(getViewport(), { touches: [] })
    expect(api?.internalEngine().dragHandler.pointerDown()).toBe(false)
    await waitFor(() => expect(api?.selectedScrollSnap()).toBe(1))
  })

  it("keeps a real Embla arrow click out of the parent click surface", async () => {
    const onParentClick = vi.fn()
    render(
      <Carousel
        opts={{ containScroll: false, loop: false }}
        onClick={onParentClick}
      >
        <CarouselContent>
          <CarouselItem>Фото 1</CarouselItem>
          <CarouselItem>Фото 2</CarouselItem>
        </CarouselContent>
        <CarouselNext disabled={false} />
      </Carousel>
    )
    const next = await screen.findByRole("button", {
      name: "Следующий слайд",
    })

    fireEvent.click(next)

    expect(onParentClick).not.toHaveBeenCalled()
  })
})
