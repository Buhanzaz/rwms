import type { MediaUserPreferencesDto } from "@/features/media/model/media"

export interface MediaPreferencesClient {
  get(userId: string): Promise<MediaUserPreferencesDto>
  update(
    userId: string,
    preferences: MediaUserPreferencesDto
  ): Promise<MediaUserPreferencesDto>
}
