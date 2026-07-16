import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import type { RepairEstimateMediaId } from "@/features/repair-estimates/model/repair-estimate"
import type { OriginalMediaViewerContext } from "@/features/media/model/media"

const originalMediaClient = new IndexedDbRepairEstimateMediaAdapter()

export function resolveOriginalMediaUrl(
  mediaId: RepairEstimateMediaId,
  context: OriginalMediaViewerContext
) {
  return originalMediaClient.resolveOriginalUrl(mediaId, context)
}
