import type {
  ContentsTransferAttempt,
  ContentsTransferRecord,
  StoredContentsTransferCommand,
} from "@/features/rental-items/contents-transfer/model/contents-transfer"

export interface ContentsTransferStore {
  findByExternalTaskId(externalTaskId: string): ContentsTransferAttempt | null
  findMatching(
    command: StoredContentsTransferCommand
  ): ContentsTransferAttempt | null
  listForRentalItem(rentalItemId: string): ContentsTransferRecord[]
  saveAttempt(
    attempt: ContentsTransferAttempt
  ): Promise<ContentsTransferAttempt>
}
