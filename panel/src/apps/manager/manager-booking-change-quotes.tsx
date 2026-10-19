import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  listPendingBookingChangeQuotes,
  PENDING_BOOKING_CHANGE_QUOTES_QUERY_KEY,
  type PendingBookingChangeQuote,
} from "@/features/orders/api/order-booking-change-quotes-api"
import { BookingChangeQuotes } from "@/features/orders/components/order-booking-change-quotes-card"

const RENTAL_WRITE_ROLES = new Set([
  "SYSTEM_ADMIN",
  "WMS_ADMIN",
  "WAREHOUSE_MANAGER",
  "RENTAL_MANAGER",
])

/** Dedicated fee intent, intentionally independent of full-order visibility. */
export function ManagerBookingChangeQuotes() {
  const { accessToken, currentUser } = useAuth()
  if (
    !accessToken ||
    !currentUser?.rentalAccess ||
    !RENTAL_WRITE_ROLES.has(currentUser.globalRole) ||
    !(
      currentUser.warehouseAccessAll ||
      currentUser.warehouseAccesses.some((grant) =>
        hasWarehouseAccess(currentUser, grant.warehouseId, "EDIT")
      )
    )
  )
    return null
  return (
    <PendingQuotes
      key={currentUser.id}
      accessToken={accessToken}
      subjectId={currentUser.id}
    />
  )
}

function PendingQuotes({
  accessToken,
  subjectId,
}: {
  accessToken: string
  subjectId: string
}) {
  const queryClient = useQueryClient()
  const queryKey = [
    ...PENDING_BOOKING_CHANGE_QUOTES_QUERY_KEY,
    subjectId,
  ] as const
  const query = useQuery({
    queryKey,
    queryFn: () => listPendingBookingChangeQuotes(accessToken),
    refetchInterval: 15_000,
  })
  const [open, setOpen] = useState(false)
  const [editingOrders, setEditingOrders] = useState<Set<string>>(
    () => new Set()
  )
  const items = query.data ?? []
  // Keep an open form when a refresh removes its stale quote, so its reason is not lost.
  const orderIds = [
    ...new Set([...items.map((item) => item.orderId), ...editingOrders]),
  ]
  if (items.length === 0 && editingOrders.size === 0 && !query.isError)
    return null
  return (
    <section
      className="mb-4 flex flex-col gap-3"
      aria-label="Неустойки по изменениям клиента"
    >
      {query.isError && (!open || orderIds.length === 0) ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить неустойки</AlertTitle>
          <AlertDescription>
            <p>{query.error.message}</p>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              Повторить загрузку неустоек
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {orderIds.length > 0 ? (
        <Collapsible
          open={open}
          onOpenChange={(next) => {
            if (editingOrders.size === 0) setOpen(next)
          }}
        >
          <CollapsibleTrigger asChild>
            <Button type="button" variant="outline" size="sm">
              Неустойки · {items.length}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="pt-3">
            <div className="flex flex-col gap-3">
              <p className="text-sm text-muted-foreground">
                Форс-мажор по доступным складам. Здесь можно снять плату без
                доступа к полному заказу.
              </p>
              {orderIds.map((orderId) => (
                <BookingChangeQuotes
                  key={orderId}
                  accessToken={accessToken}
                  orderId={orderId}
                  title={`Заказ ${orderId.slice(0, 8)}`}
                  quotesQuery={{
                    data: items
                      .filter((item) => item.orderId === orderId)
                      .map((item) => item.quote),
                    isError: query.isError,
                    isFetching: query.isFetching,
                    error: query.error,
                    refetch: () => query.refetch(),
                  }}
                  onUpdated={(quote) => {
                    queryClient.setQueryData<PendingBookingChangeQuote[]>(
                      queryKey,
                      (current) =>
                        current?.filter(
                          (item) => item.quote.quoteId !== quote.quoteId
                        )
                    )
                    void queryClient.invalidateQueries({
                      queryKey,
                      exact: true,
                    })
                  }}
                  onEditingChange={(editing) =>
                    setEditingOrders((current) => {
                      const next = new Set(current)
                      if (editing) next.add(orderId)
                      else next.delete(orderId)
                      return next
                    })
                  }
                />
              ))}
            </div>
          </CollapsibleContent>
        </Collapsible>
      ) : null}
    </section>
  )
}
