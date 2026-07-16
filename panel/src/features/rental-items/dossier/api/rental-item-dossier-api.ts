import { BrowserRentalItemDossierAdapter } from "@/features/rental-items/dossier/adapters/browser-rental-item-dossier-adapter"
import type {
  AddCabinCommentCommand,
  AddCabinPhotoGroupCommand,
  CabinOriginalPhotoContext,
  UpdateCabinGeneralCommentCommand,
  UpdateCabinStatusCommand,
} from "@/features/rental-items/dossier/model/rental-item-dossier"

export const RENTAL_ITEM_DOSSIER_QUERY_KEY = ["rental-item-dossier"] as const

const client = new BrowserRentalItemDossierAdapter()

export function rentalItemDossierQueryKey(
  warehouseId: string,
  rentalItemId: string
) {
  return [...RENTAL_ITEM_DOSSIER_QUERY_KEY, warehouseId, rentalItemId] as const
}

export function getRentalItemDossier(
  warehouseId: string,
  rentalItemId: string
) {
  return client.get(warehouseId, rentalItemId)
}

export function addRentalItemDossierPhotoGroup(
  command: AddCabinPhotoGroupCommand
) {
  return client.addPhotoGroup(command)
}

export function addRentalItemDossierComment(command: AddCabinCommentCommand) {
  return client.addComment(command)
}

export function updateRentalItemDossierStatus(
  command: UpdateCabinStatusCommand
) {
  return client.updateStatus(command)
}

export function updateRentalItemDossierGeneralComment(
  command: UpdateCabinGeneralCommentCommand
) {
  return client.updateGeneralComment(command)
}

export function getRentalItemOriginalPhotoUrl(
  photoId: string,
  context: CabinOriginalPhotoContext
) {
  return client.getOriginalPhotoUrl(photoId, context)
}

export function subscribeRentalItemDossier(listener: () => void) {
  return client.subscribe(listener)
}
