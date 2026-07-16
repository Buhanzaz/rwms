import { useEffect, useMemo, useState } from "react"

import { resolveOriginalMediaUrl } from "@/features/media/api/media-original-api"
import type { OriginalMediaViewerContext } from "@/features/media/model/media"

type OriginalMediaCandidate = {
  id: string
  originalAvailable?: boolean
}

export function useOriginalMediaUrls(
  media: OriginalMediaCandidate[],
  enabled: boolean,
  context: OriginalMediaViewerContext
) {
  const requestKey = useMemo(
    () =>
      media
        .filter((item) => item.originalAvailable === true)
        .map((item) => item.id)
        .join("\u0000"),
    [media]
  )
  const [urlsByMediaId, setUrlsByMediaId] = useState<Record<string, string>>({})

  useEffect(() => {
    const eligibleIds = requestKey ? requestKey.split("\u0000") : []
    if (!enabled || eligibleIds.length === 0) return
    let cancelled = false
    void Promise.allSettled(
      eligibleIds.map(async (mediaId) => ({
        mediaId,
        url: await resolveOriginalMediaUrl(mediaId, context),
      }))
    ).then((results) => {
      if (cancelled) return
      const resolved = Object.fromEntries(
        results.flatMap((result) =>
          result.status === "fulfilled"
            ? [[result.value.mediaId, result.value.url]]
            : []
        )
      )
      setUrlsByMediaId(resolved)
    })
    return () => {
      cancelled = true
    }
  }, [context, enabled, requestKey])

  return enabled ? urlsByMediaId : {}
}
