import { useQuery } from "@tanstack/react-query"

import { useAuth } from "@/features/auth/use-auth"
import { listDossierActorDisplays } from "@/features/rental-items/dossier/actor/actor-display-api"

export function useDossierActorDisplays(subjectIds: string[]) {
  const { accessToken, currentUser } = useAuth()
  const uniqueSubjectIds = [...new Set(subjectIds)].sort()
  const query = useQuery({
    queryKey: [
      "auth-actor-displays",
      currentUser?.id ?? "unknown-user",
      uniqueSubjectIds,
    ],
    queryFn: () => listDossierActorDisplays(accessToken!, uniqueSubjectIds),
    enabled: Boolean(accessToken && uniqueSubjectIds.length > 0),
    staleTime: 60_000,
  })

  return new Map(query.data?.map((display) => [display.subjectId, display]))
}
