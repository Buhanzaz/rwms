import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupText,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import {
  getRentalPricingSettings,
  cabinRentalPricesKey,
  equipmentRentalPricingKey,
  rentalPricingSettingsKey,
  updateRentalPrice,
  validMonthlyRentalPrice,
  type RentalPricingCategory,
  type RentalPricingType,
  type UpdateRentalPrice,
} from "@/features/assistant/api/rental-pricing-api"
import { ApiError } from "@/lib/api-client"

export function RentalPricesSettings({ accessToken }: { accessToken: string }) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: (params: UpdateRentalPrice) => updateRentalPrice(params),
    onMutate: () =>
      queryClient.cancelQueries({
        queryKey: rentalPricingSettingsKey,
        exact: true,
      }),
    onSuccess: async (settings) => {
      await queryClient.cancelQueries({
        queryKey: rentalPricingSettingsKey,
        exact: true,
      })
      queryClient.setQueryData(rentalPricingSettingsKey, settings)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: cabinRentalPricesKey }),
        queryClient.invalidateQueries({ queryKey: equipmentRentalPricingKey }),
      ])
      toast.success("Цена аренды сохранена.")
    },
    onError: (error) => toast.error(error.message),
  })
  const query = useQuery({
    queryKey: rentalPricingSettingsKey,
    queryFn: ({ signal }) => getRentalPricingSettings(accessToken, signal),
    enabled: Boolean(accessToken.trim()),
    refetchInterval: mutation.isPending ? false : 30_000,
    refetchOnWindowFocus: true,
  })
  const conflict =
    mutation.error instanceof ApiError && mutation.error.status === 409
  async function refresh() {
    const result = await query.refetch()
    if (!result.isError) mutation.reset()
  }

  return (
    <section
      aria-labelledby="rental-prices-title"
      className="flex flex-col gap-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 id="rental-prices-title" className="text-lg font-semibold">
            Цены аренды
          </h2>
          <p className="max-w-3xl text-sm text-muted-foreground">
            Цена за месяц для всех бытовок одного типа и категории. Список
            берётся из справочника бытовок; для новых сочетаний цена — 0 ₽.
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => void refresh()}
          disabled={
            query.isFetching || mutation.isPending || !accessToken.trim()
          }
        >
          {query.isFetching ? "Обновляем цены…" : "Обновить цены"}
        </Button>
      </div>
      {!accessToken.trim() ? (
        <Alert variant="destructive">
          <AlertTitle>Не получен токен доступа</AlertTitle>
          <AlertDescription>
            Войдите заново, чтобы загрузить цены аренды.
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {query.isError && (
            <Alert variant="destructive">
              <AlertTitle>Не удалось обновить цены аренды</AlertTitle>
              <AlertDescription>
                {query.error.message} Повторите обновление. Несохранённые
                значения остаются в полях.
              </AlertDescription>
            </Alert>
          )}
          {mutation.isError && (
            <Alert variant="destructive">
              <AlertTitle>
                {conflict ? "Цены изменились" : "Цена не сохранена"}
              </AlertTitle>
              <AlertDescription>
                {conflict
                  ? "Обновите цены и проверьте изменения перед сохранением. Ваш ввод сохранён."
                  : mutation.error.message}
              </AlertDescription>
            </Alert>
          )}
          {!query.data && !query.isError && (
            <div role="status" aria-label="Загружаем цены аренды">
              <Skeleton className="h-48 w-full" />
            </div>
          )}
          {query.data && (
            <div className="grid gap-4 xl:grid-cols-2">
              {query.data.types.map((type) => (
                <Card key={type.rentalTypeId} size="sm">
                  <CardHeader>
                    <CardTitle>{type.name}</CardTitle>
                    <CardDescription>
                      {type.active
                        ? "Ежемесячная аренда по категориям"
                        : "Тип неактивен в справочнике"}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <FieldGroup>
                      {type.categories.map((category) => (
                        <RentalPriceRow
                          key={category.categoryId}
                          type={type}
                          category={category}
                          disabled={
                            mutation.isPending || query.isError || conflict
                          }
                          onSave={(price) =>
                            mutation.mutateAsync({
                              accessToken,
                              rentalTypeId: type.rentalTypeId,
                              categoryId: category.categoryId,
                              expectedVersion: query.data!.version,
                              monthlyPriceRubles: price,
                            })
                          }
                        />
                      ))}
                      {type.categories.length === 0 && (
                        <FieldDescription>
                          Добавьте категории в справочник бытовок — строки цен
                          появятся автоматически.
                        </FieldDescription>
                      )}
                    </FieldGroup>
                  </CardContent>
                </Card>
              ))}
              {query.data.types.length === 0 && (
                <Alert>
                  <AlertTitle>В справочнике нет типов бытовок</AlertTitle>
                  <AlertDescription>
                    Добавьте типы в настройках бытовок. Здесь они появятся
                    автоматически, с ценой 0 ₽ для каждой категории.
                  </AlertDescription>
                </Alert>
              )}
            </div>
          )}
        </>
      )}
    </section>
  )
}

