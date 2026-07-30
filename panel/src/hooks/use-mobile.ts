import * as React from "react"

const MOBILE_BREAKPOINT = 768
const TABLET_BREAKPOINT = 1024
const MOBILE_QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px) and (pointer: coarse), (max-height: 500px) and (pointer: coarse)`

function getMediaQueryList(query: string) {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return null
  }

  return window.matchMedia(query)
}

function useMatchMedia(query: string) {
  const [matches, setMatches] = React.useState(() => {
    return getMediaQueryList(query)?.matches ?? false
  })

  React.useEffect(() => {
    const mql = getMediaQueryList(query)
    if (!mql) return

    const onChange = () => {
      setMatches(mql.matches)
    }

    onChange()
    mql.addEventListener("change", onChange)

    return () => mql.removeEventListener("change", onChange)
  }, [query])

  return matches
}

export function useIsMobile() {
  return useMatchMedia(MOBILE_QUERY)
}

export function useIsTabletOrSmaller() {
  return useMatchMedia(`(max-width: ${TABLET_BREAKPOINT - 1}px)`)
}
