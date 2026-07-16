import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

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
import {
  Field,
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
import {
  INVENTORY_QUERY_KEY,
  addInventoryRentalItem,
  createInventoryFindingId,
  resolveInventoryNumber,
} from "@/features/inventory/api/inventory-api"
import type {
  InventoryActorSnapshot,
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"
import {
  DEFAULT_RENTAL_ITEM_CHARACTERISTICS,
  INVENTORY_RENTAL_ITEM_CATEGORIES,
  RENTAL_ITEM_FINISHING_OPTIONS,
  RENTAL_ITEM_TYPE_OPTIONS,
  getDefaultRentalItemDimensions,
  getDefaultRentalItemFinishing,
  getRentalItemDimensionsForType,
  type RentalItemCreationType,
  type RentalItemFinishing,
} from "@/features/rental-items/model/rental-item-create"

type CreateCondition = "NEW" | "USED"

type CreateForm = {
  condition: CreateCondition
  category: (typeof INVENTORY_RENTAL_ITEM_CATEGORIES)[number]
  type: RentalItemCreationType | ""
  dimensions: string
  finishing: RentalItemFinishing | ""
  linoleum: boolean
}

function emptyCreateForm(condition: CreateCondition): CreateForm {
  return {
    condition,
    category: condition === "NEW" ? "Новая" : "Обычная",
    type: "",
    dimensions: "",
    finishing: "",
    linoleum: false,
  }
}

export function InventoryAddDialog({
  open,
  session,
  actor,
  onOpenChange,
  onResolved,
}: {
  open: boolean
  session: InventorySessionDto
  actor: InventoryActorSnapshot
  onOpenChange: (open: boolean) => void
  onResolved: (
    session: InventorySessionDto,
    finding: InventoryFindingDto
  ) => void
}) {
  const queryClient = useQueryClient()
  const [number, setNumber] = useState("")
  const [notFoundNumber, setNotFoundNumber] = useState<string | null>(null)
  const [findingId, setFindingId] = useState<string | null>(null)
  const [form, setForm] = useState<CreateForm | null>(null)
  const [submitted, setSubmitted] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const dimensions = useMemo(
    () => getRentalItemDimensionsForType(form?.type ?? ""),
    [form?.type]
  )

  function reset() {
    setNumber("")
    setNotFoundNumber(null)
    setFindingId(null)
    setForm(null)
    setSubmitted(false)
    setMessage(null)
  }

  const resolveMutation = useMutation({
    mutationFn: () =>
      resolveInventoryNumber({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor,
        number,
      }),
    onSuccess: (resolution) => {
      if (
        resolution.kind === "EXISTING_FINDING" ||
        resolution.kind === "OPEN_INSPECTION"
      ) {
        void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
        reset()
        onOpenChange(false)
        onResolved(resolution.session, resolution.finding)
        return
      }
      if (resolution.kind === "NOT_FOUND") {
        setNotFoundNumber(resolution.canonicalNumber)
        setFindingId(createInventoryFindingId())
        setMessage(null)
        return
      }
      if (resolution.kind === "CONFLICT") {
        setMessage(
          resolution.conflict === "RENTAL_ITEM_MISSING"
            ? `Бытовка ${resolution.finding.cabinNumber} отсутствует в актуальном реестре.`
            : resolution.conflict === "OTHER_WAREHOUSE"
              ? `Бытовка ${resolution.item?.number ?? resolution.finding.cabinNumber} относится к другому складу.`
              : `Бытовка ${resolution.item?.number ?? resolution.finding.cabinNumber} списана и не может быть восстановлена.`
        )
      }
    },
  })

  const createMutation = useMutation({
    mutationFn: () => {
      if (
        !form ||
        !notFoundNumber ||
        !findingId ||
        !form.type ||
        !form.finishing
      ) {
        throw new Error("Заполните паспортные данные")
      }
      return addInventoryRentalItem({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor,
        findingId,
        condition: form.condition,
        rentalItem: {
          number: notFoundNumber,
          type: form.type,
          dimensions: form.dimensions,
          finishing: form.finishing,
          category: form.condition === "NEW" ? "Новая" : form.category,
          characteristics: [...DEFAULT_RENTAL_ITEM_CHARACTERISTICS],
          linoleum: form.linoleum,
        },
      })
    },
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      reset()
      onOpenChange(false)
      onResolved(result.session, result.finding)
    },
  })

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    if (!form?.type || !form.dimensions || !form.finishing) return
    createMutation.mutate()
  }

  const pending = resolveMutation.isPending || createMutation.isPending
  const error =
    (resolveMutation.error instanceof Error && resolveMutation.error.message) ||
    (createMutation.error instanceof Error && createMutation.error.message) ||
    message

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !pending) reset()
        onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Добавить бытовку</DialogTitle>
          <DialogDescription>
            Введите точный номер. Поиск повторно проверит весь реестр.
          </DialogDescription>
        </DialogHeader>

        {form ? (
          <form className="flex flex-col gap-4" onSubmit={submitCreate}>
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="inventory-new-number">Номер</FieldLabel>
                <Input
                  id="inventory-new-number"
                  value={notFoundNumber ?? ""}
                  readOnly
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="inventory-new-category">
                  Категория
                </FieldLabel>
                <Select
                  value={form.condition === "NEW" ? "Новая" : form.category}
                  disabled={form.condition === "NEW"}
                  onValueChange={(value) =>
                    setForm((current) =>
                      current
                        ? {
                            ...current,
                            category: value as CreateForm["category"],
                          }
                        : current
                    )
                  }
                >
                  <SelectTrigger id="inventory-new-category" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {INVENTORY_RENTAL_ITEM_CATEGORIES.map((category) => (
                        <SelectItem key={category} value={category}>
                          {category}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>
              <Field data-invalid={submitted && !form.type}>
                <FieldLabel htmlFor="inventory-new-type">Тип</FieldLabel>
                <Select
                  value={form.type}
                  onValueChange={(value) => {
                    const type = value as RentalItemCreationType
                    setForm((current) =>
                      current
                        ? {
                            ...current,
                            type,
                            dimensions: getDefaultRentalItemDimensions(type),
                            finishing: getDefaultRentalItemFinishing(
                              type,
                              current.finishing
                            ),
                          }
                        : current
                    )
                  }}
                >
                  <SelectTrigger
                    id="inventory-new-type"
                    className="w-full"
                    aria-invalid={submitted && !form.type}
                  >
                    <SelectValue placeholder="Выберите тип" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {RENTAL_ITEM_TYPE_OPTIONS.map((type) => (
                        <SelectItem key={type} value={type}>
                          {type}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
                {submitted && !form.type ? (
                  <FieldError>Выберите тип.</FieldError>
                ) : null}
              </Field>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field data-invalid={submitted && !form.dimensions}>
                  <FieldLabel htmlFor="inventory-new-dimensions">
                    Габариты
                  </FieldLabel>
                  <Select
                    disabled={!form.type}
                    value={form.dimensions}
                    onValueChange={(value) =>
                      setForm((current) =>
                        current ? { ...current, dimensions: value } : current
                      )
                    }
                  >
                    <SelectTrigger
                      id="inventory-new-dimensions"
                      className="w-full"
                    >
                      <SelectValue placeholder="Выберите" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {dimensions.map((item) => (
                          <SelectItem key={item} value={item}>
                            {item}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field data-invalid={submitted && !form.finishing}>
                  <FieldLabel htmlFor="inventory-new-finishing">
                    Отделка
                  </FieldLabel>
                  <Select
                    value={form.finishing}
                    onValueChange={(value) =>
                      setForm((current) =>
                        current
                          ? {
                              ...current,
                              finishing: value as RentalItemFinishing,
                            }
                          : current
                      )
                    }
                  >
                    <SelectTrigger
                      id="inventory-new-finishing"
                      className="w-full"
                    >
                      <SelectValue placeholder="Выберите" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {RENTAL_ITEM_FINISHING_OPTIONS.map((item) => (
                          <SelectItem key={item} value={item}>
                            {item}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
              </div>
              <Field orientation="horizontal">
                <Checkbox
                  id="inventory-new-linoleum"
                  checked={form.linoleum}
                  onCheckedChange={(checked) =>
                    setForm((current) =>
                      current
                        ? { ...current, linoleum: checked === true }
                        : current
                    )
                  }
                />
                <FieldLabel
                  htmlFor="inventory-new-linoleum"
                  className="font-normal"
                >
                  Линолеум
                </FieldLabel>
              </Field>
            </FieldGroup>
            {error ? (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            ) : null}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={() => setForm(null)}
              >
                Назад
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? "Создаём..." : "Создать и осмотреть"}
              </Button>
            </DialogFooter>
          </form>
        ) : notFoundNumber ? (
          <div className="flex flex-col gap-4">
            <p>
              Номер <strong>{notFoundNumber}</strong> не найден. Какую бытовку
              добавить?
            </p>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setForm(emptyCreateForm("USED"))}
              >
                Добавить б/у
              </Button>
              <Button
                type="button"
                onClick={() => setForm(emptyCreateForm("NEW"))}
              >
                Добавить новую
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <Field>
              <FieldLabel htmlFor="inventory-number-search">
                Номер бытовки
              </FieldLabel>
              <Input
                id="inventory-number-search"
                autoFocus
                value={number}
                onChange={(event) => setNumber(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && number.trim())
                    resolveMutation.mutate()
                }}
              />
            </Field>
            {error ? (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            ) : null}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={() => onOpenChange(false)}
              >
                Отмена
              </Button>
              <Button
                type="button"
                disabled={pending || !number.trim()}
                onClick={() => resolveMutation.mutate()}
              >
                {pending ? "Проверяем..." : "Найти"}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
