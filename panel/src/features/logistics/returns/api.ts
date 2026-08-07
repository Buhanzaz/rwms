import { HttpReturnClient } from "@/features/logistics/returns/adapters/http-return-client"
import type {
  ReturnAcceptUndamagedCommand,
  ReturnCreateCommand,
  ReturnPickupCommand,
  StartReturnEstimatesCommand,
} from "@/features/logistics/returns/ports/return-client"

export const RETURNS_QUERY_KEY = ["logistics", "returns"] as const

export const returnClient = new HttpReturnClient()

export const listReturns = (accessToken: string, warehouseId: string) =>
  returnClient.list(accessToken, warehouseId)

export const getReturn = (accessToken: string, documentId: string) =>
  returnClient.get(accessToken, documentId)

export const createReturn = (input: ReturnCreateCommand) =>
  returnClient.create(input)

export const registerReturn = (input: ReturnPickupCommand) =>
  returnClient.register(input)

export const acceptUndamagedReturn = (input: ReturnAcceptUndamagedCommand) =>
  returnClient.acceptUndamaged(input)

export const startReturnEstimates = (input: StartReturnEstimatesCommand) =>
  returnClient.startEstimates(input)
