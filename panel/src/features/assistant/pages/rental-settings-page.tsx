import { useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
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
import { useAuth } from "@/features/auth/use-auth"

export function RentalSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const rentalSettingsQueryKey = ["rental-settings"] as const
  const settingsQuery = useQuery({
    queryKey: rentalSettingsQueryKey,
    queryFn: () => getRentalSettings(accessToken!),
    enabled: Boolean(
      accessToken &&
      currentUser &&
      currentUser.globalRole === "SYSTEM_ADMIN"
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
      queryClient.setQueryData(rentalSettingsQueryKey, settings)
      setEditedChatSelectionHoldMinutes(null)
      setEditedManualBookingHoldMinutes(null)
      setEditedPresentationHoldMinutes(null)
      setEditedDraftReservationHoldMinutes(null)
      await queryClient.invalidateQueries({
        queryKey: rentalSettingsQueryKey,
      })
      toast.success("Срок временного удержания сохранён.")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : "Не удалось сохранить."
      ),
  })

  if (
    !currentUser ||
    currentUser.globalRole !== "SYSTEM_ADMIN"
  ) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        Настройка доступна системному администратору.
      </div>
    )
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <div className="w-full">
      <Card size="sm" className="bg-muted/65">
        <CardContent>
          {settingsQuery.isPending ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <HugeiconsIcon
                icon={Loading03Icon}
                className="animate-spin"
                aria-hidden="true"
              />
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
              <FieldGroup className="flex flex-col gap-5">
                <Field>
                  <FieldLabel htmlFor="chat-selection-hold-minutes">
                    Удержание бытовок в чате
                  </FieldLabel>
                  <Input
                    id="chat-selection-hold-minutes"
                    name="chat-selection-hold-minutes"
                    autoComplete="off"
                    type="number"
                    min={1}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={chatSelectionHoldMinutes}
                    className="w-full"
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
                    name="manual-booking-hold-minutes"
                    autoComplete="off"
                    type="number"
                    min={5}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={manualBookingHoldMinutes}
                    className="w-full"
                    onChange={(event) =>
                      setEditedManualBookingHoldMinutes(event.target.value)
                    }
                  />
                  <FieldDescription>
                    Начинается после кнопки «Продолжить бронирование». Допустимо
                    от 5 минут до 24 часов. По умолчанию — 60 минут.
                  </FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="draft-reservation-hold-minutes">
                    Резерв бытовок в черновике бронирования
                  </FieldLabel>
                  <Input
                    id="draft-reservation-hold-minutes"
                    name="draft-reservation-hold-minutes"
                    autoComplete="off"
                    type="number"
                    min={1_440}
                    max={14_400}
                    step={1}
                    inputMode="numeric"
                    value={draftReservationHoldMinutes}
                    className="w-full"
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
                    name="presentation-hold-minutes"
                    autoComplete="off"
                    type="number"
                    min={5}
                    max={1_440}
                    step={1}
                    inputMode="numeric"
                    value={presentationHoldMinutes}
                    className="w-full"
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
                <Field orientation="horizontal" className="justify-end pt-1">
                  <Button type="submit" disabled={mutation.isPending}>
                    {mutation.isPending ? (
                      <HugeiconsIcon
                        icon={Loading03Icon}
                        data-icon="inline-start"
                        className="animate-spin"
                        aria-hidden="true"
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
