import { HttpWarehouseTransferClient } from "@/features/logistics/warehouse-transfers/adapters/http-warehouse-transfer-client"
import type {
  TransferArrivalCommand,
  TransferCreateCommand,
  TransferLineCommand,
  TransferReconcileCommand,
  TransferVersionedCommand,
} from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-client"

export const WAREHOUSE_TRANSFERS_QUERY_KEY = [
  "logistics",
  "warehouse-transfers",
] as const

export const warehouseTransferClient = new HttpWarehouseTransferClient()

export const listWarehouseTransfers = (
  accessToken: string,
  warehouseId: string
) => warehouseTransferClient.list(accessToken, warehouseId)

export const getWarehouseTransfer = (accessToken: string, documentId: string) =>
  warehouseTransferClient.get(accessToken, documentId)

export const createWarehouseTransfer = (input: TransferCreateCommand) =>
  warehouseTransferClient.create(input)

export const departWarehouseTransferLine = (input: TransferLineCommand) =>
  warehouseTransferClient.depart(input)

export const arriveWarehouseTransferLine = (input: TransferArrivalCommand) =>
  warehouseTransferClient.arrive(input)

export const cancelWarehouseTransfer = (input: TransferVersionedCommand) =>
  warehouseTransferClient.cancel(input)

export const reconcileWarehouseTransfer = (input: TransferReconcileCommand) =>
  warehouseTransferClient.reconcile(input)
