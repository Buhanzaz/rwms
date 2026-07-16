export type MediaProcessingStatus =
  "UPLOADING" | "PROCESSING" | "READY" | "FAILED"

/**
 * The service keeps still images and videos under one media identity. Images
 * have WebP derivatives; a video is deliberately original-only.
 */
export type MediaKind = "IMAGE" | "VIDEO"

export function mediaKindFromMimeType(
  mimeType: string | null | undefined
): MediaKind {
  return mimeType?.toLowerCase().startsWith("video/") ? "VIDEO" : "IMAGE"
}

export type MediaProvenanceDto = {
  source: "BROWSER_MOCK" | "MEDIA_SERVICE"
  ingestService: "IO"
  derivativeService: "GO"
}

export type MediaViewerContext =
  "ESTIMATE" | "INSPECTION" | "WORK" | "WAREHOUSE" | "HISTORY"

export type OriginalMediaViewerContext = Extract<
  MediaViewerContext,
  "ESTIMATE" | "INSPECTION" | "WORK"
>

export type MediaUserPreferencesDto = {
  showOriginalPhotos: boolean
}

export function canUseOriginalMedia(context: MediaViewerContext) {
  return (
    context === "ESTIMATE" || context === "INSPECTION" || context === "WORK"
  )
}
