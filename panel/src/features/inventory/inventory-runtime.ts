export const DEV_INVENTORY_FIXTURES_ENABLED =
  import.meta.env.DEV && import.meta.env.VITE_DEV_INVENTORY_FIXTURES === "true"

export function requireInventoryAccessToken(accessToken: string | null) {
  if (!accessToken?.trim()) {
    throw new Error("Для инвентаризации требуется авторизация")
  }
  return accessToken
}
