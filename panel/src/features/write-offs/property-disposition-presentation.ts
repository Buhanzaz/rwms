import type { CurrentUser } from "@/features/auth/auth-model"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"

import type {
  PropertyDispositionDecision,
  PropertyDispositionSource,
} from "./property-dispositions-api"

export function canInitiatePropertyDisposition(
  user: CurrentUser | null,
  warehouseId: string
) {
  return Boolean(
    user &&
    (user.globalRole === "WAREHOUSE_MANAGER" ||
      isGlobalAdministrator(user.globalRole)) &&
    hasWarehouseAccess(user, warehouseId, "MANAGE")
  )
}

export function canReviewPropertyDisposition(
  user: CurrentUser | null,
  warehouseId: string
) {
  return Boolean(
    user &&
    isGlobalAdministrator(user.globalRole) &&
    hasWarehouseAccess(user, warehouseId, "MANAGE")
  )
}

export function propertyDispositionStatusLabel(
  decision: Pick<
    PropertyDispositionDecision,
    "state" | "assetEffectState" | "disposition"
  >
) {
  switch (decision.state) {
    case "PENDING_APPROVAL":
      return "На согласовании"
    case "APPROVED":
      return "Одобрено, эффект не начат"
    case "MOVEMENT_PENDING":
      return "Ожидает перемещения"
    case "EFFECT_PENDING":
      return "Эффект выполняется"
    case "EFFECTIVE":
      if (
        decision.assetEffectState !== "APPLIED" &&
        decision.assetEffectState !== "NOT_REQUIRED"
      )
        return "Подтверждение эффекта"
      return decision.disposition === "WRITE_OFF" ? "Списано" : "Утеряно"
    case "REJECTED":
      return "Отклонено"
    case "QUARANTINED":
      return "Требует восстановления"
  }
}

export function propertyDispositionEffectLabel(
  decision: Pick<PropertyDispositionDecision, "assetEffectState">
) {
  switch (decision.assetEffectState) {
    case "NOT_STARTED":
      return "Не начат"
    case "PENDING":
      return "Выполняется"
    case "APPLIED":
      return "Применён"
    case "NOT_REQUIRED":
      return "Без складского эффекта"
    case "QUARANTINED":
      return "Карантин"
  }
}

const SOURCE_LABELS: Record<PropertyDispositionSource, string> = {
  MANUAL: "Ручное решение",
  REPAIR: "Ремонт",
  ESTIMATE: "Смета",
  INVENTORY: "Инвентаризация",
  UNACCOUNTED: "Неучтённое наполнение",
}

export function propertyDispositionSourceLabel(
  source: PropertyDispositionSource
) {
  return SOURCE_LABELS[source]
}
