import type {
  PendingEstimateMediaUpload,
  RepairEstimateMediaId,
  RepairEstimateMediaRefDto,
  RepairEstimateMediaRotationDegrees,
} from "@/features/repair-estimates/model/repair-estimate"
import type { OriginalMediaViewerContext } from "@/features/media/model/media"

export interface RepairEstimateMediaClient {
  upload(
    uploads: PendingEstimateMediaUpload[]
  ): Promise<RepairEstimateMediaRefDto[]>
  hydrate(
    refs: RepairEstimateMediaRefDto[]
  ): Promise<RepairEstimateMediaRefDto[]>
  resolveOriginalUrl(
    mediaId: RepairEstimateMediaId,
    context: OriginalMediaViewerContext
  ): Promise<string>
  dehydrate(refs: RepairEstimateMediaRefDto[]): RepairEstimateMediaRefDto[]
  updateRotation(
    mediaId: RepairEstimateMediaId,
    rotationDegrees: RepairEstimateMediaRotationDegrees
  ): Promise<RepairEstimateMediaRefDto>
  discard(mediaIds: RepairEstimateMediaId[]): Promise<void>
}
