import type {
  RepairTaskStatus,
  RepairTaskSubtaskStatus,
} from "@/features/repair-tasks/model/repair-task"

export function repairTaskStatusLabel(status: RepairTaskStatus) {
  if (status === "QUEUED") {
    return "В очереди"
  }
  if (status === "IN_PROGRESS") {
    return "В работе"
  }
  if (status === "COMPLETED") {
    return "Завершено"
  }
  if (status === "CANCELLED") {
    return "Отменено"
  }
  return "Черновик"
}

export function repairSubtaskStatusLabel(status: RepairTaskSubtaskStatus) {
  if (status === "IN_PROGRESS") {
    return "В работе"
  }
  if (status === "DONE") {
    return "Завершено"
  }
  if (status === "PAUSED") {
    return "На паузе"
  }
  if (status === "CANCELLED") {
    return "Отменено"
  }
  return "Ожидает"
}
