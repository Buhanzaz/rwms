import { AUTHORITY } from "@/features/auth/auth-config"
import type {
  AdminUser,
  AdminUserProfileInput,
  CreateAdminUserInput,
  ReplaceWarehouseAccessesInput,
} from "@/features/settings/users/model/users"
import { bearerRequest } from "@/lib/api-client"

const USERS_ENDPOINT = `${AUTHORITY}/api/admin/users`

export class AdminUserResponseProtocolError extends Error {
  constructor() {
    super(
      "Сервер авторизации вернул пользователя без корректного поля mobileAppAccess или rentalAccess. Обновите auth-service: сохранение доступов не подтверждено."
    )
    this.name = "AdminUserResponseProtocolError"
  }
}

function requireAdminUserEntitlements(response: unknown): AdminUser {
  if (
    typeof response !== "object" ||
    response === null ||
    Array.isArray(response) ||
    typeof (response as { mobileAppAccess?: unknown }).mobileAppAccess !==
      "boolean" ||
    typeof (response as { rentalAccess?: unknown }).rentalAccess !== "boolean"
  ) {
    throw new AdminUserResponseProtocolError()
  }

  return response as AdminUser
}

async function requestAdminUser(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return requireAdminUserEntitlements(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

export function listAdminUsers(accessToken: string) {
  return bearerRequest<unknown>(accessToken, USERS_ENDPOINT).then(
    (response) => {
      if (!Array.isArray(response)) {
        throw new AdminUserResponseProtocolError()
      }

      return response.map(requireAdminUserEntitlements)
    }
  )
}

export function createAdminUser(
  accessToken: string,
  input: CreateAdminUserInput
) {
  return requestAdminUser(accessToken, USERS_ENDPOINT, {
    method: "POST",
    body: JSON.stringify(input),
  })
}

export function updateAdminUser(
  accessToken: string,
  userId: string,
  expectedVersion: number,
  input: AdminUserProfileInput
) {
  return requestAdminUser(
    accessToken,
    `${USERS_ENDPOINT}/${encodeURIComponent(userId)}`,
    {
      method: "PUT",
      body: JSON.stringify({ ...input, expectedVersion }),
    }
  )
}

export function changeAdminUserPassword(
  accessToken: string,
  userId: string,
  expectedVersion: number,
  password: string
) {
  return bearerRequest<void>(
    accessToken,
    `${USERS_ENDPOINT}/${encodeURIComponent(userId)}/password`,
    {
      method: "PUT",
      body: JSON.stringify({ password, expectedVersion }),
    }
  )
}

export function replaceAdminUserWarehouseAccesses(
  accessToken: string,
  userId: string,
  expectedVersion: number,
  input: ReplaceWarehouseAccessesInput
) {
  return requestAdminUser(
    accessToken,
    `${USERS_ENDPOINT}/${encodeURIComponent(userId)}/warehouse-accesses`,
    {
      method: "PUT",
      body: JSON.stringify({ ...input, expectedVersion }),
    }
  )
}
