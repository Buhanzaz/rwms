import type { UserGlobalRole } from "@/features/auth/auth-model"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"
import { bearerRequest } from "@/lib/api-client"

const ACTOR_DISPLAYS_ENDPOINT = "/auth/api/users/actor-displays"
const MAX_SUBJECTS_PER_REQUEST = 100

const USER_GLOBAL_ROLES: UserGlobalRole[] = [
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
  "RENTAL_MANAGER",
  "VIEWER",
]

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function parseActorDisplay(value: unknown): DossierActorDisplay {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Auth-service вернул некорректный профиль автора.")
  }

  const candidate = value as Record<string, unknown>
  const globalRole = candidate.globalRole
  const middleName = candidate.middleName
  if (
    typeof candidate.subjectId !== "string" ||
    (candidate.principalType !== "USER" &&
      candidate.principalType !== "WORKER") ||
    (globalRole !== null &&
      !USER_GLOBAL_ROLES.includes(globalRole as UserGlobalRole)) ||
    typeof candidate.username !== "string" ||
    !isNullableString(candidate.firstName) ||
    !isNullableString(candidate.lastName) ||
    !isNullableString(candidate.email) ||
    (middleName !== undefined && !isNullableString(middleName))
  ) {
    throw new Error("Auth-service вернул некорректный профиль автора.")
  }

  return {
    subjectId: candidate.subjectId,
    principalType: candidate.principalType,
    globalRole: globalRole as UserGlobalRole | null,
    username: candidate.username,
    firstName: candidate.firstName,
    lastName: candidate.lastName,
    email: candidate.email,
    ...(middleName === undefined ? {} : { middleName }),
  }
}

async function requestActorDisplayBatch(
  accessToken: string,
  subjectIds: string[]
) {
  const query = new URLSearchParams()
  subjectIds.forEach((subjectId) => query.append("subjectId", subjectId))
  const response = await bearerRequest<unknown>(
    accessToken,
    `${ACTOR_DISPLAYS_ENDPOINT}?${query.toString()}`
  )
  if (!Array.isArray(response)) {
    throw new Error("Auth-service вернул некорректный список авторов.")
  }
  return response.map(parseActorDisplay)
}

export async function listDossierActorDisplays(
  accessToken: string,
  subjectIds: string[]
) {
  const uniqueSubjectIds = [...new Set(subjectIds)].sort()
  const batches: string[][] = []
  for (
    let offset = 0;
    offset < uniqueSubjectIds.length;
    offset += MAX_SUBJECTS_PER_REQUEST
  ) {
    batches.push(
      uniqueSubjectIds.slice(offset, offset + MAX_SUBJECTS_PER_REQUEST)
    )
  }

  const responses = await Promise.all(
    batches.map((batch) => requestActorDisplayBatch(accessToken, batch))
  )
  return responses.flat()
}
