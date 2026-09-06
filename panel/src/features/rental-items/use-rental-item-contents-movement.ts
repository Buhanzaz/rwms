import { useMemo, useRef, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  createEquipmentMovementTask,
  createEquipmentMovementTaskIdempotencyKey,
  type CreateEquipmentMovementTaskInput,
} from "@/features/logistics/api/equipment-movement-tasks-api"
import {
  invalidateRentalItemContentsQueries,
  type RentalItemContentsTransferRow,
} from "./rental-item-contents-transfer-support"
import { ApiError } from "@/lib/api-client"

type Draft = { quantity: number; selected: boolean }

/** Keeps local quantity choices bounded by the latest source balances. */
export function useRentalItemContentsDraft<
  TRow extends RentalItemContentsTransferRow,
>(sourceRows: readonly TRow[], onChange: () => void) {
  const [draft, setDraft] = useState<Record<string, Draft>>({})
  const rows = useMemo(
    () =>
      sourceRows.map((row) => ({
        ...row,
        quantity: Math.min(
          row.availableQuantity,
          draft[row.equipmentId]?.quantity ?? row.availableQuantity
        ),
        selected: draft[row.equipmentId]?.selected ?? false,
      })),
    [draft, sourceRows]
  )
  const selectedRows = rows.filter((row) => row.selected && row.quantity > 0)
  const allSelected = rows.length > 0 && rows.every((row) => row.selected)
  function toggleRow(equipmentId: string, selected: boolean) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
    onChange()
    setDraft((current) => ({
      ...current,
      [equipmentId]: {
        selected,
        quantity:
          selected && row.quantity === 0 ? row.availableQuantity : row.quantity,
      },
    }))
  }

  function changeQuantity(equipmentId: string, delta: number) {
    const row = rows.find((candidate) => candidate.equipmentId === equipmentId)
    if (!row) return
    const quantity = Math.min(
      row.availableQuantity,
      Math.max(0, row.quantity + delta)
    )
    onChange()
    setDraft((current) => ({
      ...current,
      [equipmentId]: { quantity, selected: quantity > 0 },
    }))
  }

  function toggleAll() {
    const selected = !allSelected
    onChange()
    setDraft((current) => ({
      ...current,
      ...Object.fromEntries(
        rows.map((row) => [
          row.equipmentId,
          {
            selected,
            quantity:
              selected && row.quantity === 0
                ? row.availableQuantity
                : row.quantity,
          },
        ])
      ),
    }))
  }

  return {
    rows,
    selectedRows,
    allSelected,
    toggleRow,
    changeQuantity,
    toggleAll,
  }
}

/** Sends one prepared owner command, retaining its retry identity and conflict feedback. */
export function useContentsMovementMutation({
  accessToken,
  createInput,
  onClose,
  onErrorText,
}: {
  accessToken: string | null
  createInput: () => CreateEquipmentMovementTaskInput
  onClose: () => void
  onErrorText: (message: string) => void
}) {
  const queryClient = useQueryClient()
  const idempotencyKeys = useRef(new Map<string, string>())
  function taskIdempotencyKey(input: CreateEquipmentMovementTaskInput) {
    const signature = JSON.stringify(input)
    let key = idempotencyKeys.current.get(signature)
    if (!key) {
      key = createEquipmentMovementTaskIdempotencyKey()
      idempotencyKeys.current.set(signature, key)
    }
    return key
  }

  const mutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      const input = createInput()
      return createEquipmentMovementTask({
        accessToken,
        idempotencyKey: taskIdempotencyKey(input),
        input,
      })
    },
    onSuccess: (task) => {
      void invalidateRentalItemContentsQueries(queryClient)
      toast.success(taskSuccessMessage(task.deadlineAt))
      onClose()
    },
    onError: (error) => {
      onErrorText(
        error instanceof Error ? error.message : "Не удалось создать задание."
      )
      if (error instanceof ApiError && error.status === 409) {
        void invalidateRentalItemContentsQueries(queryClient)
      }
    },
  })
  return { mutation }
}

function taskSuccessMessage(deadlineAt: string) {
  return `Задание создано. Мебель зарезервирована до ${new Intl.DateTimeFormat(
    "ru-RU",
    { dateStyle: "short", timeStyle: "short" }
  ).format(new Date(deadlineAt))}; остатки изменятся после выполнения.`
}
