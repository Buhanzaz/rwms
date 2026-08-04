import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Clock01Icon, Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

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
import { Input } from "@/components/ui/input"
import {
  getRentalSettings,
  updateRentalSettings,
} from "@/features/assistant/api/rental-presentations-api"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"

const RENTAL_SETTINGS_QUERY_KEY = ["rental-settings"] as const

export function RentalSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const settingsQuery = useQuery({
    queryKey: RENTAL_SETTINGS_QUERY_KEY,
    queryFn: () => getRentalSettings(accessToken!),
    enabled: Boolean(
      accessToken &&
      currentUser &&
      isGlobalAdministrator(currentUser.globalRole)
    ),
  })
  const [editedChatSelectionHoldMinutes, setEditedChatSelectionHoldMinutes] =
    useState<string | null>(null)
  const [editedManualBookingHoldMinutes, setEditedManualBookingHoldMinutes] =
    useState<string | null>(null)
  const [editedPresentationHoldMinutes, setEditedPresentationHoldMinutes] =
    useState<string | null>(null)
  const [
    editedDraftReservationHoldMinutes,
    setEditedDraftReservationHoldMinutes,
  ] = useState<string | null>(null)
  const chatSelectionHoldMinutes =
    editedChatSelectionHoldMinutes ??
    String(settingsQuery.data?.chatSelectionHoldMinutes ?? 10)
  const presentationHoldMinutes =
    editedPresentationHoldMinutes ??
    String(settingsQuery.data?.presentationHoldMinutes ?? 60)
  const manualBookingHoldMinutes =
    editedManualBookingHoldMinutes ??
    String(settingsQuery.data?.manualBookingHoldMinutes ?? 60)
  const draftReservationHoldMinutes =
    editedDraftReservationHoldMinutes ??
    String(settingsQuery.data?.draftReservationHoldMinutes ?? 1_440)

  const mutation = useMutation({
    mutationFn: () => {
      if (!settingsQuery.data) throw new Error("Настройки ещё не загружены.")
      const parsedChatSelectionHoldMinutes = Number(chatSelectionHoldMinutes)
      if (
        !Number.isInteger(parsedChatSelectionHoldMinutes) ||
        parsedChatSelectionHoldMinutes < 1 ||
        parsedChatSelectionHoldMinutes > 1_440
      ) {
        throw new Error(
          "Укажите для удержания бытовок в чате целое число от 1 до 1440 минут."
        )
      }
      const parsedPresentationHoldMinutes = Number(presentationHoldMinutes)
      const parsedManualBookingHoldMinutes = Number(manualBookingHoldMinutes)
      if (
        !Number.isInteger(parsedManualBookingHoldMinutes) ||
        parsedManualBookingHoldMinutes < 5 ||
        parsedManualBookingHoldMinutes > 1_440
      ) {
        throw new Error(
          "Укажите для ручного бронирования целое число от 5 до 1440 минут."
        )
      }
      if (
        !Number.isInteger(parsedPresentationHoldMinutes) ||
        parsedPresentationHoldMinutes < 5 ||
        parsedPresentationHoldMinutes > 1_440
      ) {
        throw new Error(
          "Укажите для удержания в представлении целое число от 5 до 1440 минут."
        )
      }
      const parsedDraftReservationHoldMinutes = Number(
        draftReservationHoldMinutes
      )
      if (
        !Number.isInteger(parsedDraftReservationHoldMinutes) ||
        parsedDraftReservationHoldMinutes < 1_440 ||
        parsedDraftReservationHoldMinutes > 14_400
      ) {
        throw new Error(
          "Укажите для резерва в черновике целое число от 1440 до 14400 минут."
        )
      }
      return updateRentalSettings({
        accessToken: accessToken!,
        expectedVersion: settingsQuery.data.version,
        chatSelectionHoldMinutes: parsedChatSelectionHoldMinutes,
        manualBookingHoldMinutes: parsedManualBookingHoldMinutes,
        presentationHoldMinutes: parsedPresentationHoldMinutes,
        draftReservationHoldMinutes: parsedDraftReservationHoldMinutes,
      })
    },
    onSuccess: async (settings) => {
      queryClient.setQueryData(RENTAL_SETTINGS_QUERY_KEY, settings)
      setEditedChatSelectionHoldMinutes(null)
      setEditedManualBookingHoldMinutes(null)
      setEditedPresentationHoldMinutes(null)
      setEditedDraftReservationHoldMinutes(null)
      await queryClient.invalidateQueries({
        queryKey: RENTAL_SETTINGS_QUERY_KEY,
      })
      toast.success("Срок временного удержания сохранён.")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : "Не удалось сохранить."
      ),
  })

  if (!currentUser || !isGlobalAdministrator(currentUser.globalRole)) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        Настройка доступна только системному или WMS-администратору.
      </div>
    )
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <div className="mx-auto h-full max-w-3xl overflow-y-auto py-4">
      <Card>
        <CardHeader>
          <div className="mb-2 flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <HugeiconsIcon icon={Clock01Icon} className="size-5" />
          </div>
          <CardTitle>Бронирование и чат</CardTitle>
          <CardDescription>
            Общие сроки удержания бытовок для ручного бронирования, чата,
            клиентского представления и созданного черновика заказа.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {settingsQuery.isPending ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
              Загружаем настройку…
            </div>
          ) : settingsQuery.isError ? (
            <FieldError>
              {settingsQuery.error instanceof Error
                ? settingsQuery.error.message
                : "Не удалось загрузить настройку."}
            </FieldError>
          ) : (
            <form onSubmit={submit}>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="chat-selection-hold-minutes">
                    Удержание бытовок в чате
                  </FieldLabel>
                  <Input
                    id="chat-selection-hold-minutes"
                    type="number"
                    min={1}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={chatSelectionHoldMinutes}
                    onChange={(event) =>
                      setEditedChatSelectionHoldMinutes(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Допустимо от 1 минуты до 24 часов. По умолчанию — 10 минут.
                  </FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="manual-booking-hold-minutes">
                    Удержание в ручном бронировании
                  </FieldLabel>
                  <Input
                    id="manual-booking-hold-minutes"
                    type="number"
                    min={5}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={manualBookingHoldMinutes}
                    onChange={(event) =>
                      setEditedManualBookingHoldMinutes(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Начинается после кнопки «Продолжить бронирование».
                    Допустимо от 5 минут до 24 часов. По умолчанию — 60 минут.
                  </FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="draft-reservation-hold-minutes">
                    Резерв бытовок в черновике бронирования
                  </FieldLabel>
                  <Input
                    id="draft-reservation-hold-minutes"
                    type="number"
                    min={1_440}
                    max={14_400}
                    step={1}
                    inputMode="numeric"
                    value={draftReservationHoldMinutes}
                    onChange={(event) =>
                      setEditedDraftReservationHoldMinutes(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Допустимо от 1 до 10 дней (1440–14400 минут). По умолчанию —
                    1 день. После истечения бытовка снова станет доступной.
                  </FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="presentation-hold-minutes">
                    Удержание в представлении для клиента
                  </FieldLabel>
                  <Input
                    id="presentation-hold-minutes"
                    type="number"
                    min={5}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={presentationHoldMinutes}
                    onChange={(event) =>
                      setEditedPresentationHoldMinutes(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Допустимо от 5 минут до 24 часов. По умолчанию — 60 минут.
                    После истечения ссылка ещё 24 часа работает только для
                    просмотра.
                  </FieldDescription>
                </Field>
                <Field orientation="horizontal" className="justify-end">
                  <Button type="submit" disabled={mutation.isPending}>
                    {mutation.isPending ? (
                      <HugeiconsIcon
                        icon={Loading03Icon}
                        data-icon="inline-start"
                        className="animate-spin"
                      />
                    ) : null}
                    Сохранить
                  </Button>
                </Field>
              </FieldGroup>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
