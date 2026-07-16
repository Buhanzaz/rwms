import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import type { PendingEstimateMediaUpload } from "@/features/repair-estimates/model/repair-estimate"
import type {
  WarehouseTransferPhoto,
  WarehouseTransferPhotoUpload,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferMediaClient } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-media-client"

async function dataUrlToFile(upload: WarehouseTransferPhotoUpload) {
  const response = await fetch(upload.dataUrl)
  const blob = await response.blob()
  return new File([blob], upload.fileName, {
    type: blob.type || "application/octet-stream",
  })
}

export class IndexedDbWarehouseTransferMediaClient implements WarehouseTransferMediaClient {
  private readonly adapter = new IndexedDbRepairEstimateMediaAdapter()

  async upload(uploads: WarehouseTransferPhotoUpload[]) {
    const pending: PendingEstimateMediaUpload[] = await Promise.all(
      uploads.map(async (upload) => ({
        id: upload.id,
        file: await dataUrlToFile(upload),
        previewUrl: upload.dataUrl,
        rotationDegrees: upload.rotationDegrees,
      }))
    )
    const refs = await this.adapter.upload(pending)
    return this.adapter.dehydrate(refs).map<WarehouseTransferPhoto>((ref) => ({
      id: ref.id,
      fileName: ref.fileName,
      mimeType: ref.mimeType,
      rotationDegrees: ref.rotationDegrees,
      storageRef: ref.variants.original?.storageRef ?? ref.id,
      previewStorageRef: ref.variants.small.storageRef,
      originalAvailable: ref.originalAvailable ?? true,
      processingStatus: ref.processingStatus ?? "READY",
      createdAt: ref.createdAt,
    }))
  }

  discard(mediaIds: string[]) {
    return this.adapter.discard(mediaIds)
  }
}
