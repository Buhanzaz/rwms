import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  getRentalSettings,
  updateRentalSettings,
  validRentalFee,
  type RentalSettings,
} from "@/features/assistant/api/rental-presentations-api"
import { useAuth } from "@/features/auth/use-auth"
import { RentalPricesSettings } from "@/features/assistant/components/rental-prices-settings"
import { ApiError } from "@/lib/api-client"

const queryKey = ["rental-settings"] as const
const holdFields = [
  {
    name: "chatSelectionHoldMinutes",
    label: "Удержание бытовок в чате",
    min: 1,
    max: 1440,
    description: "От 1 минуты до 24 часов. По умолчанию — 10 минут.",
  },
  {
    name: "manualBookingHoldMinutes",
    label: "Удержание в ручном бронировании",
    min: 5,
    max: 1440,
    description:
      "После кнопки «Продолжить бронирование». От 5 минут до 24 часов, по умолчанию — 60 минут.",
  },
  {
    name: "draftReservationHoldMinutes",
    label: "Резерв бытовок в черновике бронирования",
    min: 1440,
    max: 14400,
    description:
      "От 1 до 10 дней (1440–14400 минут). По умолчанию — 1 день. После истечения бытовка снова доступна.",
  },
  {
    name: "presentationHoldMinutes",
    label: "Удержание в представлении для клиента",
    min: 5,
    max: 1440,
    description:
      "От 5 минут до 24 часов, по умолчанию — 60 минут. Затем ссылка ещё 24 часа доступна только для просмотра.",
  },
] as const
type HoldName = (typeof holdFields)[number]["name"]
type Draft = Record<HoldName, string> & {
  lateChangeNoticeDays: string
  lateChangeFeeMode: "UNCONFIGURED" | "FIXED" | "PERCENT"
  lateChangeFeeValue: string
  rentalSupportPhone: string
}

function draftFrom(settings: RentalSettings): Draft {
  return {
    chatSelectionHoldMinutes: String(settings.chatSelectionHoldMinutes),
    manualBookingHoldMinutes: String(settings.manualBookingHoldMinutes),
    draftReservationHoldMinutes: String(settings.draftReservationHoldMinutes),
    presentationHoldMinutes: String(settings.presentationHoldMinutes),
    lateChangeNoticeDays: String(settings.lateChangeNoticeDays),
    lateChangeFeeMode: settings.lateChangeFeeMode ?? "UNCONFIGURED",
    lateChangeFeeValue: settings.lateChangeFeeValue ?? "",
    rentalSupportPhone: settings.rentalSupportPhone ?? "",
  }
}

export function RentalSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const allowed = currentUser?.globalRole === "SYSTEM_ADMIN"
  const settings = useQuery({
    queryKey,
    queryFn: () => getRentalSettings(accessToken!),
    enabled: Boolean(accessToken && allowed),
  })
  if (!allowed)
    return (
      <p className="text-sm text-muted-foreground">
        Настройка доступна системному администратору.
      </p>
    )
  if (!accessToken?.trim())
    return <p role="alert">Не получен токен доступа. Войдите заново.</p>
  return (
    <div className="flex flex-col gap-6">
      <RentalPricesSettings accessToken={accessToken} />
      {settings.data ? (
        <RentalSettingsForm initial={settings.data} accessToken={accessToken} />
      ) : settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить настройки</AlertTitle>
          <AlertDescription>
            {settings.error.message}
            <Button variant="outline" onClick={() => void settings.refetch()}>
              Повторить
            </Button>
          </AlertDescription>
        </Alert>
      ) : (
        <p role="status" className="text-sm text-muted-foreground">
          Загружаем настройки…
        </p>
      )}
    </div>
  )
}

