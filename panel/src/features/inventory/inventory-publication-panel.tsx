import { useState } from "react"
import { useMutation } from "@tanstack/react-query"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
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
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import {
  closeBlockedFindingPublication,
  retryFindingPublication,
} from "@/features/inventory/adapters/http-inventory-adapter"
import type {
  InventoryFinding,
  InventoryPublicationIntent,
} from "@/features/inventory/model/inventory-service"
import { useAuth } from "@/features/auth/use-auth"

const SHA256_PATTERN = /^[0-9a-f]{64}$/

const publicationLabel = {
  NOT_REQUIRED: "Не требуется",
  READY: "Готова к передаче",
  PENDING: "Передаётся",
  SUCCEEDED: "Передана",
  TRANSIENT_FAILED: "Временная ошибка",
  BLOCKED: "Заблокирована",
  CLOSED_BLOCKED: "Закрыта оператором",
} as const

type PublicationAction = {
  kind: "RECONCILE" | "CLOSE"
  finding: InventoryFinding
  publication: InventoryPublicationIntent
  idempotencyKey: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Операция не выполнена"
}

function TransientRetryButton({
  inventoryId,
  finding,
  onChanged,
}: {
  inventoryId: string
  finding: InventoryFinding
  onChanged: () => Promise<void>
}) {
  const { accessToken } = useAuth()
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    crypto.randomUUID()
  )
  const publication = finding.publication!
  const mutation = useMutation({
    mutationFn: () =>
      retryFindingPublication({
        accessToken,
        inventoryId,
        findingId: finding.id,
        expectedPublicationRevision: publication.publicationRevision,
        reconcileReason: null,
        currentPreconditionSha256: null,
        idempotencyKey,
      }),
    onSuccess: async () => {
      setIdempotencyKey(crypto.randomUUID())
      await onChanged()
      toast.success(
        `Повторная передача ${finding.displayCanonicalNumber} выполнена`
      )
    },
  })
  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        variant="outline"
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? "Повторяем..." : "Повторить передачу"}
      </Button>
      {mutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(mutation.error)}
        </p>
      ) : null}
    </div>
  )
}

export function InventoryPublicationPanel({
  inventoryId,
  findings,
  onChanged,
}: {
  inventoryId: string
  findings: InventoryFinding[]
  onChanged: () => Promise<void>
}) {
  const { accessToken } = useAuth()
  const publications = findings.filter(
    (
      finding
    ): finding is InventoryFinding & {
      publication: InventoryPublicationIntent
    } => finding.publication !== null
  )
  const [action, setAction] = useState<PublicationAction | null>(null)
  const [reason, setReason] = useState("")
  const [precondition, setPrecondition] = useState("")
  const mutation = useMutation({
    mutationFn: () => {
      if (!action) throw new Error("Действие публикации не выбрано")
      if (action.kind === "RECONCILE") {
        return retryFindingPublication({
          accessToken,
          inventoryId,
          findingId: action.finding.id,
          expectedPublicationRevision: action.publication.publicationRevision,
          reconcileReason: reason.trim(),
          currentPreconditionSha256: precondition,
          idempotencyKey: action.idempotencyKey,
        })
      }
      return closeBlockedFindingPublication({
        accessToken,
        inventoryId,
        findingId: action.finding.id,
        expectedPublicationRevision: action.publication.publicationRevision,
        reason: reason.trim(),
        idempotencyKey: action.idempotencyKey,
      })
    },
    onSuccess: async () => {
      await onChanged()
      toast.success(
        action?.kind === "RECONCILE"
          ? "Публикация повторена со сверкой"
          : "Заблокированная публикация закрыта"
      )
      setAction(null)
      setReason("")
      setPrecondition("")
    },
  })

  function openAction(
    kind: PublicationAction["kind"],
    finding: InventoryFinding & { publication: InventoryPublicationIntent }
  ) {
    setReason("")
    setPrecondition("")
    mutation.reset()
    setAction({
      kind,
      finding,
      publication: finding.publication,
      idempotencyKey: crypto.randomUUID(),
    })
  }

  if (publications.length === 0) return null
  const actionInvalid =
    !reason.trim() ||
    (action?.kind === "RECONCILE" && !SHA256_PATTERN.test(precondition))

  return (
    <section className="flex flex-col gap-3" aria-label="Публикация работ">
      <h2 className="text-lg font-semibold">Публикация работ</h2>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {publications.map((finding) => {
          const publication = finding.publication
          return (
            <Card key={finding.id} size="sm">
              <CardHeader>
                <CardTitle>{finding.displayCanonicalNumber}</CardTitle>
                <CardDescription>
                  Попыток: {publication.attemptCount}; ревизия:{" "}
                  {publication.publicationRevision}
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                <Badge variant="secondary">
                  {publicationLabel[publication.state]}
                </Badge>
                {publication.failureCode ? (
                  <p className="text-sm text-destructive">
                    {publication.failureCode}
                  </p>
                ) : null}
                {publication.maintenanceRepairId ? (
                  <p className="text-xs text-muted-foreground">
                    Ремонт: {publication.maintenanceRepairId}
                  </p>
                ) : null}
              </CardContent>
              {publication.state === "TRANSIENT_FAILED" ? (
                <CardFooter>
                  <TransientRetryButton
                    inventoryId={inventoryId}
                    finding={finding}
                    onChanged={onChanged}
                  />
                </CardFooter>
              ) : publication.state === "BLOCKED" ? (
                <CardFooter className="flex flex-wrap gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => openAction("RECONCILE", finding)}
                  >
                    Сверить и повторить
                  </Button>
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={() => openAction("CLOSE", finding)}
                  >
                    Закрыть
                  </Button>
                </CardFooter>
              ) : null}
            </Card>
          )
        })}
      </div>
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !mutation.isPending) setAction(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {action?.kind === "RECONCILE"
                ? "Сверить и повторить публикацию"
                : "Закрыть заблокированную публикацию"}
            </DialogTitle>
            <DialogDescription>
              {action?.finding.displayCanonicalNumber}. Решение будет записано с
              текущей ревизией публикации.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field data-invalid={Boolean(action && !reason.trim())}>
              <FieldLabel htmlFor="inventory-publication-reason">
                Обоснование
              </FieldLabel>
              <Textarea
                id="inventory-publication-reason"
                value={reason}
                maxLength={2000}
                aria-invalid={Boolean(action && !reason.trim())}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            {action?.kind === "RECONCILE" ? (
              <Field
                data-invalid={Boolean(
                  precondition && !SHA256_PATTERN.test(precondition)
                )}
              >
                <FieldLabel htmlFor="inventory-publication-precondition">
                  SHA-256 текущей предпосылки
                </FieldLabel>
                <Input
                  id="inventory-publication-precondition"
                  value={precondition}
                  maxLength={64}
                  spellCheck={false}
                  aria-invalid={Boolean(
                    precondition && !SHA256_PATTERN.test(precondition)
                  )}
                  onChange={(event) =>
                    setPrecondition(event.target.value.trim().toLowerCase())
                  }
                />
                <FieldDescription>
                  64 символа lowercase hexadecimal из повторной сверки
                  источника.
                </FieldDescription>
              </Field>
            ) : null}
          </FieldGroup>
          {mutation.error ? (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage(mutation.error)}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => setAction(null)}
            >
              Отмена
            </Button>
            <Button
              type="button"
              variant={action?.kind === "CLOSE" ? "destructive" : "default"}
              disabled={mutation.isPending || actionInvalid}
              onClick={() => mutation.mutate()}
            >
              {mutation.isPending ? "Сохраняем..." : "Подтвердить"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  )
}
