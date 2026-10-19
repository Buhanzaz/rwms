import { getUserManager } from "@/features/auth/oidc-client"

export type MaintenanceAccessTokenProvider = () => Promise<string>

export const currentMaintenanceAccessToken: MaintenanceAccessTokenProvider =
  async () => {
    const user = await getUserManager().getUser()
    if (!user || user.expired || !user.access_token.trim()) {
      throw new Error("Не получен токен доступа к сервису ремонта.")
    }
    return user.access_token
  }
