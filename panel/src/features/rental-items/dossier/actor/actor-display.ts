import type { UserGlobalRole } from "@/features/auth/auth-model"
import type { DossierActorReference } from "@/features/rental-items/dossier/model/dossier-service"

export type DossierActorDisplay = {
  subjectId: string
  principalType: "USER" | "WORKER"
  globalRole: UserGlobalRole | null
  username: string
  firstName: string | null
  lastName: string | null
  email: string | null
  middleName?: string | null
}

const globalRoleLabels: Record<UserGlobalRole, string> = {
  SYSTEM_ADMIN: "Системный администратор",
  WMS_ADMIN: "Администратор WMS",
  WAREHOUSE_MANAGER: "Руководитель склада",
  RENTAL_MANAGER: "Менеджер аренды",
  VIEWER: "Наблюдатель",
}

const principalTypeLabels: Record<string, string> = {
  USER: "Пользователь",
  WORKER: "Работник",
  SERVICE: "Сервис",
  SYSTEM: "Система",
}

function nonBlank(value: string | null | undefined) {
  const normalized = value?.trim()
  return normalized || null
}

export function formatDossierActorLabel(
  actor: DossierActorReference,
  display: DossierActorDisplay | undefined
) {
  const matchingDisplay =
    display?.subjectId === actor.subjectId ? display : undefined
  const role = matchingDisplay?.globalRole
    ? globalRoleLabels[matchingDisplay.globalRole]
    : (principalTypeLabels[actor.principalType] ?? "Участник")
  const fullName = matchingDisplay
    ? [
        matchingDisplay.lastName,
        matchingDisplay.firstName,
        matchingDisplay.middleName,
      ]
        .map(nonBlank)
        .filter((part): part is string => part !== null)
        .join(" ")
    : ""
  const identity =
    fullName ||
    nonBlank(matchingDisplay?.email) ||
    nonBlank(matchingDisplay?.username) ||
    actor.subjectId

  return `${role} — ${identity}`
}
