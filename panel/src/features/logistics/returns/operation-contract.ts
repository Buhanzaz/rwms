export const RETURN_OPERATION_IDS = {
  listReceipts: "listReturns",
  getReceipt: "getReturn",
  createReceipt: "createReturn",
  registerReceipt: "registerReturn",
  acceptUndamaged: "acceptUndamagedReturn",
  startEstimates: "startReturnEstimates",
} as const

export const RETURN_CONTRACT_GAPS = [
  "listCompanies",
  "listCandidates",
  "listEditCandidates",
  "importRentedCabin",
  "updateReceiptMembership",
  "resolveFurnitureDisposition",
  "returnMediaUpload",
] as const
