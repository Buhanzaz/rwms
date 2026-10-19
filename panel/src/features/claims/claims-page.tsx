import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Textarea } from "@/components/ui/textarea"
import {
  CUSTOMER_CABIN_PROBLEMS_QUERY_KEY,
  listCustomerCabinProblems,
  resolveCustomerCabinProblem,
  startCustomerCabinProblem,
} from "@/features/claims/api/claims-api"
import {
  CUSTOMER_CABIN_PROBLEM_CATEGORY_LABELS,
  CUSTOMER_CABIN_PROBLEM_CLIENT_TYPE_LABELS,
  CUSTOMER_CABIN_PROBLEM_PHASE_LABELS,
  CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS,
  CUSTOMER_CABIN_PROBLEM_RESOLUTION_LABELS,
  CUSTOMER_CABIN_PROBLEM_STATUS_LABELS,
  type CustomerCabinProblemClaim,
  type CustomerCabinProblemResolutionKind,
  type CustomerCabinProblemStatus,
} from "@/features/claims/model/claim"
import { useAuth } from "@/features/auth/use-auth"
import { ApiError } from "@/lib/api-client"

type ClaimFilter = "ALL" | CustomerCabinProblemStatus

const FILTERS: ReadonlyArray<{ value: ClaimFilter; label: string }> = [
  { value: "ALL", label: "Все претензии" },
  { value: "OPEN", label: "Требуют рассмотрения" },
  { value: "IN_PROGRESS", label: "В работе" },
  { value: "RESOLVED", label: "Решённые" },
]

function dateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function deadlineTone(value: string, status: CustomerCabinProblemStatus) {
  if (status === "RESOLVED") return "text-muted-foreground"
  return new Date(value).getTime() < Date.now()
    ? "text-destructive"
    : "text-muted-foreground"
}

function statusVariant(status: CustomerCabinProblemStatus) {
  if (status === "OPEN") return "destructive" as const
  if (status === "IN_PROGRESS") return "secondary" as const
  return "outline" as const
}

function actionLabel(action: CustomerCabinProblemClaim["actions"][number]) {
  if (action.actionKind === "STATUS_TRANSITION") {
    return "Претензия взята в работу"
  }
  return action.resolutionKind
    ? `Принято решение: ${CUSTOMER_CABIN_PROBLEM_RESOLUTION_LABELS[action.resolutionKind]}`
    : "Принято решение"
}

function claimErrorMessage(error: unknown) {
  if (error instanceof Error) return error.message
  return "Не удалось выполнить действие с претензией."
}

function ClaimHistory({ claim }: { claim: CustomerCabinProblemClaim }) {
  if (claim.actions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        Решение по претензии ещё не зафиксировано.
      </p>
    )
  }

  return (
    <ol className="space-y-3 border-l pl-4">
      {claim.actions.map((action) => (
        <li key={action.id} className="relative text-sm">
          <span className="absolute top-1.5 -left-[1.34rem] size-2 rounded-full bg-primary" />
          <p className="font-medium">{actionLabel(action)}</p>
          {action.comment ? (
            <p className="mt-1 whitespace-pre-wrap text-muted-foreground">
              {action.comment}
            </p>
          ) : null}
          <p className="mt-1 text-xs text-muted-foreground">
            {dateTime(action.occurredAt)}
          </p>
        </li>
      ))}
    </ol>
  )
}

