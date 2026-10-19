import { useRef, useState, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { FieldError, FieldGroup } from "@/components/ui/field"
import {
  CLIENTS_QUERY_KEY,
  createClient,
  createClientIdempotencyKey,
} from "@/features/clients/api/clients-api"
import {
  ClientCreateFields,
  type ClientCreateFieldsErrors,
  type ClientCreateFieldsValue,
} from "@/features/clients/components/client-create-fields"
import {
  clientNeedsContactPerson,
  isValidClientPhone,
  normalizeClientDisplayName,
  parseAdditionalContacts,
} from "@/features/clients/domain/clients"
import { useOrdersModule } from "@/features/orders/orders-module-context"

function emptyClient(): ClientCreateFieldsValue {
  return {
    clientType: "LEGAL_ENTITY",
    displayName: "",
    phone: "",
    contactPerson: "",
    email: "",
    comment: "",
    source: "",
    additionalContacts: [],
  }
}

/** Creates a logistics client from the Clients workspace without a new route. */
export function ClientCreateDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Создать клиента</DialogTitle>
          <DialogDescription>
            Основной контакт и дополнительные контактные лица сохраняются в
            единой карточке клиента.
          </DialogDescription>
        </DialogHeader>
        {open ? (
          <ClientCreateDialogContent onClose={() => onOpenChange(false)} />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function ClientCreateDialogContent({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useOrdersModule()
  const [value, setValue] = useState(emptyClient)
  const [errors, setErrors] = useState<ClientCreateFieldsErrors>({})
  const [errorText, setErrorText] = useState<string | null>(null)
  const idempotencyKey = useRef<string | null>(null)

  const createMutation = useMutation({
    mutationFn: () => {
      if (!accessToken) throw new Error("Сессия завершена.")
      idempotencyKey.current ??= createClientIdempotencyKey()
      const parsedContacts = parseAdditionalContacts(value.additionalContacts)
      if (!parsedContacts.contacts) {
        throw new Error("Проверьте дополнительные контакты клиента.")
      }
      return createClient({
        accessToken,
        idempotencyKey: idempotencyKey.current,
        input: {
          clientType: value.clientType,
          displayName: normalizeClientDisplayName(value.displayName),
          phone: value.phone.trim(),
          contactPerson: value.contactPerson.trim() || null,
          email: value.email.trim() || null,
          comment: value.comment.trim() || null,
          source: value.source.trim() || null,
          additionalContacts: parsedContacts.contacts,
        },
      })
    },
    onSuccess: async (client) => {
      queryClient.setQueryData(
        [...CLIENTS_QUERY_KEY, "detail", client.id],
        client
      )
      await queryClient.invalidateQueries({ queryKey: CLIENTS_QUERY_KEY })
      toast.success(`Клиент «${client.displayName}» создан.`)
      onClose()
      navigate(`/clients/${client.id}`)
    },
    onError: (error) => {
      setErrorText(
        error instanceof Error ? error.message : "Не удалось создать клиента."
      )
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors: ClientCreateFieldsErrors = {}
    if (!normalizeClientDisplayName(value.displayName)) {
      nextErrors.displayName = "Укажите наименование или ФИО клиента."
    }
    if (!isValidClientPhone(value.phone)) {
      nextErrors.phone = "Укажите корректный основной телефон."
    }
    if (
      clientNeedsContactPerson(value.clientType) &&
      !value.contactPerson.trim()
    ) {
      nextErrors.contactPerson =
        "Укажите основное контактное лицо юридического лица."
    }
    const parsedContacts = parseAdditionalContacts(value.additionalContacts)
    if (!parsedContacts.contacts) {
      nextErrors.additionalContacts = parsedContacts.errors
    }
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setErrorText("Проверьте обязательные поля клиента.")
      return
    }
    createMutation.mutate()
  }

  if (!currentUser || !accessToken) {
    return <FieldError>Сессия завершена.</FieldError>
  }

  return (
    <form noValidate onSubmit={submit}>
      <FieldGroup>
        <ClientCreateFields
          idPrefix="clients-create"
          value={value}
          errors={errors}
          responsibleManagerDisplayName={
            currentUser.displayName || currentUser.id
          }
          disabled={createMutation.isPending}
          onChange={(next) => {
            idempotencyKey.current = null
            setValue(next)
            setErrors({})
            setErrorText(null)
          }}
        />
        {errorText ? <FieldError>{errorText}</FieldError> : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Отмена
          </Button>
          <Button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {createMutation.isPending ? "Создаём…" : "Создать клиента"}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}
