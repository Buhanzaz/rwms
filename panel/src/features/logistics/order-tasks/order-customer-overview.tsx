import { Link } from "react-router-dom"

import type { OrderDetail } from "@/features/orders/domain/orders"
import { cn } from "@/lib/utils"

export type OrderCustomerOverviewState = "available" | "loading" | "unavailable"

function PhoneLink({ phone }: { phone: string }) {
  return (
    <a
      className="break-all underline-offset-4 hover:underline"
      href={`tel:${phone}`}
    >
      {phone}
    </a>
  )
}

export function OrderCustomerOverview({
  order,
  orderId,
  orderNumber,
  state = order ? "available" : "unavailable",
  className,
}: {
  order: OrderDetail | null
  orderId: string | null
  orderNumber?: string
  state?: OrderCustomerOverviewState
  className?: string
}) {
  if (!order) {
    return (
      <section
        aria-label="Заказ и контакты клиента"
        className={cn(
          "flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1 rounded-lg border bg-muted/20 p-3 text-sm",
          className
        )}
      >
        <p className="font-medium">
          {state === "loading"
            ? "Загружаем данные заказа и клиента…"
            : "Данные заказа и клиента недоступны."}
        </p>
        {orderId ? (
          <Link
            className="min-w-0 break-words underline-offset-4 hover:underline"
            to={`/orders/${orderId}`}
          >
            Заказ {orderNumber ?? orderId}
          </Link>
        ) : null}
      </section>
    )
  }

  const clientContacts = order.client.additionalContacts ?? []
  const orderContacts = order.additionalContacts ?? []

  return (
    <section
      aria-label="Заказ и контакты клиента"
      className={cn(
        "grid min-w-0 gap-3 rounded-lg border bg-muted/20 p-3",
        className
      )}
    >
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="font-semibold">Заказ и клиент</h3>
        {orderId ? (
          <Link
            className="min-w-0 font-medium break-words underline-offset-4 hover:underline"
            to={`/orders/${orderId}`}
          >
            Заказ {orderNumber ?? order.number}
          </Link>
        ) : null}
      </div>

      <dl className="grid min-w-0 gap-2 text-sm sm:grid-cols-2">
        <div className="min-w-0">
          <dt className="text-muted-foreground">Клиент</dt>
          <dd className="font-medium break-words">
            {order.client.displayName}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-muted-foreground">Контактное лицо</dt>
          <dd className="break-words">{order.client.contactPerson ?? "—"}</dd>
        </div>
        <div className="min-w-0 sm:col-span-2">
          <dt className="text-muted-foreground">Адрес</dt>
          <dd className="break-words">{order.deliveryAddress ?? "—"}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-muted-foreground">Телефон заказа</dt>
          <dd>
            {order.contactPhone ? (
              <PhoneLink phone={order.contactPhone} />
            ) : (
              "—"
            )}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-muted-foreground">Телефон клиента</dt>
          <dd>
            {order.client.phone ? (
              <PhoneLink phone={order.client.phone} />
            ) : (
              "—"
            )}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-muted-foreground">Координаты</dt>
          <dd className="break-words">
            {order.latitude !== null && order.longitude !== null
              ? `${order.latitude}, ${order.longitude}`
              : "—"}
          </dd>
        </div>
        <div className="min-w-0 sm:col-span-2">
          <dt className="text-muted-foreground">Комментарий к заказу</dt>
          <dd className="break-words whitespace-pre-wrap">
            {order.comment ?? "—"}
          </dd>
        </div>
      </dl>

      {clientContacts.length > 0 || orderContacts.length > 0 ? (
        <div className="grid min-w-0 gap-3 text-sm sm:grid-cols-2">
          {clientContacts.length > 0 ? (
            <ContactList
              title="Дополнительные контакты клиента"
              contacts={clientContacts}
            />
          ) : null}
          {orderContacts.length > 0 ? (
            <ContactList
              title="Дополнительные контакты заказа"
              contacts={orderContacts}
            />
          ) : null}
        </div>
      ) : null}
    </section>
  )
}

function ContactList({
  title,
  contacts,
}: {
  title: string
  contacts: readonly { name: string; phone: string }[]
}) {
  return (
    <section className="min-w-0">
      <h4 className="font-medium">{title}</h4>
      <ul className="mt-1 grid gap-1">
        {contacts.map((contact, index) => (
          <li
            key={`${contact.name}-${contact.phone}-${index}`}
            className="flex min-w-0 flex-wrap gap-x-2"
          >
            <span className="break-words">{contact.name}</span>
            <PhoneLink phone={contact.phone} />
          </li>
        ))}
      </ul>
    </section>
  )
}
