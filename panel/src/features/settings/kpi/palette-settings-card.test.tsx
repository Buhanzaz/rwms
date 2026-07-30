import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { WarehouseKpiSettings } from "@/features/settings/kpi/api/kpi-settings-api"
import { PaletteSettingsCard } from "@/features/settings/kpi/palette-settings-card"

const pointerCaptureDescriptors = new Map(
  ["hasPointerCapture", "setPointerCapture", "releasePointerCapture"].map(
    (name) => [
      name,
      Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
    ]
  )
)
const setPointerCapture = vi.fn()
const releasePointerCapture = vi.fn()

const settings: WarehouseKpiSettings = {
  warehouseId: "00000000-0000-4000-8000-000000000001",
  timeZone: "Europe/Moscow",
  status: "DRAFT",
  version: 3,
  dataAvailableFrom: null,
  repairComplexity: {
    lightBoundaryMinutes: 60,
    mediumBoundaryMinutes: 180,
    complexBoundaryMinutes: 360,
  },
  palette: {
    version: 1,
    ranges: [
      { fromPercent: 0, toPercent: 35, color: "#DC2626" },
      { fromPercent: 35, toPercent: 70, color: "#EAB308" },
      { fromPercent: 70, toPercent: 100, color: "#16A34A" },
    ],
    overdueColor: "#7F1D1D",
  },
  activeSchedule: null,
  pendingSchedule: null,
}

function renderCard() {
  return render(
    <PaletteSettingsCard
      settings={settings}
      saving={false}
      blocked={false}
      actionError={null}
      onSave={vi.fn()}
    />
  )
}

function setTrackBounds() {
  const track = screen.getByLabelText("Шкала диапазонов KPI")
  Object.defineProperty(track, "getBoundingClientRect", {
    configurable: true,
    value: () => ({ left: 100, width: 200 }) as DOMRect,
  })
}

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: {
      configurable: true,
      value: () => true,
    },
    setPointerCapture: {
      configurable: true,
      value: setPointerCapture,
    },
    releasePointerCapture: {
      configurable: true,
      value: releasePointerCapture,
    },
  })
})

beforeEach(() => {
  setPointerCapture.mockClear()
  releasePointerCapture.mockClear()
})

afterEach(cleanup)

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

describe("PaletteSettingsCard boundary editing", () => {
  it("selects a separator on a plain click without creating another range", () => {
    renderCard()
    setTrackBounds()

    const boundary = screen.getByRole("button", { name: "Граница 35%" })
    expect(screen.getByText("35%")).toBeTruthy()

    fireEvent.pointerDown(boundary, {
      pointerId: 4,
      pointerType: "mouse",
      button: 0,
      clientX: 170,
    })
    fireEvent.pointerUp(boundary, {
      pointerId: 4,
      pointerType: "mouse",
      clientX: 170,
    })
    fireEvent.click(boundary, { clientX: 180 })

    expect(
      screen.getByRole("button", { name: "Удалить границу 35%" })
    ).toBeTruthy()
    expect(screen.getAllByRole("button", { name: /^Граница / })).toHaveLength(2)

    fireEvent.click(screen.getByRole("button", { name: "Удалить границу 35%" }))

    expect(screen.queryByRole("button", { name: "Граница 35%" })).toBeNull()
    expect(screen.getAllByRole("button", { name: /^Граница / })).toHaveLength(1)
  })

  it("drags a separator, updates its visible percent and preserves a one-percent neighbor", () => {
    renderCard()
    setTrackBounds()

    const boundary = screen.getByRole("button", { name: "Граница 35%" })
    fireEvent.pointerDown(boundary, {
      pointerId: 7,
      pointerType: "mouse",
      button: 0,
      clientX: 170,
    })
    fireEvent.pointerMove(boundary, {
      pointerId: 7,
      pointerType: "mouse",
      clientX: 300,
    })

    expect(setPointerCapture).toHaveBeenCalledWith(7)
    expect(screen.getByText("69%")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Граница 69%" })).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Удалить границу 69%" })
    ).toBeTruthy()

    fireEvent.pointerMove(boundary, {
      pointerId: 7,
      pointerType: "mouse",
      clientX: 100,
    })

    const lowerBoundary = screen.getByRole("button", { name: "Граница 1%" })
    expect(screen.getByText("1%")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Удалить границу 1%" })
    ).toBeTruthy()

    fireEvent.pointerUp(lowerBoundary, {
      pointerId: 7,
      pointerType: "mouse",
      clientX: 100,
    })

    expect(releasePointerCapture).toHaveBeenCalledWith(7)
  })

  it("keeps keyboard movement and deletion for the selected separator", () => {
    renderCard()

    const boundary = screen.getByRole("button", { name: "Граница 35%" })
    fireEvent.keyDown(boundary, { key: "ArrowRight" })

    const movedBoundary = screen.getByRole("button", { name: "Граница 36%" })
    expect(movedBoundary.getAttribute("aria-pressed")).toBe("true")

    fireEvent.keyDown(movedBoundary, { key: "Delete" })

    expect(screen.queryByRole("button", { name: "Граница 36%" })).toBeNull()
    expect(screen.getAllByRole("button", { name: /^Граница / })).toHaveLength(1)
  })
})
