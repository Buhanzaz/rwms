import { useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Delete02Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"

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
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Checkbox } from "@/components/ui/checkbox"
import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"

export type LogisticsFurnitureCatalogItem = {
  name: string
  availableQuantity: number
}

export function LogisticsCabinContentsEditor({
  contents,
  catalog,
  frozen,
  mode,
  onUpdate,
}: {
  contents: RentalItemContentsItemDto[]
  catalog: LogisticsFurnitureCatalogItem[]
  frozen: boolean
  mode: "shipment" | "return"
  onUpdate: (
    updater: (items: RentalItemContentsItemDto[]) => RentalItemContentsItemDto[]
  ) => void
}) {
  const [pickerOpen, setPickerOpen] = useState(false)
  const heading =
    mode === "shipment" ? "Планируемое наполнение" : "Фактическое наполнение"

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <strong>{heading}</strong>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={frozen}
          onClick={() => setPickerOpen(true)}
        >
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          {contents.length === 0 ? "Добавить мебель" : "Добавить позицию"}
        </Button>
      </div>

      {contents.length ? (
        <div className="flex flex-col gap-2">
          {contents.map((content) => (
            <div
              key={content.name}
              className="flex flex-wrap items-center gap-2 rounded-lg border p-2"
            >
              <span className="min-w-40 flex-1">{content.name}</span>
              <Button
                type="button"
                size="icon-sm"
                variant="outline"
                disabled={frozen}
                aria-label={`Уменьшить ${content.name}`}
                onClick={() =>
                  onUpdate((items) =>
                    items
                      .map((item) =>
                        item.name === content.name
                          ? { ...item, quantity: item.quantity - 1 }
                          : item
                      )
                      .filter((item) => item.quantity > 0)
                  )
                }
              >
                <HugeiconsIcon icon={MinusSignIcon} />
              </Button>
              <span aria-label={`Количество ${content.name}`}>
                {content.quantity}
              </span>
              <Button
                type="button"
                size="icon-sm"
                variant="outline"
                disabled={frozen}
                aria-label={`Увеличить ${content.name}`}
                onClick={() =>
                  onUpdate((items) =>
                    items.map((item) =>
                      item.name === content.name
                        ? { ...item, quantity: item.quantity + 1 }
                        : item
                    )
                  )
                }
              >
                <HugeiconsIcon icon={Add01Icon} />
              </Button>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                disabled={frozen}
                aria-label={`Убрать ${content.name}`}
                onClick={() =>
                  onUpdate((items) =>
                    items.filter((item) => item.name !== content.name)
                  )
                }
              >
                <HugeiconsIcon icon={Delete02Icon} />
              </Button>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Наполнение не выбрано</p>
      )}

      <FurniturePickerDialog
        open={pickerOpen}
        mode={mode}
        catalog={catalog}
        onOpenChange={setPickerOpen}
        onAdd={(additions) => {
          onUpdate((items) => {
            const next = items.map((item) => ({ ...item }))
            additions.forEach((addition) => {
              const existing = next.find(
                (item) =>
                  item.name.toLocaleLowerCase("ru") ===
                  addition.name.toLocaleLowerCase("ru")
              )
              if (existing) existing.quantity += addition.quantity
              else next.push({ ...addition })
            })
            return next
          })
          setPickerOpen(false)
        }}
      />
    </div>
  )
}

function FurniturePickerDialog({
  open,
  mode,
  catalog,
  onOpenChange,
  onAdd,
}: {
  open: boolean
  mode: "shipment" | "return"
  catalog: LogisticsFurnitureCatalogItem[]
  onOpenChange: (open: boolean) => void
  onAdd: (items: RentalItemContentsItemDto[]) => void
}) {
  const [quantities, setQuantities] = useState<Record<string, number>>({})
  const selected = Object.entries(quantities).filter(
    ([, quantity]) => quantity > 0
  )
  const isShipment = mode === "shipment"

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Добавить мебель и оборудование</DialogTitle>
          <DialogDescription>
            {isShipment
              ? "Выберите доступные позиции и укажите планируемое количество."
              : "Укажите позиции, фактически обнаруженные в вернувшейся бытовке."}
          </DialogDescription>
        </DialogHeader>
        <FieldSet>
          <FieldLegend variant="label">Доступные позиции</FieldLegend>
          <FieldGroup>
            {catalog.map((item) => {
              const quantity = quantities[item.name] ?? 0
              const checked = quantity > 0
              const unavailable = isShipment && item.availableQuantity <= 0
              const maximum = isShipment
                ? item.availableQuantity
                : Number.MAX_SAFE_INTEGER

              return (
                <Field key={item.name} orientation="horizontal">
                  <Checkbox
                    id={`logistics-furniture-${mode}-${item.name}`}
                    checked={checked}
                    disabled={unavailable}
                    onCheckedChange={(next) =>
                      setQuantities((current) => ({
                        ...current,
                        [item.name]: next === true && !unavailable ? 1 : 0,
                      }))
                    }
                  />
                  <FieldContent className="min-w-0">
                    <FieldLabel
                      htmlFor={`logistics-furniture-${mode}-${item.name}`}
                      className="truncate"
                    >
                      {item.name}
                    </FieldLabel>
                    <FieldDescription>
                      {isShipment
                        ? `Доступно: ${item.availableQuantity}`
                        : `Учтено в реестре: ${item.availableQuantity}`}
                    </FieldDescription>
                  </FieldContent>
                  <div className="flex items-center gap-2">
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="outline"
                      disabled={!checked || quantity <= 0}
                      aria-label={`Уменьшить ${item.name}`}
                      onClick={() =>
                        setQuantities((current) => ({
                          ...current,
                          [item.name]: Math.max(0, quantity - 1),
                        }))
                      }
                    >
                      <HugeiconsIcon icon={MinusSignIcon} />
                    </Button>
                    <span>{checked ? quantity : 0}</span>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="outline"
                      disabled={unavailable || quantity >= maximum}
                      aria-label={`Увеличить ${item.name}`}
                      onClick={() =>
                        setQuantities((current) => ({
                          ...current,
                          [item.name]: Math.min(maximum, quantity + 1),
                        }))
                      }
                    >
                      <HugeiconsIcon icon={Add01Icon} />
                    </Button>
                  </div>
                </Field>
              )
            })}
          </FieldGroup>
        </FieldSet>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button
            disabled={selected.length === 0}
            onClick={() =>
              onAdd(selected.map(([name, quantity]) => ({ name, quantity })))
            }
          >
            Добавить
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
