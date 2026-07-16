import { LocalStorageContentsTransferStore } from "@/features/rental-items/contents-transfer/adapters/local-storage-contents-transfer-store"
import { BrowserContentsTransferClient } from "@/features/rental-items/contents-transfer/browser-contents-transfer-client"
import type { CreateContentsTransferCommand } from "@/features/rental-items/contents-transfer/model/contents-transfer"

const client = new BrowserContentsTransferClient()
const store = new LocalStorageContentsTransferStore()

export function transferRentalItemContentsWithTask(
  command: CreateContentsTransferCommand
) {
  return client.transfer(command)
}

export function listContentsTransfersForRentalItem(rentalItemId: string) {
  return store.listForRentalItem(rentalItemId)
}
