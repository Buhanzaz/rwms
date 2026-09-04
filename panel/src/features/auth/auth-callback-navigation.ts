import type { NavigateFunction } from "react-router-dom"

function normalizedApplicationBasePath(applicationBasePath: string) {
  if (applicationBasePath === "" || applicationBasePath === "/") return ""
  return `/${applicationBasePath.replace(/^\/+|\/+$/g, "")}`
}

/** Converts a basename-local router location into the external path stored in OIDC state. */
export function toExternalApplicationPath(
  routerPath: string,
  applicationBasePath = ""
) {
  const basePath = normalizedApplicationBasePath(applicationBasePath)
  if (basePath === "") return routerPath
  return routerPath === "/" ? `${basePath}/` : `${basePath}${routerPath}`
}

/** Converts a validated application path back to the location expected by a basename router. */
export function toApplicationRouterPath(
  externalPath: string,
  applicationBasePath = ""
) {
  const basePath = normalizedApplicationBasePath(applicationBasePath)
  if (basePath === "") return externalPath
  if (externalPath === basePath || externalPath === `${basePath}/`) return "/"
  return externalPath.startsWith(`${basePath}/`)
    ? externalPath.slice(basePath.length)
    : "/"
}

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
    returnTo === "/logistics-panel" ||
    returnTo.startsWith("/logistics-panel/")
  ) {
    assign(returnTo)
    return
  }

  navigate(returnTo, { replace: true })
}
