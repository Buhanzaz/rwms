export type MediaProcessingStatus =
  "UPLOADING" | "PROCESSING" | "READY" | "FAILED"

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
