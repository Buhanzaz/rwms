export const CLIENT_PRESENTATION_GROUP_SIZE = 30
export const CLIENT_PRESENTATION_MAX_ITEMS = 100

export type ManualBookingPresentationGroup = {
  key: string
  label: string
  rentalItemIds: string[]
}

export function buildManualBookingPresentationGroups(
  rentalItemIds: readonly string[]
): ManualBookingPresentationGroup[] {
  if (
    rentalItemIds.length === 0 ||
    rentalItemIds.length > CLIENT_PRESENTATION_MAX_ITEMS
  ) {
    throw new Error("В представлении должно быть от 1 до 100 бытовок.")
  }

  const groups: ManualBookingPresentationGroup[] = []
  for (
    let index = 0;
    index < rentalItemIds.length;
    index += CLIENT_PRESENTATION_GROUP_SIZE
  ) {
    const sequence = groups.length + 1
    groups.push({
      key: `manual-booking-${sequence}`,
      label:
        rentalItemIds.length <= CLIENT_PRESENTATION_GROUP_SIZE
          ? "Выбранные бытовки"
          : `Выбранные бытовки — группа ${sequence}`,
      rentalItemIds: rentalItemIds.slice(
        index,
        index + CLIENT_PRESENTATION_GROUP_SIZE
      ),
    })
  }
  return groups
}
