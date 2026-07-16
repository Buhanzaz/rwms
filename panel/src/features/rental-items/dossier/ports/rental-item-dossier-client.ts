import type {
  AddCabinCommentCommand,
  AddCabinPhotoGroupCommand,
  CabinOriginalPhotoContext,
  RentalItemDossierDto,
  UpdateCabinGeneralCommentCommand,
  UpdateCabinStatusCommand,
} from "@/features/rental-items/dossier/model/rental-item-dossier"

export interface RentalItemDossierClient {
  get(
    warehouseId: string,
    rentalItemId: string
  ): Promise<RentalItemDossierDto | null>
  addPhotoGroup(
    command: AddCabinPhotoGroupCommand
  ): Promise<RentalItemDossierDto>
  addComment(command: AddCabinCommentCommand): Promise<RentalItemDossierDto>
  updateStatus(command: UpdateCabinStatusCommand): Promise<RentalItemDossierDto>
  updateGeneralComment(
    command: UpdateCabinGeneralCommentCommand
  ): Promise<RentalItemDossierDto>
  getOriginalPhotoUrl(
    photoId: string,
    context: CabinOriginalPhotoContext
  ): Promise<string>
  subscribe(listener: () => void): () => void
}
