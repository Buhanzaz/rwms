import type {
  InventoryFindingDto,
  InventoryFindingOrigin,
  InventoryReconciliationStatus,
} from "@/features/inventory/model/inventory"
import { inventorySessionStatus } from "@/features/inventory/domain/inventory-session-status"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

type InventoryFindingInspectionFilter = "INSPECTED" | "NOT_INSPECTED"
type InventoryFindingAdditionFilter = "ADDED" | "NOT_ADDED"
type InventoryFindingPresenceFilter = "FOUND" | "MISSING"
type InventoryFindingWorkFilter = "WITH_WORK" | "WITHOUT_WORK"

export type InventoryFindingFiltersState = {
  cabinNumber: string
  origins: InventoryFindingOrigin[]
  statuses: RentalItemStatus[]
  reconciliations: InventoryReconciliationStatus[]
  inspections: InventoryFindingInspectionFilter[]
  additions: InventoryFindingAdditionFilter[]
  presences: InventoryFindingPresenceFilter[]
  works: InventoryFindingWorkFilter[]
}

export function createEmptyInventoryFindingFilters(): InventoryFindingFiltersState {
  return {
    cabinNumber: "",
    origins: [],
    statuses: [],
    reconciliations: [],
    inspections: [],
    additions: [],
    presences: [],
    works: [],
  }
}

function includesInspection(
  filters: InventoryFindingInspectionFilter[],
  finding: InventoryFindingDto
) {
  if (filters.length === 0) return true

  const inspected = finding.inspectionStatus !== "NOT_INSPECTED"
  return (
    (inspected && filters.includes("INSPECTED")) ||
    (!inspected && filters.includes("NOT_INSPECTED"))
  )
}

function includesAddition(
  filters: InventoryFindingAdditionFilter[],
  finding: InventoryFindingDto
) {
  if (filters.length === 0) return true

  const added =
    finding.origin === "ADDED_NEW" || finding.origin === "ADDED_USED"
  return (
    (added && filters.includes("ADDED")) ||
    (!added && filters.includes("NOT_ADDED"))
  )
}

function includesPresence(
  filters: InventoryFindingPresenceFilter[],
  finding: InventoryFindingDto
) {
  if (filters.length === 0) return true

  const found = finding.reconciliationStatus !== "MISSING"
  return (
    (found && filters.includes("FOUND")) ||
    (!found && filters.includes("MISSING"))
  )
}

function includesWork(
  filters: InventoryFindingWorkFilter[],
  finding: InventoryFindingDto
) {
  if (filters.length === 0) return true

  const hasWork = finding.lines.length > 0
  return (
    (hasWork && filters.includes("WITH_WORK")) ||
    (!hasWork && filters.includes("WITHOUT_WORK"))
  )
}

function includesReconciliation(
  filters: InventoryReconciliationStatus[],
  finding: InventoryFindingDto
) {
  if (filters.length === 0) return true

  return filters.some((filter) => {
    if (filter === "CONFLICT") {
      return (
        finding.reconciliationStatus === "CONFLICT" ||
        finding.conflicts.length > 0
      )
    }

    return finding.reconciliationStatus === filter
  })
}

export function filterInventoryFindings(
  findings: InventoryFindingDto[],
  filters: InventoryFindingFiltersState
) {
  const number = filters.cabinNumber.trim().toLocaleLowerCase("ru-RU")

  return findings.filter((finding) => {
    const status = inventorySessionStatus(finding)
    const matchesNumber =
      number.length === 0 ||
      finding.cabinNumber.toLocaleLowerCase("ru-RU").includes(number) ||
      finding.canonicalNumber.toLocaleLowerCase("ru-RU").includes(number)

    return (
      matchesNumber &&
      (filters.origins.length === 0 ||
        filters.origins.includes(finding.origin)) &&
      (filters.statuses.length === 0 ||
        (status !== null && filters.statuses.includes(status))) &&
      includesReconciliation(filters.reconciliations, finding) &&
      includesInspection(filters.inspections, finding) &&
      includesAddition(filters.additions, finding) &&
      includesPresence(filters.presences, finding) &&
      includesWork(filters.works, finding)
    )
  })
}
