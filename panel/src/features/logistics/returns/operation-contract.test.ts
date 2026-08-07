import { describe, expect, it } from "vitest"
import {
  RETURN_CONTRACT_GAPS,
  RETURN_OPERATION_IDS,
} from "@/features/logistics/returns/operation-contract"

describe("return UI contract mapping", () => {
  it("maps only actions covered by the canonical logistics contract", () => {
    expect(RETURN_OPERATION_IDS).toEqual({
      listReceipts: "listReturns",
      getReceipt: "getReturn",
      createReceipt: "createReturn",
      registerReceipt: "registerReturn",
      acceptUndamaged: "acceptUndamagedReturn",
      startEstimates: "startReturnEstimates",
    })
  })

  it("keeps browser-only candidate and transition semantics explicit", () => {
    expect(RETURN_CONTRACT_GAPS).toEqual([
      "listCompanies",
      "listCandidates",
      "listEditCandidates",
      "importRentedCabin",
      "updateReceiptMembership",
      "resolveFurnitureDisposition",
      "returnMediaUpload",
    ])
  })
})
