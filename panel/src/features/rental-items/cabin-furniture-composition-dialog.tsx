import { useMemo, useState, type FormEvent } from "react"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
  FieldLegend,
  FieldLabel,
  FieldSet,
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
import {
  cabinFurnitureRows,
  cabinHasFurniture,
  type CabinFurnitureRequirementInput,
} from "@/features/rental-items/cabin-furniture"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

export type { CabinFurnitureRequirementInput } from "@/features/rental-items/cabin-furniture"

type FurnitureDraft = CabinFurnitureRequirementInput & {
  key: string
}

type ScheduleField = {
  value: string
  onChange: (value: string) => void
}

type CabinFurnitureCompositionDialogProps = {
  cabin: Pick<RentalItemDto, "id" | "number" | "contentsItems">
  equipmentItems: EquipmentItemDto[]
  initialContents: CabinFurnitureRequirementInput[]
  open: boolean
  pending?: boolean
  error?: string | null
  schedule?: ScheduleField
  onOpenChange: (open: boolean) => void
  onSave: (contents: CabinFurnitureRequirementInput[]) => void
}

function draftIdentity() {
  return crypto.randomUUID()
}

function initialDrafts(contents: CabinFurnitureRequirementInput[]) {
  return contents.map((item) => ({ ...item, key: draftIdentity() }))
}

function furnitureItems(items: EquipmentItemDto[]) {
  return items.filter((item) => item.active && item.category === "FURNITURE")
}

export function CabinFurnitureContents({
  cabin,
  disabled = false,
  furnitureIds,
  onManage,
}: {
  cabin: Pick<RentalItemDto, "contents" | "contentsItems">
  disabled?: boolean
  furnitureIds?: ReadonlySet<string>
  onManage: () => void
}) {
  const furnitureRows = cabinFurnitureRows(cabin, furnitureIds)
  const hasFurniture = cabinHasFurniture(cabin, furnitureIds)

  return (
    <FieldSet>
      <FieldLegend variant="label">Наполнение</FieldLegend>
      {furnitureRows.length ? (
        <div className="flex flex-col gap-1 text-sm">
          {furnitureRows.map((item) => (
            <div
              key={`${item.equipmentId ?? item.name}:${item.quantity}`}
              className="flex items-center justify-between gap-4"
            >
              <span>{item.equipmentName ?? item.name}</span>
              <span className="text-muted-foreground">{item.quantity} шт.</span>
            </div>
          ))}
        </div>
      ) : cabin.contents && !furnitureIds ? (
        <FieldDescription>{cabin.contents}</FieldDescription>
      ) : (
        <FieldDescription>В бытовке нет наполнения.</FieldDescription>
      )}
      <Button
        type="button"
        className="mt-3"
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={onManage}
      >
        {hasFurniture ? "Изменить наполнение" : "Добавить мебель"}
      </Button>
    </FieldSet>
  )
}

/**
 * A cabin is edited as one complete composition. Omitting a current position means that the
 * server must remove it; the component deliberately has no independent stock-write control.
 */
