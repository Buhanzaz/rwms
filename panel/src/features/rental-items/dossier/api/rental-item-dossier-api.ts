import { HttpRentalItemDossierAdapter } from "@/features/rental-items/dossier/adapters/http-rental-item-dossier-adapter"
import type {
  DossierActivityFilters,
  GetCabinDossierQuery,
} from "@/features/rental-items/dossier/model/dossier-service"

export const RENTAL_ITEM_DOSSIER_QUERY_KEY = ["rental-item-dossier"] as const

export function rentalItemDossierQueryKey(
  rentalItemId: string,
  filters: DossierActivityFilters
) {
  return [...RENTAL_ITEM_DOSSIER_QUERY_KEY, rentalItemId, filters] as const
}

export function getRentalItemDossierPage(
  accessToken: string | null,
  rentalItemId: string,
  query: GetCabinDossierQuery = {}
) {
  return new HttpRentalItemDossierAdapter(accessToken).getPage(
    rentalItemId,
    query
  )
}
