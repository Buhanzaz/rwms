import { AUTHORITY } from "@/features/auth/auth-config"
import type {
  AdminUser,
  AdminUserProfileInput,
  CreateAdminUserInput,
  ReplaceWarehouseAccessesInput,
} from "@/features/settings/users/model/users"
import { bearerRequest } from "@/lib/api-client"

const USERS_ENDPOINT = `${AUTHORITY}/api/admin/users`

export function listAdminUsers(accessToken: string) {
  return bearerRequest<AdminUser[]>(accessToken, USERS_ENDPOINT)
}

export function createAdminUser(
  accessToken: string,
  input: CreateAdminUserInput
) {
  return bearerRequest<AdminUser>(accessToken, USERS_ENDPOINT, {
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
  return bearerRequest<AdminUser>(
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
  return bearerRequest<AdminUser>(
    accessToken,
    `${USERS_ENDPOINT}/${encodeURIComponent(userId)}/warehouse-accesses`,
    {
      method: "PUT",
      body: JSON.stringify({ ...input, expectedVersion }),
    }
  )
}
