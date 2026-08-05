import { useState, type FormEvent } from "react"
import { useMutation } from "@tanstack/react-query"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
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
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api-client"

import {
  approvePropertyDisposition,
  recoverPropertyDisposition,
  rejectPropertyDisposition,
  type PropertyDispositionDecision,
} from "./property-dispositions-api"

export type PropertyDispositionReviewAction = "APPROVE" | "REJECT" | "RECOVER"

const ACTION_COPY: Record<
  PropertyDispositionReviewAction,
  { title: string; description: string; submit: string }
> = {
  APPROVE: {
    title: "Принять решение",
    description:
      "Одобрение запускает серверный процесс. Имущество считается списанным или утерянным только после статуса «Применён».",
    submit: "Принять",
  },
  REJECT: {
    title: "Отклонить решение",
    description: "Укажите причину отклонения для неизменяемой истории решения.",
    submit: "Отклонить",
  },
  RECOVER: {
    title: "Восстановить обработку",
    description:
      "Команда повторно запускает остановленный серверный эффект с текущими версиями решения.",
    submit: "Повторить обработку",
  },
}

export function PropertyDispositionReviewDialog({
  accessToken,
  decision,
  action,
  open,
  onOpenChange,
  onSaved,
  onConflict,
}: {
  accessToken: string | null
  decision: PropertyDispositionDecision | null
  action: PropertyDispositionReviewAction
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: (decision: PropertyDispositionDecision) => void
  onConflict: () => void
}) {
  const [text, setText] = useState("")
  const [submitted, setSubmitted] = useState(false)
  const copy = ACTION_COPY[action]
  const required = action !== "APPROVE"

  const mutation = useMutation({
    mutationFn: () => {
      if (!decision) throw new Error("Решение не выбрано.")
      const normalized = text.trim()
      if (required && !normalized) throw new Error("Укажите причину.")
      if (action === "APPROVE") {
        return approvePropertyDisposition({
          accessToken,
          warehouseId: decision.warehouseId,
          decisionId: decision.id,
          expectedVersion: decision.version,
          comment: normalized || null,
        })
      }
      if (action === "REJECT") {
        return rejectPropertyDisposition({
          accessToken,
          warehouseId: decision.warehouseId,
          decisionId: decision.id,
          expectedVersion: decision.version,
          reason: normalized,
        })
      }
      return recoverPropertyDisposition({
        accessToken,
        warehouseId: decision.warehouseId,
        decisionId: decision.id,
        expectedVersion: decision.version,
        expectedRecoveryVersion: decision.recoveryVersion,
        reason: normalized,
      })
    },
    onSuccess: (updated) => {
      toast.success(
        action === "APPROVE"
          ? "Решение принято, серверный эффект запущен."
          : "Решение обновлено."
      )
      onSaved(updated)
      onOpenChange(false)
    },
    onError: (unknownError) => {
      if (unknownError instanceof ApiError && unknownError.status === 409) {
        toast.error(
          "Решение уже изменено. Список обновлён — откройте его заново."
        )
        onConflict()
      }
    },
  })

  function reset(nextOpen: boolean) {
    if (mutation.isPending) return
    if (nextOpen) {
      setText("")
      setSubmitted(false)
      mutation.reset()
    }
    onOpenChange(nextOpen)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    if (!required || text.trim()) mutation.mutate()
  }

  return (
    <Dialog open={open && decision !== null} onOpenChange={reset}>
      <DialogContent>
        <form className="contents" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{copy.title}</DialogTitle>
            <DialogDescription>{copy.description}</DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field data-invalid={submitted && required && !text.trim()}>
              <FieldLabel htmlFor="property-disposition-review-text">
                {action === "APPROVE"
                  ? "Комментарий (необязательно)"
                  : "Причина"}
              </FieldLabel>
              <Textarea
                id="property-disposition-review-text"
                value={text}
                maxLength={2000}
                disabled={mutation.isPending}
                aria-invalid={submitted && required && !text.trim()}
                onChange={(event) => setText(event.target.value)}
              />
              {submitted && required && !text.trim() ? (
                <FieldError>Укажите причину.</FieldError>
              ) : null}
            </Field>
            {mutation.isError ? (
              <FieldError>
                {mutation.error instanceof ApiError &&
                mutation.error.status === 409
                  ? "Решение уже изменено. Список обновлён — повторите действие с актуальной версией."
                  : mutation.error instanceof Error
                    ? mutation.error.message
                    : "Не удалось обновить решение."}
              </FieldError>
            ) : null}
          </FieldGroup>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={mutation.isPending}
              onClick={() => reset(false)}
            >
              Отмена
            </Button>
            <Button
              type="submit"
              variant={action === "REJECT" ? "destructive" : "default"}
              disabled={mutation.isPending}
            >
              {mutation.isPending ? "Выполнение..." : copy.submit}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