function ClaimCard({
  claim,
  busy,
  onStart,
  onResolve,
}: {
  claim: CustomerCabinProblemClaim
  busy: boolean
  onStart: (claim: CustomerCabinProblemClaim) => void
  onResolve: (claim: CustomerCabinProblemClaim) => void
}) {
  return (
    <Card size="sm" className="min-w-0">
      <CardHeader className="items-start gap-3 sm:flex-row">
        <div className="min-w-0 space-y-1">
          <CardTitle className="break-words">
            {claim.clientDisplayName}
          </CardTitle>
          <CardDescription>
            {CUSTOMER_CABIN_PROBLEM_CLIENT_TYPE_LABELS[claim.clientType]}
            {claim.clientPhone ? ` · ${claim.clientPhone}` : ""}
            {claim.orderContactPhone &&
            claim.orderContactPhone !== claim.clientPhone
              ? ` · Контакт доставки: ${claim.orderContactPhone}`
              : ""}
          </CardDescription>
        </div>
        <CardAction className="flex flex-wrap items-center justify-end gap-2">
          <Badge variant={statusVariant(claim.status)}>
            {CUSTOMER_CABIN_PROBLEM_STATUS_LABELS[claim.status]}
          </Badge>
          {claim.status === "OPEN" ? (
            <Button
              type="button"
              size="sm"
              disabled={busy}
              onClick={() => onStart(claim)}
            >
              Взять в работу
            </Button>
          ) : null}
          {claim.status === "IN_PROGRESS" ? (
            <Button
              type="button"
              size="sm"
              disabled={busy}
              onClick={() => onResolve(claim)}
            >
              Зафиксировать решение
            </Button>
          ) : null}
        </CardAction>
      </CardHeader>
      <CardContent className="grid min-w-0 gap-4">
        <p className="text-sm whitespace-pre-wrap">{claim.description}</p>
        <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
          <div className="min-w-0">
            <dt className="text-muted-foreground">Создана</dt>
            <dd className="mt-0.5 break-words">{dateTime(claim.reportedAt)}</dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Заказ</dt>
            <dd className="mt-0.5 break-words">{claim.orderNumber}</dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Категория</dt>
            <dd className="mt-0.5 break-words">
              {CUSTOMER_CABIN_PROBLEM_CATEGORY_LABELS[claim.category]}
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Когда обнаружено</dt>
            <dd className="mt-0.5 break-words">
              {CUSTOMER_CABIN_PROBLEM_PHASE_LABELS[claim.phase]}
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Срок решения</dt>
            <dd
              className={`mt-0.5 break-words ${deadlineTone(claim.resolutionDeadline, claim.status)}`}
            >
              {dateTime(claim.resolutionDeadline)}
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Адрес</dt>
            <dd className="mt-0.5 break-words">
              {claim.deliveryAddress ?? "Не указан"}
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-muted-foreground">Решение</dt>
            <dd className="mt-0.5 break-words">
              {claim.resolutionKind
                ? CUSTOMER_CABIN_PROBLEM_RESOLUTION_LABELS[claim.resolutionKind]
                : "Не принято"}
            </dd>
          </div>
        </dl>
        {claim.resolutionComment ? (
          <div className="rounded-lg bg-muted/60 p-3 text-sm">
            <p className="font-medium">Комментарий к решению</p>
            <p className="mt-1 whitespace-pre-wrap text-muted-foreground">
              {claim.resolutionComment}
            </p>
          </div>
        ) : null}
      </CardContent>
      <CardFooter className="block border-t pt-4">
        <p className="mb-3 text-sm font-medium">История претензии</p>
        <ClaimHistory claim={claim} />
      </CardFooter>
    </Card>
  )
}

function ClaimCardSkeleton() {
  return (
    <Card size="sm">
      <CardHeader>
        <Skeleton className="h-5 w-52" />
        <Skeleton className="h-4 w-40" />
      </CardHeader>
      <CardContent className="space-y-3">
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-4/5" />
      </CardContent>
    </Card>
  )
}

/**
 * Shared RWMS/manager view of the server-owned claim lifecycle. Customer commitments are not
 * edited here: a decision is accepted only through the version-fenced logistics command.
 */
