import { useEffect, useRef } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  confirmOrderPayment,
  getOrderPayment,
  ORDER_PAYMENT_QUERY_KEY,
} from "@/features/orders/api/order-payments-api"
import { ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import { OrderPaymentCard } from "@/features/orders/components/order-payment-card"
import {
  observeOrderPayment,
  paymentAllowsFulfillment,
  paymentRefetchInterval,
} from "@/features/orders/domain/order-payment"
import type { OrderDetail } from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"

/** Staff wrapper around the same bill rendered to the customer; access remains server-owned. */
export function ManagerOrderPaymentCard({ order }: { order: OrderDetail }) {
  const { accessToken, currentUser, capabilities } = useOrdersModule()
  const queryClient = useQueryClient()
  const command = useRef(new OrderCommandIdentityRegistry())
  const refreshedVersion = useRef<number | null>(null)
  const enabled = Boolean(accessToken && currentUser?.id)
  const queryKey = [...ORDER_PAYMENT_QUERY_KEY, currentUser?.id, order.id]
  const paymentQuery = useQuery({
    queryKey,
    queryFn: async () =>
      observeOrderPayment(await getOrderPayment(accessToken!, order.id)),
    enabled,
    refetchInterval: (query) =>
      paymentRefetchInterval(query.state.data?.payment),
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
    retry: false,
  })
  const { refetch } = paymentQuery
  useEffect(() => {
    if (enabled) void refetch({ cancelRefetch: false })
  }, [enabled, order.version, refetch])

  const paymentVersion = paymentQuery.data?.payment.orderVersion
  useEffect(() => {
    if (
      paymentVersion === undefined ||
      paymentVersion <= order.version ||
      refreshedVersion.current === paymentVersion
    )
      return
    refreshedVersion.current = paymentVersion
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "detail", currentUser?.id, order.id],
      exact: true,
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "history", currentUser?.id, order.id],
      exact: true,
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "list"],
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "order-tasks", order.warehouseId],
      exact: true,
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "order-tasks-detail", order.id],
      exact: true,
    })
  }, [
    currentUser?.id,
    order.id,
    order.version,
    order.warehouseId,
    paymentVersion,
    queryClient,
  ])

  const confirmation = useMutation({
    mutationFn: async () => {
      const payment = paymentQuery.data?.payment
      if (!accessToken || !payment || paymentQuery.isError)
        throw new Error("Сначала обновите состояние оплаты.")
      const fingerprint =
        "confirm-payment:" + payment.orderId + ":" + payment.orderVersion
      const updated = await confirmOrderPayment({
        accessToken,
        orderId: payment.orderId,
        expectedVersion: payment.orderVersion,
        idempotencyKey: command.current.keyFor(fingerprint),
      })
      command.current.confirm(fingerprint)
      return observeOrderPayment(updated)
    },
    onSuccess: (observation) => {
      queryClient.setQueryData(queryKey, observation)
    },
    onError: () => {
      // Reconcile a conflict or lost response, but a new version always needs another explicit click.
      void refetch()
    },
  })
  return (
    <OrderPaymentCard
      mode="MANAGER"
      observation={paymentQuery.data}
      loading={paymentQuery.isPending}
      refreshing={paymentQuery.isFetching}
      unavailable={paymentQuery.isError}
      pending={confirmation.isPending}
      error={
        paymentQuery.error?.message ??
        (paymentQuery.data?.payment.state === "CONFIRMED"
          ? null
          : confirmation.error?.message)
      }
      onConfirm={() => confirmation.mutate()}
      onRefresh={() => {
        confirmation.reset()
        void refetch()
      }}
    >
      {capabilities.logisticsTaskNavigation &&
      !paymentQuery.isError &&
      paymentAllowsFulfillment(paymentQuery.data?.payment) ? (
        <Button asChild>
          <Link to="/logistics/order-tasks">Перейти к заданиям</Link>
        </Button>
      ) : null}
    </OrderPaymentCard>
  )
}
