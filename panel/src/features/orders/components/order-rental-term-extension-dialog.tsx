import { useEffect, useMemo, useRef, useState } from "react"
import { useMutation } from "@tanstack/react-query"
import {
  Add01Icon,
  Loading03Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { FieldError, FieldLegend, FieldSet } from "@/components/ui/field"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { extendOrderRentalTerms } from "@/features/orders/api/orders-api"
import { OrderUnitContentsView } from "@/features/orders/components/order-unit-contents"
import type {
  OrderDetail,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { ApiError } from "@/lib/api-client"

function isShippedRentalTerm(candidate: OrderUnitCandidate) {
  const shipmentDate = candidate.rentalTerm?.shipmentDate
  const returnDate = candidate.rentalTerm?.returnDate
  return (
    candidate.unit.status === "RENTED" &&
    shipmentDate !== null &&
    shipmentDate !== undefined &&
    returnDate !== null &&
    returnDate !== undefined
  )
}

function monthLabel(value: number) {
  const remainder10 = value % 10
  const remainder100 = value % 100
  if (remainder10 === 1 && remainder100 !== 11) return `${value} месяц`
  if (
    remainder10 >= 2 &&
    remainder10 <= 4 &&
    (remainder100 < 10 || remainder100 >= 20)
  ) {
    return `${value} месяца`
  }
  return `${value} месяцев`
}

/**
 * Extends one or more already shipped rental terms through the single
 * versioned logistics command. Selection is local dialog state only and stays
 * available while a stale-version response refreshes the server projection.
 */
export function OrderRentalTermExtensionDialog({
  open,
  order,
  onOpenChange,
  onUpdated,
  onConflict,
}: {
  open: boolean
  order: OrderDetail
  onOpenChange: (open: boolean) => void
  onUpdated: (order: OrderDetail) => void
  onConflict: () => void
}) {
  const { accessToken } = useOrdersModule()
  const [selectedUnitIds, setSelectedUnitIds] = useState<Set<string>>(
    () => new Set()
  )
  const [additionalMonths, setAdditionalMonths] = useState(1)
  const [errorText, setErrorText] = useState<string | null>(null)
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const wasOpen = useRef(false)
  const eligibleCandidates = useMemo(
    () =>
      order.units.filter(
        (candidate) => candidate.added && isShippedRentalTerm(candidate)
      ),
    [order.units]
  )
  const eligibleUnitIds = useMemo(
    () => new Set(eligibleCandidates.map((candidate) => candidate.unit.id)),
    [eligibleCandidates]
  )
  const eligibleUnitIdsKey = eligibleCandidates
    .map((candidate) => candidate.unit.id)
    .join(",")

  useEffect(() => {
    if (!open) {
      wasOpen.current = false
      return
    }
    if (wasOpen.current) return

    wasOpen.current = true
    setSelectedUnitIds(new Set())
    setAdditionalMonths(1)
    setErrorText(null)
  }, [open])

  useEffect(() => {
    if (!open || !wasOpen.current) return
    setSelectedUnitIds((current) => {
      const next = new Set(
        [...current].filter((unitId) => eligibleUnitIds.has(unitId))
      )
      return next.size === current.size ? current : next
    })
  }, [eligibleUnitIds, eligibleUnitIdsKey, open])

  const extendMutation = useMutation({
    mutationFn: ({
      terms,
      fingerprint,
    }: {
      terms: Array<{ unitId: string; additionalMonths: number }>
      fingerprint: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")

      return extendOrderRentalTerms({
        accessToken,
        orderId: order.id,
        expectedVersion: order.version,
        terms,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      onUpdated(projection)
      toast.success("Срок аренды продлён. Дата возврата пересчитана.")
      onOpenChange(false)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        onConflict()
        setErrorText(
          "Срок аренды изменился на сервере. Актуальные данные загружены; проверьте выбранные бытовки и повторите действие."
        )
        return
      }

      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось продлить срок аренды."
      )
    },
  })

  function toggleUnit(unitId: string, checked: boolean) {
    commandIdentity.current.reset()
    setErrorText(null)
    setSelectedUnitIds((current) => {
      const next = new Set(current)
      if (checked) next.add(unitId)
      else next.delete(unitId)
      return next
    })
  }

  function changeAdditionalMonths(next: number) {
    commandIdentity.current.reset()
    setErrorText(null)
    setAdditionalMonths(Math.max(1, next))
  }

  function submit() {
    const terms = eligibleCandidates.flatMap((candidate) =>
      selectedUnitIds.has(candidate.unit.id)
        ? [
            {
              unitId: candidate.unit.id,
              additionalMonths,
            },
          ]
        : []
    )
    if (terms.length === 0) return

    const fingerprint = `extend-rental-terms:${order.id}:${order.version}:${terms
      .map((term) => `${term.unitId}:${term.additionalMonths}`)
      .join(",")}`
    extendMutation.mutate({ terms, fingerprint })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Продлить аренду</DialogTitle>
          <DialogDescription>
            Выберите одну или несколько уже отгруженных бытовок. Один срок
            продления применяется ко всем выбранным бытовкам.
          </DialogDescription>
        </DialogHeader>

        {eligibleCandidates.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            В заказе нет отгруженных бытовок, доступных для продления.
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-muted/20 p-3">
              <div>
                <p className="font-medium">Срок продления</p>
                <p className="text-sm text-muted-foreground">
                  Применяется одинаково ко всем выбранным бытовкам.
                </p>
              </div>
              <div
                className="flex items-center gap-1"
                aria-label="Количество месяцев продления"
              >
                <Button
                  type="button"
                  size="icon-sm"
                  variant="outline"
                  aria-label="Уменьшить срок продления"
                  disabled={additionalMonths <= 1 || extendMutation.isPending}
                  onClick={() => changeAdditionalMonths(additionalMonths - 1)}
                >
                  <HugeiconsIcon icon={MinusSignIcon} />
                </Button>
                <output
                  className="min-w-24 text-center text-sm font-medium"
                  aria-live="polite"
                >
                  {monthLabel(additionalMonths)}
                </output>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="outline"
                  aria-label="Увеличить срок продления"
                  disabled={extendMutation.isPending}
                  onClick={() => changeAdditionalMonths(additionalMonths + 1)}
                >
                  <HugeiconsIcon icon={Add01Icon} />
                </Button>
              </div>
            </div>

            <FieldSet className="gap-3">
              <FieldLegend>Бытовки для продления</FieldLegend>
              {eligibleCandidates.map((candidate) => {
                const selected = selectedUnitIds.has(candidate.unit.id)
                return (
                  <section
                    key={candidate.unit.id}
                    className="rounded-lg border p-3"
                    aria-label={`Бытовка ${candidate.unit.number}`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <Checkbox
                          id={`extend-rental-term-${candidate.unit.id}`}
                          aria-label={`Выбрать бытовку ${candidate.unit.number} для продления`}
                          checked={selected}
                          disabled={extendMutation.isPending}
                          onCheckedChange={(checked) =>
                            toggleUnit(candidate.unit.id, checked === true)
                          }
                        />
                        <label
                          htmlFor={`extend-rental-term-${candidate.unit.id}`}
                          className="cursor-pointer font-medium"
                        >
                          {candidate.unit.number}
                        </label>
                      </div>
                      <Badge variant="outline">
                        Текущий срок:{" "}
                        {monthLabel(candidate.rentalTerm!.rentalMonths)}
                      </Badge>
                    </div>

                    <div className="mt-3 grid gap-3 sm:grid-cols-2">
                      <div>
                        <h3 className="text-sm font-medium">
                          Мебель по заказу
                        </h3>
                        {candidate.desiredContents.length === 0 ? (
                          <p className="mt-1 text-sm text-muted-foreground">
                            Не выбрана.
                          </p>
                        ) : (
                          <div className="mt-1 flex flex-wrap gap-2">
                            {candidate.desiredContents.map((equipment) => (
                              <Badge
                                key={equipment.equipmentId}
                                variant="outline"
                              >
                                {equipment.equipmentName} × {equipment.quantity}
                              </Badge>
                            ))}
                          </div>
                        )}
                      </div>
                      <div>
                        <h3 className="text-sm font-medium">
                          Наполнение бытовки
                        </h3>
                        <div className="mt-1 text-sm">
                          <OrderUnitContentsView
                            contents={candidate.unit.contents}
                          />
                        </div>
                      </div>
                    </div>
                  </section>
                )
              })}
            </FieldSet>
          </div>
        )}

        {errorText ? <FieldError>{errorText}</FieldError> : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={extendMutation.isPending}
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button
            type="button"
            disabled={
              selectedUnitIds.size === 0 ||
              eligibleCandidates.length === 0 ||
              extendMutation.isPending
            }
            onClick={submit}
          >
            {extendMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {extendMutation.isPending
              ? "Продлеваем…"
              : "Продлить выбранные бытовки"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
