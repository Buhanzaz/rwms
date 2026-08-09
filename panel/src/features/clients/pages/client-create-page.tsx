import { useRef, useState, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { ArrowLeft01Icon, Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link, useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { FieldError, FieldGroup } from "@/components/ui/field"
import {
  CLIENTS_QUERY_KEY,
  createClient,
} from "@/features/clients/api/clients-api"
import {
  ClientCreateFields,
  type ClientCreateFieldsErrors,
  type ClientCreateFieldsValue,
} from "@/features/clients/components/client-create-fields"
import {
  clientNeedsContactPerson,
  isValidClientPhone,
  type CreateClientInput,
} from "@/features/clients/domain/clients"
import { CLIENTS_NAVIGATION } from "@/features/clients/clients-navigation"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { useOrdersModule } from "@/features/orders/orders-module-context"

const EMPTY_CLIENT_VALUE: ClientCreateFieldsValue = {
  clientType: "INDIVIDUAL",
  displayName: "",
  phone: "",
  contactPerson: "",
  email: "",
  comment: "",
  source: "",
}

/** Creates a client from the same field set used in orders, booking and chat. */
export function ClientCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [value, setValue] =
    useState<ClientCreateFieldsValue>(EMPTY_CLIENT_VALUE)
  const [errors, setErrors] = useState<ClientCreateFieldsErrors>({})
  const [errorText, setErrorText] = useState<string | null>(null)
  const command = useRef(new OrderCommandIdentityRegistry())
  const displayNameRef = useRef<HTMLInputElement>(null)
  const phoneRef = useRef<HTMLInputElement>(null)
  const contactPersonRef = useRef<HTMLInputElement>(null)

  const mutation = useMutation({
    mutationFn: ({
      input,
      fingerprint,
    }: {
      input: CreateClientInput
      fingerprint: string
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")
      return createClient({
        accessToken,
        idempotencyKey: command.current.keyFor(fingerprint),
        input,
      })
    },
    onSuccess: async (client, { fingerprint }) => {
      command.current.confirm(fingerprint)
      await queryClient.invalidateQueries({ queryKey: CLIENTS_QUERY_KEY })
      toast.success(`Клиент «${client.displayName}» создан.`)
      navigate(`/clients/${client.id}`, { replace: true })
    },
    onError: (error) =>
      setErrorText(
        error instanceof Error ? error.message : "Не удалось создать клиента."
      ),
  })

  function changeValue(next: ClientCreateFieldsValue) {
    command.current.reset()
    setValue(next)
    setErrors({})
    setErrorText(null)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: ClientCreateFieldsErrors = {}
    if (!value.displayName.trim()) {
      nextErrors.displayName = "Укажите наименование или ФИО."
    }
    if (!value.phone.trim()) {
      nextErrors.phone = "Укажите основной телефон."
    } else if (!isValidClientPhone(value.phone)) {
      nextErrors.phone = "Укажите корректный основной телефон."
    }
    if (
      clientNeedsContactPerson(value.clientType) &&
      !value.contactPerson.trim()
    ) {
      nextErrors.contactPerson = "Укажите основное контактное лицо."
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setErrorText("Проверьте обязательные поля клиента.")
      const firstInvalid = nextErrors.displayName
        ? displayNameRef.current
        : nextErrors.phone
          ? phoneRef.current
          : contactPersonRef.current
      firstInvalid?.focus()
      return
    }

    const input: CreateClientInput = {
      clientType: value.clientType,
      displayName: value.displayName.trim(),
      phone: value.phone.trim(),
      contactPerson: value.contactPerson.trim() || null,
      email: value.email.trim() || null,
      comment: value.comment.trim() || null,
      source: value.source.trim() || null,
    }
    mutation.mutate({ input, fingerprint: JSON.stringify(input) })
  }

  if (!accessToken || !currentUser) {
    return <FieldError>Сессия завершена.</FieldError>
  }

  return (
    <div className="h-full overflow-y-auto pr-1">
      <Card className="mx-auto max-w-4xl" size="sm">
        <CardHeader>
          <CardTitle>Создать клиента</CardTitle>
          <CardDescription>
            Заполните единую карточку арендатора. Ответственного менеджера
            сервер фиксирует из текущей сессии.
          </CardDescription>
        </CardHeader>
        <form onSubmit={submit}>
          <CardContent className="pb-7">
            <FieldGroup>
              <ClientCreateFields
                idPrefix="client"
                value={value}
                responsibleManagerDisplayName={
                  currentUser.displayName || currentUser.id
                }
                errors={errors}
                refs={{
                  displayName: displayNameRef,
                  phone: phoneRef,
                  contactPerson: contactPersonRef,
                }}
                disabled={mutation.isPending}
                onChange={changeValue}
              />
              {errorText ? (
                <FieldError aria-live="polite">{errorText}</FieldError>
              ) : null}
            </FieldGroup>
          </CardContent>
          <CardFooter className="justify-end gap-2 border-t">
            <Button asChild type="button" variant="outline">
              <Link to={CLIENTS_NAVIGATION.listPath}>
                <HugeiconsIcon
                  icon={ArrowLeft01Icon}
                  data-icon="inline-start"
                />
                Отмена
              </Link>
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? (
                <HugeiconsIcon
                  icon={Loading03Icon}
                  data-icon="inline-start"
                  className="animate-spin"
                />
              ) : null}
              {mutation.isPending ? "Создаём…" : "Создать клиента"}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  )
}
