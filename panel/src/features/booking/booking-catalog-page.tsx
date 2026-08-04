import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useInfiniteQuery, useMutation } from "@tanstack/react-query"
import { useNavigate } from "react-router-dom"
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
import { useSelectedRentalItemsAvailability } from "@/features/booking/use-selected-rental-items-availability"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

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
  const [now, setNow] = useState(() => Date.now())
  const holdIdentity = useRef(new OrderCommandIdentityRegistry())

  useEffect(() => {
    if (!activeHold) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [activeHold])

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
      activeHold &&
      activeHold.draftId === draftId &&
      activeHold.expiresAt !== null &&
      Date.parse(activeHold.expiresAt) > now
        ? new Set(activeHold.rentalItemIds)
        : new Set<string>(),
    [activeHold, draftId, now]
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
      navigate("/booking/continue")
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
            checkedIds.size + stagedIds.size >= 100
          ) {
            toast.error(
              "В одно представление можно добавить не более 100 бытовок."
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
              disabled={stagedIds.size === 0 || holdMutation.isPending}
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