export function CabinFurnitureCompositionDialog({
  cabin,
  equipmentItems,
  initialContents,
  open,
  pending = false,
  error,
  schedule,
  onOpenChange,
  onSave,
}: CabinFurnitureCompositionDialogProps) {
  const [drafts, setDrafts] = useState<FurnitureDraft[]>(() =>
    initialDrafts(initialContents)
  )
  const [validationError, setValidationError] = useState<string | null>(null)
  const furniture = useMemo(
    () => furnitureItems(equipmentItems),
    [equipmentItems]
  )

  function updateDraft(
    key: string,
    update: Partial<Pick<FurnitureDraft, "equipmentId" | "quantity">>
  ) {
    setDrafts((current) =>
      current.map((item) => (item.key === key ? { ...item, ...update } : item))
    )
    setValidationError(null)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    event.stopPropagation()
    const selectedIds = new Set<string>()
    const contents: CabinFurnitureRequirementInput[] = []

    for (const draft of drafts) {
      const item = furniture.find(
        (candidate) => candidate.id === draft.equipmentId
      )
      if (
        !item ||
        !Number.isSafeInteger(draft.quantity) ||
        draft.quantity < 1 ||
        selectedIds.has(draft.equipmentId)
      ) {
        setValidationError(
          "Выберите уникальные позиции мебели и укажите целое количество."
        )
        return
      }
      selectedIds.add(draft.equipmentId)
      contents.push({
        equipmentId: draft.equipmentId,
        quantity: draft.quantity,
      })
    }

    if (schedule && !/^\d{4}-\d{2}-\d{2}$/.test(schedule.value)) {
      setValidationError("Укажите дату задания.")
      return
    }

    setValidationError(null)
    onSave(contents)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>
              Изменить наполнение бытовки {cabin.number}
            </DialogTitle>
            <DialogDescription>
              Укажите итоговый состав. Сервер сам сформирует задание: уберёт
              отсутствующие позиции и добавит недостающие из дополнительного
              оборудования.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            {schedule ? (
              <Field>
                <FieldLabel>Дата задания</FieldLabel>
                <Input
                  aria-label="Дата задания на изменение наполнения"
                  type="date"
                  required
                  disabled={pending}
                  value={schedule.value}
                  onChange={(event) => {
                    schedule.onChange(event.target.value)
                    setValidationError(null)
                  }}
                />
              </Field>
            ) : null}
            <FieldSet disabled={pending}>
              <FieldLegend variant="label">Итоговое наполнение</FieldLegend>
              <FieldDescription>
                Список и доступный остаток берутся из раздела «Дополнительное
                оборудование».
              </FieldDescription>
              <FieldGroup className="mt-3">
                {drafts.map((draft) => {
                  const selectableFurniture = furniture.filter(
                    (candidate) =>
                      candidate.id === draft.equipmentId ||
                      !drafts.some(
                        (other) =>
                          other.key !== draft.key &&
                          other.equipmentId === candidate.id
                      )
                  )
                  return (
                    <div
                      key={draft.key}
                      className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_8rem_auto]"
                    >
                      <Field>
                        <FieldLabel>Мебель</FieldLabel>
                        <Select
                          value={draft.equipmentId}
                          onValueChange={(equipmentId) =>
                            updateDraft(draft.key, { equipmentId })
                          }
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="Выберите позицию" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectGroup>
                              {selectableFurniture.map((item) => (
                                <SelectItem key={item.id} value={item.id}>
                                  {item.name} · доступно{" "}
                                  {item.availableQuantity}
                                </SelectItem>
                              ))}
                            </SelectGroup>
                          </SelectContent>
                        </Select>
                      </Field>
                      <Field>
                        <FieldLabel>Количество</FieldLabel>
                        <Input
                          aria-label={`Количество мебели ${draft.key}`}
                          type="number"
                          min={1}
                          step={1}
                          value={draft.quantity}
                          onChange={(event) =>
                            updateDraft(draft.key, {
                              quantity: Number(event.target.value),
                            })
                          }
                        />
                      </Field>
                      <Button
                        type="button"
                        className="self-end"
                        size="sm"
                        variant="outline"
                        onClick={() =>
                          setDrafts((current) =>
                            current.filter((item) => item.key !== draft.key)
                          )
                        }
                      >
                        <HugeiconsIcon
                          icon={Delete02Icon}
                          data-icon="inline-start"
                        />
                        Удалить
                      </Button>
                    </div>
                  )
                })}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={drafts.length >= 100 || furniture.length === 0}
                  onClick={() =>
                    setDrafts((current) => [
                      ...current,
                      { key: draftIdentity(), equipmentId: "", quantity: 1 },
                    ])
                  }
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить позицию
                </Button>
                {furniture.length === 0 ? (
                  <FieldDescription>
                    Доступной мебели на этом складе нет.
                  </FieldDescription>
                ) : null}
              </FieldGroup>
            </FieldSet>
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {error ? <FieldError>{error}</FieldError> : null}
          </FieldGroup>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Создаётся…" : "Сохранить наполнение"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
