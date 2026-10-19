import { useCallback, useState, type SetStateAction } from "react"

import { useIsMobile } from "@/hooks/use-mobile"

/**
 * Keeps filter panels available by default on desktop while leaving them
 * collapsed on touch-first mobile layouts. The two states are intentionally
 * separate, so resizing does not unexpectedly reveal mobile filters.
 */
export function useResponsiveFiltersOpen() {
  const isMobile = useIsMobile()
  const [desktopFiltersOpen, setDesktopFiltersOpen] = useState(true)
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false)
  const filtersOpen = isMobile ? mobileFiltersOpen : desktopFiltersOpen

  const setFiltersOpen = useCallback(
    (next: SetStateAction<boolean>) => {
      if (isMobile) {
        setMobileFiltersOpen(next)
        return
      }

      setDesktopFiltersOpen(next)
    },
    [isMobile]
  )

  return { filtersOpen, setFiltersOpen }
}
