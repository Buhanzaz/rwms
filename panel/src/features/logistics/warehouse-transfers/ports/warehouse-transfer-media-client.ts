import type {
  WarehouseTransferPhoto,
  WarehouseTransferPhotoUpload,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

export interface WarehouseTransferMediaClient {
  upload(
    uploads: WarehouseTransferPhotoUpload[]
  ): Promise<WarehouseTransferPhoto[]>
  discard(mediaIds: string[]): Promise<void>
}
