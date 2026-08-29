export const TRANSFER_OPERATION_IDS = {
  listTransfers: "listTransfers",
  getTransfer: "getTransfer",
  createTransfer: "createTransfer",
  getTransferPlan: "getTransferPlan",
  updateTransferPlan: "updateTransferPlan",
  confirmTransferPlan: "confirmTransferPlan",
  departLine: "departTransferLine",
  arrivalPreflight: "getTransferArrivalPreflight",
  arriveLine: "arriveTransferLine",
  cancelTransfer: "cancelTransfer",
  reconcileDocument: "reconcileDocument",
} as const

export const TRANSFER_CONTRACT_GAPS = [
  "retrySourceTask",
  "retryDestinationTask",
  "cancelSingleLine",
  "accountingCorrection",
  "contentsTransferBetweenCabins",
] as const
