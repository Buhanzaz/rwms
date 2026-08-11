import {
  useCallback,
  useDeferredValue,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react"
import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query"
import { useLocation, useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  checkRentalItemsAvailability,
  listAvailableRentalItems,
  RENTAL_BOOKING_AVAILABLE_QUERY_KEY,
  unavailableRentalItemIds,
} from "@/features/booking/api/booking-availability-api"
import { putManualBookingDraftHold } from "@/features/booking/api/manual-booking-drafts-api"
import { BookingUnavailableDialog } from "@/features/booking/booking-availability"
import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { useBookingHoldExpiry } from "@/features/booking/use-booking-hold-expiry"
import { useSelectedRentalItemsAvailability } from "@/features/booking/use-selected-rental-items-availability"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { getOrder, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const PAGE_SIZE = 50
const BOOKING_QUERY_CACHE_TIME_MS = 2 * 60 * 60 * 1_000

function uniqueAvailableItems(
  pages: readonly { content: RentalItemDto[] }[] | undefined
) {
  const ids = new Set<string>()
  return (pages ?? []).flatMap((page) =>
    page.content.filter((item) => {
      if (
        ids.has(item.id) ||
        item.status !== "FREE" ||
        item.activeOrderReservation
      ) {
        return false
      }
      ids.add(item.id)
      return true
    })
  )
}

function BookingPageMessage({ children }: { children: string }) {
  return (
    <div className="flex h-full items-center justify-center rounded-lg border bg-card p-4 text-sm text-muted-foreground">
      {children}
    </div>
  )
}

export function BookingCatalogPage() {
  const { accessToken, currentUser, status } = useAuth()
  const { selectedWarehouse } = useWarehouse()

  let content
  if (status !== "authenticated" || !accessToken || !currentUser) {
    content = (
      <BookingPageMessage>
        Для бронирования требуется авторизация.
      </BookingPageMessage>
    )
  } else if (!currentUser.rentalAccess) {
    content = (
      <BookingPageMessage>
        Для пользователя не включён доступ к аренде и бронированию.
      </BookingPageMessage>
    )
  } else {
    content = (
      <BookingCatalogOrderBoundary
        accessToken={accessToken}
        subjectId={currentUser.id}
        selectedWarehouseId={selectedWarehouse?.id ?? null}
      />
    )
  }

  return (
    <>
      <ManagerBookingAlertDialog />
      {content}
    </>
  )
}

function BookingCatalogOrderBoundary({
  accessToken,
  subjectId,
  selectedWarehouseId,
}: {
  accessToken: string
  subjectId: string
  selectedWarehouseId: string | null
}) {
  const location = useLocation()
  const query = new URLSearchParams(location.search)
  const orderId = query.get("orderId")
  const clientId = query.get("clientId")
  const mode = query.get("mode") === "REPLACEMENT" ? "REPLACEMENT" : "NORMAL"
  const replacementUnitIds = query.getAll("replacementUnitId")
  const orderQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "detail", orderId],
    queryFn: () => getOrder(accessToken, orderId!),
    enabled: Boolean(orderId),
  })

  if (!orderId || !clientId) {
    return (
      <BookingPageMessage>
        Откройте добавление бытовок из карточки существующего заказа.
      </BookingPageMessage>
    )
  }
  if (orderQuery.isPending) {
    return <BookingPageMessage>Проверяем текущий заказ…</BookingPageMessage>
  }
  if (orderQuery.isError) {
    return (
      <BookingPageMessage>
        Не удалось открыть заказ для бронирования.
      </BookingPageMessage>
    )
  }

  const order = orderQuery.data
  if (order.client.id !== clientId) {
    return (
      <BookingPageMessage>
        Ссылка бронирования не соответствует клиенту этого заказа.
      </BookingPageMessage>
    )
  }
  const warehouseId = order.warehouseId ?? selectedWarehouseId
  if (!warehouseId) {
    return <BookingPageMessage>Склад не выбран.</BookingPageMessage>
  }
  if (mode === "NORMAL" && !order.permissions.canEdit) {
    return (
      <BookingPageMessage>
        Обычное бронирование для этого заказа уже недоступно.
      </BookingPageMessage>
    )
  }
  if (
    mode === "REPLACEMENT" &&
    (!order.permissions.canReplaceUnits ||
      replacementUnitIds.length === 0 ||
      new Set(replacementUnitIds).size !== replacementUnitIds.length ||
      replacementUnitIds.some(
        (unitId) =>
          !order.units.some(
            (candidate) => candidate.added && candidate.unit.id === unitId
          )
      ))
  ) {
    return (
      <BookingPageMessage>
        Выбранные бытовки нельзя заменить через это представление.
      </BookingPageMessage>
    )
  }

  return (
    <BookingCatalogPageState
      key={`${order.id}:${warehouseId}:${mode}`}
      accessToken={accessToken}
      subjectId={subjectId}
      warehouseId={warehouseId}
      selectionLimit={mode === "REPLACEMENT" ? replacementUnitIds.length : 100}
      replacement={mode === "REPLACEMENT"}
    />
  )
}

