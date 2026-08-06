import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

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
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

import {
  buildCabinContentsPlan,
  cabinContentsSnapshotMatches,
  cabinContentsQuantitiesAreValid,
  cabinDispositionContents,
} from "./cabin-contents-disposition"
import { CabinContentsDispositionFields } from "./cabin-contents-disposition-fields"
import {
  createPropertyDisposition,
  createPropertyDispositionIdempotencyKey,
  type CabinContentsDispositionMode,
  type PropertyDispositionDecision,
  type PropertyDispositionKind,
} from "./property-dispositions-api"

const EXCLUDED_CABIN_STATUSES: RentalItemStatus[] = [
  "RENTED",
  "BOOKED",
  "RESERVED",
  "IN_TRANSFER",
  "WRITTEN_OFF",
  "LOST",
]

function stockBalance(item: EquipmentItemDto) {
  return item.balances.find(
    (balance) => balance.locationKind === "STOCK" && balance.availableStock > 0
  )
}

function normalizedEvidence(value: string) {
  const normalized = value.trim()
  if (!normalized) return null
  try {
    return new URL(normalized).toString()
  } catch {
    throw new Error("Укажите корректную ссылку на подтверждение.")
  }
}

export function PropertyDispositionCreateDialog({
  accessToken,
  warehouseId,
  disposition,
  open,
  onOpenChange,
  onSaved,
}: {
  accessToken: string | null
  warehouseId: string
  disposition: PropertyDispositionKind
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: (decision: PropertyDispositionDecision) => void
}) {
  const queryClient = useQueryClient()
  const [assetKind, setAssetKind] = useState<"CABIN" | "EQUIPMENT">("CABIN")
  const [assetId, setAssetId] = useState("")
  const [quantity, setQuantity] = useState(1)
  const [reason, setReason] = useState("")
  const [evidenceLink, setEvidenceLink] = useState("")
  const [contentsMode, setContentsMode] =
    useState<CabinContentsDispositionMode>("MOVE_SELECTED_TO_STOCK")
  const [contentsQuantities, setContentsQuantities] = useState<
    Record<string, number>
  >({})
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState<{
    signature: string
    idempotencyKey: string
  } | null>(null)

  const cabinsQuery = useQuery({
    queryKey: ["rental-items", warehouseId, "disposition-sources"],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: 0,
        size: 200,
        excludeStatuses: EXCLUDED_CABIN_STATUSES,
      }),
    enabled: open && Boolean(accessToken),
  })
  const equipmentQuery = useQuery({
    queryKey: ["equipment-items", warehouseId, "disposition-sources"],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled: open && Boolean(accessToken),
  })

  const cabins = (cabinsQuery.data?.content ?? []).filter(
    (item) => !EXCLUDED_CABIN_STATUSES.includes(item.status)
  )
  const equipment = useMemo(
    () => (equipmentQuery.data ?? []).filter(stockBalance),
    [equipmentQuery.data]
  )
  const selectedCabin = cabins.find((item) => item.id === assetId) ?? null
  const selectedEquipment =
    equipment.find((item) => item.id === assetId) ?? null
  const selectedStock = selectedEquipment
    ? stockBalance(selectedEquipment)
    : null
  const noAvailableAssets =
    assetKind === "CABIN"
      ? cabinsQuery.isSuccess && cabins.length === 0
      : equipmentQuery.isSuccess && equipment.length === 0
  const cabinContents = useMemo(() => {
    if (!selectedCabin) return []
    return cabinDispositionContents(selectedCabin.id, equipmentQuery.data ?? [])
  }, [equipmentQuery.data, selectedCabin])
  const quantityValid = Boolean(
    selectedStock &&
    Number.isSafeInteger(quantity) &&
    quantity >= 1 &&
    quantity <= selectedStock.availableStock
  )
  const contentsValid = cabinContentsQuantitiesAreValid(
    cabinContents,
    contentsQuantities
  )
  const cabinSnapshotComplete = Boolean(
    selectedCabin &&
    cabinContentsSnapshotMatches(selectedCabin.contentsItems, cabinContents)
  )

  const mutation = useMutation({
    mutationFn: (input: { idempotencyKey: string; signature: string }) => {
      const normalizedReason = reason.trim()
      if (!normalizedReason) throw new Error("Укажите причину.")
      const evidence = normalizedEvidence(evidenceLink)
      if (assetKind === "CABIN") {
        if (!selectedCabin) throw new Error("Выберите бытовку.")
        if (!cabinSnapshotComplete) {
          throw new Error(
            "Остатки наполнения не совпали с паспортом бытовки. Обновите данные."
          )
        }
        const contentsPlan = buildCabinContentsPlan(
          contentsMode,
          cabinContents,
          contentsQuantities
        )
        return createPropertyDisposition({
          accessToken,
          idempotencyKey: input.idempotencyKey,
          request: {
            warehouseId,
            assetKind: "CABIN",
            assetId: selectedCabin.id,
            expectedAssetVersion: selectedCabin.version,
            disposition,
            reason: normalizedReason,
            evidenceLink: evidence,
            contentsPlan,
          },
        })
      }
      if (!selectedEquipment || !selectedStock || !quantityValid) {
        throw new Error("Выберите оборудование и допустимое количество.")
      }
      return createPropertyDisposition({
        accessToken,
        idempotencyKey: input.idempotencyKey,
        request: {
          warehouseId,
          assetKind: "EQUIPMENT",
          assetId: selectedEquipment.id,
          expectedAssetVersion: selectedEquipment.version,
          expectedSourceBalanceVersion: selectedStock.version,
          quantity,
          disposition,
          reason: normalizedReason,
          evidenceLink: evidence,
        },
      })
    },
    onSuccess: (decision) => {
      toast.success("Решение отправлено администратору.")
      void queryClient.invalidateQueries({
        queryKey: ["property-dispositions"],
      })
      void queryClient.invalidateQueries({
        queryKey: ["equipment-items", warehouseId],
      })
      onSaved(decision)
      onOpenChange(false)
    },
    onError: (unknownError) => {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось создать решение по имуществу."
      )
      void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      void queryClient.invalidateQueries({
        queryKey: ["equipment-items", warehouseId],
      })
    },
  })

  function clearAttempt() {
    setAttempt(null)
    setError(null)
  }

  function reset(nextOpen: boolean) {
    if (mutation.isPending) return
    if (nextOpen) {
      setAssetKind("CABIN")
      setAssetId("")
      setQuantity(1)
      setReason("")
      setEvidenceLink("")
      setContentsMode("MOVE_SELECTED_TO_STOCK")
      setContentsQuantities({})
      setSubmitted(false)
      setAttempt(null)
      setError(null)
    }
    onOpenChange(nextOpen)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    setError(null)
    const hasAsset =
      assetKind === "CABIN"
        ? Boolean(selectedCabin)
        : Boolean(selectedEquipment)
    if (!hasAsset || !reason.trim()) return
    if (assetKind === "EQUIPMENT" && !quantityValid) return
    if (assetKind === "CABIN" && (!cabinSnapshotComplete || !contentsValid))
      return

    let evidence: string | null
    try {
      evidence = normalizedEvidence(evidenceLink)
    } catch (unknownError) {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Некорректная ссылка."
      )
      return
    }
    const signature = JSON.stringify({
      warehouseId,
      disposition,
      assetKind,
      assetId,
      assetVersion:
        assetKind === "CABIN"
          ? selectedCabin?.version
          : selectedEquipment?.version,
      sourceBalanceVersion: selectedStock?.version ?? null,
      quantity: assetKind === "EQUIPMENT" ? quantity : null,
      reason: reason.trim(),
      evidence,
      contentsMode,
      contentsQuantities,
    })
    const idempotencyKey =
      attempt?.signature === signature
        ? attempt.idempotencyKey
        : createPropertyDispositionIdempotencyKey()
    if (attempt?.signature !== signature)
      setAttempt({ signature, idempotencyKey })
    mutation.mutate({ signature, idempotencyKey })
  }

  const noun = disposition === "WRITE_OFF" ? "списание" : "утрату"

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <form className="contents" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Добавить {noun}</DialogTitle>
            <DialogDescription>
              Создаётся решение по одному корневому объекту. Фактическое
              списание или утрата наступит только после подтверждения
              администратора и завершения серверного эффекта.
            </DialogDescription>
          </DialogHeader>

          <FieldGroup>
            <Field>
              <FieldLabel>Вид имущества</FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                aria-label="Вид имущества"
                value={assetKind}
                disabled={mutation.isPending}
                onValueChange={(value) => {
                  if (value !== "CABIN" && value !== "EQUIPMENT") return
                  setAssetKind(value)
                  setAssetId("")
                  setContentsQuantities({})
                  clearAttempt()
                }}
              >
                <ToggleGroupItem value="CABIN">Бытовка</ToggleGroupItem>
                <ToggleGroupItem value="EQUIPMENT">Мебель</ToggleGroupItem>
              </ToggleGroup>
            </Field>

            <Field data-invalid={submitted && !assetId}>
              <FieldLabel htmlFor="property-disposition-asset">
                Имущество со склада
              </FieldLabel>
              <Select
                value={assetId}
                disabled={
                  mutation.isPending ||
                  cabinsQuery.isLoading ||
                  equipmentQuery.isLoading ||
                  noAvailableAssets
                }
                onValueChange={(value) => {
                  setAssetId(value)
                  setContentsQuantities({})
                  clearAttempt()
                }}
              >
                <SelectTrigger
                  id="property-disposition-asset"
                  className="w-full"
                  aria-invalid={submitted && !assetId}
                >
                  <SelectValue placeholder="Выберите имущество" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {assetKind === "CABIN"
                      ? cabins.map((item) => (
                          <SelectItem key={item.id} value={item.id}>
                            {item.number} — {item.type}
                          </SelectItem>
                        ))
                      : equipment.map((item) => (
                          <SelectItem key={item.id} value={item.id}>
                            {item.name} — доступно{" "}
                            {stockBalance(item)?.availableStock ?? 0} шт.
                          </SelectItem>
                        ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              {submitted && !assetId ? (
                <FieldError>Выберите имущество.</FieldError>
              ) : null}
              {cabinsQuery.isError || equipmentQuery.isError ? (
                <FieldError>
                  Не удалось загрузить актуальные складские остатки.
                </FieldError>
              ) : null}
              {assetKind === "EQUIPMENT" &&
              equipmentQuery.isSuccess &&
              equipment.length === 0 ? (
                <FieldDescription>
                  На складе нет свободного оборудования для списания. Сначала
                  переместите мебель из бытовки на склад.
                </FieldDescription>
              ) : null}
              {assetKind === "CABIN" &&
              cabinsQuery.isSuccess &&
              cabins.length === 0 ? (
                <FieldDescription>
                  На этом складе нет бытовок, доступных для списания.
                </FieldDescription>
              ) : null}
            </Field>

            {assetKind === "EQUIPMENT" ? (
              <Field data-invalid={submitted && !quantityValid}>
                <FieldLabel htmlFor="property-disposition-quantity">
                  Количество
                </FieldLabel>
                <Input
                  id="property-disposition-quantity"
                  type="number"
                  min={1}
                  max={selectedStock?.availableStock ?? 1}
                  step={1}
                  value={quantity}
                  disabled={mutation.isPending}
                  aria-invalid={submitted && !quantityValid}
                  onChange={(event) => {
                    setQuantity(Number(event.target.value))
                    clearAttempt()
                  }}
                />
                <FieldDescription>
                  Доступно на складе: {selectedStock?.availableStock ?? 0} шт.
                </FieldDescription>
                {submitted && !quantityValid ? (
                  <FieldError>
                    Количество должно быть целым и не выше остатка.
                  </FieldError>
                ) : null}
              </Field>
            ) : null}

            {assetKind === "CABIN" &&
            selectedCabin &&
            !equipmentQuery.isLoading &&
            cabinSnapshotComplete ? (
              <CabinContentsDispositionFields
                contents={cabinContents}
                mode={contentsMode}
                quantities={contentsQuantities}
                disabled={mutation.isPending}
                error={
                  submitted && !contentsValid
                    ? "Исправьте количество наполнения."
                    : null
                }
                onModeChange={(mode) => {
                  setContentsMode(mode)
                  clearAttempt()
                }}
                onQuantityChange={(equipmentId, nextQuantity) => {
                  setContentsQuantities((current) => ({
                    ...current,
                    [equipmentId]: nextQuantity,
                  }))
                  clearAttempt()
                }}
              />
            ) : null}

            {assetKind === "CABIN" &&
            selectedCabin &&
            !equipmentQuery.isLoading &&
            !cabinSnapshotComplete ? (
              <FieldError>
                Остатки наполнения не совпали с паспортом бытовки. Обновите
                данные и повторите.
              </FieldError>
            ) : null}

            <Field data-invalid={submitted && !reason.trim()}>
              <FieldLabel htmlFor="property-disposition-reason">
                Причина
              </FieldLabel>
              <Textarea
                id="property-disposition-reason"
                value={reason}
                maxLength={2000}
                disabled={mutation.isPending}
                aria-invalid={submitted && !reason.trim()}
                placeholder="Обязательная причина решения"
                onChange={(event) => {
                  setReason(event.target.value)
                  clearAttempt()
                }}
              />
              {submitted && !reason.trim() ? (
                <FieldError>Укажите причину.</FieldError>
              ) : null}
            </Field>

            <Field>
              <FieldLabel htmlFor="property-disposition-evidence">
                Подтверждение
              </FieldLabel>
              <Input
                id="property-disposition-evidence"
                type="url"
                value={evidenceLink}
                maxLength={2000}
                disabled={mutation.isPending}
                placeholder="https://… (необязательно)"
                onChange={(event) => {
                  setEvidenceLink(event.target.value)
                  clearAttempt()
                }}
              />
            </Field>

            {error ? <FieldError>{error}</FieldError> : null}
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
              disabled={
                mutation.isPending ||
                !accessToken ||
                noAvailableAssets ||
                (assetKind === "CABIN" &&
                  Boolean(selectedCabin) &&
                  !cabinSnapshotComplete)
              }
            >
              {mutation.isPending ? "Отправка..." : "Отправить администратору"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
