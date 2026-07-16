import type {
  RepairTaskKind,
  RepairTaskOrigin,
} from "@/features/repair-tasks/model/repair-task"

const dateTimeFormatter = new Intl.DateTimeFormat("ru-RU", {
  dateStyle: "medium",
  timeStyle: "short",
})

export function formatAcceptanceDateTime(value: string | null) {
  return value ? dateTimeFormatter.format(new Date(value)) : "—"
}

export function formatActiveTime(seconds: number) {
  const safeSeconds = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(safeSeconds / 3600)
  const minutes = Math.floor((safeSeconds % 3600) / 60)
  const remainder = safeSeconds % 60

  if (hours > 0) {
    return `${hours} ч ${minutes} мин`
  }

  return `${minutes} мин ${remainder} с`
}

export function repairTaskOriginLabel(
  origin: RepairTaskOrigin,
  kind: RepairTaskKind = "REPAIR"
) {
  if (kind === "REWORK") return "Доработка"
  if (origin === "INVENTORY") return "Инвентаризация"
  return origin === "ESTIMATE" ? "Из сметы" : "Прямой ремонт"
}
