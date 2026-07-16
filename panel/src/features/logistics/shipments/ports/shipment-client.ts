import type {
  Shipment,
  ShipmentCandidate,
  ShipmentDraftInput,
  ShipmentSourceCandidate,
} from "@/features/logistics/shipments/model"

export type ShipmentCommandContext = {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: string
}

export interface ShipmentClient {
  list(warehouseId: string): Promise<Shipment[]>
  listAvailableWarehouseStock(
    warehouseId: string,
    exceptShipmentId?: string | null
  ): Promise<Array<{ name: string; availableQuantity: number }>>
  listCandidates(
    warehouseId: string,
    company: string
  ): Promise<ShipmentCandidate[]>
  listSourceCabins(
    warehouseId: string,
    excludedIds: string[],
    exceptShipmentId?: string | null
  ): Promise<ShipmentSourceCandidate[]>
  saveDraft(input: ShipmentDraftInput): Promise<Shipment>
  dispatchPreparation(
    context: ShipmentCommandContext,
    shipmentId: string,
    rentalItemId: string
  ): Promise<Shipment>
  confirmPreparation(
    warehouseId: string,
    shipmentId: string,
    actor: string
  ): Promise<Shipment>
  finalize(
    warehouseId: string,
    shipmentId: string,
    actor: string
  ): Promise<Shipment>
  cancel(
    context: ShipmentCommandContext,
    shipmentId: string,
    reason: string
  ): Promise<Shipment>
}
