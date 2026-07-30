import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { useIsMobile } from "@/hooks/use-mobile"

const MOBILE_QUERY =
  "(max-width: 767px) and (pointer: coarse), (max-height: 500px) and (pointer: coarse)"

function installMatchMedia({
  narrow,
  coarsePointer,
}: {
  narrow: boolean
  coarsePointer: boolean
}) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: query === MOBILE_QUERY && narrow && coarsePointer,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  )
}

function MobileProbe() {
  const isMobile = useIsMobile()

  return <output>{isMobile ? "mobile" : "desktop"}</output>
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("useIsMobile", () => {
  it("keeps a narrow desktop viewport in desktop mode", () => {
    installMatchMedia({ narrow: true, coarsePointer: false })

    render(<MobileProbe />)

    expect(screen.getByText("desktop")).toBeTruthy()
    expect(window.matchMedia).toHaveBeenCalledWith(MOBILE_QUERY)
  })

  it("uses mobile mode for a narrow coarse-pointer device", () => {
    installMatchMedia({ narrow: true, coarsePointer: true })

    render(<MobileProbe />)

    expect(screen.getByText("mobile")).toBeTruthy()
  })
})
