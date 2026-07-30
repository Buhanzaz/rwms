import { describe, expect, it } from "vitest"
import {
  TRANSFER_CONTRACT_GAPS,
  TRANSFER_OPERATION_IDS,
} from "@/features/logistics/warehouse-transfers/operation-contract"

describe("warehouse transfer UI contract mapping", () => {
  it("maps document and line actions to existing operationIds", () => {
    expect(TRANSFER_OPERATION_IDS).toEqual({
      listTransfers: "listTransfers",
      getTransfer: "getTransfer",
      createTransfer: "createTransfer",
      departLine: "departTransferLine",
      arrivalPreflight: "getTransferArrivalPreflight",
      arriveLine: "arriveTransferLine",
      cancelTransfer: "cancelTransfer",
      reconcileDocument: "reconcileDocument",
    })
  })

  it("keeps browser orchestration and contents transfer as gaps", () => {
    expect(TRANSFER_CONTRACT_GAPS).toEqual([
      "listCandidates",
      "retrySourceTask",
      "retryDestinationTask",
      "cancelSingleLine",
      "accountingCorrection",
      "contentsTransferBetweenCabins",
    ])
  })
})
