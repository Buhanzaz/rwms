import { useCallback, useEffect, useMemo, useState } from "react"
import { useInfiniteQuery } from "@tanstack/react-query"
import { useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import {
  listAvailableRentalItems,
  RENTAL_BOOKING_AVAILABLE_QUERY_KEY,
} from "@/features/booking/api/booking-availability-api"
import { BookingUnavailableDialog } from "@/features/booking/booking-availability"
import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { useSelectedRentalItemsAvailability } from "@/features/booking/use-selected-rental-items-availability"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"

const PAGE_SIZE = 200
const BOOKING_QUERY_CACHE_TIME_MS = 2 * 60 * 60 * 1_000
const AVAILABLE_REFETCH_INTERVAL_MS = 15_000

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
  } else if (!selectedWarehouse) {
    content = <BookingPageMessage>Склад не выбран.</BookingPageMessage>
  } else {
    content = (
      <BookingCatalogPageState
        key={selectedWarehouse.id}
        accessToken={accessToken}
        subjectId={currentUser.id}
        warehouseId={selectedWarehouse.id}
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

function BookingCatalogPageState({
  accessToken,
  subjectId,
  warehouseId,
}: {
  accessToken: string
  subjectId: string
  warehouseId: string
}) {
  const navigate = useNavigate()
  const { selectedItems, selectedIds, select, toggle, removeMany } =
    useBookingSelection()
  const [search, setSearch] = useState("")
  const [unavailableItems, setUnavailableItems] = useState<RentalItemDto[]>([])

  const availableQuery = useInfiniteQuery({
    queryKey: [
      ...RENTAL_BOOKING_AVAILABLE_QUERY_KEY,
      subjectId,
      warehouseId,
      search,
    ],
    queryFn: ({ pageParam }) =>
      listAvailableRentalItems({
        accessToken,
        warehouseId,
        page: pageParam,
        size: PAGE_SIZE,
        search,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    staleTime: Infinity,
    gcTime: BOOKING_QUERY_CACHE_TIME_MS,
    refetchInterval: AVAILABLE_REFETCH_INTERVAL_MS,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })

  const items = useMemo(
    () => uniqueAvailableItems(availableQuery.data?.pages),
    [availableQuery.data?.pages]
  )
  const selectedItemById = useMemo(
    () => new Map(selectedItems.map((item) => [item.id, item])),
    [selectedItems]
  )
  const selectedItemIds = useMemo(
    () => selectedItems.map((item) => item.id),
    [selectedItems]
  )

  useEffect(() => {
    items.forEach((item) => {
      if (selectedIds.has(item.id)) select(item)
    })
  }, [items, select, selectedIds])
  const handleUnavailable = useCallback(
    (ids: string[]) => {
      const removed = ids.flatMap((id) => {
        const item = selectedItemById.get(id)
        return item ? [item] : []
      })
      removeMany(ids)
      if (removed.length > 0) setUnavailableItems(removed)
    },
    [removeMany, selectedItemById]
  )
  useSelectedRentalItemsAvailability({
    accessToken,
    subjectId,
    warehouseId,
    rentalItemIds: selectedItemIds,
    onUnavailable: handleUnavailable,
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
        selectedIds={selectedIds}
        search={search}
        onSearchChange={setSearch}
        onToggle={(item) => {
          if (!selectedIds.has(item.id) && selectedIds.size >= 100) {
            toast.error(
              "В одно представление можно добавить не более 100 бытовок."
            )
            return
          }
          toggle(item)
        }}
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
              disabled={selectedIds.size === 0}
              onClick={() => navigate("/booking/continue")}
            >
              Забронировать {selectedIds.size} выбранных
            </Button>
            <Button
              type="button"
              disabled={selectedIds.size === 0}
              onClick={() => navigate("/booking/continue")}
            >
              Продолжить бронирование
            </Button>
          </>
        }
        footer={
          <div className="space-y-1 text-center text-sm text-muted-foreground">
            <p>
              {totalElements === 0
                ? "Свободные бытовки не найдены"
                : `Загружено ${items.length} из ${totalElements}. Выбрано: ${selectedIds.size}`}
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
