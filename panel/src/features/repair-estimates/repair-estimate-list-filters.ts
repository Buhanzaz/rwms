import type { LogisticsDocumentFiltersState } from "@/features/logistics/logistics-document-filters"
import type {
  RepairEstimateStatus,
  RepairEstimateSummaryDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"
import { smartLocalSearch } from "@/lib/smart-local-search"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export type RepairEstimateListFilters =
  LogisticsDocumentFiltersState<RepairEstimateStatus> & {
    sourceParties: string[]
    authorIds: string[]
  }

export const EMPTY_REPAIR_ESTIMATE_LIST_FILTERS: RepairEstimateListFilters = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
  sourceParties: [],
  authorIds: [],
}

function nonBlank(value: string | null | undefined) {
  const normalized = value?.trim()
  return normalized || null
}

function localCalendarDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""

  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? ""

  return `${part("year")}-${part("month")}-${part("day")}`
}

export function repairEstimateAuthorIds(estimates: RepairEstimateSummaryDto[]) {
  return [
    ...new Set(
      estimates
        .map((estimate) => estimate.authorName)
        .filter((authorId) => UUID_PATTERN.test(authorId))
    ),
  ].sort()
}

export function formatRepairEstimateAuthor(
  authorReference: string,
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  const actor = actorsById.get(authorReference)
  if (!actor) return "Не указано"

  return (
    [actor.lastName, actor.firstName, actor.middleName]
      .map(nonBlank)
      .filter((part): part is string => part !== null)
      .join(" ") || "Не указано"
  )
}

export function textFilterOptions(values: Iterable<string | null | undefined>) {
  return [
    ...new Set(
      [...values].filter((value): value is string => Boolean(value?.trim()))
    ),
  ]
    .sort((left, right) => left.localeCompare(right, "ru"))
    .map((value) => ({ value, label: value }))
}

export function repairEstimateAuthorOptions(
  estimates: RepairEstimateSummaryDto[],
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  return [...new Set(estimates.map((estimate) => estimate.authorName))]
    .map((value) => ({
      value,
      label: formatRepairEstimateAuthor(value, actorsById),
    }))
    .sort((left, right) => left.label.localeCompare(right.label, "ru"))
}

export function filterRepairEstimates(
  estimates: RepairEstimateSummaryDto[],
  search: string,
  filters: RepairEstimateListFilters,
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  const filtered = estimates.filter((estimate) => {
    if (
      filters.states.length > 0 &&
      !filters.states.includes(estimate.status)
    ) {
      return false
    }
    if (
      filters.sourceParties.length > 0 &&
      !filters.sourceParties.includes(estimate.sourceParty)
    ) {
      return false
    }
    if (
      filters.authorIds.length > 0 &&
      !filters.authorIds.includes(estimate.authorName)
    ) {
      return false
    }

    const createdAt = localCalendarDate(estimate.createdAt)
    if (filters.dateFrom && createdAt < filters.dateFrom) return false
    if (filters.dateTo && createdAt > filters.dateTo) return false
    return true
  })

  return smartLocalSearch(filtered, search, (estimate) => [
    estimate.cabinNumber,
    estimate.sourceParty,
    formatRepairEstimateAuthor(estimate.authorName, actorsById),
  ])
}
