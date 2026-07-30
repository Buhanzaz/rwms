import type { LogisticsDocumentFiltersState } from "@/features/logistics/logistics-document-filters"
import { repairTaskOriginLabel } from "@/features/acceptance/acceptance-formatters"
import type {
  RepairTaskAcceptanceStatus,
  RepairTaskDto,
} from "@/features/repair-tasks/model/repair-task"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export const WRITE_OFF_AUTHOR_UNAVAILABLE = "Автор недоступен"
const UNKNOWN_AUTHOR_FILTER_VALUE = "__unknown-write-off-author__"

export type WriteOffListFilters =
  LogisticsDocumentFiltersState<RepairTaskAcceptanceStatus> & {
    sources: string[]
    authors: string[]
  }

export type WriteOffFilterOptions = {
  sources: Array<{ value: string; label: string }>
  authors: Array<{ value: string; label: string }>
}

export const EMPTY_WRITE_OFF_LIST_FILTERS: WriteOffListFilters = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
  sources: [],
  authors: [],
}

function nonBlank(value: string | null | undefined) {
  const normalized = value?.trim()
  return normalized || null
}

function localCalendarDate(value: string | null | undefined) {
  if (!value) return ""

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

function uniqueOptions(
  values: Array<{ value: string; label: string }>
): Array<{ value: string; label: string }> {
  return [...new Map(values.map((item) => [item.value, item])).values()].sort(
    (left, right) => left.label.localeCompare(right.label, "ru")
  )
}

export function writeOffSourceValue(task: RepairTaskDto) {
  return `${task.origin}:${task.kind}`
}

export function writeOffActorIds(tasks: RepairTaskDto[]) {
  return [
    ...new Set(
      tasks
        .map((task) => task.decisionActorId)
        .filter((actorId): actorId is string => Boolean(actorId))
        .filter((actorId) => UUID_PATTERN.test(actorId))
    ),
  ].sort()
}

export function formatWriteOffAuthor(
  actorId: string | null | undefined,
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  if (!actorId) return WRITE_OFF_AUTHOR_UNAVAILABLE

  const actor = actorsById.get(actorId)
  if (!actor) return WRITE_OFF_AUTHOR_UNAVAILABLE

  const fullName = [actor.lastName, actor.firstName]
    .map(nonBlank)
    .filter((part): part is string => part !== null)
    .join(" ")

  return fullName || WRITE_OFF_AUTHOR_UNAVAILABLE
}

export function buildWriteOffFilterOptions(
  tasks: RepairTaskDto[],
  actorsById: ReadonlyMap<string, DossierActorDisplay>
): WriteOffFilterOptions {
  const sources = uniqueOptions(
    tasks.map((task) => ({
      value: writeOffSourceValue(task),
      label: repairTaskOriginLabel(task.origin, task.kind),
    }))
  )
  const authorIds = [
    ...new Set(
      tasks
        .map((task) => task.decisionActorId)
        .filter((actorId): actorId is string => Boolean(actorId))
    ),
  ]
  const hasUnavailableAuthor = tasks.some(
    (task) =>
      formatWriteOffAuthor(task.decisionActorId, actorsById) ===
      WRITE_OFF_AUTHOR_UNAVAILABLE
  )
  const authors = uniqueOptions([
    ...authorIds.flatMap((actorId) => {
      const label = formatWriteOffAuthor(actorId, actorsById)
      return label === WRITE_OFF_AUTHOR_UNAVAILABLE
        ? []
        : [{ value: actorId, label }]
    }),
    ...(hasUnavailableAuthor
      ? [
          {
            value: UNKNOWN_AUTHOR_FILTER_VALUE,
            label: WRITE_OFF_AUTHOR_UNAVAILABLE,
          },
        ]
      : []),
  ])

  return { sources, authors }
}

function matchesAuthorFilter(
  task: RepairTaskDto,
  selectedAuthors: string[],
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  if (selectedAuthors.length === 0) return true

  return selectedAuthors.some((author) => {
    if (author === UNKNOWN_AUTHOR_FILTER_VALUE) {
      return (
        formatWriteOffAuthor(task.decisionActorId, actorsById) ===
        WRITE_OFF_AUTHOR_UNAVAILABLE
      )
    }

    return author === task.decisionActorId
  })
}

export function filterWriteOffTasks(
  tasks: RepairTaskDto[],
  search: string,
  filters: WriteOffListFilters,
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU")

  return tasks.filter((task) => {
    if (
      normalizedSearch &&
      ![
        task.cabinNumber,
        repairTaskOriginLabel(task.origin, task.kind),
        formatWriteOffAuthor(task.decisionActorId, actorsById),
      ].some((value) =>
        value.toLocaleLowerCase("ru-RU").includes(normalizedSearch)
      )
    ) {
      return false
    }
    if (
      filters.sources.length > 0 &&
      !filters.sources.includes(writeOffSourceValue(task))
    ) {
      return false
    }
    if (!matchesAuthorFilter(task, filters.authors, actorsById)) return false
    if (
      filters.states.length > 0 &&
      !filters.states.includes(task.acceptanceStatus)
    ) {
      return false
    }

    const writtenOffDate = localCalendarDate(task.writtenOffAt)
    if (filters.dateFrom && writtenOffDate < filters.dateFrom) return false
    if (filters.dateTo && writtenOffDate > filters.dateTo) return false
    return true
  })
}
