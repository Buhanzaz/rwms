import { useMemo, useState, type FormEvent } from "react"
import { useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
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
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import {
  buildCabinContentsPlan,
  cabinContentsSnapshotMatches,
  cabinContentsQuantitiesAreValid,
  cabinDispositionContents,
} from "@/features/write-offs/cabin-contents-disposition"
import { CabinContentsDispositionFields } from "@/features/write-offs/cabin-contents-disposition-fields"
import type {
  CabinContentsDispositionMode,
  CabinContentsDispositionPlanInput,
} from "@/features/write-offs/property-dispositions-api"
import { createPropertyDispositionIdempotencyKey } from "@/features/write-offs/property-dispositions-api"

export type RepairTaskWriteOffDecision = {
  reason: string
  contentsPlan: CabinContentsDispositionPlanInput | null
  idempotencyKey: string
}

type RepairTaskWriteOffDialogProps = {
  accessToken: string | null
  warehouseId: string
  rentalItemId: string
  open: boolean
  pending: boolean
  error?: string | null
  onOpenChange: (open: boolean) => void
  onConfirm: (decision: RepairTaskWriteOffDecision) => void
}

export function RepairTaskWriteOffDialog({
  accessToken,
  warehouseId,
  rentalItemId,
  open,
  pending,
  error,
  onOpenChange,
  onConfirm,
}: RepairTaskWriteOffDialogProps) {
  const [reason, setReason] = useState("")
  const [mode, setMode] = useState<CabinContentsDispositionMode>(
    "MOVE_SELECTED_TO_STOCK"
  )
  const [quantities, setQuantities] = useState<Record<string, number>>({})
  const [submitted, setSubmitted] = useState(false)
  const [attempt, setAttempt] = useState<{
    signature: string
    idempotencyKey: string
  } | null>(null)

  const cabinQuery = useQuery({
    queryKey: ["rental-item", rentalItemId, "disposition-source"],
    queryFn: () => getAssetRentalItem(accessToken, rentalItemId),
    enabled: open && Boolean(accessToken && rentalItemId),
  })
  const equipmentQuery = useQuery({
    queryKey: [
      "equipment-items",
      warehouseId,
      "disposition-source",
      rentalItemId,
    ],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled: open && Boolean(accessToken && rentalItemId),
  })
  const contents = useMemo(
    () => cabinDispositionContents(rentalItemId, equipmentQuery.data ?? []),
    [equipmentQuery.data, rentalItemId]
  )
  const snapshotComplete =
    cabinQuery.data !== undefined &&
    cabinContentsSnapshotMatches(cabinQuery.data.contentsItems, contents)
  const contentsValid = cabinContentsQuantitiesAreValid(contents, quantities)
  const loading = cabinQuery.isLoading || equipmentQuery.isLoading

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    const normalizedReason = reason.trim()
    if (!normalizedReason || loading || !snapshotComplete || !contentsValid)
      return
    const contentsPlan = buildCabinContentsPlan(mode, contents, quantities)
    const signature = JSON.stringify({ reason: normalizedReason, contentsPlan })
    const idempotencyKey =
      attempt?.signature === signature
        ? attempt.idempotencyKey
        : createPropertyDispositionIdempotencyKey()
    if (attempt?.signature !== signature)
      setAttempt({ signature, idempotencyKey })
    onConfirm({
      reason: normalizedReason,
      contentsPlan,
      idempotencyKey,
    })
  }

  function reset(nextOpen: boolean) {
    if (pending) return
    if (!nextOpen) {
      setReason("")
      setMode("MOVE_SELECTED_TO_STOCK")
      setQuantities({})
      setSubmitted(false)
      setAttempt(null)
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <form className="contents" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать решение о списании бытовки</DialogTitle>
            <DialogDescription>
              Решение попадёт администратору. Терминальный статус появится
              только после одобрения и подтверждённого серверного эффекта.
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            {!loading && snapshotComplete ? (
              <CabinContentsDispositionFields
                contents={contents}
                mode={mode}
                quantities={quantities}
                disabled={pending}
                error={
                  submitted && !contentsValid
                    ? "Исправьте количество наполнения."
                    : null
                }
                onModeChange={(nextMode) => {
                  setMode(nextMode)
                  setAttempt(null)
                }}
                onQuantityChange={(equipmentId, quantity) => {
                  setQuantities((current) => ({
                    ...current,
                    [equipmentId]: quantity,
                  }))
                  setAttempt(null)
                }}
              />
            ) : null}

            <Field data-invalid={submitted && !reason.trim()}>
              <FieldLabel htmlFor="repair-write-off-reason">
                Причина списания
              </FieldLabel>
              <Textarea
                id="repair-write-off-reason"
                value={reason}
                maxLength={2000}
                disabled={pending}
                aria-invalid={submitted && !reason.trim()}
                placeholder="Укажите обязательную причину"
                onChange={(event) => {
                  setReason(event.target.value)
                  setAttempt(null)
                }}
              />
              {submitted && !reason.trim() ? (
                <FieldError>Укажите причину списания.</FieldError>
              ) : null}
            </Field>

            {cabinQuery.isError || equipmentQuery.isError ? (
              <FieldError>
                Не удалось загрузить актуальное наполнение бытовки. Решение не
                отправлено.
              </FieldError>
            ) : null}
            {!loading && cabinQuery.data && !snapshotComplete ? (
              <FieldError>
                Версии наполнения не совпали с паспортом бытовки. Обновите
                данные и повторите.
              </FieldError>
            ) : null}
            {error ? <FieldError>{error}</FieldError> : null}
          </FieldGroup>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => reset(false)}
            >
              Отмена
            </Button>
            <Button
              type="submit"
              disabled={pending || loading || !snapshotComplete}
            >
              {pending ? "Отправка..." : "Отправить администратору"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
