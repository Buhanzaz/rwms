import { HttpReturnClient } from "@/features/logistics/returns/adapters/http-return-client"
import type {
  ReturnAcceptUndamagedCommand,
  ReturnCreateCommand,
  ReturnPickupCommand,
  StartReturnEstimatesCommand,
} from "@/features/logistics/returns/ports/return-client"

export const RETURNS_QUERY_KEY = ["logistics", "returns"] as const

export const returnClient = new HttpReturnClient()

export const listReturns = (
  accessToken: string,
  warehouseId: string,
  scheduledDate?: string,
  assetId?: string
) => returnClient.list(accessToken, warehouseId, scheduledDate, assetId)

export const createReturn = (input: ReturnCreateCommand) =>
  returnClient.create(input)

export const registerReturn = (input: ReturnPickupCommand) =>
  returnClient.register(input)

export const acceptUndamagedReturn = (input: ReturnAcceptUndamagedCommand) =>
  returnClient.acceptUndamaged(input)

export const startReturnEstimates = (input: StartReturnEstimatesCommand) =>
  returnClient.startEstimates(input)