function RentalPriceRow({
  type,
  category,
  disabled,
  onSave,
}: {
  type: RentalPricingType
  category: RentalPricingCategory
  disabled: boolean
  onSave: (price: UpdateRentalPrice["monthlyPriceRubles"]) => Promise<unknown>
}) {
  const [draft, setDraft] = useState<{ value: string; originalPrice: string }>()
  const [error, setError] = useState<string>()
  const [saving, setSaving] = useState(false)
  const id = `rental-price-${type.rentalTypeId}-${category.categoryId}`
  const value = draft?.value ?? category.monthlyPriceRubles
  const changedElsewhere = Boolean(
    draft && draft.originalPrice !== category.monthlyPriceRubles
  )
  const dirty = value !== category.monthlyPriceRubles
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (disabled || saving || changedElsewhere || !dirty) return
    if (!validMonthlyRentalPrice(value)) {
      setError(
        "Укажите целую сумму от 0 до 9223372036854775807 ₽. Пустое поле не означает 0 ₽."
      )
      document.getElementById(id)?.focus()
      return
    }
    setSaving(true)
    try {
      await onSave(value)
      setDraft(undefined)
      setError(undefined)
    } catch {
      // The parent surfaces the server error; never erase the failed draft.
    } finally {
      setSaving(false)
    }
  }
  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <Field data-invalid={Boolean(error)} data-disabled={disabled || saving}>
        <FieldLabel htmlFor={id}>
          <span className="sr-only">{type.name} —</span> {category.name}{" "}
          {!category.active && <Badge variant="secondary">Неактивна</Badge>}
        </FieldLabel>
        <div className="flex flex-wrap items-center gap-2">
          <InputGroup className="min-w-40 flex-1">
            <InputGroupInput
              id={id}
              inputMode="numeric"
              autoComplete="off"
              value={value}
              disabled={disabled || saving}
              aria-invalid={Boolean(error)}
              aria-describedby={
                [error && `${id}-error`, changedElsewhere && `${id}-conflict`]
                  .filter(Boolean)
                  .join(" ") || undefined
              }
              onChange={(event) => {
                setDraft({
                  value: event.target.value,
                  originalPrice:
                    draft?.originalPrice ?? category.monthlyPriceRubles,
                })
                setError(undefined)
              }}
            />
            <InputGroupAddon align="inline-end">
              <InputGroupText>₽/мес.</InputGroupText>
            </InputGroupAddon>
          </InputGroup>
          <Button
            type="submit"
            variant="outline"
            size="sm"
            disabled={disabled || saving || changedElsewhere || !dirty}
            aria-label={`Сохранить цену: ${type.name} — ${category.name}`}
          >
            {saving ? "Сохраняем…" : "Сохранить цену"}
          </Button>
        </div>
        {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
        {changedElsewhere && (
          <FieldDescription id={`${id}-conflict`}>
            Эта цена уже изменена: сейчас {category.monthlyPriceRubles} ₽/мес.
            Ваш ввод не сохранён.
          </FieldDescription>
        )}
        {draft && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="self-start"
            disabled={disabled || saving}
            aria-label={`Сбросить правку: ${type.name} — ${category.name}`}
            onClick={() => {
              setDraft(undefined)
              setError(undefined)
            }}
          >
            {changedElsewhere
              ? "Использовать актуальную цену"
              : "Сбросить правку"}
          </Button>
        )}
      </Field>
    </form>
  )
}
