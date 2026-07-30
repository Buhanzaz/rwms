export const TRANSFER_OPERATION_IDS = {
  listTransfers: "listTransfers",
  getTransfer: "getTransfer",
  createTransfer: "createTransfer",
  departLine: "departTransferLine",
  arrivalPreflight: "getTransferArrivalPreflight",
  arriveLine: "arriveTransferLine",
  cancelTransfer: "cancelTransfer",
  reconcileDocument: "reconcileDocument",
} as const

export const TRANSFER_CONTRACT_GAPS = [
  "listCandidates",
  "retrySourceTask",
  "retryDestinationTask",
  "cancelSingleLine",
  "accountingCorrection",
  "contentsTransferBetweenCabins",
] as const
