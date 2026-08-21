import { getRentalItemDossierPage } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type { DossierActivity } from "@/features/rental-items/dossier/model/dossier-service"

const PHOTO_ACTIVITY_SOURCE_TYPES = ["INVENTORY", "MEDIA"] as const

/**
 * Loads every inventory/media activity needed to label the cabin photo
 * archive independently from the user's History-tab filters and pagination.
 * Repeating a supposedly opaque next cursor is rejected instead of looping.
 */
export async function loadRentalItemPhotoActivities(
  accessToken: string,
  rentalItemId: string
): Promise<DossierActivity[]> {
  const activities: DossierActivity[] = []
  const seenCursors = new Set<string>()
  let after: string | undefined

  for (;;) {
    const page = await getRentalItemDossierPage(accessToken, rentalItemId, {
      limit: 100,
      sourceTypes: [...PHOTO_ACTIVITY_SOURCE_TYPES],
      ...(after ? { after } : {}),
    })
    activities.push(...page.activities)

    const next = page.nextCursor
    if (!next) return activities
    if (seenCursors.has(next)) {
      throw new Error("Dossier repeated the photo archive cursor")
    }
    seenCursors.add(next)
    after = next
  }
}
