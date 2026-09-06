import type {
  RepairTaskAcceptanceStatus,
  RepairTaskDto,
} from "@/features/repair-tasks/model/repair-task"

import {
  acceptanceStatusLabels,
  repairTaskOriginLabel,
} from "./acceptance-formatters"

export type AcceptanceFiltersState = {
  sources: string[]
  sourceParties: string[]
  authors: string[]
  statuses: RepairTaskAcceptanceStatus[]
  readyAtFrom: string
  readyAtTo: string
}

export type AcceptanceFilterOptions = {
  sources: Array<{ value: string; label: string }>
  sourceParties: Array<{ value: string; label: string }>
  authors: Array<{ value: string; label: string }>
}

export const acceptanceStatusOptions = (
  Object.entries(acceptanceStatusLabels) as Array<
    [RepairTaskAcceptanceStatus, string]
  >
).map(([value, label]) => ({ value, label }))

export function createEmptyAcceptanceFilters(): AcceptanceFiltersState {
  return {
    sources: [],
    sourceParties: [],
    authors: [],
    statuses: [],
    readyAtFrom: "",
    readyAtTo: "",
  }
}

export function acceptanceSourceValue(task: RepairTaskDto) {
  return `${task.origin}:${task.kind}`
}

function uniqueOptions(
  values: Array<{ value: string; label: string }>
): Array<{ value: string; label: string }> {
  return [...new Map(values.map((item) => [item.value, item])).values()].sort(
    (left, right) => left.label.localeCompare(right.label, "ru")
  )
}

export function buildAcceptanceFilterOptions(
  tasks: RepairTaskDto[],
  authorLabel: (actorId: string) => string = () => "Автор недоступен"
): AcceptanceFilterOptions {
  return {
    sources: uniqueOptions(
      tasks.map((task) => ({
        value: acceptanceSourceValue(task),
        label: repairTaskOriginLabel(task.origin, task.kind),
      }))
    ),
    sourceParties: uniqueOptions(
      tasks.flatMap((task) =>
        task.sourceParty
          ? [{ value: task.sourceParty, label: task.sourceParty }]
          : []
      )
    ),
    authors: uniqueOptions(
      tasks.flatMap((task) =>
        task.actorId
          ? [{ value: task.actorId, label: authorLabel(task.actorId) }]
          : []
      )
    ),
  }
}

function matchesDateRange(
  value: string | null | undefined,
  from: string,
  to: string
) {
  if (!from && !to) return true
  if (!value) return false

  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) return false

  const fromTimestamp = from ? new Date(`${from}T00:00:00`).getTime() : null
  const toTimestamp = to ? new Date(`${to}T23:59:59.999`).getTime() : null

  return (
    (fromTimestamp === null || timestamp >= fromTimestamp) &&
    (toTimestamp === null || timestamp <= toTimestamp)
  )
}

function matchesSearch(
  task: RepairTaskDto,
  search: string,
  authorLabel: (actorId: string) => string
) {
  const normalized = search.trim().toLocaleLowerCase("ru-RU")
  if (!normalized) return true

  return [
    task.cabinNumber,
    repairTaskOriginLabel(task.origin, task.kind),
    task.sourceParty ?? "",
    authorLabel(task.actorId),
  ].some((value) => value.toLocaleLowerCase("ru-RU").includes(normalized))
}

export function filterAcceptanceTasks(
  tasks: RepairTaskDto[],
  search: string,
  filters: AcceptanceFiltersState,
  authorLabel: (actorId: string) => string = () => "Автор недоступен"
) {
  return tasks.filter(
    (task) =>
      matchesSearch(task, search, authorLabel) &&
      (filters.sources.length === 0 ||
        filters.sources.includes(acceptanceSourceValue(task))) &&
      (filters.sourceParties.length === 0 ||
        (task.sourceParty !== null &&
          task.sourceParty !== undefined &&
          filters.sourceParties.includes(task.sourceParty))) &&
      (filters.authors.length === 0 ||
        filters.authors.includes(task.actorId)) &&
      (filters.statuses.length === 0 ||
        filters.statuses.includes(task.acceptanceStatus)) &&
      matchesDateRange(task.readyAt, filters.readyAtFrom, filters.readyAtTo)
  )
}
