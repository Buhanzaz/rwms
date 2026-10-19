import type {
  WarehouseSupportLinkInfo,
  WarehouseSupportLinkInput,
  WarehouseSupportWeekday,
} from "@/api/warehouse-api"

export type WarehouseSupportLinkDraft = {
  key: string
  supportWarehouseId: string
  active: boolean
  priority: string
  allowDrivers: boolean
  allowVehicles: boolean
  allowInventory: boolean
  allowDirectFulfillment: boolean
  allowInterwarehouseTransfer: boolean
  allowContractorFallback: boolean
  allowedWeekdays: WarehouseSupportWeekday[]
  allowedDates: string
  excludedDates: string
  serviceStart: string
  serviceEnd: string
}

type WarehouseSupportLinksFormResult =
  | { links: WarehouseSupportLinkInput[]; error: null }
  | { links: null; error: string }

const CALENDAR_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

function formatTime(value: string | null) {
  return value?.slice(0, 5) ?? ""
}

export function createWarehouseSupportLinkDraft(
  link: WarehouseSupportLinkInfo,
  index: number
): WarehouseSupportLinkDraft {
  return {
    key: link.id || `saved-${index}`,
    supportWarehouseId: link.supportWarehouseId,
    active: link.active,
    priority: String(link.priority),
    allowDrivers: link.allowDrivers,
    allowVehicles: link.allowVehicles,
    allowInventory: link.allowInventory,
    allowDirectFulfillment: link.allowDirectFulfillment,
    allowInterwarehouseTransfer: link.allowInterwarehouseTransfer,
    allowContractorFallback: link.allowContractorFallback,
    allowedWeekdays: [...link.allowedWeekdays],
    allowedDates: link.allowedDates.join(", "),
    excludedDates: link.excludedDates.join(", "),
    serviceStart: formatTime(link.serviceStart),
    serviceEnd: formatTime(link.serviceEnd),
  }
}

export function createEmptyWarehouseSupportLinkDraft(
  key: string,
  priority: number
): WarehouseSupportLinkDraft {
  return {
    key,
    supportWarehouseId: "",
    active: true,
    priority: String(priority),
    allowDrivers: true,
    allowVehicles: true,
    allowInventory: true,
    allowDirectFulfillment: true,
    allowInterwarehouseTransfer: true,
    allowContractorFallback: false,
    allowedWeekdays: [],
    allowedDates: "",
    excludedDates: "",
    serviceStart: "",
    serviceEnd: "",
  }
}

function parseDateList(value: string) {
  const dates = value
    .split(/[\s,;]+/)
    .map((item) => item.trim())
    .filter(Boolean)

  if (
    dates.some((date) => {
      if (!CALENDAR_DATE_PATTERN.test(date)) return true
      const parsed = new Date(`${date}T00:00:00Z`)
      return (
        Number.isNaN(parsed.getTime()) || !parsed.toISOString().startsWith(date)
      )
    })
  ) {
    return null
  }

  return [...new Set(dates)].sort()
}

export function parseWarehouseSupportLinkDrafts(
  servedWarehouseId: string,
  drafts: WarehouseSupportLinkDraft[]
): WarehouseSupportLinksFormResult {
  const supportWarehouseIds = new Set<string>()
  const links: WarehouseSupportLinkInput[] = []

  for (const draft of drafts) {
    const priority = Number(draft.priority.trim())
    const allowedDates = parseDateList(draft.allowedDates)
    const excludedDates = parseDateList(draft.excludedDates)
    const serviceStart = draft.serviceStart.trim() || null
    const serviceEnd = draft.serviceEnd.trim() || null

    if (!draft.supportWarehouseId) {
      return { links: null, error: "Выберите опорный склад для каждой связи." }
    }
    if (draft.supportWarehouseId === servedWarehouseId) {
      return { links: null, error: "Склад не может обслуживать сам себя." }
    }
    if (supportWarehouseIds.has(draft.supportWarehouseId)) {
      return {
        links: null,
        error: "Один опорный склад нельзя добавить дважды.",
      }
    }
    if (!Number.isInteger(priority) || priority < 1) {
      return { links: null, error: "Приоритет должен быть целым числом от 1." }
    }
    if (allowedDates === null || excludedDates === null) {
      return {
        links: null,
        error: "Даты обслуживания указываются в формате ГГГГ-ММ-ДД.",
      }
    }
    if ((serviceStart === null) !== (serviceEnd === null)) {
      return {
        links: null,
        error: "Укажите и начало, и окончание интервала обслуживания.",
      }
    }
    if (
      serviceStart !== null &&
      serviceEnd !== null &&
      serviceStart >= serviceEnd
    ) {
      return {
        links: null,
        error: "Окончание обслуживания должно быть позже начала.",
      }
    }

    supportWarehouseIds.add(draft.supportWarehouseId)
    links.push({
      supportWarehouseId: draft.supportWarehouseId,
      active: draft.active,
      priority,
      allowDrivers: draft.allowDrivers,
      allowVehicles: draft.allowVehicles,
      allowInventory: draft.allowInventory,
      allowDirectFulfillment: draft.allowDirectFulfillment,
      allowInterwarehouseTransfer: draft.allowInterwarehouseTransfer,
      allowContractorFallback: draft.allowContractorFallback,
      allowedWeekdays: [...new Set(draft.allowedWeekdays)],
      allowedDates,
      excludedDates,
      serviceStart,
      serviceEnd,
    })
  }

  return { links, error: null }
}
