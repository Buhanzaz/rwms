import type { RepairSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

const REPAIR_SETTINGS_ACTIONS: RepairSettingsActionDto[] = [
  {
    id: "repair-stage-settings",
    title: "Этапы ремонта",
    mockOnly: true,
    order: 10,
  },
  {
    id: "repair-route-settings",
    title: "Маршрут ремонта",
    mockOnly: true,
    order: 20,
  },
  {
    id: "repair-acceptance-settings",
    title: "Приёмка ремонта",
    mockOnly: true,
    order: 30,
  },
  {
    id: "repair-rework-settings",
    title: "Доработки",
    mockOnly: true,
    order: 40,
  },
]

export async function getRepairSettingsActions() {
  return REPAIR_SETTINGS_ACTIONS
}
