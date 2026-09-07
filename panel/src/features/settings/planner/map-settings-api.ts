import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"

export type MapProvider = "STANDARD" | "YANDEX"

export interface AdminMapSettings {
  version: number
  provider: MapProvider
  yandex_api_key_configured: boolean
}

export interface MapSettingsInput {
  expected_version: number
  provider: MapProvider
  yandex_api_key: string | null
}

function parseSettings(value: unknown): AdminMapSettings {
  const row = value as Partial<AdminMapSettings> | null
  if (
    !row ||
    typeof row !== "object" ||
    !Number.isSafeInteger(row.version) ||
    (row.version ?? 0) < 1 ||
    !["STANDARD", "YANDEX"].includes(row.provider ?? "") ||
    typeof row.yandex_api_key_configured !== "boolean" ||
    (row.provider === "YANDEX" && !row.yandex_api_key_configured)
  ) {
    throw invalidApiResponseError(new Error("Некорректные настройки карты"))
  }
  return {
    version: row.version!,
    provider: row.provider!,
    yandex_api_key_configured: row.yandex_api_key_configured,
  }
}

function settingsUrl() {
  return `${window.location.origin}/api/logistics-planner/v1/admin/map-settings`
}

export const mapSettingsApi = {
  async get(token: string, signal?: AbortSignal) {
    return parseSettings(
      await bearerRequest<unknown>(token, settingsUrl(), {
        signal,
        cache: "no-store",
      })
    )
  },
  async save(token: string, input: MapSettingsInput) {
    return parseSettings(
      await bearerRequest<unknown>(token, settingsUrl(), {
        method: "PUT",
        body: JSON.stringify(input),
        cache: "no-store",
      })
    )
  },
}

/** Notify same-origin logistics tabs to reload the server setting; never broadcast a key. */
export function notifyMapSettingsChanged(version: number) {
  if (typeof BroadcastChannel === "undefined") return
  try {
    const channel = new BroadcastChannel("rwms-logistics-map-settings")
    try {
      channel.postMessage({ type: "changed", version })
    } finally {
      channel.close()
    }
  } catch {
    // Logistics also reloads on focus and polls; notification failure cannot undo a saved setting.
  }
}
