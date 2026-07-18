import type {
  CabinDossierPage,
  GetCabinDossierQuery,
} from "@/features/rental-items/dossier/model/dossier-service"

export interface RentalItemDossierClient {
  getPage(
    cabinId: string,
    query?: GetCabinDossierQuery
  ): Promise<CabinDossierPage>
}
