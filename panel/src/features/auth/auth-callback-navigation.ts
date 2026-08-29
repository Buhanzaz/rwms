import type { NavigateFunction } from "react-router-dom"

/**
 * Leaves the panel SPA when the shared OIDC callback belongs to the standalone
 * logistics workspace; ordinary panel destinations retain client-side routing.
 */
export function navigateAfterLogin(
  returnTo: string,
  navigate: NavigateFunction,
  assign: (target: string) => void = (target) => window.location.assign(target)
) {
  if (
    returnTo === "/logistics-simulator" ||
    returnTo.startsWith("/logistics-simulator/")
  ) {
    assign(returnTo)
    return
  }

  navigate(returnTo, { replace: true })
}
