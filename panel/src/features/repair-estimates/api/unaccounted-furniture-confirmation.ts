import { ApiError } from "@/lib/api-client"

export const MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED =
  "MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED"

/** The draft was saved before maintenance requested an explicit retry choice. */
export class UnaccountedFurnitureConfirmationRequiredError extends ApiError {
  readonly savedEstimateId: string
  readonly savedEstimateVersion: number

  constructor(
    cause: ApiError,
    savedEstimateId: string,
    savedEstimateVersion: number
  ) {
    super(cause.message, cause.status, cause.code)
    this.name = "UnaccountedFurnitureConfirmationRequiredError"
    this.savedEstimateId = savedEstimateId
    this.savedEstimateVersion = savedEstimateVersion
  }
}

export function requiresUnaccountedFurnitureConfirmation(error: unknown) {
  return (
    error instanceof ApiError &&
    error.code === MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED
  )
}
