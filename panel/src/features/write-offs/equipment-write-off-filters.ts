import type { LogisticsDocumentFiltersState } from "@/features/logistics/logistics-document-filters"
import type { EquipmentDispositionListItemDto } from "@/types/equipment"

export type EquipmentWriteOffFilters = LogisticsDocumentFiltersState<string> & {
  names: string[]
}

export type EquipmentWriteOffFilterOption = {
  value: string
  label: string
}

export type EquipmentWriteOffFilterOptions = {
  names: EquipmentWriteOffFilterOption[]
  operations: EquipmentWriteOffFilterOption[]
}

export const EMPTY_EQUIPMENT_WRITE_OFF_FILTERS: EquipmentWriteOffFilters = {
  names: [],
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
}

const operationLabels: Record<string, string> = {
  EQUIPMENT_WRITTEN_OFF: "Списание",
  EQUIPMENT_LOST: "Утрата",
}

function uniqueOptions(options: EquipmentWriteOffFilterOption[]) {
  return [
    ...new Map(options.map((option) => [option.value, option])).values(),
  ].sort((left, right) => left.label.localeCompare(right.label, "ru-RU"))
}

export function equipmentWriteOffOperationLabel(kind: string) {
  return operationLabels[kind] ?? kind
}

export function buildEquipmentWriteOffFilterOptions(
  items: EquipmentDispositionListItemDto[]
): EquipmentWriteOffFilterOptions {
  return {
    names: uniqueOptions(
      items.map((item) => ({
        value: item.equipmentName,
        label: item.equipmentName,
      }))
    ),
    operations: uniqueOptions(
      items.map((item) => ({
        value: item.kind,
        label: equipmentWriteOffOperationLabel(item.kind),
      }))
    ),
  }
}

export function filterEquipmentWriteOffItems(
  items: EquipmentDispositionListItemDto[],
  filters: EquipmentWriteOffFilters
) {
  return items.filter((item) => {
    if (
      filters.names.length > 0 &&
      !filters.names.includes(item.equipmentName)
    ) {
      return false
    }
    if (filters.states.length > 0 && !filters.states.includes(item.kind)) {
      return false
    }

    const occurredDate = item.occurredAt.slice(0, 10)
    if (filters.dateFrom && occurredDate < filters.dateFrom) return false
    if (filters.dateTo && occurredDate > filters.dateTo) return false

    return true
  })
}
