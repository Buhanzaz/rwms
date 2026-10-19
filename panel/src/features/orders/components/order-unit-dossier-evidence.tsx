import { useMemo, useState } from "react"
import { useInfiniteQuery } from "@tanstack/react-query"

import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { DossierActivityRegister } from "@/features/rental-items/dossier/dossier-activity-register"
import {
  getRentalItemDossierPage,
  rentalItemDossierQueryKey,
} from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type {
  DossierActivity,
  DossierActivityCode,
  DossierActivityFilters,
} from "@/features/rental-items/dossier/model/dossier-service"
import type {
  OrderMovement,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"

const ESTIMATE_ACTIVITY_CODES = [
  "ESTIMATE_CREATED",
  "ESTIMATE_DRAFT_CHANGED",
  "ESTIMATE_COMPLETED",
  "ESTIMATE_AMENDED",
] as const satisfies readonly DossierActivityCode[]

const REPAIR_ACTIVITY_CODES = [
  "REPAIR_CREATED",
  "REPAIR_PLAN_CHANGED",
  "REPAIR_QUEUED",
  "REPAIR_STAGE_COMPLETED",
  "REPAIR_PENDING_ACCEPTANCE",
  "REPAIR_REWORK_CREATED",
  "REPAIR_TRANSFER_PREPARED",
  "REPAIR_TRANSFERRED",
  "REPAIR_ACCEPTED",
  "REPAIR_WRITTEN_OFF",
] as const satisfies readonly DossierActivityCode[]

const MAINTENANCE_ACTIVITY_CODES = [
  ...ESTIMATE_ACTIVITY_CODES,
  ...REPAIR_ACTIVITY_CODES,
]

function containsActivity(
  activities: DossierActivity[],
  codes: readonly DossierActivityCode[]
) {
  return activities.some((activity) => codes.includes(activity.activityCode))
}

function EvidenceStatus({
  label,
  confirmed,
  complete,
}: {
  label: string
  confirmed: boolean
  complete: boolean
}) {
  return (
    <div className="rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <p className="font-medium">{label}</p>
        <Badge variant={confirmed ? "secondary" : "outline"}>
          {confirmed
            ? "Подтверждено событием"
            : complete
              ? "Подтверждённо отсутствует"
              : "Нет подтверждения"}
        </Badge>
      </div>
      {!confirmed && !complete ? (
        <p className="mt-2 text-sm text-muted-foreground">
          Проекция неполная или недоступна: отсутствие события нельзя считать
          доказательством отсутствия действия.
        </p>
      ) : null}
    </div>
  )
}

export function OrderUnitDossierEvidence({
  accessToken,
  orderId,
  orderCreatedAt,
  candidates,
  movements,
}: {
  accessToken: string
  orderId: string
  orderCreatedAt: string
  candidates: OrderUnitCandidate[]
  movements: OrderMovement[]
}) {
  const [requestedUnitId, setRequestedUnitId] = useState<string | null>(null)
  const selectedCandidate =
    candidates.find((candidate) => candidate.unit.id === requestedUnitId) ??
    candidates[0] ??
    null
  const selectedUnitId = selectedCandidate?.unit.id ?? null
  const actualReturnAt = movements
    .filter(
      (movement) =>
        movement.actualAt !== null &&
        movement.documentType === "RETURN" &&
        movement.cabins.some((cabin) => cabin.rentalItemId === selectedUnitId)
    )
    .map((movement) => movement.actualAt!)
    .sort((left, right) => Date.parse(left) - Date.parse(right))
    .at(-1)
  const evidenceLowerBound = actualReturnAt ?? orderCreatedAt
  const filters = useMemo<DossierActivityFilters>(
    () => ({
      occurredFrom: evidenceLowerBound,
      activityCodes: [...MAINTENANCE_ACTIVITY_CODES],
      sourceTypes: ["MAINTENANCE"],
    }),
    [evidenceLowerBound]
  )
  const dossierQuery = useInfiniteQuery({
    queryKey: [
      ...rentalItemDossierQueryKey(selectedUnitId ?? "none", filters),
      "order-evidence",
      orderId,
    ],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      getRentalItemDossierPage(accessToken, selectedUnitId!, {
        ...filters,
        limit: 50,
        after: pageParam ?? undefined,
      }),
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: selectedUnitId !== null,
  })
  const pages = dossierQuery.data?.pages
  const activities = pages?.flatMap((page) => page.activities) ?? []
  const projectionComplete = Boolean(
    pages &&
    pages.length > 0 &&
    !dossierQuery.hasNextPage &&
    !dossierQuery.isFetching &&
    !dossierQuery.isError &&
    pages.every((page) => page.visibility === "COMPLETE")
  )
  const estimateConfirmed = containsActivity(
    activities,
    ESTIMATE_ACTIVITY_CODES
  )
  const repairConfirmed = containsActivity(activities, REPAIR_ACTIVITY_CODES)

  if (candidates.length === 0) return null

  return (
    <section
      className="flex flex-col gap-3"
      aria-labelledby="order-dossier-title"
    >
      <div>
        <h2 id="order-dossier-title" className="text-lg font-semibold">
          Смета и ремонт после заказа
        </h2>
        <p className="text-sm text-muted-foreground">
          Доказательства читаются из публичной проекции досье по выбранной
          бытовке. Нижняя граница — фактический возврат, а пока он не
          зарегистрирован — момент создания заказа.
        </p>
      </div>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Бытовка для проверки</CardTitle>
          <CardDescription>
            Состояние каждой бытовки проверяется независимо. Сейчас события
            проверяются с {new Date(evidenceLowerBound).toLocaleString("ru-RU")}
            {actualReturnAt ? " (фактический возврат)" : " (создание заказа)"}.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <Select
            value={selectedUnitId ?? ""}
            onValueChange={setRequestedUnitId}
          >
            <SelectTrigger
              aria-label="Бытовка для проверки досье"
              className="w-full max-w-xl"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {candidates.map((candidate) => (
                  <SelectItem key={candidate.unit.id} value={candidate.unit.id}>
                    {candidate.unit.number}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>

          {dossierQuery.isError ? (
            <p className="text-sm text-muted-foreground">
              Досье недоступно: нет подтверждения наличия или отсутствия сметы и
              ремонта.
            </p>
          ) : dossierQuery.isPending ? (
            <p className="text-sm text-muted-foreground">
              Проверяем подтверждённые события…
            </p>
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-2">
                <EvidenceStatus
                  label="Смета"
                  confirmed={estimateConfirmed}
                  complete={projectionComplete}
                />
                <EvidenceStatus
                  label="Ремонт"
                  confirmed={repairConfirmed}
                  complete={projectionComplete}
                />
              </div>
              {estimateConfirmed && !repairConfirmed ? (
                <p className="rounded-lg border border-dashed p-3 text-sm">
                  {projectionComplete
                    ? "Смета есть, ремонт после неё не зафиксирован."
                    : "Смета есть; данных недостаточно, чтобы подтвердить отсутствие ремонта после неё."}
                </p>
              ) : null}
            </>
          )}
        </CardContent>
      </Card>

      <DossierActivityRegister
        pages={pages}
        error={dossierQuery.error}
        isLoading={dossierQuery.isPending}
        hasNextPage={Boolean(dossierQuery.hasNextPage)}
        isFetchingNextPage={dossierQuery.isFetchingNextPage}
        onLoadMore={() => void dossierQuery.fetchNextPage()}
      />
    </section>
  )
}
