import type {
  ContentsTransferTaskRef,
  CreateContentsTransferCommand,
} from "@/features/rental-items/contents-transfer/model/contents-transfer"

export type ContentsTransferTaskCommand = Pick<
  CreateContentsTransferCommand,
  "externalTaskId" | "serviceWarehouseId" | "source" | "target" | "items"
>

export interface ContentsTransferTaskClient {
  dispatch(
    accessToken: string,
    command: ContentsTransferTaskCommand
  ): Promise<ContentsTransferTaskRef>
}