function BookingCatalogPageState({
  accessToken,
  subjectId,
  warehouseId,
  selectionLimit,
  replacement,
}: {
  accessToken: string
  subjectId: string
  warehouseId: string
  selectionLimit: number
  replacement: boolean
}) {
  const navigate = useNavigate()
  const location = useLocation()
  const {
    draftId,
    checkedItems,
    checkedIds,
    stagedItems,
    stagedIds,
    activeHold,
    toggleChecked,
    addCheckedToStaged,
    removeStaged,
    removeMany,
    syncSnapshot,
    setActiveHold,
  } = useBookingSelection()
  const [search, setSearch] = useState("")
  const [unavailableItems, setUnavailableItems] = useState<RentalItemDto[]>([])
  const deferredSearch = useDeferredValue(search)
  const holdExpired = useBookingHoldExpiry(activeHold)
  const holdIdentity = useRef(new OrderCommandIdentityRegistry())

  const availableQuery = useInfiniteQuery({
    queryKey: [
      ...RENTAL_BOOKING_AVAILABLE_QUERY_KEY,
      subjectId,
      warehouseId,
      deferredSearch,
    ],
    queryFn: ({ pageParam }) =>
      listAvailableRentalItems({
        accessToken,
        warehouseId,
        page: pageParam,
        size: PAGE_SIZE,
        search: deferredSearch,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    staleTime: Infinity,
    gcTime: BOOKING_QUERY_CACHE_TIME_MS,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })

  const items = useMemo(
    () => uniqueAvailableItems(availableQuery.data?.pages),
    [availableQuery.data?.pages]
  )
  const selectionItems = useMemo(
    () => [...checkedItems, ...stagedItems],
    [checkedItems, stagedItems]
  )
  const selectedItemById = useMemo(
    () => new Map(selectionItems.map((item) => [item.id, item])),
    [selectionItems]
  )
  const activeHeldIds = useMemo(
    () =>
      activeHold && activeHold.draftId === draftId && !holdExpired
        ? new Set(activeHold.rentalItemIds)
        : new Set<string>(),
    [activeHold, draftId, holdExpired]
  )
  const unheldSelectionIds = useMemo(
    () =>
      selectionItems
        .map((item) => item.id)
        .filter((id) => !activeHeldIds.has(id)),
    [activeHeldIds, selectionItems]
  )
  const unheldStagedIds = useMemo(
    () =>
      stagedItems.map((item) => item.id).filter((id) => !activeHeldIds.has(id)),
    [activeHeldIds, stagedItems]
  )

  useEffect(() => {
    items.forEach((item) => {
      if (checkedIds.has(item.id) || stagedIds.has(item.id)) syncSnapshot(item)
    })
  }, [checkedIds, items, stagedIds, syncSnapshot])
  const handleUnavailable = useCallback(
    (ids: string[]) => {
      const removed = ids.flatMap((id) => {
        const item = selectedItemById.get(id)
        return item ? [item] : []
      })
      removeMany(ids)
      if (removed.length > 0) {
        setUnavailableItems((current) => {
          const byId = new Map(current.map((item) => [item.id, item]))
          removed.forEach((item) => byId.set(item.id, item))
          return [...byId.values()]
        })
      }
    },
    [removeMany, selectedItemById]
  )
  useSelectedRentalItemsAvailability({
    accessToken,
    subjectId,
    warehouseId,
    rentalItemIds: unheldSelectionIds,
    onUnavailable: handleUnavailable,
  })

  const holdMutation = useMutation({
    mutationFn: async () => {
      const rentalItemIds = stagedItems.map((item) => item.id).sort()
      if (rentalItemIds.length === 0) {
        throw new Error("Сначала добавьте хотя бы одну бытовку.")
      }
      const fingerprint = JSON.stringify({
        draftId,
        warehouseId,
        rentalItemIds,
      })
      try {
        const hold = await putManualBookingDraftHold({
          accessToken,
          draftId,
          warehouseId,
          rentalItemIds,
          idempotencyKey: holdIdentity.current.keyFor(fingerprint),
        })
        return { hold, fingerprint }
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 409 &&
          unheldStagedIds.length > 0
        ) {
          const availability = await checkRentalItemsAvailability({
            accessToken,
            warehouseId,
            rentalItemIds: unheldStagedIds,
          })
          handleUnavailable(
            unavailableRentalItemIds(availability, unheldStagedIds)
          )
        }
        throw error
      }
    },
    onSuccess: ({ hold, fingerprint }) => {
      holdIdentity.current.confirm(fingerprint)
      setActiveHold(hold)
      navigate({ pathname: "/booking/continue", search: location.search })
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось зарезервировать выбранные бытовки."
      ),
  })

  const totalElements = availableQuery.data?.pages[0]?.totalElements ?? 0

  if (availableQuery.isPending) {
    return <BookingPageMessage>Загружаем свободные бытовки…</BookingPageMessage>
  }

  if (availableQuery.isError && !availableQuery.data) {
    return (
      <div
        role="alert"
        className="flex h-full flex-col items-center justify-center gap-3 rounded-lg border bg-card p-4 text-sm text-destructive"
      >
        <span>
          {availableQuery.error instanceof Error
            ? availableQuery.error.message
            : "Не удалось загрузить свободные бытовки."}
        </span>
        <Button
          type="button"
          variant="outline"
          onClick={() => availableQuery.refetch()}
        >
          Повторить
        </Button>
      </div>
    )
  }

  return (
    <>
      <BookingCabinBrowser
        accessToken={accessToken}
        subjectId={subjectId}
        warehouseId={warehouseId}
        items={items}
        selectedIds={checkedIds}
        stagedIds={stagedIds}
        search={search}
        onSearchChange={setSearch}
        onToggle={(item) => {
          if (
            !checkedIds.has(item.id) &&
            !stagedIds.has(item.id) &&
            checkedIds.size + stagedIds.size >= selectionLimit
          ) {
            toast.error(
              replacement
                ? `Для замены нужно выбрать ровно ${selectionLimit} бытовок.`
                : "В одно представление можно добавить не более 100 бытовок."
            )
            return
          }
          toggleChecked(item)
        }}
        onRemoveStaged={(item) => removeStaged(item.id)}
        onReachEnd={() => {
          if (
            availableQuery.hasNextPage &&
            !availableQuery.isFetchingNextPage
          ) {
            void availableQuery.fetchNextPage()
          }
        }}
        actions={
          <>
            <Button
              type="button"
              variant="outline"
              disabled={checkedIds.size === 0}
              onClick={() => {
                addCheckedToStaged()
                toast.success(`Добавлено бытовок: ${checkedIds.size}`)
              }}
            >
              Добавить {checkedIds.size} выбранных
            </Button>
            <Button
              type="button"
              disabled={
                stagedIds.size === 0 ||
                (replacement && stagedIds.size !== selectionLimit) ||
                holdMutation.isPending
              }
              onClick={() => holdMutation.mutate()}
            >
              {holdMutation.isPending
                ? "Резервируем…"
                : "Продолжить бронирование"}
            </Button>
          </>
        }
        footer={
          <div className="grid gap-1 text-center text-sm text-muted-foreground">
            <p>
              {totalElements === 0
                ? "Свободные бытовки не найдены"
                : `Загружено ${items.length} из ${totalElements}. Отмечено: ${checkedIds.size}. Добавлено: ${stagedIds.size}`}
            </p>
            {availableQuery.isFetchingNextPage ? (
              <p role="status">Загрузка ещё бытовок…</p>
            ) : availableQuery.isFetchNextPageError ? (
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => availableQuery.fetchNextPage()}
              >
                Повторить загрузку
              </Button>
            ) : null}
          </div>
        }
      />

      <BookingUnavailableDialog
        items={unavailableItems}
        onAcknowledge={() => setUnavailableItems([])}
      />
    </>
  )
}
