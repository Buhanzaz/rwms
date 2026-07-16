import { listCompanies as listLegacyCompanies } from "@/features/logistics/api/logistics-api"
import { BrowserShipmentClient } from "@/features/logistics/shipments/adapters/browser-shipment-client"
export { hasActiveShipmentForRentalItemLowLevel as hasActiveShipmentForRentalItem } from "@/features/logistics/shipments/active-shipment-guard"
import type { ShipmentDraftInput } from "@/features/logistics/shipments/model"
export {
  SHIPMENTS_QUERY_KEY,
  SHIPMENTS_STORAGE_KEY,
  SHIPMENTS_UPDATED_EVENT,
} from "@/features/logistics/shipments/storage"

const client = new BrowserShipmentClient()

export const listShipments = (warehouseId: string) => client.list(warehouseId)
export const listShipmentCandidates = (warehouseId: string, company: string) =>
  client.listCandidates(warehouseId, company)
export const listShipmentSourceCabins = (
  warehouseId: string,
  excludedIds: string[],
  exceptShipmentId?: string | null
) => client.listSourceCabins(warehouseId, excludedIds, exceptShipmentId)
export const listCompanies = listLegacyCompanies
export const listShipmentEquipment = (
  warehouseId: string,
  exceptShipmentId?: string | null
) => client.listAvailableWarehouseStock(warehouseId, exceptShipmentId)
export const upsertShipmentDraft = (input: ShipmentDraftInput) =>
  client.saveDraft(input)
export const dispatchShipmentPreparationTask = (input: {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: string
  shipmentId: string
  rentalItemId: string
}) => client.dispatchPreparation(input, input.shipmentId, input.rentalItemId)
export async function retryShipmentPreparationTasks(input: {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: string
  shipmentId: string
}) {
  let shipment = (await client.list(input.warehouseId)).find(
    (item) => item.id === input.shipmentId
  )
  if (!shipment) throw new Error("Отгрузка не найдена")
  for (const item of shipment.items) {
    if (
      item.changes.length > 0 &&
      item.preparationTask &&
      item.preparationTask.dispatchStatus !== "DISPATCHED"
    ) {
      shipment = await client.dispatchPreparation(
        input,
        input.shipmentId,
        item.rentalItemId
      )
    }
  }
  return shipment
}
export const confirmShipmentPreparation = (input: {
  warehouseId: string
  shipmentId: string
  actor: string
}) =>
  client.confirmPreparation(input.warehouseId, input.shipmentId, input.actor)
export const finalizeShipment = (input: {
  warehouseId: string
  shipmentId: string
  actor: string
}) => client.finalize(input.warehouseId, input.shipmentId, input.actor)
export const cancelShipment = (input: {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  actor: string
  shipmentId: string
  reason: string
}) => client.cancel(input, input.shipmentId, input.reason)

export {
  buildShipmentPreparationTaskText,
  combineShipmentPreparationCatalog,
  computeShipmentContentsChanges,
  createShipmentPreparationTask,
  shipmentPreparationIdentity,
  stablePreparationExternalTaskId,
} from "@/features/logistics/shipments/domain"
export type { ShipmentDraftInput }
