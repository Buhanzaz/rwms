import { useEffect, useState, type FormEvent } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  AssetRentalItemConflictError,
  getRentalItemCreationOptions,
  rentalItemCreationOptionsQueryKey,
  updateAssetRentalItemPassport,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  RentalItemCompositionFields,
  type RentalItemCompositionFormValue,
} from "@/features/rental-items/rental-item-composition-fields"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { FieldError } from "@/components/ui/field"
import { useAuth } from "@/features/auth/use-auth"

function compositionFromRentalItem(
  rentalItem: RentalItemDto
): RentalItemCompositionFormValue {
  return {
    rentalTypeId: rentalItem.rentalTypeId,
    dimensionId: rentalItem.dimensionId,
    finishingId: rentalItem.finishingId,
    category: rentalItem.category ?? "",
    characteristicIds: rentalItem.characteristics.map(
      (characteristic) => characteristic.id
    ),
    linoleum:
      rentalItem.linoleum === null
        ? ""
        : rentalItem.linoleum
          ? "yes"
          : "no",
  }
}

export function RentalItemPassportDialog({
  open,
  rentalItem,
  onOpenChange,
  onSaved,
}: {
  open: boolean
  rentalItem: RentalItemDto
  onOpenChange: (open: boolean) => void
  onSaved: (rentalItem: RentalItemDto) => void
}) {
  const { accessToken } = useAuth()
  const [value, setValue] = useState<RentalItemCompositionFormValue>(() =>
    compositionFromRentalItem(rentalItem)
  )
  const [submitted, setSubmitted] = useState(false)
  const optionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(rentalItem.warehouseId),
    queryFn: () => getRentalItemCreationOptions(accessToken, rentalItem.warehouseId),
    enabled: Boolean(open && accessToken),
  })

  useEffect(() => {
    if (open) {
      setValue(compositionFromRentalItem(rentalItem))
      setSubmitted(false)
    }
  }, [open, rentalItem])

  const saveMutation = useMutation({
    mutationFn: () => {
      if (
        !value.rentalTypeId ||
        !value.dimensionId ||
        !value.finishingId ||
        !value.category.trim() ||
        !value.linoleum
      ) {
        throw new Error("Заполните состав бытовки.")
      }

      return updateAssetRentalItemPassport({
        accessToken,
        input: {
          id: rentalItem.id,
          expectedVersion: rentalItem.version,
          rentalTypeId: value.rentalTypeId,
          dimensionId: value.dimensionId,
          finishingId: value.finishingId,
          category: value.category,
          characteristicIds: value.characteristicIds,
          linoleum: value.linoleum === "yes",
          passport: rentalItem.passport,
          tags: rentalItem.tags,
        },
      })
    },
    onSuccess: (saved) => {
      onSaved(saved)
      onOpenChange(false)
      toast.success("Паспорт бытовки сохранён")
    },
    onError: (error) => {
      toast.error(
        error instanceof AssetRentalItemConflictError
          ? "Бытовка была изменена другим пользователем. Обновите данные и повторите действие."
          : error instanceof Error
            ? error.message
            : "Не удалось сохранить паспорт бытовки"
      )
    },
  })

  const optionsError = optionsQuery.error
    ? optionsQuery.error instanceof Error
      ? optionsQuery.error.message
      : "Не удалось загрузить настройки бытовок."
    : null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    if (
      value.rentalTypeId &&
      value.dimensionId &&
      value.finishingId &&
      value.category.trim() &&
      value.linoleum
    ) {
      saveMutation.mutate()
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!saveMutation.isPending) onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Паспорт бытовки</DialogTitle>
          <DialogDescription>
            Изменения состава сохраняются в asset-service.
          </DialogDescription>
        </DialogHeader>

        {!optionsQuery.data ? (
          <div className="flex flex-col gap-4">
            {optionsError ? (
              <FieldError role="alert">{optionsError}</FieldError>
            ) : (
              <p className="text-sm text-muted-foreground">
                Загружаем настройки бытовок…
              </p>
            )}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
              >
                Отмена
              </Button>
              {optionsError ? (
                <Button
                  type="button"
                  onClick={() => void optionsQuery.refetch()}
                >
                  Повторить
                </Button>
              ) : null}
            </DialogFooter>
          </div>
        ) : (
          <form className="flex flex-col gap-5" onSubmit={submit}>
            <RentalItemCompositionFields
              options={optionsQuery.data}
              value={value}
              categoryMode="EDIT"
              submitted={submitted}
              disabled={saveMutation.isPending}
              onChange={setValue}
            />

            {saveMutation.isError ? (
              <FieldError role="alert">
                {saveMutation.error instanceof AssetRentalItemConflictError
                  ? "Бытовка была изменена другим пользователем. Обновите данные и повторите действие."
                  : saveMutation.error instanceof Error
                    ? saveMutation.error.message
                    : "Не удалось сохранить паспорт бытовки"}
              </FieldError>
            ) : null}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={saveMutation.isPending}
                onClick={() => onOpenChange(false)}
              >
                Отмена
              </Button>
              <Button type="submit" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? "Сохраняем…" : "Сохранить"}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
