import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type KpiSettingsStatus =
  "UNCONFIGURED" | "DRAFT" | "SCHEDULED" | "ACTIVE"

export type KpiPaletteRange = {
  fromPercent: number
  toPercent: number
  color: string
}

export type KpiPalette = {
  version: number
  ranges: KpiPaletteRange[]
  overdueColor: string
  problemColor?: string
  completedColor?: string
}

export type KpiPaletteResponse = {
  version: number
  palette: KpiPalette | null
}

export type KpiWorkBreak = {
  start: string
  end: string
}

export type KpiWorkSchedule = {
  id: string
  version: number
  effectiveFrom: string
  shiftStart: string
  shiftEnd: string
  daysOff: number[]
  breaks: KpiWorkBreak[]
}

export type KpiSettingsResponse = KpiPaletteResponse & {
  status: KpiSettingsStatus
  dataAvailableFrom: string | null
  minimumEffectiveDate: string
  activeSchedule: KpiWorkSchedule | null
  pendingSchedule: KpiWorkSchedule | null
}

export type SaveKpiPaletteInput = {
  expectedVersion: number
  ranges: KpiPaletteRange[]
  overdueColor: string
  problemColor?: string
  completedColor?: string
}

export type SaveWorkScheduleInput = {
  expectedVersion: number
  effectiveFrom: string
  shiftStart: string
  shiftEnd: string
  daysOff: number[]
  breaks: KpiWorkBreak[]
}

export const kpiSettingsKeys = {
  all: ["task-board", "kpi-settings"] as const,
  settings: ["task-board", "kpi-settings", "global"] as const,
  palette: ["task-board", "kpi-palette"] as const,
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function isString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0
}

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
}

function parseBreak(value: unknown): KpiWorkBreak {
  if (!isRecord(value) || !isString(value.start) || !isString(value.end)) {
    throw new Error("Сервис доски задач вернул некорректный перерыв KPI.")
  }
  return { start: value.start, end: value.end }
}

function parseSchedule(value: unknown): KpiWorkSchedule | null {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !isString(value.id) ||
    !isVersion(value.version) ||
    !isString(value.effectiveFrom) ||
    !isString(value.shiftStart) ||
    !isString(value.shiftEnd) ||
    !Array.isArray(value.daysOff) ||
    !value.daysOff.every(
      (day) => typeof day === "number" && Number.isInteger(day)
    ) ||
    !Array.isArray(value.breaks)
  ) {
    throw new Error("Сервис доски задач вернул некорректный график KPI.")
  }

  return {
    id: value.id,
    version: value.version,
    effectiveFrom: value.effectiveFrom,
    shiftStart: value.shiftStart,
    shiftEnd: value.shiftEnd,
    daysOff: value.daysOff,
    breaks: value.breaks.map(parseBreak),
  }
}

function parsePalette(value: unknown): KpiPalette | null {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !isVersion(value.version) ||
    !Array.isArray(value.ranges) ||
    !isString(value.overdueColor)
  ) {
    throw new Error("Сервис доски задач вернул некорректную палитру KPI.")
  }

  const ranges = value.ranges.map((range) => {
    if (
      !isRecord(range) ||
      typeof range.fromPercent !== "number" ||
      typeof range.toPercent !== "number" ||
      !isString(range.color)
    ) {
      throw new Error("Сервис доски задач вернул некорректную палитру KPI.")
    }
    return {
      fromPercent: range.fromPercent,
      toPercent: range.toPercent,
      color: range.color,
    }
  })
  if (!isString(value.problemColor)) {
    throw new Error("Сервис доски задач вернул некорректную палитру KPI.")
  }
  if (value.completedColor !== undefined && !isString(value.completedColor)) {
    throw new Error("Сервис доски задач вернул некорректную палитру KPI.")
  }
  return {
    version: value.version,
    ranges,
    overdueColor: value.overdueColor,
    problemColor: value.problemColor,
    completedColor: value.completedColor === undefined ? "#238636" : value.completedColor,
  }
}

function parseSettings(value: unknown): KpiSettingsResponse {
  const statuses: KpiSettingsStatus[] = [
    "UNCONFIGURED",
    "DRAFT",
    "SCHEDULED",
    "ACTIVE",
  ]
  if (
    !isRecord(value) ||
    typeof value.status !== "string" ||
    !statuses.includes(value.status as KpiSettingsStatus) ||
    !isVersion(value.version) ||
    !(value.dataAvailableFrom === null || isString(value.dataAvailableFrom)) ||
    !isString(value.minimumEffectiveDate)
  ) {
    throw new Error("Сервис доски задач вернул некорректные настройки KPI.")
  }

  return {
    status: value.status as KpiSettingsStatus,
    version: value.version,
    dataAvailableFrom: value.dataAvailableFrom,
    minimumEffectiveDate: value.minimumEffectiveDate,
    palette: parsePalette(value.palette),
    activeSchedule: parseSchedule(value.activeSchedule),
    pendingSchedule: parseSchedule(value.pendingSchedule),
  }
}

function parsePaletteResponse(value: unknown): KpiPaletteResponse {
  if (!isRecord(value) || !isVersion(value.version)) {
    throw new Error("Сервис доски задач вернул некорректную общую палитру KPI.")
  }

  return {
    version: value.version,
    palette: parsePalette(value.palette),
  }
}

function settingsEndpoint() {
  return `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/kpi-settings`
}

function kpiPaletteEndpoint() {
  return `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/kpi-palette`
}

function json(method: string, body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) }
}

export async function getKpiSettings(accessToken: string) {
  return parseSettings(
    await bearerRequest<unknown>(accessToken, settingsEndpoint())
  )
}

export async function getKpiPalette(accessToken: string) {
  return parsePaletteResponse(
    await bearerRequest<unknown>(accessToken, kpiPaletteEndpoint())
  )
}

export async function saveKpiPalette(
  accessToken: string,
  input: SaveKpiPaletteInput
) {
  return parsePaletteResponse(
    await bearerRequest<unknown>(
      accessToken,
      kpiPaletteEndpoint(),
      json("PUT", input)
    )
  )
}

export async function saveWorkSchedule(
  accessToken: string,
  input: SaveWorkScheduleInput
) {
  return parseSettings(
    await bearerRequest<unknown>(
      accessToken,
      `${settingsEndpoint()}/work-schedule`,
      json("PUT", input)
    )
  )
}

export function deletePendingWorkSchedule(
  accessToken: string,
  expectedVersion: number
) {
  const params = new URLSearchParams({
    expectedVersion: String(expectedVersion),
  })
  return bearerRequest<void>(
    accessToken,
    `${settingsEndpoint()}/work-schedule/pending?${params}`,
    { method: "DELETE" }
  )
}

export async function activateKpiSettings(
  accessToken: string,
  expectedVersion: number,
  idempotencyKey: string
) {
  if (!idempotencyKey.trim()) {
    throw new Error("Не получен ключ идемпотентности активации KPI.")
  }
  return parseSettings(
    await bearerRequest<unknown>(
      accessToken,
      `${settingsEndpoint()}/activate`,
      {
        ...json("POST", { expectedVersion }),
        headers: { "Idempotency-Key": idempotencyKey },
      }
    )
  )
}
