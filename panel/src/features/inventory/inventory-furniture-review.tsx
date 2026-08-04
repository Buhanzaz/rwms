import { useEffect, useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import type {
  InventoryFurnitureReviewDto,
  InventoryFurnitureReviewItemDto,
} from "@/features/inventory/model/inventory"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

const quantityFormatter = new Intl.NumberFormat("ru-RU", {
  maximumFractionDigits: 0,
})

type QuantityValues = Record<string, string>

function stockQuantityKey(equipmentId: string) {
  return `stock:${equipmentId}`
}

function cabinQuantityKey(equipmentId: string, findingId: string) {
  return `cabin:${equipmentId}:${findingId}`
}

function quantityValuesFromReview(review: InventoryFurnitureReviewDto) {
  return Object.fromEntries(
    review.items.flatMap((item) => [
      [stockQuantityKey(item.equipmentId), String(item.observedStockQuantity)],
      ...item.cabins.map(
        (cabin) =>
          [
            cabinQuantityKey(item.equipmentId, cabin.findingId),
            String(cabin.observedQuantity),
          ] as const
      ),
    ])
  ) as QuantityValues
}

function cloneReview(
  review: InventoryFurnitureReviewDto
): InventoryFurnitureReviewDto {
  return {
    ...review,
    items: review.items.map((item) => ({
      ...item,
      cabins: item.cabins.map((cabin) => ({ ...cabin })),
    })),
  }
}

function isWholeNonNegativeQuantity(value: string) {
  if (!/^\d+$/.test(value)) return false
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0
}

function furnitureStatusLabel(status: string) {
  return RENTAL_ITEM_STATUS_LABEL[status as RentalItemStatus] ?? status
}

function cabinQuantity(item: InventoryFurnitureReviewItemDto) {
  return item.cabins.reduce((total, cabin) => total + cabin.observedQuantity, 0)
}

function hasReviewQuantityChanges(
  draft: InventoryFurnitureReviewDto,
  review: InventoryFurnitureReviewDto
) {
  if (draft.items.length !== review.items.length) return true
  const savedItems = new Map(
    review.items.map((item) => [item.equipmentId, item])
  )
  return draft.items.some((item) => {
    const saved = savedItems.get(item.equipmentId)
    if (
      !saved ||
      item.observedStockQuantity !== saved.observedStockQuantity ||
      item.cabins.length !== saved.cabins.length
    ) {
      return true
    }
    const savedCabins = new Map(
      saved.cabins.map((cabin) => [cabin.findingId, cabin])
    )
    return item.cabins.some(
      (cabin) =>
        cabin.observedQuantity !==
        savedCabins.get(cabin.findingId)?.observedQuantity
    )
  })
}

function updateStockQuantity(
  review: InventoryFurnitureReviewDto,
  equipmentId: string,
  observedStockQuantity: number
) {
  return {
    ...review,
    items: review.items.map((item) =>
      item.equipmentId === equipmentId
        ? { ...item, observedStockQuantity }
        : item
    ),
  }
}

function updateCabinQuantity(
  review: InventoryFurnitureReviewDto,
  equipmentId: string,
  findingId: string,
  observedQuantity: number
) {
  return {
    ...review,
    items: review.items.map((item) =>
      item.equipmentId === equipmentId
        ? {
            ...item,
            cabins: item.cabins.map((cabin) =>
              cabin.findingId === findingId
                ? { ...cabin, observedQuantity }
                : cabin
            ),
          }
        : item
    ),
  }
}

type InventoryFurnitureReviewProps = {
  review: InventoryFurnitureReviewDto
  pending: boolean
  error: string | null
  onSave: (review: InventoryFurnitureReviewDto) => void
  onDraftChange: (changed: boolean) => void
}

function furnitureReviewKey(review: InventoryFurnitureReviewDto) {
  return [
    review.sessionRevision,
    review.assetSnapshotSha256,
    review.reviewSha256 ?? "unconfirmed",
    review.confirmed,
  ].join(":")
}

export function InventoryFurnitureReview(props: InventoryFurnitureReviewProps) {
  return (
    <InventoryFurnitureReviewForm
      key={furnitureReviewKey(props.review)}
      {...props}
    />
  )
}

function InventoryFurnitureReviewForm({
  review,
  pending,
  error,
  onSave,
  onDraftChange,
}: InventoryFurnitureReviewProps) {
  const [draft, setDraft] = useState(() => cloneReview(review))
  const [quantityValues, setQuantityValues] = useState<QuantityValues>(() =>
    quantityValuesFromReview(review)
  )

  const invalidQuantity = Object.values(quantityValues).some(
    (value) => !isWholeNonNegativeQuantity(value)
  )
  const savedQuantityValues = quantityValuesFromReview(review)
  const valuesChanged = Object.entries(savedQuantityValues).some(
    ([key, value]) => quantityValues[key] !== value
  )
  const draftChanged = valuesChanged || hasReviewQuantityChanges(draft, review)
  const canSave = !invalidQuantity && (!review.confirmed || draftChanged)

  useEffect(() => {
    onDraftChange(draftChanged)
  }, [draftChanged, onDraftChange])

  const setStock = (equipmentId: string, value: string) => {
    const key = stockQuantityKey(equipmentId)
    setQuantityValues((current) => ({ ...current, [key]: value }))
    if (!isWholeNonNegativeQuantity(value)) return
    setDraft((current) =>
      updateStockQuantity(current, equipmentId, Number(value))
    )
  }

  const setCabin = (equipmentId: string, findingId: string, value: string) => {
    const key = cabinQuantityKey(equipmentId, findingId)
    setQuantityValues((current) => ({ ...current, [key]: value }))
    if (!isWholeNonNegativeQuantity(value)) return
    setDraft((current) =>
      updateCabinQuantity(current, equipmentId, findingId, Number(value))
    )
  }

  return (
    <section
      className="flex flex-col gap-4"
      aria-labelledby="furniture-review-title"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="furniture-review-title" className="text-lg font-semibold">
            Сверка мебели
          </h2>
          <p className="text-sm text-muted-foreground">
            Укажите фактический остаток на складе и количество мебели в
            бытовках. Все значения сохраняются одной полной сверкой.
          </p>
        </div>
        <Badge variant={review.confirmed ? "default" : "secondary"}>
          {review.confirmed ? "Сверка сохранена" : "Требуется сверка"}
        </Badge>
        <Badge variant="outline">Позиций: {draft.items.length}</Badge>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <Card size="sm">
          <CardContent className="pt-6">
            <p className="text-sm text-muted-foreground">
              Текущий остаток на складе
            </p>
            <p className="text-lg font-semibold">
              {quantityFormatter.format(
                draft.items.reduce(
                  (total, item) => total + item.currentStockQuantity,
                  0
                )
              )}{" "}
              шт.
            </p>
          </CardContent>
        </Card>
        <Card size="sm">
          <CardContent className="pt-6">
            <p className="text-sm text-muted-foreground">Посчитано на складе</p>
            <p className="text-lg font-semibold">
              {quantityFormatter.format(
                draft.items.reduce(
                  (total, item) => total + item.observedStockQuantity,
                  0
                )
              )}{" "}
              шт.
            </p>
          </CardContent>
        </Card>
        <Card size="sm">
          <CardContent className="pt-6">
            <p className="text-sm text-muted-foreground">В бытовках</p>
            <p className="text-lg font-semibold">
              {quantityFormatter.format(
                draft.items.reduce(
                  (total, item) => total + cabinQuantity(item),
                  0
                )
              )}{" "}
              шт.
            </p>
          </CardContent>
        </Card>
      </div>

      {draft.items.length === 0 ? (
        <Card size="sm">
          <CardContent className="pt-6 text-sm text-muted-foreground">
            В снимке инвентаризации нет мебели для сверки. Сохраните пустую
            сверку, чтобы подтвердить этот результат.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {draft.items.map((item) => {
            const stockKey = stockQuantityKey(item.equipmentId)
            const stockValue = quantityValues[stockKey] ?? ""
            const cabinTotal = cabinQuantity(item)
            return (
              <Card key={item.equipmentId} size="sm">
                <CardHeader className="gap-1">
                  <CardTitle className="text-base">
                    {item.equipmentName}
                  </CardTitle>
                  <p className="text-sm text-muted-foreground">
                    В бытовках: {quantityFormatter.format(cabinTotal)} шт.
                  </p>
                </CardHeader>
                <CardContent className="flex flex-col gap-3">
                  <dl className="grid grid-cols-2 gap-2 text-sm">
                    <dt className="text-muted-foreground">Текущий остаток</dt>
                    <dd>
                      {quantityFormatter.format(item.currentStockQuantity)} шт.
                    </dd>
                    <dt className="text-muted-foreground">
                      Бытовок с позицией
                    </dt>
                    <dd>{item.cabins.length}</dd>
                  </dl>
                  <Field data-invalid={!isWholeNonNegativeQuantity(stockValue)}>
                    <FieldLabel htmlFor={`furniture-stock-${item.equipmentId}`}>
                      Посчитано на складе
                    </FieldLabel>
                    <Input
                      id={`furniture-stock-${item.equipmentId}`}
                      type="number"
                      min={0}
                      step={1}
                      inputMode="numeric"
                      value={stockValue}
                      disabled={pending}
                      aria-invalid={!isWholeNonNegativeQuantity(stockValue)}
                      onChange={(event) =>
                        setStock(item.equipmentId, event.target.value)
                      }
                    />
                  </Field>
                  <details className="rounded-md border px-3 py-2">
                    <summary className="cursor-pointer text-sm font-medium marker:text-muted-foreground">
                      Бытовки ({item.cabins.length})
                    </summary>
                    <div className="mt-3 flex flex-col gap-3">
                      {item.cabins.map((cabin) => {
                        const cabinKey = cabinQuantityKey(
                          item.equipmentId,
                          cabin.findingId
                        )
                        const cabinValue = quantityValues[cabinKey] ?? ""
                        return (
                          <div
                            key={cabin.findingId}
                            className="grid gap-2 rounded-md border p-3 sm:grid-cols-[minmax(0,1fr)_8rem] sm:items-end"
                          >
                            <div className="min-w-0">
                              <p className="font-medium break-words">
                                {cabin.cabinNumber}
                              </p>
                              <p className="text-sm text-muted-foreground">
                                {furnitureStatusLabel(cabin.status)} · было{" "}
                                {quantityFormatter.format(
                                  cabin.currentQuantity
                                )}{" "}
                                шт.
                              </p>
                            </div>
                            <Field
                              data-invalid={
                                !isWholeNonNegativeQuantity(cabinValue)
                              }
                            >
                              <FieldLabel
                                htmlFor={`furniture-cabin-${item.equipmentId}-${cabin.findingId}`}
                              >
                                Стало
                              </FieldLabel>
                              <Input
                                id={`furniture-cabin-${item.equipmentId}-${cabin.findingId}`}
                                type="number"
                                min={0}
                                step={1}
                                inputMode="numeric"
                                value={cabinValue}
                                disabled={pending}
                                aria-invalid={
                                  !isWholeNonNegativeQuantity(cabinValue)
                                }
                                onChange={(event) =>
                                  setCabin(
                                    item.equipmentId,
                                    cabin.findingId,
                                    event.target.value
                                  )
                                }
                              />
                            </Field>
                          </div>
                        )
                      })}
                    </div>
                  </details>
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}

      <div className="flex flex-col items-start gap-2">
        {review.confirmed ? (
          <p className="text-sm text-muted-foreground">
            Если заметили ошибку, измените значение и сохраните всю сверку
            повторно до завершения инвентаризации.
          </p>
        ) : null}
        {invalidQuantity ? (
          <FieldError>
            Для каждого остатка укажите целое неотрицательное число.
          </FieldError>
        ) : null}
        {error ? <FieldError>{error}</FieldError> : null}
        <Button
          type="button"
          disabled={pending || !canSave}
          onClick={() => onSave(draft)}
        >
          {pending
            ? "Сохраняем сверку..."
            : review.confirmed
              ? "Сохранить изменения сверки"
              : "Сохранить сверку мебели"}
        </Button>
      </div>
    </section>
  )
}
