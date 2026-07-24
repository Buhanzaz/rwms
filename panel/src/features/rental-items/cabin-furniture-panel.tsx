import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"
import { FieldDescription, FieldError, FieldSet } from "@/components/ui/field"
import {
  createCabinFurnitureTask,
  type CabinFurnitureTaskRequirement,
} from "@/features/logistics/cabin-furniture-tasks-api"
import {
  CabinFurnitureCompositionDialog,
  CabinFurnitureContents,
  furnitureEquipmentIds,
  type CabinFurnitureRequirementInput,
} from "@/features/rental-items/cabin-furniture-composition-dialog"
import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

function localCalendarDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, "0")
  const day = String(now.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function message(cause: unknown) {
  return cause instanceof Error
    ? cause.message
    : "Не удалось создать задание на изменение наполнения."
}

function currentContents(
  contents: Array<{ equipmentId?: string; quantity: number }>,
  furnitureIds?: ReadonlySet<string>
): CabinFurnitureRequirementInput[] {
  const values = new Map<string, number>()
  for (const item of contents) {
    if (
      item.equipmentId &&
      item.quantity > 0 &&
      (!furnitureIds || furnitureIds.has(item.equipmentId))
    ) {
      values.set(
        item.equipmentId,
        (values.get(item.equipmentId) ?? 0) + item.quantity
      )
    }
  }
  return [...values.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([equipmentId, quantity]) => ({ equipmentId, quantity }))
}

/**
 * Repair work reuses the logistics-owned task command. The repair form owns no
 * equipment list or movement calculation; it only displays the selected cabin
 * and lets an operator request its final composition.
 */
export function CabinFurniturePanel({
  accessToken,
  warehouseId,
  rentalItemId,
  disabled = false,
}: {
  accessToken: string | null
  warehouseId: string
  rentalItemId: string
  disabled?: boolean
}) {
  const queryClient = useQueryClient()
  const attempt = useRef<CommandAttempt | null>(null)
  const [open, setOpen] = useState(false)
  const [scheduledDate, setScheduledDate] = useState(localCalendarDate)
  const [notice, setNotice] = useState<string | null>(null)
  const cabinQuery = useQuery({
    queryKey: ["rental-item", rentalItemId],
    queryFn: () => getAssetRentalItem(accessToken, rentalItemId),
    enabled: Boolean(accessToken && rentalItemId),
  })
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "cabin-furniture", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled: Boolean(accessToken && warehouseId),
  })
  const mutation = useMutation({
    mutationFn: ({
      idempotencyKey,
      contents,
    }: {
      idempotencyKey: string
      contents: CabinFurnitureTaskRequirement[]
    }) =>
      createCabinFurnitureTask({
        accessToken: accessToken!,
        warehouseId,
        rentalItemId,
        scheduledDate,
        contents,
        idempotencyKey,
      }),
    onSuccess: (result) => {
      setNotice(
        result.taskId
          ? "Задание на изменение наполнения создано."
          : "Наполнение уже соответствует выбранному составу."
      )
      setOpen(false)
      void queryClient.invalidateQueries({
        queryKey: ["rental-item", rentalItemId],
      })
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      void queryClient.invalidateQueries({ queryKey: ["equipment"] })
    },
  })

  if (!rentalItemId) {
    return null
  }

  if (cabinQuery.isError) {
    return (
      <FieldError>
        Не удалось загрузить наполнение выбранной бытовки.
      </FieldError>
    )
  }

  if (!cabinQuery.data) {
    return (
      <FieldSet>
        <FieldDescription>Загружаем наполнение бытовки…</FieldDescription>
      </FieldSet>
    )
  }

  const cabin = cabinQuery.data
  const furnitureIds = furnitureEquipmentIds(equipmentQuery.data)
  const contents = currentContents(cabin.contentsItems, furnitureIds)
  const dialogError = mutation.error ? message(mutation.error) : null

  return (
    <>
      <CabinFurnitureContents
        cabin={cabin}
        disabled={
          disabled ||
          mutation.isPending ||
          equipmentQuery.isFetching ||
          equipmentQuery.isError
        }
        furnitureIds={furnitureIds}
        onManage={() => {
          setNotice(null)
          setOpen(true)
        }}
      />
      {equipmentQuery.isError ? (
        <FieldError>
          Не удалось загрузить дополнительное оборудование.
        </FieldError>
      ) : null}
      {notice ? <FieldDescription>{notice}</FieldDescription> : null}
      {open ? (
        <CabinFurnitureCompositionDialog
          cabin={cabin}
          equipmentItems={equipmentQuery.data ?? []}
          initialContents={contents}
          open
          pending={mutation.isPending}
          error={dialogError}
          schedule={{ value: scheduledDate, onChange: setScheduledDate }}
          onOpenChange={setOpen}
          onSave={(nextContents) => {
            const normalized = [...nextContents].sort((left, right) =>
              left.equipmentId.localeCompare(right.equipmentId)
            )
            const signature = JSON.stringify({
              warehouseId,
              rentalItemId,
              scheduledDate,
              contents: normalized,
            })
            const idempotencyKey =
              attempt.current?.signature === signature
                ? attempt.current.idempotencyKey
                : crypto.randomUUID()
            attempt.current = { signature, idempotencyKey }
            mutation.mutate({ idempotencyKey, contents: normalized })
          }}
        />
      ) : null}
    </>
  )
}
