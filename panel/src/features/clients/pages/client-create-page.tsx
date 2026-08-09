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
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  CLIENTS_QUERY_KEY,
  createClient,
} from "@/features/clients/api/clients-api"
import {
  CLIENT_TYPES,
  CLIENT_TYPE_LABELS,
  clientNeedsContactPerson,
  isValidClientPhone,
  type ClientType,
  type CreateClientInput,
} from "@/features/clients/domain/clients"
import { CLIENTS_NAVIGATION } from "@/features/clients/clients-navigation"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { useOrdersModule } from "@/features/orders/orders-module-context"

type ClientFormErrors = Partial<
  Record<"displayName" | "phone" | "contactPerson", string>
>

export function ClientCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [clientType, setClientType] = useState<ClientType>("INDIVIDUAL")
  const [displayName, setDisplayName] = useState("")
  const [phone, setPhone] = useState("")
  const [contactPerson, setContactPerson] = useState("")
  const [email, setEmail] = useState("")
  const [comment, setComment] = useState("")
  const [source, setSource] = useState("")
  const [errors, setErrors] = useState<ClientFormErrors>({})
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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: ClientFormErrors = {}
    if (!displayName.trim())
      nextErrors.displayName = "Укажите наименование или ФИО."
    if (!phone.trim()) nextErrors.phone = "Укажите основной телефон."
    else if (!isValidClientPhone(phone)) {
      nextErrors.phone = "Укажите корректный основной телефон."
    }
    if (clientNeedsContactPerson(clientType) && !contactPerson.trim()) {
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
      clientType,
      displayName: displayName.trim(),
      phone: phone.trim(),
      contactPerson: contactPerson.trim() || null,
      email: email.trim() || null,
      comment: comment.trim() || null,
      source: source.trim() || null,
    }
    mutation.mutate({ input, fingerprint: JSON.stringify(input) })
  }

  if (!accessToken || !currentUser) {
    return <FieldError>Сессия завершена.</FieldError>
  }

  return (
    <div className="h-full overflow-y-auto pr-1">
      <Card className="mx-auto max-w-3xl" size="sm">
        <CardHeader>
          <CardTitle>Создать клиента</CardTitle>
          <CardDescription>
            Обязательные поля соответствуют карточке арендатора. Ответственного
            менеджера сервер фиксирует из текущей сессии.
          </CardDescription>
        </CardHeader>
        <form onSubmit={submit}>
          <CardContent>
            <FieldGroup>
              <FieldSet>
                <FieldLegend>Тип клиента</FieldLegend>
                <ToggleGroup
                  type="single"
                  value={clientType}
                  spacing={2}
                  aria-label="Тип клиента"
                  onValueChange={(value) => {
                    if (!value) return
                    command.current.reset()
                    setClientType(value as ClientType)
                    setErrors({})
                    setErrorText(null)
                  }}
                >
                  {CLIENT_TYPES.map((type) => (
                    <ToggleGroupItem key={type} value={type}>
                      {CLIENT_TYPE_LABELS[type]}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </FieldSet>

              <Field data-invalid={Boolean(errors.displayName) || undefined}>
                <FieldLabel htmlFor="client-display-name">
                  Наименование или ФИО
                </FieldLabel>
                <Input
                  id="client-display-name"
                  ref={displayNameRef}
                  name="displayName"
                  value={displayName}
                  required
                  maxLength={512}
                  aria-invalid={Boolean(errors.displayName)}
                  autoComplete="name"
                  onChange={(event) => {
                    command.current.reset()
                    setDisplayName(event.target.value)
                    setErrors((current) => ({
                      ...current,
                      displayName: undefined,
                    }))
                  }}
                />
                {errors.displayName ? (
                  <FieldError>{errors.displayName}</FieldError>
                ) : null}
              </Field>

              <Field data-invalid={Boolean(errors.phone) || undefined}>
                <FieldLabel htmlFor="client-main-phone">
                  Основной телефон
                </FieldLabel>
                <Input
                  id="client-main-phone"
                  ref={phoneRef}
                  name="phone"
                  type="tel"
                  value={phone}
                  required
                  maxLength={32}
                  pattern="(?:\+|8)[0-9() .-]{6,31}"
                  aria-invalid={Boolean(errors.phone)}
                  autoComplete="tel"
                  placeholder="+7 999 000-00-00"
                  onChange={(event) => {
                    command.current.reset()
                    setPhone(event.target.value)
                    setErrors((current) => ({ ...current, phone: undefined }))
                  }}
                />
                {errors.phone ? <FieldError>{errors.phone}</FieldError> : null}
              </Field>

              {clientNeedsContactPerson(clientType) ? (
                <Field
                  data-invalid={Boolean(errors.contactPerson) || undefined}
                >
                  <FieldLabel htmlFor="client-contact-person">
                    Основное контактное лицо
                  </FieldLabel>
                  <Input
                    id="client-contact-person"
                    ref={contactPersonRef}
                    name="contactPerson"
                    value={contactPerson}
                    required
                    maxLength={255}
                    aria-invalid={Boolean(errors.contactPerson)}
                    autoComplete="name"
                    onChange={(event) => {
                      command.current.reset()
                      setContactPerson(event.target.value)
                      setErrors((current) => ({
                        ...current,
                        contactPerson: undefined,
                      }))
                    }}
                  />
                  <FieldDescription>
                    Обязательно для ИП и юридического лица.
                  </FieldDescription>
                  {errors.contactPerson ? (
                    <FieldError>{errors.contactPerson}</FieldError>
                  ) : null}
                </Field>
              ) : null}

              <Field>
                <FieldLabel htmlFor="client-email">Email</FieldLabel>
                <Input
                  id="client-email"
                  name="email"
                  type="email"
                  value={email}
                  maxLength={320}
                  autoComplete="email"
                  spellCheck={false}
                  placeholder="client@example.ru"
                  onChange={(event) => {
                    command.current.reset()
                    setEmail(event.target.value)
                  }}
                />
                <FieldDescription>
                  Желателен; нужен для электронной отправки документов.
                </FieldDescription>
              </Field>

              <Field>
                <FieldLabel htmlFor="client-responsible-manager">
                  Ответственный менеджер
                </FieldLabel>
                <Input
                  id="client-responsible-manager"
                  name="responsibleManager"
                  value={currentUser.displayName || currentUser.id}
                  required
                  readOnly
                  autoComplete="off"
                  aria-readonly="true"
                />
                <FieldDescription>
                  Обязательное поле. Менеджер определяется текущей
                  авторизованной сессией и не отправляется как клиентские
                  данные.
                </FieldDescription>
              </Field>

              <Field>
                <FieldLabel htmlFor="client-comment">Комментарий</FieldLabel>
                <Textarea
                  id="client-comment"
                  name="comment"
                  value={comment}
                  maxLength={2_000}
                  autoComplete="off"
                  onChange={(event) => {
                    command.current.reset()
                    setComment(event.target.value)
                  }}
                />
                <FieldDescription>Необязательное поле.</FieldDescription>
              </Field>

              <Field>
                <FieldLabel htmlFor="client-source">
                  Источник клиента
                </FieldLabel>
                <Input
                  id="client-source"
                  name="source"
                  value={source}
                  maxLength={255}
                  autoComplete="off"
                  placeholder="Рекомендация, сайт, звонок"
                  onChange={(event) => {
                    command.current.reset()
                    setSource(event.target.value)
                  }}
                />
                <FieldDescription>Необязательное поле.</FieldDescription>
              </Field>

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
