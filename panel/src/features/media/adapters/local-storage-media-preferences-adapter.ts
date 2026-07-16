import type { MediaUserPreferencesDto } from "@/features/media/model/media"
import type { MediaPreferencesClient } from "@/features/media/ports/media-preferences-client"

export const MEDIA_PREFERENCES_STORAGE_KEY = "rwms:media-preferences:v1"

type MediaPreferencesEnvelope = {
  schemaVersion: 1
  byUserId: Record<string, MediaUserPreferencesDto>
}

const DEFAULT_PREFERENCES: MediaUserPreferencesDto = {
  showOriginalPhotos: false,
}

function readEnvelope(): MediaPreferencesEnvelope {
  if (typeof window === "undefined") {
    return { schemaVersion: 1, byUserId: {} }
  }

  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(MEDIA_PREFERENCES_STORAGE_KEY) ?? "null"
    ) as Partial<MediaPreferencesEnvelope> | null
    if (
      parsed?.schemaVersion !== 1 ||
      !parsed.byUserId ||
      typeof parsed.byUserId !== "object"
    ) {
      return { schemaVersion: 1, byUserId: {} }
    }
    return { schemaVersion: 1, byUserId: parsed.byUserId }
  } catch {
    return { schemaVersion: 1, byUserId: {} }
  }
}

export class LocalStorageMediaPreferencesAdapter implements MediaPreferencesClient {
  async get(userId: string) {
    const preferences = readEnvelope().byUserId[userId]
    return {
      showOriginalPhotos: preferences?.showOriginalPhotos === true,
    }
  }

  async update(userId: string, preferences: MediaUserPreferencesDto) {
    if (!userId.trim()) throw new Error("Не указан пользователь")
    const envelope = readEnvelope()
    const normalized = {
      showOriginalPhotos: preferences.showOriginalPhotos === true,
    }
    window.localStorage.setItem(
      MEDIA_PREFERENCES_STORAGE_KEY,
      JSON.stringify({
        schemaVersion: 1,
        byUserId: { ...envelope.byUserId, [userId]: normalized },
      } satisfies MediaPreferencesEnvelope)
    )
    return normalized
  }
}

export { DEFAULT_PREFERENCES as DEFAULT_MEDIA_PREFERENCES }
