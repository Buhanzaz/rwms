import { AUTHORITY } from "@/features/auth/auth-config"
import type { CurrentUser } from "@/features/auth/auth-model"
import { bearerRequest } from "@/lib/api-client"

export function getCurrentUser(accessToken: string) {
  return bearerRequest<CurrentUser>(accessToken, `${AUTHORITY}/api/users/me`)
}