export function ClaimsPage() {
  const { accessToken } = useAuth()
  const queryClient = useQueryClient()
  const [filter, setFilter] = useState<ClaimFilter>("ALL")
  const [resolutionTarget, setResolutionTarget] =
    useState<CustomerCabinProblemClaim | null>(null)
  const [resolutionKind, setResolutionKind] =
    useState<CustomerCabinProblemResolutionKind>("DISCOUNT")
  const [resolutionComment, setResolutionComment] = useState("")
  const [commandError, setCommandError] = useState<string | null>(null)

  const claims = useQuery({
    queryKey: [...CUSTOMER_CABIN_PROBLEMS_QUERY_KEY, filter],
    queryFn: () =>
      listCustomerCabinProblems(
        accessToken!,
        filter === "ALL" ? undefined : filter
      ),
    enabled: Boolean(accessToken),
  })

  const refreshClaims = async (message?: string) => {
    await queryClient.invalidateQueries({
      queryKey: CUSTOMER_CABIN_PROBLEMS_QUERY_KEY,
    })
    if (message) setCommandError(message)
  }

  const start = useMutation({
    mutationFn: (claim: CustomerCabinProblemClaim) =>
      startCustomerCabinProblem(accessToken!, claim.id, claim.version),
    onSuccess: () => refreshClaims(),
    onError: async (error) => {
      await refreshClaims(
        error instanceof ApiError && error.status === 409
          ? "Претензия уже изменилась. Данные обновлены — повторите действие при необходимости."
          : claimErrorMessage(error)
      )
    },
  })

  const resolve = useMutation({
    mutationFn: (claim: CustomerCabinProblemClaim) =>
      resolveCustomerCabinProblem(accessToken!, claim.id, {
        expectedVersion: claim.version,
        resolutionKind,
        resolutionComment: resolutionComment.trim(),
      }),
    onSuccess: async () => {
      setResolutionTarget(null)
      setResolutionComment("")
      await refreshClaims()
    },
    onError: async (error) => {
      await refreshClaims(
        error instanceof ApiError && error.status === 409
          ? "Претензия уже изменилась. Данные обновлены — повторите действие при необходимости."
          : claimErrorMessage(error)
      )
    },
  })

  const submitResolution = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!resolutionTarget || !resolutionComment.trim()) return
    resolve.mutate(resolutionTarget)
  }

  const busy = start.isPending || resolve.isPending

  return (
    <section className="flex min-h-0 min-w-0 flex-col gap-4 overflow-auto pb-4">
      <PageToolbar className="justify-end">
        <PageToolbarActions>
          <Select
            value={filter}
            onValueChange={(value) => setFilter(value as ClaimFilter)}
          >
            <SelectTrigger
              aria-label="Статус претензий"
              className="w-full sm:w-56"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {FILTERS.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </PageToolbarActions>
      </PageToolbar>

      {commandError ? (
        <Alert variant="destructive">
          <AlertTitle>Действие не выполнено</AlertTitle>
          <AlertDescription>{commandError}</AlertDescription>
        </Alert>
      ) : null}

      {claims.isError ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить претензии</AlertTitle>
          <AlertDescription>{claimErrorMessage(claims.error)}</AlertDescription>
        </Alert>
      ) : null}

      <div className="grid min-w-0 gap-4">
        {claims.isLoading ? (
          <>
            <ClaimCardSkeleton />
            <ClaimCardSkeleton />
          </>
        ) : null}
        {claims.data?.map((claim) => (
          <ClaimCard
            key={claim.id}
            claim={claim}
            busy={busy}
            onStart={(item) => {
              setCommandError(null)
              start.mutate(item)
            }}
            onResolve={(item) => {
              setCommandError(null)
              setResolutionTarget(item)
              setResolutionKind("DISCOUNT")
              setResolutionComment("")
            }}
          />
        ))}
        {claims.data?.length === 0 ? (
          <Card size="sm">
            <CardHeader>
              <CardTitle>Претензий нет</CardTitle>
              <CardDescription>
                В выбранном состоянии нет обращений, доступных вашему складу и
                RWMS.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : null}
      </div>

      <Dialog
        open={resolutionTarget !== null}
        onOpenChange={(open) => {
          if (!open && !resolve.isPending) setResolutionTarget(null)
        }}
      >
        <DialogContent>
          <form onSubmit={submitResolution}>
            <DialogHeader>
              <DialogTitle>Зафиксировать решение</DialogTitle>
              <DialogDescription>
                Решение будет сохранено в истории претензии. Связанный процесс
                скидки, замены или возврата не создаётся молча.
              </DialogDescription>
            </DialogHeader>
            <div className="mt-4 grid gap-3">
              <label className="grid gap-1.5 text-sm font-medium">
                Вариант решения
                <Select
                  value={resolutionKind}
                  onValueChange={(value) =>
                    setResolutionKind(
                      value as CustomerCabinProblemResolutionKind
                    )
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS.map((kind) => (
                      <SelectItem key={kind} value={kind}>
                        {CUSTOMER_CABIN_PROBLEM_RESOLUTION_LABELS[kind]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>
              <label className="grid gap-1.5 text-sm font-medium">
                Комментарий к решению
                <Textarea
                  value={resolutionComment}
                  onChange={(event) => setResolutionComment(event.target.value)}
                  maxLength={2000}
                  required
                  placeholder="Что согласовано с клиентом и какие следующие действия нужны"
                />
              </label>
            </div>
            <DialogFooter className="mt-5">
              <Button
                type="button"
                variant="outline"
                disabled={resolve.isPending}
                onClick={() => setResolutionTarget(null)}
              >
                Отмена
              </Button>
              <Button
                type="submit"
                disabled={resolve.isPending || !resolutionComment.trim()}
              >
                Сохранить решение
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </section>
  )
}
