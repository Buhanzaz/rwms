import type {
  WarehouseTransferTaskCommand,
  WarehouseTransferTaskRef,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

export interface WarehouseTransferTaskClient {
  dispatch(
    accessToken: string,
    command: WarehouseTransferTaskCommand
  ): Promise<WarehouseTransferTaskRef>
  recover(
    accessToken: string,
    command: {
      serviceWarehouseId: string
      externalTaskId: string
    }
  ): Promise<WarehouseTransferTaskRef | null>
  cancel(
    accessToken: string,
    command: {
      serviceWarehouseId: string
      externalTaskId: string
      expectedTaskVersion: number
      reason: string
    }
  ): Promise<void>
}
