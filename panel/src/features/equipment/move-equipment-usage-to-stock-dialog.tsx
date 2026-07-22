import { useRef, useState } from "react"
import { WarehouseIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  createEquipmentUsageMoveIdempotencyKey,
  moveEquipmentUsageToStock,
  type MoveEquipmentUsageToStockInput,
} from "@/features/equipment/api/equipment-usage-movements-api"
import { invalidateRentalItemContentsQueries } from "@/features/rental-items/rental-item-contents-transfer-support"
import { ApiError } from "@/lib/api-client"
import type {
  EquipmentItemDto,
  EquipmentRentalUsageDto,
} from "@/types/equipment"

const quantityFormatter = new Intl.NumberFormat("ru-RU")

export function MoveEquipmentUsageToStockDialog({
  accessToken,
  equipment,
  usage,
  onClose,
}: {
  accessToken: string
  equipment: EquipmentItemDto
  usage: EquipmentRentalUsageDto
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [quantity, setQuantity] = useState(String(usage.availableQuantity))
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKeys = useRef(new Map<string, string>())
  const stockBalance = equipment.balances.find(
    (balance) =>
      balance.rentalItemId === null && balance.locationKind === "STOCK"
  )
  const requestedQuantity = Number(quantity)
  const validQuantity =
    Number.isSafeInteger(requestedQuantity) &&
    requestedQuantity >= 1 &&
    requestedQuantity <= usage.availableQuantity

  function idempotencyKey(input: MoveEquipmentUsageToStockInput) {
    const signature = JSON.stringify(input)
    const existing = idempotencyKeys.current.get(signature)
    if (existing) return existing

    const key = createEquipmentUsageMoveIdempotencyKey()
    idempotencyKeys.current.set(signature, key)
    return key
  }

  const mutation = useMutation({
    mutationFn: () => {
      if (!validQuantity) {
        throw new Error(
          `Укажите целое количество от 1 до ${quantityFormatter.format(usage.availableQuantity)}.`
        )
      }

      const input: MoveEquipmentUsageToStockInput = {
        equipmentId: equipment.id,
        warehouseId: usage.warehouseId,
        rentalItemId: usage.rentalItemId,
        sourceLocationKind: usage.locationKind,
        sourceExpectedVersion: usage.balanceVersion,
        targetExpectedVersion: stockBalance?.version ?? 0,
        quantity: requestedQuantity,
      }

      return moveEquipmentUsageToStock({
        accessToken,
        idempotencyKey: idempotencyKey(input),
        input,
      })
    },
    onSuccess: async (movement) => {
      await invalidateRentalItemContentsQueries(queryClient)
      toast.success(
        `На склад перемещено ${quantityFormatter.format(movement.quantity)} шт. «${equipment.name}».`
      )
      onClose()
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void invalidateRentalItemContentsQueries(queryClient)
        toast.error(
          "Остатки уже изменились. Список обновлён — откройте перемещение ещё раз."
        )
        onClose()
        return
      }

      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось переместить оборудование на склад."
      )
    },
  })

  function submit() {
    setErrorText(null)
    if (!validQuantity) {
      setErrorText(
        `Укажите целое количество от 1 до ${quantityFormatter.format(usage.availableQuantity)}.`
      )
      return
    }

    mutation.mutate()
  }

  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !mutation.isPending) {
          onClose()
        }
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Переместить на склад?</AlertDialogTitle>
          <AlertDialogDescription>
            Оборудование «{equipment.name}» из бытовки {usage.rentalItemNumber}{" "}
            сразу будет отражено в складском остатке.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <FieldGroup className="gap-4">
          <Field data-invalid={Boolean(errorText) || !validQuantity}>
            <FieldLabel htmlFor="equipment-usage-move-quantity">
              Количество
            </FieldLabel>
            <Input
              id="equipment-usage-move-quantity"
              type="number"
              min={1}
              max={usage.availableQuantity}
              step={1}
              value={quantity}
              aria-invalid={Boolean(errorText) || !validQuantity}
              onChange={(event) => {
                setQuantity(event.target.value)
                setErrorText(null)
              }}
            />
            <FieldDescription>
              В бытовке: {quantityFormatter.format(usage.quantity)} шт.;
              доступно к перемещению:{" "}
              {quantityFormatter.format(usage.availableQuantity)} шт.
            </FieldDescription>
          </Field>
        </FieldGroup>
        {errorText ? <FieldError>{errorText}</FieldError> : null}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={mutation.isPending}>
            Отмена
          </AlertDialogCancel>
          <AlertDialogAction
            disabled={!validQuantity || mutation.isPending}
            onClick={(event) => {
              event.preventDefault()
              submit()
            }}
          >
            <HugeiconsIcon icon={WarehouseIcon} data-icon="inline-start" />
            {mutation.isPending ? "Перемещение…" : "Переместить на склад"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
