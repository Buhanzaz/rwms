import { HttpShipmentClient } from "@/features/logistics/shipments/adapters/http-shipment-client"
import type {
  ShipmentCreateCommand,
  ShipmentPlanCommand,
  ShipmentVersionedCommand,
} from "@/features/logistics/shipments/ports/shipment-client"

export const SHIPMENTS_QUERY_KEY = ["logistics", "shipments"] as const

export const shipmentClient = new HttpShipmentClient()

export const listShipments = (accessToken: string, warehouseId: string) =>
  shipmentClient.list(accessToken, warehouseId)

export const getShipment = (accessToken: string, documentId: string) =>
  shipmentClient.get(accessToken, documentId)

export const createShipment = (input: ShipmentCreateCommand) =>
  shipmentClient.create(input)

export const replaceShipmentPlan = (input: ShipmentPlanCommand) =>
  shipmentClient.replacePlan(input)

export const confirmShipmentPreparation = (input: ShipmentVersionedCommand) =>
  shipmentClient.confirmPreparation(input)

export const cancelShipment = (input: ShipmentVersionedCommand) =>
  shipmentClient.cancel(input)
