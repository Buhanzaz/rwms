import { useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { getEquipmentItems } from "@/api/equipment-api"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  RentalClientPicker,
  type RentalClientSelection,
} from "@/features/logistics/rental-client-picker"
import type {
  ConfirmInventoryReturnsRequest,
  ConfirmInventoryShipmentsRequest,
  InventoryCabinDispositionReview,
} from "@/features/inventory/model/inventory-service"

type ClientDecision = {
  date: string
  client: RentalClientSelection | null
}

type FurnitureDraft = {
  key: string
  equipmentId: string
  quantity: string
}

type ShipmentDecision = ClientDecision & {
  shipped: boolean
  furniture: FurnitureDraft[]
}

function furnitureDraft(): FurnitureDraft {
  return {
    key: crypto.randomUUID(),
    equipmentId: "",
    quantity: "1",
  }
}

/**
 * Renders the server-owned return and shipment phases before furniture reconciliation.
 * Omitted missing cabins are deliberately not represented as a checkbox choice: confirmation
 * sends only real shipments and the server deterministically records every remainder as write-off.
 */
export function InventoryCabinDispositionReviewCard({
  review,
  accessToken,
  warehouseId,
  defaultDate,
  pending,
  error,
  onConfirmReturns,
  onConfirmShipments,
}: {
  review: InventoryCabinDispositionReview
  accessToken: string
  warehouseId: string
  defaultDate: string
  pending: boolean
  error: string | null
  onConfirmReturns: (request: ConfirmInventoryReturnsRequest) => void
  onConfirmShipments: (request: ConfirmInventoryShipmentsRequest) => void
}) {
  const [returns, setReturns] = useState<Record<string, ClientDecision>>({})
  const [shipments, setShipments] = useState<Record<string, ShipmentDecision>>(
    {}
  )
  const equipmentQuery = useQuery({
    queryKey: ["inventory", "shipment-furniture-catalog", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled:
      review.phase === "SHIPMENTS" && Boolean(accessToken && warehouseId),
  })
  const furniture = useMemo(
    () =>
      (equipmentQuery.data ?? [])
        .filter((item) => item.active && item.category === "FURNITURE")
        .slice()
        .sort((left, right) =>
          left.name.localeCompare(right.name, "ru", { sensitivity: "base" })
        ),
    [equipmentQuery.data]
  )
  const furnitureById = useMemo(
    () => new Map(furniture.map((item) => [item.id, item])),
    [furniture]
  )

  useEffect(() => {
    setReturns(
      Object.fromEntries(
        review.returnCandidates.map((candidate) => [
          candidate.findingId,
          { date: defaultDate, client: null },
        ])
      )
    )
    setShipments(
      Object.fromEntries(
        review.missingCandidates.map((candidate) => [
          candidate.findingId,
          {
            date: defaultDate,
            client: null,
            shipped: false,
            furniture: [],
          },
        ])
      )
    )
  }, [defaultDate, review.reviewRevision])

  if (review.phase === "COMPLETED") return null

  const returnsComplete = review.returnCandidates.every((candidate) => {
    const decision = returns[candidate.findingId]
    return Boolean(decision?.date && decision.client)
  })
  const shipmentsComplete = review.missingCandidates.every((candidate) => {
    const decision = shipments[candidate.findingId]
    if (!decision?.shipped) return true
    return Boolean(
      decision.date &&
      decision.client &&
      decision.furniture.every(
        (item) =>
          furnitureById.has(item.equipmentId) &&
          Number.isInteger(Number(item.quantity)) &&
          Number(item.quantity) >= 1
      )
    )
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {review.phase === "RETURNS"
            ? "Возвраты найденных бытовок"
            : "Отгрузки ненайденных бытовок"}
        </CardTitle>
        <CardDescription>
          {review.phase === "RETURNS"
            ? "Для каждой найденной бытовки, которая числилась в аренде, укажите фактический возврат."
            : "Отметьте только бытовки, которые действительно были отгружены, и укажите клиента и мебель."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {review.phase === "RETURNS" ? (
          review.returnCandidates.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Найденных бытовок из аренды нет. Подтвердите пустой этап.
            </p>
          ) : (
            review.returnCandidates.map((candidate) => {
              const decision = returns[candidate.findingId] ?? {
                date: defaultDate,
                client: null,
              }
              return (
                <section
                  key={candidate.findingId}
                  className="grid gap-3 rounded-md border p-4 md:grid-cols-2"
                  aria-label={`Возврат бытовки ${candidate.cabinNumber}`}
                >
                  <h3 className="font-medium md:col-span-2">
                    Бытовка {candidate.cabinNumber}
                  </h3>
                  <Field>
                    <FieldLabel htmlFor={`return-date-${candidate.findingId}`}>
                      Дата возврата
                    </FieldLabel>
                    <Input
                      id={`return-date-${candidate.findingId}`}
                      type="date"
                      max={defaultDate}
                      value={decision.date}
                      disabled={pending}
                      onChange={(event) =>
                        setReturns((current) => ({
                          ...current,
                          [candidate.findingId]: {
                            ...decision,
                            date: event.target.value,
                          },
                        }))
                      }
                    />
                  </Field>
                  <RentalClientPicker
                    accessToken={accessToken}
                    idPrefix={`return-${candidate.findingId}`}
                    value={decision.client}
                    disabled={pending}
                    onChange={(client) =>
                      setReturns((current) => ({
                        ...current,
                        [candidate.findingId]: { ...decision, client },
                      }))
                    }
                  />
                </section>
              )
            })
          )
        ) : (
          <>
            <p className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
              Все ненайденные бытовки, которые не отмечены как отгруженные,
              будут автоматически отправлены в списание на согласование.
            </p>
            {equipmentQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">
                Загружаем каталог мебели…
              </p>
            ) : equipmentQuery.isError ? (
              <div className="flex flex-col gap-2">
                <FieldError role="alert">
                  {equipmentQuery.error instanceof Error
                    ? equipmentQuery.error.message
                    : "Не удалось загрузить каталог мебели."}
                </FieldError>
                <Button
                  type="button"
                  variant="outline"
                  className="self-start"
                  onClick={() => void equipmentQuery.refetch()}
                >
                  Повторить загрузку
                </Button>
              </div>
            ) : null}
            {review.missingCandidates.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Ненайденных бытовок нет. Подтвердите пустой этап.
              </p>
            ) : (
              review.missingCandidates.map((candidate) => {
                const decision = shipments[candidate.findingId] ?? {
                  date: defaultDate,
                  client: null,
                  shipped: false,
                  furniture: [],
                }
                return (
                  <section
                    key={candidate.findingId}
                    className="flex flex-col gap-3 rounded-md border p-4"
                    aria-label={`Отгрузка бытовки ${candidate.cabinNumber}`}
                  >
                    <Field orientation="horizontal">
                      <Checkbox
                        id={`shipment-${candidate.findingId}`}
                        checked={decision.shipped}
                        disabled={pending}
                        onCheckedChange={(checked) =>
                          setShipments((current) => ({
                            ...current,
                            [candidate.findingId]: {
                              ...decision,
                              shipped: checked === true,
                            },
                          }))
                        }
                      />
                      <FieldLabel
                        htmlFor={`shipment-${candidate.findingId}`}
                        className="font-normal"
                      >
                        Бытовка {candidate.cabinNumber} была отгружена
                      </FieldLabel>
                    </Field>
                    {decision.shipped ? (
                      <div className="grid gap-3 md:grid-cols-2">
                        <Field>
                          <FieldLabel
                            htmlFor={`shipment-date-${candidate.findingId}`}
                          >
                            Дата отгрузки
                          </FieldLabel>
                          <Input
                            id={`shipment-date-${candidate.findingId}`}
                            type="date"
                            max={defaultDate}
                            value={decision.date}
                            disabled={pending}
                            onChange={(event) =>
                              setShipments((current) => ({
                                ...current,
                                [candidate.findingId]: {
                                  ...decision,
                                  date: event.target.value,
                                },
                              }))
                            }
                          />
                        </Field>
                        <RentalClientPicker
                          accessToken={accessToken}
                          idPrefix={`shipment-client-${candidate.findingId}`}
                          value={decision.client}
                          disabled={pending}
                          onChange={(client) =>
                            setShipments((current) => ({
                              ...current,
                              [candidate.findingId]: { ...decision, client },
                            }))
                          }
                        />
                        <div className="flex flex-col gap-2 md:col-span-2">
                          <div className="flex items-center justify-between gap-2">
                            <p className="text-sm font-medium">
                              Мебель в отгрузке
                            </p>
                            <Button
                              type="button"
                              size="sm"
                              variant="outline"
                              disabled={
                                pending ||
                                equipmentQuery.isLoading ||
                                equipmentQuery.isError
                              }
                              onClick={() =>
                                setShipments((current) => ({
                                  ...current,
                                  [candidate.findingId]: {
                                    ...decision,
                                    furniture: [
                                      ...decision.furniture,
                                      furnitureDraft(),
                                    ],
                                  },
                                }))
                              }
                            >
                              Добавить мебель
                            </Button>
                          </div>
                          {decision.furniture.map((item, index) => (
                            <div
                              key={item.key}
                              className="grid gap-2 md:grid-cols-[minmax(0,1fr)_8rem_auto]"
                            >
                              <Select
                                value={item.equipmentId}
                                disabled={pending || equipmentQuery.isError}
                                onValueChange={(equipmentId) =>
                                  setShipments((current) => ({
                                    ...current,
                                    [candidate.findingId]: {
                                      ...decision,
                                      furniture: decision.furniture.map(
                                        (row) =>
                                          row.key === item.key
                                            ? { ...row, equipmentId }
                                            : row
                                      ),
                                    },
                                  }))
                                }
                              >
                                <SelectTrigger
                                  aria-label={`Мебель ${index + 1}`}
                                >
                                  <SelectValue placeholder="Выберите мебель" />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectGroup>
                                    {furniture
                                      .filter(
                                        (candidateItem) =>
                                          candidateItem.id ===
                                            item.equipmentId ||
                                          !decision.furniture.some(
                                            (other) =>
                                              other.key !== item.key &&
                                              other.equipmentId ===
                                                candidateItem.id
                                          )
                                      )
                                      .map((candidateItem) => (
                                        <SelectItem
                                          key={candidateItem.id}
                                          value={candidateItem.id}
                                        >
                                          {candidateItem.name}
                                        </SelectItem>
                                      ))}
                                  </SelectGroup>
                                </SelectContent>
                              </Select>
                              <Input
                                aria-label={`Количество мебели ${index + 1}`}
                                type="number"
                                min={1}
                                value={item.quantity}
                                onChange={(event) =>
                                  setShipments((current) => ({
                                    ...current,
                                    [candidate.findingId]: {
                                      ...decision,
                                      furniture: decision.furniture.map(
                                        (row) =>
                                          row.key === item.key
                                            ? {
                                                ...row,
                                                quantity: event.target.value,
                                              }
                                            : row
                                      ),
                                    },
                                  }))
                                }
                              />
                              <Button
                                type="button"
                                variant="ghost"
                                onClick={() =>
                                  setShipments((current) => ({
                                    ...current,
                                    [candidate.findingId]: {
                                      ...decision,
                                      furniture: decision.furniture.filter(
                                        (row) => row.key !== item.key
                                      ),
                                    },
                                  }))
                                }
                              >
                                Удалить
                              </Button>
                            </div>
                          ))}
                        </div>
                      </div>
                    ) : null}
                  </section>
                )
              })
            )}
          </>
        )}
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
      </CardContent>
      <CardFooter className="border-t">
        <Button
          type="button"
          disabled={
            pending ||
            (review.phase === "RETURNS" ? !returnsComplete : !shipmentsComplete)
          }
          onClick={() => {
            if (review.phase === "RETURNS") {
              onConfirmReturns({
                expectedSessionRevision: review.sessionRevision,
                expectedReviewRevision: review.reviewRevision,
                returns: review.returnCandidates.map((candidate) => {
                  const decision = returns[candidate.findingId]!
                  return {
                    findingId: candidate.findingId,
                    expectedFindingRevision: candidate.findingRevision,
                    returnedOn: decision.date,
                    clientId: decision.client!.id,
                    clientSnapshot: decision.client!.displayName,
                  }
                }),
              })
              return
            }
            onConfirmShipments({
              expectedSessionRevision: review.sessionRevision,
              expectedReviewRevision: review.reviewRevision,
              shipments: review.missingCandidates.flatMap((candidate) => {
                const decision = shipments[candidate.findingId]!
                if (!decision.shipped) return []
                return [
                  {
                    findingId: candidate.findingId,
                    expectedFindingRevision: candidate.findingRevision,
                    departedOn: decision.date,
                    clientId: decision.client!.id,
                    clientSnapshot: decision.client!.displayName,
                    furniture: decision.furniture.map((item) => ({
                      equipmentId: item.equipmentId,
                      catalogVersion: furnitureById.get(item.equipmentId)!
                        .version,
                      quantity: Number(item.quantity),
                    })),
                  },
                ]
              }),
            })
          }}
        >
          {pending
            ? "Сохраняем…"
            : review.phase === "RETURNS"
              ? "Подтвердить возвраты"
              : "Подтвердить отгрузки и списание остальных"}
        </Button>
      </CardFooter>
    </Card>
  )
}