function RentalSettingsForm({
  initial,
  accessToken,
}: {
  initial: RentalSettings
  accessToken: string
}) {
  const queryClient = useQueryClient()
  const [version, setVersion] = useState(initial.version)
  const [draft, setDraft] = useState(() => draftFrom(initial))
  const [errors, setErrors] = useState<Partial<Record<keyof Draft, string>>>({})
  const [reloading, setReloading] = useState(false)
  function adopt(settings: RentalSettings) {
    queryClient.setQueryData(queryKey, settings)
    setVersion(settings.version)
    setDraft(draftFrom(settings))
    setErrors({})
  }
  const mutation = useMutation({
    mutationFn: (params: Parameters<typeof updateRentalSettings>[0]) =>
      updateRentalSettings(params),
    onSuccess: (settings) => {
      adopt(settings)
      toast.success("Настройки аренды сохранены.")
    },
    onError: (error) => toast.error(error.message),
  })
  const pending = mutation.isPending || reloading
  const conflict =
    mutation.error instanceof ApiError && mutation.error.status === 409
  function change<K extends keyof Draft>(name: K, value: Draft[K]) {
    setDraft((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: undefined }))
  }
  async function reload() {
    setReloading(true)
    try {
      adopt(await getRentalSettings(accessToken))
      mutation.reset()
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось обновить настройки."
      )
    } finally {
      setReloading(false)
    }
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const invalid: Partial<Record<keyof Draft, string>> = {}
    for (const field of holdFields) {
      const value = Number(draft[field.name])
      if (
        !/^\d+$/.test(draft[field.name]) ||
        !Number.isInteger(value) ||
        value < field.min ||
        value > field.max
      )
        invalid[field.name] =
          `Укажите целое число от ${field.min} до ${field.max} минут.`
    }
    const notice = Number(draft.lateChangeNoticeDays)
    if (
      !/^\d+$/.test(draft.lateChangeNoticeDays) ||
      !Number.isInteger(notice) ||
      notice < 0 ||
      notice > 2147483647
    )
      invalid.lateChangeNoticeDays =
        "Укажите целое число календарных дней от 0 до 2147483647."
    const mode =
      draft.lateChangeFeeMode === "UNCONFIGURED"
        ? null
        : draft.lateChangeFeeMode
    const value =
      mode === null ? null : draft.lateChangeFeeValue.trim().replace(",", ".")
    if (!validRentalFee(mode, value))
      invalid.lateChangeFeeValue =
        mode === "FIXED"
          ? "Укажите целую сумму от 0 до 9223372036854775807 ₽. Пустое поле не означает нулевую плату."
          : "Укажите процент от 0 до 100, не более двух знаков после запятой."
    const phone = draft.rentalSupportPhone.trim() || null
    if (phone !== null && !/^\+[1-9][0-9]{7,14}$/.test(phone))
      invalid.rentalSupportPhone =
        "Укажите международный номер: + и от 8 до 15 цифр, без пробелов."
    setErrors(invalid)
    const firstError = Object.keys(invalid)[0]
    if (firstError) {
      document.getElementById(firstError)?.focus()
      return
    }
    mutation.mutate({
      accessToken,
      expectedVersion: version,
      chatSelectionHoldMinutes: Number(draft.chatSelectionHoldMinutes),
      manualBookingHoldMinutes: Number(draft.manualBookingHoldMinutes),
      presentationHoldMinutes: Number(draft.presentationHoldMinutes),
      draftReservationHoldMinutes: Number(draft.draftReservationHoldMinutes),
      lateChangeNoticeDays: notice,
      lateChangeFeeMode: mode,
      lateChangeFeeValue: value,
      rentalSupportPhone: phone,
    })
  }
  return (
    <form onSubmit={submit} noValidate className="flex w-full flex-col gap-5">
      <FieldSet disabled={pending} className="grid gap-5 xl:grid-cols-2">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Перенос и отмена</CardTitle>
            <CardDescription>
              Срок предупреждения и правило неустойки для поздних изменений.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <FieldGroup>
              <Field data-invalid={Boolean(errors.lateChangeNoticeDays)}>
                <FieldLabel htmlFor="lateChangeNoticeDays">
                  Предупреждение, календарные дни
                </FieldLabel>
                <Input
                  id="lateChangeNoticeDays"
                  inputMode="numeric"
                  autoComplete="off"
                  value={draft.lateChangeNoticeDays}
                  onChange={(event) =>
                    change("lateChangeNoticeDays", event.target.value)
                  }
                  aria-invalid={Boolean(errors.lateChangeNoticeDays)}
                  aria-describedby="notice-description notice-error"
                />
                <FieldDescription id="notice-description">
                  В часовом поясе склада, не интервалами по 24 часа. По
                  умолчанию — 2 дня.
                </FieldDescription>
                {errors.lateChangeNoticeDays && (
                  <FieldError id="notice-error">
                    {errors.lateChangeNoticeDays}
                  </FieldError>
                )}
              </Field>
              <Field>
                <FieldLabel id="fee-mode-label">
                  Неустойка за позднее изменение
                </FieldLabel>
                <ToggleGroup
                  type="single"
                  value={draft.lateChangeFeeMode}
                  onValueChange={(value) => {
                    if (value)
                      change(
                        "lateChangeFeeMode",
                        value as Draft["lateChangeFeeMode"]
                      )
                  }}
                  aria-labelledby="fee-mode-label"
                  variant="outline"
                  className="flex-wrap"
                >
                  <ToggleGroupItem value="UNCONFIGURED">
                    Не настроена
                  </ToggleGroupItem>
                  <ToggleGroupItem value="FIXED">Сумма, ₽</ToggleGroupItem>
                  <ToggleGroupItem value="PERCENT">Процент, %</ToggleGroupItem>
                </ToggleGroup>
                <FieldDescription>
                  «Не настроена» не означает бесплатное изменение. Нулевая плата
                  задаётся явно выбранным правилом и значением 0.
                </FieldDescription>
              </Field>
              {draft.lateChangeFeeMode !== "UNCONFIGURED" && (
                <Field data-invalid={Boolean(errors.lateChangeFeeValue)}>
                  <FieldLabel htmlFor="lateChangeFeeValue">
                    {draft.lateChangeFeeMode === "FIXED"
                      ? "Сумма неустойки, ₽"
                      : "Размер неустойки, %"}
                  </FieldLabel>
                  <Input
                    id="lateChangeFeeValue"
                    inputMode={
                      draft.lateChangeFeeMode === "FIXED"
                        ? "numeric"
                        : "decimal"
                    }
                    autoComplete="off"
                    value={draft.lateChangeFeeValue}
                    onChange={(event) =>
                      change("lateChangeFeeValue", event.target.value)
                    }
                    aria-invalid={Boolean(errors.lateChangeFeeValue)}
                    aria-describedby="fee-description fee-error"
                  />
                  <FieldDescription id="fee-description">
                    {draft.lateChangeFeeMode === "FIXED"
                      ? "Целые рубли, без копеек."
                      : "От 0 до 100%, не более двух знаков после запятой."}
                  </FieldDescription>
                  {errors.lateChangeFeeValue && (
                    <FieldError id="fee-error">
                      {errors.lateChangeFeeValue}
                    </FieldError>
                  )}
                </Field>
              )}
              <Field data-invalid={Boolean(errors.rentalSupportPhone)}>
                <FieldLabel htmlFor="rentalSupportPhone">
                  Телефон поддержки аренды
                </FieldLabel>
                <Input
                  id="rentalSupportPhone"
                  type="tel"
                  autoComplete="tel"
                  placeholder="+74951234567"
                  value={draft.rentalSupportPhone}
                  onChange={(event) =>
                    change("rentalSupportPhone", event.target.value)
                  }
                  aria-invalid={Boolean(errors.rentalSupportPhone)}
                  aria-describedby="phone-description phone-error"
                />
                <FieldDescription id="phone-description">
                  Реальный номер компании для звонка из приложения, не телефон
                  клиента. Пустое поле — номер не указан.
                </FieldDescription>
                {errors.rentalSupportPhone && (
                  <FieldError id="phone-error">
                    {errors.rentalSupportPhone}
                  </FieldError>
                )}
              </Field>
            </FieldGroup>
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Временное удержание бытовок</CardTitle>
            <CardDescription>
              Отдельные сроки для чата, ручного бронирования и клиентского
              представления.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <FieldGroup>
              {holdFields.map((field) => (
                <Field
                  key={field.name}
                  data-invalid={Boolean(errors[field.name])}
                >
                  <FieldLabel htmlFor={field.name}>{field.label}</FieldLabel>
                  <Input
                    id={field.name}
                    inputMode="numeric"
                    autoComplete="off"
                    value={draft[field.name]}
                    onChange={(event) => change(field.name, event.target.value)}
                    aria-invalid={Boolean(errors[field.name])}
                    aria-describedby={`${field.name}-description ${field.name}-error`}
                  />
                  <FieldDescription id={`${field.name}-description`}>
                    {field.description}
                  </FieldDescription>
                  {errors[field.name] && (
                    <FieldError id={`${field.name}-error`}>
                      {errors[field.name]}
                    </FieldError>
                  )}
                </Field>
              ))}
            </FieldGroup>
          </CardContent>
        </Card>
      </FieldSet>
      {mutation.isError && (
        <Alert variant="destructive">
          <AlertTitle>
            {conflict ? "Настройки уже изменены" : "Не удалось сохранить"}
          </AlertTitle>
          <AlertDescription>
            {conflict
              ? "Ваши правки сохранены в форме. Загрузите актуальные настройки перед повторным редактированием."
              : mutation.error.message}
            {conflict && (
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={() => void reload()}
              >
                Загрузить актуальные и сбросить правки
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}
      <div className="flex justify-end">
        <Button type="submit" disabled={pending}>
          {pending && (
            <HugeiconsIcon
              icon={Loading03Icon}
              data-icon="inline-start"
              className="animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
          )}
          Сохранить
        </Button>
      </div>
    </form>
  )
}
