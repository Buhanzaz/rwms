export const PANEL_AUTH_CALLBACK_PATH = "/auth/callback"

export function isPanelAuthCallbackRequest(
  requestUrl: string,
  browserOrigin: string
) {
  return (
    new URL(requestUrl, browserOrigin).pathname === PANEL_AUTH_CALLBACK_PATH
  )
}
