import {
  buildKpiPeriodQuery,
  type KpiPeriod,
  type KpiPeriodType,
} from "@/features/kpi/domain/kpi-period"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type KpiCoverageStatus =
  "PROVISIONAL" | "PARTIAL" | "COMPLETE" | "NO_DATA"

export type GroupKpiMetric = {
  workerGroupId: string
  kpi: number | null
  speed: number | null
  utilization: number | null
  completedTaskCount: number
  completedBudgetSeconds: number
  activeSeconds: number
  penalizedIdleSeconds: number
}

export type GroupKpiResponse = {
  warehouseId: string
  periodType: KpiPeriodType
  periodStart: string
  periodEnd: string
  coverageStart: string | null
  coverageEnd: string | null
  status: KpiCoverageStatus
  dataAvailableFrom: string | null
  formulaVersion: string
  asOf: string
  groups: GroupKpiMetric[]
}

export type KpiWorkerGroup = {
  id: string
  name: string
  active: boolean
}

export const kpiKeys = {
  all: ["analytics", "group-kpi"] as const,
  period: (warehouseId: string, period: KpiPeriod) =>
    [...kpiKeys.all, warehouseId, buildKpiPeriodQuery(period)] as const,
  groups: (warehouseId: string) =>
    ["task-board", "worker-groups", warehouseId] as const,
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0
}

function nullableString(value: unknown): value is string | null {
  return value === null || nonEmptyString(value)
}

function metricNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
}

function nullableMetric(value: unknown): value is number | null {
  return value === null || metricNumber(value)
}

function parseMetric(value: unknown): GroupKpiMetric {
  if (
    !isRecord(value) ||
    !nonEmptyString(value.workerGroupId) ||
    !nullableMetric(value.kpi) ||
    !nullableMetric(value.speed) ||
    !nullableMetric(value.utilization) ||
    !metricNumber(value.completedTaskCount) ||
    !metricNumber(value.completedBudgetSeconds) ||
    !metricNumber(value.activeSeconds) ||
    !metricNumber(value.penalizedIdleSeconds)
  ) {
    throw new Error("Сервис аналитики вернул некорректный KPI бригады.")
  }

  return {
    workerGroupId: value.workerGroupId,
    kpi: value.kpi,
    speed: value.speed,
    utilization: value.utilization,
    completedTaskCount: value.completedTaskCount,
    completedBudgetSeconds: value.completedBudgetSeconds,
    activeSeconds: value.activeSeconds,
    penalizedIdleSeconds: value.penalizedIdleSeconds,
  }
}

function parseResponse(value: unknown): GroupKpiResponse {
  const periodTypes: KpiPeriodType[] = ["YEAR", "QUARTER", "MONTH", "DAY"]
  const statuses: KpiCoverageStatus[] = [
    "PROVISIONAL",
    "PARTIAL",
    "COMPLETE",
    "NO_DATA",
  ]
  if (
    !isRecord(value) ||
    !nonEmptyString(value.warehouseId) ||
    typeof value.periodType !== "string" ||
    !periodTypes.includes(value.periodType as KpiPeriodType) ||
    !nonEmptyString(value.periodStart) ||
    !nonEmptyString(value.periodEnd) ||
    !nullableString(value.coverageStart) ||
    !nullableString(value.coverageEnd) ||
    typeof value.status !== "string" ||
    !statuses.includes(value.status as KpiCoverageStatus) ||
    !nullableString(value.dataAvailableFrom) ||
    !nonEmptyString(value.formulaVersion) ||
    !nonEmptyString(value.asOf) ||
    !Array.isArray(value.groups)
  ) {
    throw new Error("Сервис аналитики вернул некорректную историю KPI.")
  }

  return {
    warehouseId: value.warehouseId,
    periodType: value.periodType as KpiPeriodType,
    periodStart: value.periodStart,
    periodEnd: value.periodEnd,
    coverageStart: value.coverageStart,
    coverageEnd: value.coverageEnd,
    status: value.status as KpiCoverageStatus,
    dataAvailableFrom: value.dataAvailableFrom,
    formulaVersion: value.formulaVersion,
    asOf: value.asOf,
    groups: value.groups.map(parseMetric),
  }
}

function analyticsEndpoint(warehouseId: string, period: KpiPeriod) {
  if (!warehouseId.trim()) throw new Error("Не выбран склад для просмотра KPI.")
  return `${window.location.origin}/api/analytics/v1/warehouses/${encodeURIComponent(warehouseId)}/group-kpi?${buildKpiPeriodQuery(period)}`
}

export async function getGroupKpi(
  accessToken: string,
  warehouseId: string,
  period: KpiPeriod
) {
  return parseResponse(
    await bearerRequest<unknown>(
      accessToken,
      analyticsEndpoint(warehouseId, period)
    )
  )
}

export async function listKpiWorkerGroups(
  accessToken: string,
  warehouseId: string
): Promise<KpiWorkerGroup[]> {
  const value = await bearerRequest<unknown>(
    accessToken,
    `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/warehouses/${encodeURIComponent(warehouseId)}/worker-groups`
  )
  if (!Array.isArray(value)) {
    throw new Error("Сервис доски задач вернул некорректный список бригад.")
  }
  return value.map((group) => {
    if (
      !isRecord(group) ||
      !nonEmptyString(group.id) ||
      !nonEmptyString(group.name) ||
      typeof group.active !== "boolean"
    ) {
      throw new Error("Сервис доски задач вернул некорректную бригаду.")
    }
    return { id: group.id, name: group.name, active: group.active }
  })
}
