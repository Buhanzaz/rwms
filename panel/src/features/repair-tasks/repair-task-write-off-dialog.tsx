import { useState, type FormEvent } from "react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Textarea } from "@/components/ui/textarea"

type RepairTaskWriteOffDialogProps = {
  open: boolean
  pending: boolean
  error?: string | null
  onOpenChange: (open: boolean) => void
  onConfirm: (reason: string) => void
}

export function RepairTaskWriteOffDialog({
  open,
  pending,
  error,
  onOpenChange,
  onConfirm,
}: RepairTaskWriteOffDialogProps) {
  const [reason, setReason] = useState("")
  const [reasonError, setReasonError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedReason = reason.trim()
    if (!normalizedReason) {
      setReasonError("Укажите причину списания")
      return
    }
    setReasonError(null)
    onConfirm(normalizedReason)
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (pending) return
        if (!nextOpen) {
          setReason("")
          setReasonError(null)
        }
        onOpenChange(nextOpen)
      }}
    >
      <DialogContent>
        <form className="contents" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Списать бытовку</DialogTitle>
            <DialogDescription>
              Бытовка получит терминальный статус и появится в разделе
              «Списание».
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            <Field data-invalid={Boolean(reasonError)}>
              <FieldLabel htmlFor="repair-write-off-reason">
                Причина списания
              </FieldLabel>
              <Textarea
                id="repair-write-off-reason"
                value={reason}
                disabled={pending}
                aria-invalid={Boolean(reasonError)}
                placeholder="Укажите обязательную причину"
                onChange={(event) => {
                  setReason(event.target.value)
                  if (reasonError) setReasonError(null)
                }}
              />
              <FieldError>{reasonError}</FieldError>
            </Field>
          </FieldGroup>

          {error ? <FieldError>{error}</FieldError> : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => {
                setReason("")
                setReasonError(null)
                onOpenChange(false)
              }}
            >
              Отмена
            </Button>
            <Button type="submit" variant="destructive" disabled={pending}>
              {pending ? "Списание..." : "Списать"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
