import * as React from "react"

const MOBILE_BREAKPOINT = 768
const TABLET_BREAKPOINT = 1024

function useMatchMedia(query: string) {
  const [matches, setMatches] = React.useState(() => {
    return window.matchMedia(query).matches
  })

  React.useEffect(() => {
    const mql = window.matchMedia(query)
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
  return useMatchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
}

export function useIsTabletOrSmaller() {
  return useMatchMedia(`(max-width: ${TABLET_BREAKPOINT - 1}px)`)
}
