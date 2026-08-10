import type {
  CabinSelection,
  CabinSearchGroup,
  CabinSearchResult,
} from "@/features/assistant/api/assistant-api"

export function assistantSearchGroupLabel(
  group: CabinSearchGroup,
  index: number
) {
  const categories =
    group.categories && group.categories.length > 0
      ? group.categories.join(" или ")
      : group.category
  const filters = [
    group.cabinType,
    group.finish,
    group.dimensions,
    categories,
    group.characteristics,
    group.linoleum === true
      ? "С линолеумом"
      : group.linoleum === false
        ? "Без линолеума"
        : null,
  ].filter((value): value is string => Boolean(value))
  return filters.join(" · ") || `Подборка ${index + 1}`
}

export function selectionGroups(
  result: CabinSearchResult,
  selectedIds: ReadonlySet<string>
) {
  return result.groups.flatMap((entry, index) => {
    const rentalItemIds = entry.cabins
      .map((cabin) => cabin.id)
      .filter((id) => selectedIds.has(id))
    return rentalItemIds.length === 0
      ? []
      : [
          {
            key: `group-${index + 1}`,
            label: assistantSearchGroupLabel(entry.group, index),
            rentalItemIds,
          },
        ]
  })
}

export function reconcileSearchResultSelection(
  result: CabinSearchResult,
  selection: CabinSelection,
  removedIds: ReadonlySet<string>
): CabinSearchResult {
  return {
    ...result,
    expiresAt: selection.expiresAt ?? result.expiresAt,
    groups: result.groups
      .map((entry) => ({
        ...entry,
        cabins: entry.cabins.filter((cabin) => !removedIds.has(cabin.id)),
      }))
      .filter((entry) => entry.cabins.length > 0),
  }
}
