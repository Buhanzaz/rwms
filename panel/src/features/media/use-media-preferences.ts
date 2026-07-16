import { useCallback, useEffect, useState } from "react"

import { LocalStorageMediaPreferencesAdapter } from "@/features/media/adapters/local-storage-media-preferences-adapter"
import {
  canUseOriginalMedia,
  type MediaViewerContext,
} from "@/features/media/model/media"

const mediaPreferencesClient = new LocalStorageMediaPreferencesAdapter()

export function useOriginalPhotoPreference(
  userId: string | null | undefined,
  context: MediaViewerContext
) {
  const allowed = canUseOriginalMedia(context)
  const [showOriginalPhotos, setShowOriginalPhotosState] = useState(false)
  const [loading, setLoading] = useState(allowed && Boolean(userId))

  useEffect(() => {
    let cancelled = false
    if (!allowed || !userId) {
      return
    }
    queueMicrotask(() => {
      if (!cancelled) setLoading(true)
    })
    void mediaPreferencesClient.get(userId).then((preferences) => {
      if (cancelled) return
      setShowOriginalPhotosState(preferences.showOriginalPhotos)
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [allowed, userId])

  const setShowOriginalPhotos = useCallback(
    async (nextValue: boolean) => {
      if (!allowed || !userId) return
      setShowOriginalPhotosState(nextValue)
      try {
        await mediaPreferencesClient.update(userId, {
          showOriginalPhotos: nextValue,
        })
      } catch (error) {
        setShowOriginalPhotosState(!nextValue)
        throw error
      }
    },
    [allowed, userId]
  )

  return {
    allowed,
    loading: allowed && Boolean(userId) && loading,
    showOriginalPhotos: allowed && showOriginalPhotos,
    setShowOriginalPhotos,
  }
}
