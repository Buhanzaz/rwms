import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"

export const REPAIRS_TABLE_FILTER_IDS = [
  "repairType",
  "task",
  "date",
  "priority",
  "status",
] as const

export type RepairsTableFilterId = (typeof REPAIRS_TABLE_FILTER_IDS)[number]

export type RepairsTableFiltersState = Record<RepairsTableFilterId, string[]>

export type RepairsTableFilterOption = {
  value: string
  label: string
}

export type RepairsTableFilterDefinition = {
  id: RepairsTableFilterId
  label: string
  options: RepairsTableFilterOption[]
}

const NO_DATE_VALUE = "__NO_DATE__"
const stringCollator = new Intl.Collator("ru", {
  numeric: true,
  sensitivity: "base",
})

export function createEmptyRepairsTableFilters(): RepairsTableFiltersState {
  return {
    repairType: [],
    task: [],
    date: [],
    priority: [],
    status: [],
  }
}

export function formatRepairCalendarDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T00:00:00`))
}

export function getOperationalRepairSubtask(
  repair: RepairTaskDto
): RepairTaskSubtaskDto | null {
  return (
    repair.subtasks
      .filter(
        (subtask) => subtask.status !== "DONE" && subtask.status !== "CANCELLED"
      )
      .sort((left, right) => {
        const statusOrder =
          Number(right.status === "IN_PROGRESS") -
          Number(left.status === "IN_PROGRESS")
        if (statusOrder !== 0) return statusOrder
        const entryTypeOrder =
          Number(right.entryType === "REAL") - Number(left.entryType === "REAL")
        return entryTypeOrder || left.sortOrder - right.sortOrder
      })[0] ??
    repair.subtasks
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)[0] ??
    null
  )
}

export function getRepairTaskLabel(repair: RepairTaskDto) {
  if (repair.awaitingMovement) return "Перемещение на ремонт"
  const subtask = getOperationalRepairSubtask(repair)
  if (!subtask) return "Задание ещё не сформировано"
  if (subtask.taskText) return subtask.taskText
  if (subtask.taskTitle) return subtask.taskTitle
  if (subtask.kind === "MOVE_TO_REPAIR") return "Переместить на ремонт"
  if (subtask.kind === "MOVE_FROM_REPAIR") return "Вернуть после ремонта"
  return repair.kind === "REWORK" ? "Доработка" : "Ремонтные работы"
}

export function getRepairOperationalStatusLabel(repair: RepairTaskDto) {
  if (repair.status === "DRAFT") return "Черновик"
  if (repair.awaitingMovement) return "Ожидает перемещения"
  const status = getOperationalRepairSubtask(repair)?.status
  if (status === "IN_PROGRESS") return "В работе"
  if (status === "PAUSED") return "На паузе"
  if (status === "WAITING") return "Ожидает"
  if (status === "DONE") return "Завершено"
  if (status === "CANCELLED") return "Отменено"
  return "Нет активного этапа"
}

export function getRepairStageLabel(repair: RepairTaskDto) {
  const stages = repair.subtasks
    .filter((subtask) => subtask.status !== "CANCELLED")
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
  if (stages.length === 0) return "0 из 0"
  const operational = getOperationalRepairSubtask(repair)
  const index = operational
    ? stages.findIndex((stage) => stage.id === operational.id)
    : -1
  return `${index >= 0 ? index + 1 : stages.length} из ${stages.length}`
}

export function getRepairTypeLabel(repair: RepairTaskDto) {
  if (repair.kind === "REWORK") return "Доработка"
  if (repair.origin === "ESTIMATE") return "Ремонт по смете"
  if (repair.origin === "INVENTORY") return "Ремонт по инвентаризации"
  return "Прямой ремонт"
}

export function getRepairDate(repair: RepairTaskDto) {
  return (
    getOperationalRepairSubtask(repair)?.scheduledDate ??
    repair.dispatchDate ??
    null
  )
}

export function getRepairPriority(repair: RepairTaskDto) {
  return getOperationalRepairSubtask(repair)?.priority ?? repair.priority ?? 3
}

function getRepairFilterValue(
  repair: RepairTaskDto,
  filterId: RepairsTableFilterId
) {
  if (filterId === "repairType") return getRepairTypeLabel(repair)
  if (filterId === "task") return getRepairTaskLabel(repair)
  if (filterId === "date") return getRepairDate(repair) ?? NO_DATE_VALUE
  if (filterId === "priority") return String(getRepairPriority(repair))
  return getRepairOperationalStatusLabel(repair)
}

function getOptionLabel(filterId: RepairsTableFilterId, value: string) {
  if (filterId === "date") {
    return value === NO_DATE_VALUE
      ? "Без даты"
      : formatRepairCalendarDate(value)
  }
  return value
}

function compareFilterOptions(
  filterId: RepairsTableFilterId,
  left: string,
  right: string
) {
  if (left === NO_DATE_VALUE) return 1
  if (right === NO_DATE_VALUE) return -1
  if (left === "—") return 1
  if (right === "—") return -1
  if (filterId === "date" || filterId === "priority") {
    return left.localeCompare(right, "ru", { numeric: true })
  }
  return stringCollator.compare(left, right)
}

export function buildRepairsTableFilterDefinitions(
  repairs: RepairTaskDto[]
): RepairsTableFilterDefinition[] {
  const definitions: Array<{
    id: RepairsTableFilterId
    label: string
  }> = [
    { id: "repairType", label: "Тип ремонта" },
    { id: "task", label: "Задание" },
    { id: "date", label: "Дата" },
    { id: "priority", label: "Приоритет" },
    { id: "status", label: "Статус" },
  ]

  return definitions.map((definition) => {
    const values = Array.from(
      new Set(
        repairs.map((repair) => getRepairFilterValue(repair, definition.id))
      )
    ).sort((left, right) => compareFilterOptions(definition.id, left, right))

    return {
      ...definition,
      options: values.map((value) => ({
        value,
        label: getOptionLabel(definition.id, value),
      })),
    }
  })
}

export function filterRepairsTable(
  repairs: RepairTaskDto[],
  search: string,
  filters: RepairsTableFiltersState
) {
  const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU")

  return repairs.filter((repair) => {
    if (
      normalizedSearch &&
      !repair.cabinNumber.toLocaleLowerCase("ru-RU").includes(normalizedSearch)
    ) {
      return false
    }

    return REPAIRS_TABLE_FILTER_IDS.every((filterId) => {
      const selectedValues = filters[filterId]
      return (
        selectedValues.length === 0 ||
        selectedValues.includes(getRepairFilterValue(repair, filterId))
      )
    })
  })
}
